<?php

declare(strict_types=1);

namespace App\Domain\Running;

/** Statistics use every valid raw interval, before chart reduction. */
final class RunAnalysis
{
    /** @param array<string, list<mixed>> $streams
     * @param list<array{int, int}> $zoneBounds
     *
     * @return array<string, mixed>
     */
    public function analyze(array $streams, array $zoneBounds = []): array
    {
        $times = $streams['time'] ?? [];
        $timeline = $scatter = $distribution = [];
        $zones = array_map(static fn (array $range, int $i): array => ['name' => 'Z'.($i + 1), 'from' => $range[0], 'to' => $range[1], 'seconds' => 0.0], $zoneBounds, array_keys($zoneBounds));
        $coverage = ['samples' => count($times), 'analyzedSeconds' => 0.0, 'heartRateSeconds' => 0.0, 'paceSeconds' => 0.0, 'gapSeconds' => 0.0, 'pausedSeconds' => 0.0, 'invalidIntervals' => 0];
        $hrSum = $speedSum = $speedSquare = 0.0;
        $highest = -1.0;
        $halves = [['seconds' => 0.0, 'speed' => 0.0, 'heartRate' => 0.0], ['seconds' => 0.0, 'speed' => 0.0, 'heartRate' => 0.0]];
        $last = $this->number($times ? array_last($times) : null, 0, 604800) ?? 0;
        $first = $this->number($times[0] ?? null, 0, 604800) ?? 0;
        $middle = ($first + $last) / 2;
        $stride = max(1, (int) ceil(count($times) / 1400));
        $pendingGap = false;
        foreach ($times as $i => $rawTime) {
            $time = $this->number($rawTime, 0, 604800);
            if (null === $time || $time <= $highest) {
                ++$coverage['invalidIntervals'];
                $pendingGap = true;
                continue;
            }
            $highest = $time;
            $speed = $this->number($streams['velocity_smooth'][$i] ?? null, 0.5, 12);
            $hr = $this->number($streams['heartrate'][$i] ?? null, 30, 250);
            $cadence = $this->number($streams['cadence'][$i] ?? null, 1, 150);
            $moving = $streams['moving'][$i] ?? null;
            $paused = false === $moving || 0 === $moving || (isset($streams['velocity_smooth'][$i]) && 0.0 === (float) $streams['velocity_smooth'][$i]);
            $next = $this->number($times[$i + 1] ?? null, 0, 604800);
            $dt = null === $next ? 0.0 : $next - $time;
            $gap = $dt > 30;
            if (0 === $i % $stride || $i === count($times) - 1) {
                $distanceKm = null === ($distance = $this->number($streams['distance'][$i] ?? null, 0, 1000000)) ? null : round($distance / 1000, 3);
                if ($pendingGap) {
                    $timeline[] = ['seconds' => $time - 0.001, 'distanceKm' => $distanceKm, 'paceSecondsPerKm' => null, 'heartRate' => null, 'cadenceSpm' => null, 'altitudeM' => null, 'powerW' => null];
                    $pendingGap = false;
                }
                $timeline[] = ['seconds' => $time, 'distanceKm' => $distanceKm, 'paceSecondsPerKm' => null === $speed || $paused ? null : round(1000 / $speed, 1), 'heartRate' => $hr, 'cadenceSpm' => null === $cadence || $paused ? null : $cadence * 2, 'altitudeM' => $this->number($streams['altitude'][$i] ?? null, -500, 9000), 'powerW' => $this->number($streams['watts'][$i] ?? null, 0, 2500)];
                if (!$paused && null !== $hr && null !== $speed) {
                    $scatter[] = ['paceSecondsPerKm' => round(1000 / $speed, 1), 'heartRate' => $hr, 'seconds' => $time];
                }
            }
            if ($dt <= 0) {
                if (null !== $next) {
                    ++$coverage['invalidIntervals'];
                    $pendingGap = true;
                }
                continue;
            }
            if ($gap) {
                $coverage['gapSeconds'] += $dt;
                $pendingGap = true;
                continue;
            }
            if ($paused) {
                $coverage['pausedSeconds'] += $dt;
                continue;
            }
            $coverage['analyzedSeconds'] += $dt;
            if (null !== $hr) {
                $hrSum += $hr * $dt;
                $coverage['heartRateSeconds'] += $dt;
                $band = (int) (floor($hr / 10) * 10);
                $distribution[$band] = ($distribution[$band] ?? 0) + $dt;
                foreach ($zones as &$zone) {
                    if ($hr >= $zone['from'] && $hr <= $zone['to']) {
                        $zone['seconds'] += $dt;
                        break;
                    }
                }
                unset($zone);
            }
            if (null !== $speed) {
                $speedSum += $speed * $dt;
                $speedSquare += $speed * $speed * $dt;
                $coverage['paceSeconds'] += $dt;
            }
            if (null !== $speed && null !== $hr) {
                foreach ([max(0, min($next, $middle) - $time), max(0, $next - max($time, $middle))] as $half => $seconds) {
                    $halves[$half]['seconds'] += $seconds;
                    $halves[$half]['speed'] += $speed * $seconds;
                    $halves[$half]['heartRate'] += $hr * $seconds;
                }
            }
        }
        ksort($distribution);
        $meanSpeed = $coverage['paceSeconds'] > 0 ? $speedSum / $coverage['paceSeconds'] : null;
        $cv = null !== $meanSpeed ? sqrt(max(0, $speedSquare / $coverage['paceSeconds'] - $meanSpeed ** 2)) / $meanSpeed * 100 : null;
        $efficiency = null;
        if ($last - $first >= 1200 && null !== $cv && $cv <= 15 && min(array_column($halves, 'seconds')) >= ($last - $first) * 0.45) {
            $a = $halves[0]['speed'] / $halves[0]['heartRate'];
            $b = $halves[1]['speed'] / $halves[1]['heartRate'];
            $efficiency = round(($b / $a - 1) * 100, 2);
        }

        return ['timeline' => $timeline, 'scatter' => $scatter, 'zones' => $zones, 'heartRateDistribution' => array_map(static fn (int $from, float $seconds): array => ['from' => $from, 'to' => $from + 9, 'seconds' => $seconds], array_keys($distribution), array_values($distribution)), 'coverage' => $coverage, 'metrics' => ['weightedHeartRate' => $coverage['heartRateSeconds'] > 0 ? round($hrSum / $coverage['heartRateSeconds'], 1) : null, 'paceSecondsPerKm' => null !== $meanSpeed ? round(1000 / $meanSpeed, 1) : null, 'speedVariationPercent' => null !== $cv ? round($cv, 2) : null, 'efficiencyChangePercent' => $efficiency], 'notes' => [
            '统计按相邻原始时间戳加权；超过 30 秒的记录断档、显式暂停和无效值不计入有效时长。',
            '曲线仅为显示抽样，统计使用全部有效区间。导入器可能已前向填充传感器缺项，覆盖率代表导入数据覆盖，并非硬件测量完整率。',
            '步频沿用 Dreeve 的单脚周期 × 2 口径；不同导出工具的单位可能不同，请结合设备记录核对。',
            '前后半程效率变化仅在至少 20 分钟、两半有效配速/心率覆盖各达 90% 且速度变异不超过 15% 时展示；未做坡度或温度校正，不等同于有氧能力评分。',
        ]];
    }

    private function number(mixed $value, float $min, float $max): ?float
    {
        return (is_int($value) || is_float($value)) && is_finite((float) $value) && $value >= $min && $value <= $max ? (float) $value : null;
    }
}
