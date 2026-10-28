<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\DailyCollectionReadModel;
use App\Domain\Training\TrainingError;
use PHPUnit\Framework\TestCase;

final class DailyCollectionReadModelTest extends TestCase
{
    public function testSummariesKeepHrvDistinctAndDoNotLeakEncodedPayload(): void
    {
        $raw = ['id' => 'synthetic', 'kind' => 'hrv_rmssd', 'payload' => ['timestamp' => 1768003200000, 'deviceId' => 'private-device', 'value' => json_encode(['samples' => [['hrv' => 30, 'sdnn' => 99], ['hrv' => 40, 'sdnn' => 88]]])]];
        $row = DailyCollectionReadModel::summarize($raw);
        self::assertSame('RMSSD', $row['metrics'][0]['label']);
        self::assertSame(40.0, $row['metrics'][0]['latest']);
        self::assertSame('ms', $row['metrics'][0]['unit']);
        self::assertStringNotContainsString('private-device', json_encode($row));
        $raw['kind'] = 'hrv_sdnn';
        self::assertSame(88.0, DailyCollectionReadModel::summarize($raw)['metrics'][0]['latest']);
        $raw['kind'] = 'respiratory_rate';
        self::assertSame([], DailyCollectionReadModel::summarize($raw)['metrics']);
        self::assertSame('availability_only', DailyCollectionReadModel::summarize($raw)['interpretation']);
    }

    public function testMissingValuesDoNotBecomeZeroAndSamplesAreSortedBySourceTime(): void
    {
        $row = DailyCollectionReadModel::summarize(['id' => 'demo', 'kind' => 'hrv_sdnn', 'payload' => ['value' => ['samples' => [['s' => 20, 'sdnn' => 50], ['s' => 10, 'sdnn' => 30], ['s' => 30, 'sdnn' => null]]]]]);
        self::assertSame(50.0, $row['metrics'][0]['latest']);
        self::assertSame(2, $row['metrics'][0]['count']);
        self::assertNull($row['date']);
    }

    public function testPaginationDetectsTombstoneOrContentChanges(): void
    {
        $rows = [['id' => 'a', 'deleted' => false], ['id' => 'b', 'deleted' => false]];
        $page = DailyCollectionReadModel::page($rows, 0, 1, null);
        self::assertSame(1, $page['nextOffset']);
        self::assertCount(1, DailyCollectionReadModel::page($rows, 1, 1, $page['snapshot'])['records']);
        $rows[0]['deleted'] = true;
        $this->expectException(TrainingError::class);
        DailyCollectionReadModel::page($rows, 1, 1, $page['snapshot']);
    }
}
