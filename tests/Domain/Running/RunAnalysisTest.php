<?php

declare(strict_types=1);

namespace App\Tests\Domain\Running;

use App\Domain\Running\RunAnalysis;
use PHPUnit\Framework\TestCase;

final class RunAnalysisTest extends TestCase
{
    public function testRecordingGapIsStillVisibleAfterChartDownsampling(): void
    {
        $times = range(0, 10000, 5);
        foreach ($times as $index => &$time) {
            if ($index >= 2) {
                $time += 40;
            }
        }
        unset($time);
        $result = new RunAnalysis()->analyze(['time' => $times, 'distance' => range(0, 30000, 15), 'heartrate' => array_fill(0, count($times), 150)]);
        $gaps = array_values(array_filter($result['timeline'], static fn (array $point): bool => null === $point['heartRate']));
        self::assertCount(1, $gaps);
        self::assertNotNull($gaps[0]['distanceKm'], 'A gap must also break the distance-axis chart.');
    }

    public function testZeroSpeedDoesNotBecomeActiveTimeWhenMovingFlagIsAbsent(): void
    {
        $result = new RunAnalysis()->analyze(['time' => [0, 10, 20], 'velocity_smooth' => [0, 3, 3], 'heartrate' => [100, 150, 150]]);
        self::assertSame(10.0, $result['coverage']['pausedSeconds']);
        self::assertSame(10.0, $result['coverage']['heartRateSeconds']);
        self::assertSame(150.0, $result['metrics']['weightedHeartRate']);
    }

    public function testWeightsHeartRateByElapsedTimeAndExcludesRecordingGapsAndPauses(): void
    {
        $result = new RunAnalysis()->analyze(['time' => [0, 1, 10, 50, 60], 'heartrate' => [100, 160, 180, 150, 150], 'velocity_smooth' => [3, 3, 3, 0, 0], 'moving' => [true, true, true, false, false], 'cadence' => [85, 85, 85, 0, 0]], [[90, 120], [121, 170], [171, 240]]);
        self::assertSame(10.0, $result['coverage']['analyzedSeconds']);
        self::assertSame(40.0, $result['coverage']['gapSeconds']);
        self::assertSame(10.0, $result['coverage']['pausedSeconds']);
        self::assertSame(154.0, $result['metrics']['weightedHeartRate']);
        self::assertSame([1.0, 9.0, 0.0], array_column($result['zones'], 'seconds'));
        self::assertSame(170.0, $result['timeline'][0]['cadenceSpm']);
    }

    public function testMissingSensorsStayNullAndInvalidTimeDoesNotProduceNegativeDurations(): void
    {
        $result = new RunAnalysis()->analyze(['time' => [0, 10, 8, 20], 'velocity_smooth' => [3, 3, 3, 3]]);
        self::assertNull($result['metrics']['weightedHeartRate']);
        self::assertSame([], $result['zones']);
        self::assertNull($result['timeline'][0]['heartRate']);
        self::assertGreaterThan(0, $result['coverage']['invalidIntervals']);
        self::assertNull($result['metrics']['efficiencyChangePercent']);
    }

    public function testChartSamplingDoesNotChangeWeightedStatistics(): void
    {
        $time = range(0, 4000);
        $result = new RunAnalysis()->analyze(['time' => $time, 'heartrate' => array_fill(0, count($time), 150), 'velocity_smooth' => array_fill(0, count($time), 3)]);
        self::assertLessThanOrEqual(1600, count($result['timeline']));
        self::assertSame(4000.0, $result['coverage']['heartRateSeconds']);
        self::assertSame(150.0, $result['metrics']['weightedHeartRate']);
        self::assertSame(0.0, $result['metrics']['efficiencyChangePercent']);
    }
}
