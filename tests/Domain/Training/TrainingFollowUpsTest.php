<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training;

use App\Domain\Training\TrainingActivities;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingFeedback;
use App\Domain\Training\TrainingRepository;
use App\Domain\Training\TrainingService;
use App\Infrastructure\ValueObject\Time\SerializableTimezone;
use App\Tests\Domain\Training\Notification\ReminderTestClock;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

final class TrainingFollowUpsTest extends TestCase
{
    private Connection $db;
    private TrainingService $training;

    protected function setUp(): void
    {
        $this->db = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $this->db->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $this->db->executeStatement('CREATE TABLE TrainingActivityLink (activityId TEXT PRIMARY KEY NOT NULL, sessionId TEXT NOT NULL)');
        $this->db->executeStatement('CREATE TABLE Activity (activityId TEXT PRIMARY KEY, name TEXT, startDateTime TEXT, distance INTEGER, movingTimeInSeconds INTEGER, elapsedTimeInSeconds INTEGER, averageHeartRate INTEGER, sportType TEXT, markedForDeletion INTEGER)');
        $clock = new ReminderTestClock('2026-10-18T18:00:00Z'); // October 19 in the profile timezone.
        $repository = new TrainingRepository($this->db, $clock);
        $this->training = new TrainingService($repository, new TrainingActivities($this->db, new SerializableTimezone('Asia/Shanghai')), $clock);
    }

    protected function tearDown(): void
    {
        $this->db->close();
    }

    public function testOlderUnresolvedWorkRemainsDiscoverableAndCanBePagedWithoutMutation(): void
    {
        $this->session('old', '2026-07-01T06:00:00+08:00');
        $this->session('boundary', '2026-09-20T00:00:00+08:00');
        $this->session('recent', '2026-10-18T06:00:00+08:00');
        $this->session('local-today', '2026-10-18T17:00:00Z');
        $before = $this->db->fetchAllAssociative('SELECT * FROM TrainingRecord ORDER BY id');

        $first = $this->training->followUps(pageSize: 2);
        self::assertSame('2026-09-20', $first['from']);
        self::assertSame('2026-10-19', $first['to']);
        self::assertSame(3, $first['totalItems']);
        self::assertSame(2, $first['totalPages']);
        self::assertSame(['boundary', 'recent'], array_column(array_column($first['items'], 'session'), 'id'));
        self::assertSame(1, $first['olderPendingCount']);
        self::assertSame('2026-07-01', $first['earliestPendingDate']);
        $last = $this->training->followUps(page: 999, pageSize: 2);
        self::assertSame(2, $last['page']);
        self::assertSame(['local-today'], array_column(array_column($last['items'], 'session'), 'id'));
        $old = $this->training->followUps('2026-07-01', '2026-07-31');
        self::assertSame(['old'], array_column(array_column($old['items'], 'session'), 'id'));
        self::assertSame(0, $old['olderPendingCount']);
        self::assertNull($old['earliestPendingDate']);
        self::assertSame($before, $this->db->fetchAllAssociative('SELECT * FROM TrainingRecord ORDER BY id'));
    }

    public function testReasonsDistinguishUnknownCompletionFromMatchingFeedbackAndBrokenLinks(): void
    {
        $this->activity('a', '2026-10-18 06:00:00');
        $this->activity('b', '2026-10-18 06:10:00');
        $this->activity('missing', '2026-10-18 07:00:00');
        $this->session('planned', '2026-10-18T06:00:00+08:00');
        $this->session('unlinked', '2026-10-18T06:00:00+08:00', ['status' => 'completed', 'feedback' => ['notes' => '跑完但未导入']]);
        $this->session('overlap', '2026-10-18T06:00:00+08:00', ['activityIds' => ['a', 'b']]);
        $this->session('missing-file', '2026-10-18T07:00:00+08:00', ['activityIds' => ['missing'], 'feedback' => ['pain' => false]]);
        $this->db->update('Activity', ['markedForDeletion' => 1], ['activityId' => 'missing']);
        $result = $this->training->followUps();
        $reasons = [];
        foreach ($result['items'] as $item) {
            $reasons[$item['session']['id']] = $item['reasons'];
        }
        self::assertSame(['unrecorded'], $reasons['planned']);
        self::assertSame(['unlinked'], $reasons['unlinked']);
        self::assertSame(['needs_review', 'feedback'], $reasons['overlap']);
        self::assertSame(['needs_review'], $reasons['missing-file']);
        self::assertContains('overlap', array_column(array_column($this->training->reconciliation()['items'], 'planned'), 'id'));
        self::assertSame('planned', $this->training->get('sessions', 'planned')['status']);

        $this->training->update('sessions', 'overlap', ['version' => 1, 'activityIds' => ['a'], 'feedback' => ['rpe' => 4]]);
        self::assertNotContains('overlap', array_column(array_column($this->training->followUps()['items'], 'session'), 'id'));
        self::assertNotContains('overlap', array_column(array_column($this->training->reconciliation()['items'], 'planned'), 'id'));
    }

    public function testRestInactiveFutureAndInProgressPlansDoNotCreateRunningFollowUps(): void
    {
        foreach (['planned', 'completed'] as $status) {
            $this->session('rest-'.$status, '2026-10-18T06:00:00+08:00', ['type' => 'rest', 'status' => $status]);
        }
        foreach (['skipped', 'cancelled'] as $status) {
            $this->session($status, '2026-10-18T06:00:00+08:00', ['status' => $status]);
        }
        $this->session('future', '2026-10-19T06:00:00+08:00');
        $this->session('future-completed', '2026-10-19T06:00:00+08:00', ['status' => 'completed']);
        $this->session('in-progress', '2026-10-19T01:45:00+08:00');
        $result = $this->training->followUps(page: 50);
        self::assertSame([], $result['items']);
        self::assertSame(0, $result['totalItems']);
        self::assertSame(1, $result['page']);
        self::assertSame(1, $result['totalPages']);
    }

    /** @param array<string, mixed>|null $feedback */
    #[DataProvider('feedbackExamples')]
    public function testOnlyMeaningfulFeedbackClosesItsTask(?array $feedback, bool $hasContent): void
    {
        self::assertSame($hasContent, TrainingFeedback::hasContent($feedback));
        $this->session('feedback', '2026-10-18T06:00:00+08:00', ['status' => 'completed', 'feedback' => $feedback]);
        self::assertSame($hasContent ? ['unlinked'] : ['unlinked', 'feedback'], $this->training->followUps()['items'][0]['reasons']);
    }

    /** @return iterable<string, array{array<string, mixed>|null, bool}> */
    public static function feedbackExamples(): iterable
    {
        yield 'missing' => [null, false];
        yield 'empty object' => [[], false];
        yield 'all unknown' => [['rpe' => null, 'thermalFeeling' => null, 'pain' => null, 'notes' => '  ', 'fuel' => ['carbsGrams' => null, 'fluidMl' => null, 'giComfort' => 'unknown', 'notes' => '']], false];
        yield 'no pain is a real response' => [['pain' => false], true];
        yield 'perceived effort' => [['rpe' => 4], true];
        yield 'text feedback' => [['notes' => '轻松完成'], true];
        yield 'explicit zero fuel' => [['fuel' => ['fluidMl' => 0]], true];
        yield 'gut feedback' => [['fuel' => ['giComfort' => 'good']], true];
    }

    public function testRangeAndPageBoundsAreValidated(): void
    {
        self::assertSame(0, $this->training->followUps('2025-10-19', '2026-10-19')['totalItems']);
        foreach ([['2025-10-18', '2026-10-19', 1, 20], ['2026-10-20', '2026-10-19', 1, 20], ['bad', '2026-10-19', 1, 20], [null, null, 0, 20], [null, null, 1, 51]] as $arguments) {
            try {
                $this->training->followUps(...$arguments);
                self::fail('Expected invalid follow-up range to fail.');
            } catch (TrainingError $error) {
                self::assertSame(422, $error->status);
            }
        }
    }

    /** @param array<string, mixed> $changes */
    private function session(string $id, string $start, array $changes = []): void
    {
        $this->training->create('sessions', [...['id' => $id, 'title' => $id, 'startAt' => $start, 'durationMinutes' => 30, 'type' => 'easy'], ...$changes]);
    }

    private function activity(string $id, string $start): void
    {
        $this->db->insert('Activity', ['activityId' => $id, 'name' => $id, 'startDateTime' => $start, 'distance' => 5000, 'movingTimeInSeconds' => 1800, 'elapsedTimeInSeconds' => 1800, 'averageHeartRate' => 140, 'sportType' => 'Run', 'markedForDeletion' => 0]);
    }
}
