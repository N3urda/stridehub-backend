<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training;

use App\Domain\Training\TrainingActivities;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Domain\Training\TrainingService;
use App\Infrastructure\Time\Clock\Clock;
use App\Infrastructure\ValueObject\Time\SerializableDateTime;
use App\Infrastructure\ValueObject\Time\SerializableTimezone;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use PHPUnit\Framework\TestCase;

final class TrainingReconciliationTest extends TestCase
{
    private Connection $db;
    private TrainingRepository $repository;
    private TrainingService $training;

    protected function setUp(): void
    {
        $this->db = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $this->db->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $this->db->executeStatement('CREATE TABLE TrainingActivityLink (activityId TEXT PRIMARY KEY NOT NULL, sessionId TEXT NOT NULL)');
        $this->db->executeStatement('CREATE TABLE Activity (activityId TEXT PRIMARY KEY, name TEXT, startDateTime TEXT, distance INTEGER, movingTimeInSeconds INTEGER, elapsedTimeInSeconds INTEGER, averageHeartRate INTEGER, sportType TEXT, markedForDeletion INTEGER)');
        $clock = $this->createStub(Clock::class);
        $clock->method('getCurrentDateTimeImmutable')->willReturn(SerializableDateTime::fromString('2026-09-27T18:00:00Z'));
        $this->repository = new TrainingRepository($this->db, $clock);
        $this->training = new TrainingService($this->repository, new TrainingActivities($this->db, new SerializableTimezone('Asia/Shanghai')), $clock);
    }

    protected function tearDown(): void
    {
        $this->db->close();
    }

    public function testLegacyStoredSingleLinkIsNormalizedOnEveryRead(): void
    {
        $payload = [...TrainingInput::defaults('sessions'), ...$this->session(), 'activityId' => 'run-a'];
        unset($payload['id'], $payload['activityIds']);
        $this->db->insert('TrainingRecord', ['kind' => 'sessions', 'id' => 'run', 'payload' => json_encode($payload, JSON_THROW_ON_ERROR), 'version' => 7, 'updatedAt' => '2026-09-27T00:00:00Z', 'naturalKey' => 'run-a']);
        self::assertSame(['run-a'], $this->training->get('sessions', 'run')['activityIds'] ?? []);
        self::assertSame(['run-a'], $this->training->list('sessions')[0]['activityIds'] ?? []);
        self::assertSame(7, $this->training->get('sessions', 'run')['version']);
    }

    public function testSplitFilesAggregateOnlyRecordedMovementAndMeasuredHeartRate(): void
    {
        $this->activity('warmup', '2026-09-27 06:30:00', 2000, 600, 120);
        $this->activity('main', '2026-09-27 06:50:00', 8000, 2400, 150);
        $this->activity('cooldown', '2026-09-27 07:35:00', 1000, 300);
        $saved = $this->training->create('sessions', [...$this->session(), 'activityIds' => ['warmup', 'main', 'cooldown']]);
        self::assertSame('warmup', $saved['activityId']);
        self::assertSame('completed', $saved['status']);
        $comparison = $this->training->comparison('run');
        self::assertSame(['warmup', 'main', 'cooldown'], array_column($comparison['actualActivities'], 'id'));
        self::assertEquals(11, $comparison['actual']['distanceKm']);
        self::assertEquals(55, $comparison['actual']['durationMinutes']);
        self::assertEquals(144, $comparison['actual']['averageHeartRate']);
        self::assertEquals(['distanceKm' => 1, 'durationMinutes' => -5], $comparison['delta']);
        self::assertStringContainsString('心率', implode(' ', $comparison['dataQuality']));
        self::assertStringContainsString('分段', implode(' ', $comparison['dataQuality']));
        self::assertSame([], $comparison['candidates']);
    }

    public function testEveryLinkedActivityIsExclusiveAndFailedReplacementRollsBack(): void
    {
        foreach (['a', 'b', 'c'] as $id) {
            $this->activity($id);
        }
        $this->training->create('sessions', [...$this->session('one'), 'activityIds' => ['a', 'b']]);
        $this->training->create('sessions', [...$this->session('two'), 'activityIds' => ['c']]);
        $this->assertError(409, fn () => $this->training->update('sessions', 'two', ['version' => 1, 'activityIds' => ['c', 'b'], 'title' => 'must not save']));
        self::assertSame(['c'], $this->training->get('sessions', 'two')['activityIds']);
        self::assertSame('测试长跑', $this->training->get('sessions', 'two')['title']);
        self::assertSame(1, $this->training->get('sessions', 'two')['version']);
        self::assertSame(['a' => 'one', 'b' => 'one', 'c' => 'two'], $this->claims());
        $this->assertError(409, fn () => $this->training->update('sessions', 'one', ['version' => 0, 'activityIds' => []]));
        self::assertSame(['a' => 'one', 'b' => 'one', 'c' => 'two'], $this->claims());
    }

    public function testLegacyReplacementUnlinkAndDeleteReleaseClaimsAtomically(): void
    {
        foreach (['a', 'b', 'c'] as $id) {
            $this->activity($id);
        }
        $this->training->create('sessions', [...$this->session(), 'activityIds' => ['a', 'b']]);
        $changed = $this->training->update('sessions', 'run', ['version' => 1, 'activityId' => 'c']);
        self::assertSame(['c'], $changed['activityIds']);
        self::assertSame(['c' => 'run'], $this->claims());
        $unlinked = $this->training->update('sessions', 'run', ['version' => 2, 'activityId' => null]);
        self::assertSame([], $unlinked['activityIds']);
        self::assertNull($unlinked['activityId']);
        self::assertSame('completed', $unlinked['status']);
        self::assertSame([], $this->claims());
        $this->training->update('sessions', 'run', ['version' => 3, 'activityIds' => ['a', 'b']]);
        $this->assertError(409, fn () => $this->training->delete('sessions', 'run', 3));
        self::assertSame(['a' => 'run', 'b' => 'run'], $this->claims());
        $this->training->delete('sessions', 'run', 4);
        self::assertSame([], $this->claims());
        $this->training->create('sessions', [...$this->session('replacement'), 'activityIds' => ['b']]);
        self::assertSame(['b' => 'replacement'], $this->claims());
    }

    public function testUnrelatedUpdatesPreserveAllLinksAndContradictoryFieldsAreRejected(): void
    {
        $this->activity('a');
        $this->activity('b');
        $this->training->create('sessions', [...$this->session(), 'activityId' => 'a', 'activityIds' => ['a', 'b']]);
        $updated = $this->training->update('sessions', 'run', ['version' => 1, 'notes' => '保留所有关联']);
        self::assertSame(['a', 'b'], $updated['activityIds']);
        $this->assertError(422, fn () => $this->training->update('sessions', 'run', ['version' => 2, 'activityId' => 'b', 'activityIds' => ['a', 'b']]));
        $this->assertError(422, fn () => $this->training->update('sessions', 'run', ['version' => 2, 'activityId' => null, 'activityIds' => ['a']]));
        self::assertSame(2, $this->training->get('sessions', 'run')['version']);
    }

    public function testMissingAndDuplicateActivityIdsNeverSavePartialLinks(): void
    {
        $this->activity('a');
        $this->assertError(422, fn () => $this->training->create('sessions', [...$this->session(), 'activityIds' => ['a', 'missing']]));
        $this->assertError(422, fn () => $this->training->create('sessions', [...$this->session(), 'activityIds' => ['a', 'a']]));
        self::assertSame([], $this->training->list('sessions'));
        self::assertSame([], $this->claims());
    }

    public function testBatchRollsBackEarlierRecordsAndClaimsOnLaterCollision(): void
    {
        $this->activity('a');
        $this->assertError(409, fn () => $this->training->batch([
            [...$this->session('one'), 'version' => 0, 'activityIds' => ['a']],
            [...$this->session('two'), 'version' => 0, 'activityIds' => ['a']],
        ]));
        self::assertSame([], $this->training->list('sessions'));
        self::assertSame([], $this->claims());
    }

    public function testBatchCanAtomicallyCorrectSwappedAssignmentsInEitherOrder(): void
    {
        $this->activity('a');
        $this->activity('b');
        $this->training->create('sessions', [...$this->session('one'), 'activityIds' => ['a']]);
        $this->training->create('sessions', [...$this->session('two'), 'activityIds' => ['b']]);
        $result = $this->training->batch([
            ['id' => 'one', 'version' => 1, 'activityIds' => ['b']],
            ['id' => 'two', 'version' => 1, 'activityIds' => ['a']],
        ]);
        self::assertSame(['b', 'a'], array_column($result, 'activityId'));
        self::assertSame(['a' => 'two', 'b' => 'one'], $this->claims());
        $this->assertError(409, fn () => $this->training->batch([
            ['id' => 'one', 'version' => 2, 'activityIds' => []],
            ['id' => 'two', 'version' => 1, 'activityIds' => ['a', 'b']],
        ]));
        self::assertSame(['a' => 'two', 'b' => 'one'], $this->claims());
        self::assertSame(['b'], $this->training->get('sessions', 'one')['activityIds']);
    }

    public function testCandidatesIncludeLateAndCrossMidnightRunsButExcludeAllClaimedFiles(): void
    {
        $this->activity('late', '2026-09-27 20:00:00');
        $this->activity('midnight', '2026-09-28 02:00:00');
        $this->activity('outside', '2026-09-28 12:00:00');
        $this->activity('claimed-first', '2026-09-27 20:30:00');
        $this->activity('claimed-second', '2026-09-27 21:00:00');
        $this->training->create('sessions', [...$this->session('occupied'), 'activityIds' => ['claimed-first', 'claimed-second']]);
        $this->training->create('sessions', [...$this->session(), 'startAt' => '2026-09-27T22:30:00+08:00']);
        $comparison = $this->training->comparison('run');
        self::assertSame(['late', 'midnight'], array_column($comparison['candidates'], 'id'));
        self::assertEquals(-150, $comparison['candidates'][0]['startOffsetMinutes']);
        self::assertEquals(210, $comparison['candidates'][1]['startOffsetMinutes']);
        self::assertNotEmpty($comparison['candidates'][0]['matchReasons']);
        self::assertSame('planned', $this->training->get('sessions', 'run')['status']);
        self::assertSame(1, $this->training->get('sessions', 'run')['version']);
        self::assertNull($comparison['actual']);
    }

    public function testMissingOrDeletedLinkedFilesAreVisibleAndSuppressMisleadingDelta(): void
    {
        $this->activity('a');
        $this->activity('deleted');
        $this->activity('missing');
        $this->training->create('sessions', [...$this->session(), 'activityIds' => ['a', 'deleted', 'missing']]);
        $this->db->update('Activity', ['markedForDeletion' => 1], ['activityId' => 'deleted']);
        $this->db->delete('Activity', ['activityId' => 'missing']);
        $comparison = $this->training->comparison('run');
        self::assertSame(['a'], array_column($comparison['actualActivities'], 'id'));
        self::assertNull($comparison['delta']);
        self::assertStringContainsString('deleted', implode(' ', $comparison['dataQuality']));
        self::assertStringContainsString('missing', implode(' ', $comparison['dataQuality']));
        self::assertSame(['run'], array_column(array_column($this->training->reconciliation()['items'], 'planned'), 'id'));
    }

    public function testFeedbackAndNotesRemainEditableWhenPreviouslyLinkedRecordingDisappears(): void
    {
        $this->activity('a');
        $this->training->create('sessions', [...$this->session(), 'activityIds' => ['a']]);
        $this->db->update('Activity', ['markedForDeletion' => 1], ['activityId' => 'a']);
        $saved = $this->training->update('sessions', 'run', ['version' => 1, 'feedback' => ['rpe' => 4], 'notes' => '原运动待重新导入']);
        self::assertSame(['rpe' => 4], $saved['feedback']);
        self::assertSame('原运动待重新导入', $saved['notes']);
        self::assertSame(['a'], $saved['activityIds']);
        self::assertSame(['a' => 'run'], $this->claims());
        $comparison = $this->training->comparison('run');
        self::assertNull($comparison['actual']);
        self::assertStringContainsString('a', implode(' ', $comparison['dataQuality']));
        $this->assertError(422, fn () => $this->training->update('sessions', 'run', ['version' => 2, 'activityIds' => ['a']]));
        self::assertSame(2, $this->training->get('sessions', 'run')['version']);
    }

    public function testOverlappingRecordingsWarnWithoutInventingDeduplicatedPerformance(): void
    {
        $this->activity('a', '2026-09-27 06:00:00', 5000, 1800, 150, 2400);
        $this->activity('b', '2026-09-27 06:30:00', 3000, 1200, 140, 1200);
        $this->training->create('sessions', [...$this->session(), 'activityIds' => ['a', 'b']]);
        $comparison = $this->training->comparison('run');
        self::assertStringContainsString('重叠', implode(' ', $comparison['dataQuality']));
        self::assertEquals(50, $comparison['actual']['durationMinutes']);
        self::assertNull($comparison['delta']);
    }

    public function testReconciliationUsesLocalDateAndOnlySessionsNeedingMatching(): void
    {
        $this->training->create('sessions', [...$this->session('past'), 'startAt' => '2026-09-21T06:30:00+08:00']);
        $this->training->create('sessions', [...$this->session('outside'), 'startAt' => '2026-09-20T06:30:00+08:00']);
        $this->training->create('sessions', [...$this->session('future'), 'startAt' => '2026-09-28T06:30:00+08:00']);
        $this->training->create('sessions', [...$this->session('completed'), 'startAt' => '2026-09-28T00:10:00+08:00', 'status' => 'completed']);
        foreach (['skipped', 'cancelled'] as $status) {
            $this->training->create('sessions', [...$this->session($status), 'status' => $status]);
        }
        $this->training->create('sessions', [...$this->session('rest'), 'type' => 'rest']);
        $this->activity('a');
        $this->training->create('sessions', [...$this->session('linked'), 'activityId' => 'a']);
        self::assertSame(['past', 'completed'], array_column(array_column($this->training->reconciliation()['items'], 'planned'), 'id'));
        self::assertSame(['outside', 'past'], array_column(array_column($this->training->reconciliation('2026-09-20', '2026-09-21')['items'], 'planned'), 'id'));
        $this->assertError(422, fn () => $this->training->reconciliation('2026-09-29', '2026-09-28'));
    }

    /** @return array<string, mixed> */
    private function session(string $id = 'run'): array
    {
        return ['id' => $id, 'title' => '测试长跑', 'startAt' => '2026-09-27T06:30:00+08:00', 'durationMinutes' => 60, 'distanceKm' => 10, 'type' => 'long'];
    }

    private function activity(string $id, string $start = '2026-09-27 06:30:00', int $distance = 5000, int $seconds = 1800, ?int $heartRate = null, ?int $elapsed = null): void
    {
        $this->db->insert('Activity', ['activityId' => $id, 'name' => '记录 '.$id, 'startDateTime' => $start, 'distance' => $distance, 'movingTimeInSeconds' => $seconds, 'elapsedTimeInSeconds' => $elapsed, 'averageHeartRate' => $heartRate, 'sportType' => 'Run', 'markedForDeletion' => 0]);
    }

    private function assertError(int $status, callable $operation): void
    {
        try {
            $operation();
            self::fail('Expected TrainingError '.$status);
        } catch (TrainingError $error) {
            self::assertSame($status, $error->status);
        }
    }

    /** @return array<string, string> */
    private function claims(): array
    {
        return $this->db->fetchAllKeyValue('SELECT activityId, sessionId FROM TrainingActivityLink ORDER BY activityId');
    }
}
