<?php

declare(strict_types=1);

namespace App\Tests\Domain\Mobile;

use App\Domain\Mobile\MobileSessionService;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Security\AdminPasswordHash;
use App\Infrastructure\Security\AdminUserName;
use App\Infrastructure\Security\AdminUserProvider;
use App\Tests\Domain\Training\Notification\ReminderTestClock;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\Lock\Store\InMemoryStore;
use Symfony\Component\PasswordHasher\Hasher\PasswordHasherFactory;
use Symfony\Component\PasswordHasher\Hasher\UserPasswordHasher;
use Symfony\Component\Security\Core\User\InMemoryUser;

final class MobileSessionServiceTest extends TestCase
{
    private Connection $connection;
    private TrainingRepository $records;
    private ReminderTestClock $clock;
    private string $passwordHash;

    protected function setUp(): void
    {
        $this->connection = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $this->connection->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $this->clock = new ReminderTestClock('2026-10-23T00:00:00Z');
        $this->records = new TrainingRepository($this->connection, $this->clock);
        $this->passwordHash = password_hash('correct-password', PASSWORD_BCRYPT, ['cost' => 4]);
    }

    protected function tearDown(): void
    {
        $this->connection->close();
    }

    private function service(?string $username = null, ?string $passwordHash = null): MobileSessionService
    {
        return new MobileSessionService($this->records, new AdminUserProvider(AdminUserName::fromString($username ?? 'admin'), AdminPasswordHash::fromString($passwordHash ?? $this->passwordHash)), new UserPasswordHasher(new PasswordHasherFactory([InMemoryUser::class => ['algorithm' => 'bcrypt', 'cost' => 4]])), $this->clock, new LockFactory(new InMemoryStore()), AdminUserName::fromString($username ?? 'admin'));
    }

    public function testSevenDayBoundaryExpiresWithoutARefresh(): void
    {
        $service = $this->service();
        $session = $service->create('admin', 'correct-password', 'Phone', '127.0.0.1');
        self::assertFalse($session['persistent']);
        self::assertSame('2026-10-30T00:00:00+00:00', $session['expiresAt']);
        $this->clock->now = '2026-10-29T23:59:59Z';
        self::assertSame('admin', $service->authenticate($session['token'])['username']);
        $this->clock->now = '2026-10-30T00:00:00Z';
        $this->expectException(TrainingError::class);
        $service->authenticate($session['token']);
    }

    public function testChangingPasswordOrUsernameInvalidatesExistingSessions(): void
    {
        $token = $this->service()->create('admin', 'correct-password', 'Phone', '127.0.0.1')['token'];
        foreach ([$this->service('changed-admin'), $this->service(passwordHash: password_hash('new-password', PASSWORD_BCRYPT, ['cost' => 4]))] as $changed) {
            try {
                $changed->authenticate($token);
                self::fail('Credentials changed but old session was accepted.');
            } catch (TrainingError $error) {
                self::assertSame(401, $error->status);
            }
        }
    }

    public function testRevokingOneDeviceDoesNotLogOutAnotherDevice(): void
    {
        $service = $this->service();
        $first = $service->create('admin', 'correct-password', 'Phone A', '127.0.0.1');
        $second = $service->create('admin', 'correct-password', 'Phone B', '127.0.0.1');
        self::assertNotSame($first['token'], $second['token']);
        $service->revoke($first['token']);
        self::assertSame('admin', $service->authenticate($second['token'])['username']);
        $this->expectException(TrainingError::class);
        $service->authenticate($first['token']);
    }

    public function testThrottleCannotBeBypassedByChangingUsernameOrDeviceAndEventuallyRecovers(): void
    {
        $service = $this->service();
        for ($i = 0; $i < 5; ++$i) {
            try {
                $service->create('missing-'.$i, 'wrong-password', 'Phone '.$i, '127.0.0.1');
                self::fail('Invalid credentials accepted.');
            } catch (TrainingError $error) {
                self::assertSame(401, $error->status);
            }
        }
        try {
            $service->create('admin', 'correct-password', 'Phone', '127.0.0.1');
            self::fail('Throttle was bypassed.');
        } catch (TrainingError $error) {
            self::assertSame(429, $error->status);
        }
        $this->clock->now = '2026-10-23T00:15:00Z';
        self::assertSame('admin', $service->create('admin', 'correct-password', 'Phone', '127.0.0.1')['username']);
    }

    public function testPersistentSessionSurvivesYearsButRemainsRevocable(): void
    {
        $service = $this->service();
        $session = $service->create('admin', 'correct-password', 'Phone', '127.0.0.1', persistent: true);
        self::assertTrue($session['persistent']);
        self::assertNull($session['expiresAt']);
        self::assertContains('persistent-mobile-sessions', $session['capabilities']);
        foreach (['2026-11-01T00:00:00Z', '2036-10-23T00:00:00Z'] as $date) {
            $this->clock->now = $date;
            self::assertTrue($service->authenticate($session['token'])['persistent']);
        }
        $service->revoke($session['token']);
        $this->expectException(TrainingError::class);
        $service->authenticate($session['token']);
    }

    public function testPersistentSessionStillInvalidatesWhenCredentialsChange(): void
    {
        $token = $this->service()->create('admin', 'correct-password', 'Phone', '127.0.0.1', persistent: true)['token'];
        foreach ([$this->service('changed-admin'), $this->service(passwordHash: password_hash('new-password', PASSWORD_BCRYPT, ['cost' => 4]))] as $changed) {
            foreach (['authenticate', 'promote'] as $method) {
                try {
                    $changed->$method($token);
                    self::fail('Changed credentials accepted an old persistent session.');
                } catch (TrainingError $error) {
                    self::assertSame(401, $error->status);
                }
            }
        }
    }

    public function testPromotionKeepsSameTokenAndBusinessDataAndIsIdempotent(): void
    {
        $service = $this->service();
        $session = $service->create('admin', 'correct-password', 'Phone', '127.0.0.1');
        $id = hash('sha256', $session['token']);
        $row = $this->records->find('mobile-sessions', $id);
        unset($row['persistent']); // A session issued by 0.3.1 has no flag.
        $this->records->save('mobile-sessions', $id, $row, $row['version']);
        $business = $this->records->save('health-context', 'default', ['notes' => 'Keep my data'], 0);
        self::assertFalse($service->authenticate($session['token'])['persistent']);
        $promoted = $service->promote($session['token']);
        self::assertTrue($promoted['persistent']);
        self::assertNull($promoted['expiresAt']);
        self::assertArrayNotHasKey('token', $promoted);
        $version = $this->records->find('mobile-sessions', $id)['version'];
        self::assertSame($promoted, $service->promote($session['token']));
        self::assertSame($version, $this->records->find('mobile-sessions', $id)['version']);
        self::assertSame($business, $this->records->find('health-context', 'default'));
        self::assertCount(1, $this->records->list('mobile-sessions'));
        $this->clock->now = '2046-10-23T00:00:00Z';
        self::assertSame('admin', $service->authenticate($session['token'])['username']);
    }

    #[DataProvider('inactiveSessions')]
    public function testPromotionCannotReviveExpiredOrRevokedSessions(bool $revoke): void
    {
        $service = $this->service();
        $token = $service->create('admin', 'correct-password', 'Phone', '127.0.0.1')['token'];
        if ($revoke) {
            $service->revoke($token);
        } else {
            $this->clock->now = '2026-10-30T00:00:00Z';
        }
        try {
            $service->promote($token);
            self::fail('Inactive session was promoted.');
        } catch (TrainingError $error) {
            self::assertSame(401, $error->status);
        }
        $stored = $this->records->find('mobile-sessions', hash('sha256', $token));
        self::assertFalse($stored['persistent'] ?? false);
    }

    public static function inactiveSessions(): iterable
    {
        yield 'expired at seven-day boundary' => [false];
        yield 'revoked' => [true];
    }

    public function testConcurrentDeletionDuringPromotionCannotReinsertTheSession(): void
    {
        $service = $this->service();
        $token = $service->create('admin', 'correct-password', 'Phone', '127.0.0.1')['token'];
        $this->connection->executeStatement("CREATE TRIGGER delete_promoted_session BEFORE UPDATE ON TrainingRecord WHEN OLD.kind = 'mobile-sessions' BEGIN DELETE FROM TrainingRecord WHERE kind = OLD.kind AND id = OLD.id; END");
        try {
            $service->promote($token);
            self::fail('Concurrent revoke must prevent promotion.');
        } catch (TrainingError $error) {
            self::assertContains($error->status, [401, 409]);
        }
        self::assertNull($this->records->find('mobile-sessions', hash('sha256', $token)));
    }

    public function testConcurrentPromotionReadsBackTheExistingPersistentVersion(): void
    {
        $service = $this->service();
        $token = $service->create('admin', 'correct-password', 'Phone', '127.0.0.1')['token'];
        $this->connection->executeStatement("CREATE TRIGGER promote_session_concurrently BEFORE UPDATE ON TrainingRecord WHEN OLD.kind = 'mobile-sessions' AND json_extract(OLD.payload, '$.persistent') = 0 BEGIN UPDATE TrainingRecord SET payload = json_set(payload, '$.persistent', json('true'), '$.expiresAt', NULL), version = version + 1 WHERE kind = OLD.kind AND id = OLD.id; SELECT RAISE(IGNORE); END");
        $metadata = $service->promote($token);
        self::assertTrue($metadata['persistent']);
        self::assertNull($metadata['expiresAt']);
        $stored = $this->records->find('mobile-sessions', hash('sha256', $token));
        self::assertSame(2, $stored['version']);
        self::assertSame($metadata, $service->authenticate($token));
    }

    #[DataProvider('invalidExpirations')]
    public function testMalformedExpirationFailsClosed(array $changes, bool $removeExpiry = false): void
    {
        $service = $this->service();
        $token = $service->create('admin', 'correct-password', 'Phone', '127.0.0.1')['token'];
        $id = hash('sha256', $token);
        $row = $this->records->find('mobile-sessions', $id);
        $payload = array_replace($row, $changes);
        if ($removeExpiry) {
            unset($payload['expiresAt']);
        }
        $this->records->save('mobile-sessions', $id, $payload, $row['version']);
        foreach (['authenticate', 'promote'] as $method) {
            try {
                $service->$method($token);
                self::fail('Malformed expiration was accepted.');
            } catch (TrainingError $error) {
                self::assertSame(401, $error->status);
            }
        }
    }

    public static function invalidExpirations(): iterable
    {
        yield 'legacy null is not persistent' => [['expiresAt' => null]];
        yield 'missing expiry' => [[], true];
        yield 'numeric expiry' => [['expiresAt' => 9999999999]];
        yield 'array expiry' => [['expiresAt' => []]];
        yield 'invalid calendar date' => [['expiresAt' => '2026-11-31T00:00:00+00:00']];
        yield 'relative expiry' => [['expiresAt' => 'tomorrow']];
        yield 'string flag is not opt-in' => [['persistent' => 'true', 'expiresAt' => null]];
        yield 'numeric flag is not opt-in' => [['persistent' => 1, 'expiresAt' => null]];
        yield 'null flag with finite expiry' => [['persistent' => null]];
        yield 'string flag with finite expiry' => [['persistent' => 'true']];
        yield 'persistent flag needs explicit null expiry' => [['persistent' => true]];
        yield 'persistent flag needs expiry key' => [['persistent' => true], true];
    }
}
