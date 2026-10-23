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
    private const array CAPABILITIES = ['health', 'training', 'running', 'activities', 'activity-upload', 'weight-collection', 'mobile-workflows'];

    public function __construct(private TrainingRepository $records, private AdminUserProvider $users, private UserPasswordHasherInterface $hasher, private Clock $clock, private LockFactory $locks, private AdminUserName $adminUsername)
    {
    }

    /** @return array<string, mixed> */
    public function create(string $username, #[\SensitiveParameter] string $password, string $deviceName, string $clientIp): array
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
            $expiresAt = $this->clock->getCurrentDateTimeImmutable()->modify('+'.self::LIFETIME.' seconds')->format(DATE_ATOM);
            $this->records->save(self::SESSIONS, hash('sha256', $token), ['username' => $user->getUserIdentifier(), 'credentialVersion' => $this->credentialVersion($user), 'deviceName' => trim($deviceName), 'expiresAt' => $expiresAt], 0);

            return ['token' => $token, ...$this->metadata(['username' => $user->getUserIdentifier(), 'expiresAt' => $expiresAt])];
        } finally {
            $lock->release();
        }
    }

    /** @return array<string, mixed> */
    public function authenticate(#[\SensitiveParameter] string $token): array
    {
        if (!preg_match('/^shmobile_[a-f0-9]{64}$/D', $token)) {
            throw new TrainingError('Invalid mobile session.', 401);
        }
        $session = $this->records->find(self::SESSIONS, hash('sha256', $token));
        if (null === $session || strtotime($session['expiresAt']) <= $this->clock->getCurrentDateTimeImmutable()->getTimestamp()) {
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

        return $this->metadata($session);
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
        return ['expiresAt' => $session['expiresAt'], 'username' => $session['username'], 'apiVersion' => 1, 'capabilities' => self::CAPABILITIES];
    }
}
