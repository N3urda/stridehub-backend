<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training;

use App\Domain\Health\HealthContextService;
use App\Domain\Training\TrainingActivities;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Domain\Training\TrainingService;
use App\Domain\Training\TrainingToday;
use App\Tests\Domain\Training\Notification\ReminderTestClock;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use PHPUnit\Framework\TestCase;

final class TrainingTodayTest extends TestCase
{
    private Connection $connection;
    private TrainingRepository $repository;
    private TrainingService $training;
    private ReminderTestClock $clock;

    protected function setUp(): void
    {
        $this->connection = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $this->connection->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $this->connection->executeStatement('CREATE TABLE TrainingActivityLink (activityId VARCHAR(255) NOT NULL PRIMARY KEY, sessionId VARCHAR(80) NOT NULL)');
        $this->connection->executeStatement('CREATE TABLE Activity (activityId TEXT PRIMARY KEY, name TEXT, startDateTime TEXT, distance INTEGER, movingTimeInSeconds INTEGER, elapsedTimeInSeconds INTEGER, averageHeartRate INTEGER, sportType TEXT, markedForDeletion INTEGER)');
        $this->clock = new ReminderTestClock('2026-09-28T16:30:00Z');
        $this->repository = new TrainingRepository($this->connection, $this->clock);
        $this->training = new TrainingService($this->repository, new TrainingActivities($this->connection), $this->clock);
        $this->repository->save('profile', 'default', TrainingInput::defaults('profile'), 0);
    }

    public function testTodayUsesProfileLocalCalendarAndKeepsAllSessionStatesWithoutWrites(): void
    {
        $this->session('yesterday', '2026-09-28T15:00:00Z');
        $this->session('today-planned', '2026-09-28T17:00:00Z');
        $this->session('today-completed', '2026-09-28T16:00:00Z', ['status' => 'completed']);
        $this->session('today-skipped', '2026-09-29T02:00:00Z', ['status' => 'skipped']);
        $this->session('today-cancelled', '2026-09-29T03:00:00Z', ['status' => 'cancelled']);
        $this->session('tomorrow', '2026-09-29T16:00:00Z');
        $this->repository->save('check-ins', '2026-09-28', ['date' => '2026-09-28', 'fatigue' => 5], 0);
        $this->repository->save('check-ins', '2026-09-29', ['date' => '2026-09-29', 'sleepHours' => 6.5, 'fatigue' => null, 'soreness' => null, 'pain' => null], 0);
        $before = $this->connection->fetchAllAssociative('SELECT * FROM TrainingRecord ORDER BY kind,id');
        $result = $this->today()->get();
        self::assertSame('2026-09-29', $result['date']);
        self::assertSame('Asia/Shanghai', $result['timezone']);
        self::assertSame('2026-09-28T16:30:00+00:00', $result['generatedAt']);
        self::assertSame(['today-completed', 'today-planned', 'today-skipped', 'today-cancelled'], array_column($result['sessions'], 'id'));
        self::assertSame('2026-09-29', $result['checkIn']['date']);
        self::assertNull($result['checkIn']['pain']);
        self::assertSame($this->training->reconciliation(), $result['reconciliation']);
        self::assertSame($before, $this->connection->fetchAllAssociative('SELECT * FROM TrainingRecord ORDER BY kind,id'));
    }

    public function testPendingFeedbackIncludesOnlyCompletedRecentPastSessionsWithoutFeedback(): void
    {
        $this->session('old', '2026-09-22T20:00:00+08:00', ['status' => 'completed']);
        $this->session('boundary', '2026-09-23T00:00:00+08:00', ['status' => 'completed']);
        $this->session('yesterday', '2026-09-28T20:00:00+08:00', ['status' => 'completed']);
        $this->session('today', '2026-09-29T00:00:00+08:00', ['status' => 'completed']);
        $this->session('future', '2026-09-29T10:00:00+08:00', ['status' => 'completed']);
        $this->session('with-feedback', '2026-09-28T20:00:00+08:00', ['status' => 'completed', 'feedback' => ['notes' => '仅文字反馈']]);
        $this->session('planned', '2026-09-28T20:00:00+08:00');
        $ids = array_column($this->today()->get()['pendingFeedback'], 'id');
        sort($ids);
        self::assertSame(['boundary', 'today', 'yesterday'], $ids);
    }

    public function testDatedActiveHealthConstraintsApplyWithoutExpiringOnReviewDate(): void
    {
        $this->repository->save('health-context', 'default', ['reports' => [], 'constraints' => [
            $this->constraint('undated'),
            $this->constraint('starts-today', ['validFrom' => '2026-09-29']),
            $this->constraint('needs-review', ['validFrom' => '2026-09-01', 'reviewOn' => '2026-09-10']),
            $this->constraint('future', ['validFrom' => '2026-09-30']),
            $this->constraint('inactive', ['status' => 'inactive']),
        ], 'lifestylePreferences' => [], 'notes' => ''], 0);
        $health = $this->today()->get()['health'];
        self::assertSame(1, $health['version']);
        self::assertSame(['undated', 'starts-today', 'needs-review'], array_column($health['constraints'], 'id'));
    }

    public function testEmptyTodayRemainsUnknownAndDoesNotCreateHabitualOrHealthRecords(): void
    {
        $result = $this->today()->get();
        self::assertSame([], $result['sessions']);
        self::assertNull($result['checkIn']);
        self::assertSame(['version' => 0, 'constraints' => []], $result['health']);
        self::assertSame([], $result['pendingFeedback']);
        self::assertSame(['items' => []], $result['reconciliation']);
        self::assertNotEmpty($result['dataQuality']);
        self::assertNull($this->repository->find('health-context', 'default'));
        self::assertSame([], $this->repository->list('sessions'));
    }

    private function today(): TrainingToday
    {
        self::assertTrue(class_exists(TrainingToday::class), 'Today aggregation has not been implemented.');

        return new TrainingToday($this->training, $this->repository, $this->clock, new HealthContextService($this->repository));
    }

    /** @param array<string, mixed> $changes */
    private function session(string $id, string $start, array $changes = []): void
    {
        $this->repository->save('sessions', $id, array_replace(TrainingInput::defaults('sessions'), ['title' => $id, 'startAt' => $start, 'durationMinutes' => 30, 'type' => 'easy'], $changes), 0);
    }

    /** @param array<string, mixed> $changes
     * @return array<string, mixed>
     */
    private function constraint(string $id, array $changes = []): array
    {
        return array_replace(['id' => $id, 'description' => '来源明确的原文限制', 'sourceType' => 'clinician', 'sourceReportId' => null, 'validFrom' => null, 'reviewOn' => null, 'status' => 'active'], $changes);
    }
}
