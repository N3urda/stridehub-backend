<?php

declare(strict_types=1);

namespace App\Tests\Infrastructure\Mcp;

use App\Domain\Health\HealthContextService;
use App\Domain\Health\HealthDataService;
use App\Domain\Health\WeightRecordService;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Mcp\McpWorkspace;
use App\Tests\Controller\Admin\AdminWebTestCase;

final class McpWorkspaceTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testPlanIsAtomicAndRetryReturnsItsOriginalReceipt(): void
    {
        $args = ['requestId' => 'week-one', 'reason' => '用户要求的演示计划',
            'training' => [['id' => 'run-one', 'title' => 'DEMO Easy', 'startAt' => '2026-01-10T08:00:00+08:00', 'type' => 'easy', 'durationMinutes' => 30]],
            'nutrition' => [['id' => 'food-one', 'date' => '2026-01-10', 'targets' => ['energyKcal' => -10]]]];
        $result = $this->call('save_plan', $args);
        self::assertFalse($result['ok']);
        self::assertNull($this->getContainer()->get(TrainingRepository::class)->find('sessions', 'run-one'));
        self::assertFalse($this->call('get_operation', ['requestId' => 'week-one'])['data']['found']);
        $args['nutrition'][0]['targets']['energyKcal'] = 2000;
        $first = $this->call('save_plan', $args);
        self::assertTrue($first['ok'], json_encode($first));
        self::assertFalse($first['data']['replayed']);
        $retry = $this->call('save_plan', $args);
        self::assertTrue($retry['data']['replayed']);
        self::assertSame($first['data']['result'], $retry['data']['result']);
        self::assertSame(1, $retry['data']['result']['training'][0]['version']);
        self::assertSame('ai', $retry['data']['result']['nutrition'][0]['source']);
        self::assertSame($args['reason'], $retry['data']['result']['nutrition'][0]['rationale']);
        $args['reason'] = '不同请求';
        self::assertSame('conflict', $this->call('save_plan', $args)['error']['code']);
    }

    public function testGeneratedIdsStableAndStaleUpdateRollsBackOtherWrites(): void
    {
        $first = $this->call('record_progress', ['requestId' => 'log-one', 'meals' => [['date' => '2026-01-10', 'foods' => 'DEMO meal']]]);
        self::assertTrue($first['ok'], json_encode($first));
        $meal = $first['data']['result']['meals'][0];
        self::assertStringStartsWith('mcp-', $meal['id']);
        $result = $this->call('record_progress', ['requestId' => 'log-two',
            'measurements' => [['id' => 'waist', 'date' => '2026-01-10', 'waistCm' => 80]],
            'meals' => [['id' => $meal['id'], 'version' => 9, 'foods' => 'changed']]]);
        self::assertSame('conflict', $result['error']['code']);
        self::assertNull($this->getContainer()->get(TrainingRepository::class)->find('measurements', 'waist'));
        self::assertSame($meal, $this->getContainer()->get(HealthDataService::class)->get('meals', $meal['id']));
    }

    public function testHealthContextMergePreservesUnrelatedReportsAndChecksVersion(): void
    {
        $first = $this->call('update_health_profile', ['requestId' => 'context-one', 'reason' => '用户提供', 'healthContext' => ['version' => 0,
            'reports' => [['id' => 'first', 'title' => 'DEMO first', 'reportDate' => '2026-01-01', 'sourceLabel' => 'DEMO user']]]]);
        self::assertTrue($first['ok'], json_encode($first));
        $second = $this->call('update_health_profile', ['requestId' => 'context-two', 'reason' => '用户提供', 'healthContext' => ['version' => 1,
            'reports' => [['id' => 'second', 'title' => 'DEMO second', 'reportDate' => '2026-01-02', 'sourceLabel' => 'DEMO user']]]]);
        self::assertTrue($second['ok'], json_encode($second));
        self::assertCount(2, $second['data']['result']['healthContext']['reports']);
        $stale = $this->call('update_health_profile', ['requestId' => 'context-stale', 'reason' => '用户提供', 'healthContext' => ['version' => 1, 'notes' => 'stale']]);
        self::assertSame('conflict', $stale['error']['code']);
        self::assertSame(2, $this->getContainer()->get(HealthContextService::class)->get()['version']);
    }

    public function testPaginationDetectsChangesAndOverviewDoesNotInventMissingMeasurements(): void
    {
        $this->call('record_progress', ['requestId' => 'page-setup', 'meals' => [['id' => 'one', 'date' => '2026-01-10', 'foods' => 'DEMO one'], ['id' => 'two', 'date' => '2026-01-11', 'foods' => 'DEMO two']]]);
        $args = ['topic' => 'meals', 'from' => '2026-01-01', 'to' => '2026-01-31', 'limit' => 1];
        $page = $this->call('get_history', $args)['data'];
        self::assertSame(2, $page['total']);
        self::assertSame(1, $page['nextOffset']);
        self::assertSame('conflict', $this->call('get_history', [...$args, 'offset' => 1])['error']['code']);
        self::assertTrue($this->call('get_history', [...$args, 'offset' => 1, 'snapshot' => $page['snapshot']])['ok']);
        $this->call('record_progress', ['requestId' => 'page-edit', 'meals' => [['id' => 'one', 'version' => 1, 'notes' => 'changed']]]);
        self::assertSame('conflict', $this->call('get_history', [...$args, 'offset' => 1, 'snapshot' => $page['snapshot']])['error']['code']);
        $overview = $this->call('get_overview', [])['data'];
        self::assertNull($overview['body']['weight']);
        self::assertNull($overview['workouts']['summary']['distanceKm']);
        self::assertContains('weight_in_range', $overview['missingData']);
    }

    public function testWeightUsesSharedHistoryAndDeletionCanBeRetried(): void
    {
        $version = $this->getContainer()->get(WeightRecordService::class)->list()['version'];
        $result = $this->call('record_progress', ['requestId' => 'weight-one', 'weight' => ['date' => '2026-01-10', 'version' => $version, 'weightKg' => 72]]);
        self::assertTrue($result['ok'], json_encode($result));
        $args = ['requestId' => 'delete-weight', 'recordType' => 'weight', 'id' => '2026-01-10', 'version' => $result['data']['result']['weightVersion']];
        self::assertTrue($this->call('remove_record', $args)['ok']);
        self::assertTrue($this->call('remove_record', $args)['data']['replayed']);
        self::assertNull(array_find($this->getContainer()->get(WeightRecordService::class)->list()['records'], static fn (array $r): bool => '2026-01-10' === $r['date']));
    }

    public function testProgressMergesFeedbackAndKeepsPlanFields(): void
    {
        $plan = $this->call('save_plan', ['requestId' => 'feedback-plan', 'reason' => 'DEMO', 'training' => [
            ['id' => 'easy', 'title' => 'DEMO Easy', 'startAt' => '2026-01-10T08:00:00+08:00', 'type' => 'easy', 'durationMinutes' => 30],
        ]]);
        self::assertTrue($plan['ok']);
        $first = $this->call('record_progress', ['requestId' => 'actual-one',
            'training' => [['id' => 'easy', 'version' => 1, 'feedback' => ['rpe' => 4, 'notes' => 'DEMO 保留备注']]],
            'bloodPressure' => [['id' => 'pressure', 'measuredAt' => '2026-01-10T08:00:00+08:00', 'systolic' => 120, 'diastolic' => 80]],
            'checkIns' => [['id' => 'check', 'date' => '2026-01-10', 'fatigue' => 3]],
        ]);
        self::assertTrue($first['ok'], json_encode($first));
        $second = $this->call('record_progress', ['requestId' => 'actual-two', 'training' => [['id' => 'easy', 'version' => 2, 'feedback' => ['rpe' => 5], 'status' => 'completed']]]);
        self::assertTrue($second['ok'], json_encode($second));
        $saved = $second['data']['result']['training'][0];
        self::assertSame('completed', $saved['status']);
        self::assertSame('DEMO 保留备注', $saved['feedback']['notes']);
        self::assertSame(5, $saved['feedback']['rpe']);
        self::assertSame(30, $saved['durationMinutes']);
    }

    public function testWorkoutDetailsPaginateSensorsAndExcludeRawPayloads(): void
    {
        $repository = $this->getContainer()->get(TrainingRepository::class);
        $repository->save('zepp-workout', 'zepp-one', ['sportType' => 1, 'sportName' => 'DEMO Run', 'startedAt' => '2026-01-10T08:00:00+08:00',
            'distanceMeters' => 5000, 'durationSeconds' => 1800, 'elapsedSeconds' => 1900, 'averageHeartRateBpm' => 130], 0);
        $repository->save('zepp-workout-detail', 'zepp-one', ['sourceSummary' => ['private' => 'raw'], 'sourceDetail' => ['private' => 'raw'],
            'streams' => ['heartRate' => [['at' => 'a', 'bpm' => 120], ['at' => 'b', 'bpm' => 130], ['at' => 'c', 'bpm' => 140]]]], 0);
        $overview = $this->call('get_workout_details', ['workoutId' => 'zepp-one'])['data'];
        self::assertArrayNotHasKey('sourceDetail', $overview['details']['zepp']);
        self::assertArrayNotHasKey('sourceSummary', $overview['details']['zepp']);
        self::assertSame([], $overview['samples']['zepp.heartRate']['records']);
        self::assertSame(3, $overview['samples']['zepp.heartRate']['total']);
        $page = $this->call('get_workout_details', ['workoutId' => 'zepp-one', 'includeSamples' => true, 'offset' => 1, 'limit' => 1])['data'];
        self::assertSame(130, $page['samples']['zepp.heartRate']['records'][0]['bpm']);
        self::assertSame(2, $page['samples']['zepp.heartRate']['nextOffset']);
    }

    /** @param array<string, mixed> $args
     * @return array<string, mixed>
     */
    private function call(string $tool, array $args): array
    {
        return $this->getContainer()->get(McpWorkspace::class)->call($tool, $args)->structuredContent;
    }
}
