<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training;

use App\Domain\Health\WorkoutCatalog;
use App\Domain\Training\TrainingActivities;
use App\Domain\Training\TrainingRepository;
use App\Domain\Training\TrainingService;
use App\Infrastructure\ValueObject\Time\SerializableTimezone;
use App\Tests\Domain\Training\Notification\ReminderTestClock;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use PHPUnit\Framework\TestCase;

final class TrainingActivitiesCatalogTest extends TestCase
{
    private Connection $db;
    private TrainingRepository $repository;
    private TrainingActivities $activities;
    private ReminderTestClock $clock;

    protected function setUp(): void
    {
        $this->db = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $this->db->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $this->db->executeStatement('CREATE TABLE TrainingActivityLink (activityId TEXT PRIMARY KEY NOT NULL, sessionId TEXT NOT NULL)');
        $this->db->executeStatement('CREATE TABLE Activity (activityId TEXT PRIMARY KEY, name TEXT, startDateTime TEXT, distance INTEGER, movingTimeInSeconds INTEGER, elapsedTimeInSeconds INTEGER, averageHeartRate INTEGER, sportType TEXT, markedForDeletion INTEGER)');
        $this->clock = new ReminderTestClock('2026-10-18T18:00:00Z');
        $this->repository = new TrainingRepository($this->db, $this->clock);
        $zone = new SerializableTimezone('Asia/Shanghai');
        $this->activities = new TrainingActivities($this->db, $zone, new WorkoutCatalog($this->db, $this->repository, $zone));
    }

    protected function tearDown(): void
    {
        $this->db->close();
    }

    public function testBulkLookupRetainsCloudAndAliasIdentityWhileExcludingUnavailableRuns(): void
    {
        $file = ['name' => 'Imported run', 'startDateTime' => '2026-10-18 06:00:00', 'distance' => 5000, 'movingTimeInSeconds' => 1800, 'elapsedTimeInSeconds' => 1800, 'averageHeartRate' => 140, 'sportType' => 'Run', 'markedForDeletion' => 0];
        $this->db->insert('Activity', ['activityId' => 'file', ...$file]);
        $this->db->insert('Activity', [...$file, 'activityId' => 'deleted', 'markedForDeletion' => 1]);
        $this->cloud('cloud-linked');
        $this->cloud('cloud-only');
        $this->cloud('cloud-walk', 6);
        $this->repository->save('workout-links', 'cloud-linked', ['activityId' => 'file'], 0);

        $found = $this->activities->findMany(['cloud-only', 'cloud-linked', 'file', 'cloud-walk', 'deleted', 'missing', 'cloud-only']);
        self::assertCount(3, $found);
        self::assertSame('cloud-only', $found['cloud-only']['id']);
        self::assertSame('file', $found['cloud-linked']['id']);
        self::assertSame($found['file'], $found['cloud-linked']);
        foreach (array_keys($found) as $id) {
            self::assertSame($this->activities->find($id), $found[$id]);
        }
        self::assertSame([], $this->activities->findMany([]));
    }

    public function testCloudLinkedSessionDoesNotBecomeAFollowUpUntilItsRecordDisappears(): void
    {
        $this->cloud('cloud-only');
        $training = new TrainingService($this->repository, $this->activities, $this->clock);
        $training->create('sessions', ['id' => 'cloud-session', 'title' => 'Cloud run', 'startAt' => '2026-10-18T06:00:00+08:00', 'durationMinutes' => 30, 'type' => 'easy', 'activityIds' => ['cloud-only'], 'feedback' => ['pain' => false]]);

        self::assertSame([], $training->followUps()['items']);
        self::assertSame([], $training->reconciliation()['items']);
        $this->repository->delete('zepp-workout', 'cloud-only', 1);
        self::assertSame(['needs_review'], $training->followUps()['items'][0]['reasons']);
    }

    private function cloud(string $id, int $sportType = 1): void
    {
        $this->repository->save('zepp-workout', $id, ['sportType' => $sportType, 'sportName' => 'Cloud workout', 'startedAt' => '2026-10-18T06:00:00+08:00', 'distanceMeters' => 5000, 'durationSeconds' => 1800, 'elapsedSeconds' => 1800, 'averageHeartRateBpm' => 140], 0);
    }
}
