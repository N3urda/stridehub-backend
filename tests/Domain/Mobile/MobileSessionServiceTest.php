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
}
