<?php

declare(strict_types=1);

namespace App\Domain\Mobile;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Security\AdminUserName;
use App\Infrastructure\Security\AdminUserProvider;
use App\Infrastructure\Time\Clock\Clock;
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\PasswordHasher\Hasher\UserPasswordHasherInterface;
use Symfony\Component\Security\Core\Exception\UserNotFoundException;
use Symfony\Component\Security\Core\User\InMemoryUser;

final readonly class MobileSessionService
{
    private const string SESSIONS = 'mobile-sessions';
    private const string THROTTLE = 'mobile-login-throttle';
    private const int LIFETIME = 604800;
    private const int THROTTLE_WINDOW = 900;
    private const int MAX_FAILURES = 5;
    private const array CAPABILITIES = ['health', 'training', 'running', 'activities', 'activity-upload', 'weight-collection', 'mobile-workflows', 'persistent-mobile-sessions'];

    public function __construct(private TrainingRepository $records, private AdminUserProvider $users, private UserPasswordHasherInterface $hasher, private Clock $clock, private LockFactory $locks, private AdminUserName $adminUsername)
    {
    }

    /** @return array<string, mixed> */
    public function create(string $username, #[\SensitiveParameter] string $password, string $deviceName, string $clientIp, bool $persistent = false): array
    {
        if ('' === trim($deviceName) || mb_strlen($deviceName) > 100) {
            throw new TrainingError('deviceName must contain 1..100 characters.');
        }
        $lock = $this->locks->createLock('mobile-session-login', 30);
        if (!$lock->acquire(true)) {
            throw new TrainingError('Login temporarily unavailable.', 429);
        }
        try {
            $now = $this->clock->getCurrentDateTimeImmutable()->getTimestamp();
            // Both buckets are independent of the submitted username and device name.
            // Never trust a body/header supplied address for this purpose.
            $keys = ['account', hash('sha256', $clientIp)];
            $buckets = [];
            foreach ($keys as $key) {
                $bucket = $this->records->find(self::THROTTLE, $key);
                if (null !== $bucket && $bucket['until'] > $now && $bucket['failures'] >= self::MAX_FAILURES) {
                    throw new TrainingError('Too many login attempts. Try again later.', 429);
                }
                $buckets[$key] = $bucket;
            }
            $user = null;
            try {
                $user = $this->users->loadUserByIdentifier((string) $this->adminUsername);
            } catch (UserNotFoundException) {
                // An unconfigured administrator cannot create a native session.
            }
            $check = $user ?? new InMemoryUser('invalid', '$2y$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.', []);
            $valid = $this->hasher->isPasswordValid($check, $password);
            if (null === $user || !hash_equals($user->getUserIdentifier(), $username) || !$valid) {
                foreach ($buckets as $key => $bucket) {
                    $fresh = null === $bucket || $bucket['until'] <= $now;
                    $this->records->save(self::THROTTLE, (string) $key, ['failures' => $fresh ? 1 : $bucket['failures'] + 1, 'until' => $fresh ? $now + self::THROTTLE_WINDOW : $bucket['until']], $bucket['version'] ?? 0);
                }
                throw new TrainingError('Invalid username or password.', 401);
            }
            foreach ($buckets as $key => $bucket) {
                if (null !== $bucket) {
                    $this->records->delete(self::THROTTLE, (string) $key, $bucket['version']);
                }
            }
            $token = 'shmobile_'.bin2hex(random_bytes(32));
            $expiresAt = $persistent ? null : $this->clock->getCurrentDateTimeImmutable()->modify('+'.self::LIFETIME.' seconds')->format(DATE_ATOM);
            $session = $this->records->save(self::SESSIONS, hash('sha256', $token), ['username' => $user->getUserIdentifier(), 'credentialVersion' => $this->credentialVersion($user), 'deviceName' => trim($deviceName), 'persistent' => $persistent, 'expiresAt' => $expiresAt], 0);

            return ['token' => $token, ...$this->metadata($session)];
        } finally {
            $lock->release();
        }
    }

    /** @return array<string, mixed> */
    public function authenticate(#[\SensitiveParameter] string $token): array
    {
        return $this->metadata($this->validSession($token));
    }

    /** @return array<string, mixed> */
    private function validSession(#[\SensitiveParameter] string $token): array
    {
        if (!preg_match('/^shmobile_[a-f0-9]{64}$/D', $token)) {
            throw new TrainingError('Invalid mobile session.', 401);
        }
        $session = $this->records->find(self::SESSIONS, hash('sha256', $token));
        if (null === $session || !$this->hasValidLifetime($session)
            || !is_string($session['username'] ?? null) || !is_string($session['credentialVersion'] ?? null)) {
            throw new TrainingError('Invalid mobile session.', 401);
        }
        try {
            $user = $this->users->loadUserByIdentifier($session['username']);
        } catch (UserNotFoundException) {
            throw new TrainingError('Invalid mobile session.', 401);
        }
        if (!hash_equals($session['credentialVersion'], $this->credentialVersion($user))) {
            throw new TrainingError('Invalid mobile session.', 401);
        }

        return $session;
    }

    /** @return array<string, mixed> */
    public function promote(#[\SensitiveParameter] string $token): array
    {
        $session = $this->validSession($token);
        if (true === ($session['persistent'] ?? false)) {
            return $this->metadata($session);
        }
        // An expected existing version makes this UPDATE-only: a concurrent revoke
        // or promotion cannot turn this operation into an INSERT/resurrection.
        try {
            $promoted = $this->records->save(self::SESSIONS, hash('sha256', $token), [...$session, 'persistent' => true, 'expiresAt' => null], $session['version']);
        } catch (TrainingError $error) {
            if (409 !== $error->status) {
                throw $error;
            }
            $current = $this->validSession($token);
            if (true !== ($current['persistent'] ?? false)) {
                throw $error;
            }
            $promoted = $current;
        }

        return $this->metadata($promoted);
    }

    /** @param array<string, mixed> $session */
    private function hasValidLifetime(array $session): bool
    {
        $persistent = array_key_exists('persistent', $session) ? $session['persistent'] : false;
        if (!is_bool($persistent) || !array_key_exists('expiresAt', $session)) {
            return false;
        }
        if ($persistent) {
            return null === $session['expiresAt'];
        }
        $expiresAt = $session['expiresAt'];
        if (!is_string($expiresAt) || !preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/D', $expiresAt)) {
            return false;
        }
        $expiry = \DateTimeImmutable::createFromFormat('!Y-m-d\TH:i:sP', $expiresAt);

        return false !== $expiry && false === \DateTimeImmutable::getLastErrors()
            && $expiry->format('Y-m-d\TH:i:s') === substr($expiresAt, 0, 19)
            && $expiry->getTimestamp() > $this->clock->getCurrentDateTimeImmutable()->getTimestamp();
    }

    public function revoke(#[\SensitiveParameter] string $token): void
    {
        $this->authenticate($token);
        $session = $this->records->find(self::SESSIONS, hash('sha256', $token)) ?? throw new TrainingError('Invalid mobile session.', 401);
        $this->records->delete(self::SESSIONS, hash('sha256', $token), $session['version']);
    }

    private function credentialVersion(InMemoryUser $user): string
    {
        return hash('sha256', $user->getUserIdentifier()."\0".$user->getPassword());
    }

    /** @param array<string, mixed> $session
     * @return array<string, mixed>
     */
    private function metadata(array $session): array
    {
        return ['persistent' => $session['persistent'] ?? false, 'expiresAt' => $session['expiresAt'], 'username' => $session['username'], 'apiVersion' => 1, 'capabilities' => self::CAPABILITIES];
    }
}
