<?php

declare(strict_types=1);

namespace App\Domain\Running;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Infrastructure\Time\Clock\Clock;
use Doctrine\DBAL\Connection;

final readonly class RunningOverview
{
    public const string FILTER = "sportType IN ('Run','TrailRun','VirtualRun') AND (markedForDeletion IS NULL OR markedForDeletion = 0)";

    public function __construct(private Connection $db, private Clock $clock)
    {
    }

    /** @return array<string, mixed> */
    public function build(?string $from = null, ?string $to = null, string $sport = 'all', int $page = 1): array
    {
        if (!in_array($sport, ['all', 'Run', 'TrailRun', 'VirtualRun'], true) || $page < 1 || $page > 1000000) {
            throw new TrainingError('跑步类型或页码无效。');
        }
        $now = $this->clock->getCurrentDateTimeImmutable();
        $range = $this->db->fetchAssociative('SELECT MIN(startDateTime) AS first, MAX(startDateTime) AS last FROM Activity WHERE '.self::FILTER);
        $from = 'all' === $from ? substr($range['first'] ?? $now->format('Y-m-d'), 0, 10) : ($from ?? $now->modify('-89 days')->format('Y-m-d'));
        $to ??= $now->format('Y-m-d');
        TrainingInput::date($from);
        TrainingInput::date($to);
        if ($from > $to || (int) substr($to, 0, 4) - (int) substr($from, 0, 4) > 100) {
            throw new TrainingError('日期范围无效，最多支持 100 年。');
        }
        $where = self::FILTER.' AND startDateTime >= ? AND startDateTime < ?';
        $params = [$from.' 00:00:00', new \DateTimeImmutable($to)->modify('+1 day')->format('Y-m-d').' 00:00:00'];
        if ('all' !== $sport) {
            $where .= ' AND sportType = ?';
            $params[] = $sport;
        }
        $rows = $this->db->fetchAllAssociative('SELECT activityId, name, startDateTime, sportType, distance, movingTimeInSeconds, elevation, averageHeartRate, averageCadence, deviceName FROM Activity WHERE '.$where.' ORDER BY startDateTime DESC, activityId', $params);
        $weekly = $monthly = $daily = [];
        $summary = $this->emptyTotals();
        $dayStart = max($from, new \DateTimeImmutable($to)->modify('-365 days')->format('Y-m-d'));
        for ($date = new \DateTimeImmutable($from); $date <= new \DateTimeImmutable($to); $date = $date->modify('+1 day')) {
            $week = $date->modify('monday this week')->format('Y-m-d');
            $month = $date->format('Y-m');
            $weekly[$week] ??= ['date' => $week, ...$this->emptyTotals()];
            $monthly[$month] ??= ['date' => $month, ...$this->emptyTotals()];
            if ($date->format('Y-m-d') >= $dayStart) {
                $daily[$date->format('Y-m-d')] = ['date' => $date->format('Y-m-d'), 'count' => 0, 'distanceKm' => 0.0];
            }
        }
        $activeDays = [];
        foreach ($rows as $row) {
            $day = substr($row['startDateTime'], 0, 10);
            $week = new \DateTimeImmutable($day)->modify('monday this week')->format('Y-m-d');
            $month = substr($day, 0, 7);
            $activeDays[$day] = true;
            $this->add($summary, $row);
            $this->add($weekly[$week], $row);
            $this->add($monthly[$month], $row);
            if (isset($daily[$day])) {
                ++$daily[$day]['count'];
                $daily[$day]['distanceKm'] += (float) $row['distance'] / 1000;
            }
        }
        $best = $this->db->fetchAllAssociative('SELECT b.distanceInMeter, b.timeInSeconds, a.activityId, a.name, a.startDateTime FROM ActivityBestEffort b JOIN Activity a ON a.activityId = b.activityId WHERE '.str_replace('sportType', 'a.sportType', $where).' AND b.timeInSeconds > 0 ORDER BY b.distanceInMeter, b.timeInSeconds, a.startDateTime', $params);
        $records = [];
        foreach ($best as $row) {
            $key = (int) $row['distanceInMeter'];
            $records[$key] ??= ['distanceM' => $key, 'seconds' => (int) $row['timeInSeconds'], 'activityId' => $row['activityId'], 'name' => $row['name'], 'date' => substr($row['startDateTime'], 0, 10)];
        }

        return ['filters' => ['from' => $from, 'to' => $to, 'sportType' => $sport, 'timezone' => $now->getTimezone()->getName()], 'availableRange' => ['from' => null === ($range['first'] ?? null) ? null : substr($range['first'], 0, 10), 'to' => null === ($range['last'] ?? null) ? null : substr($range['last'], 0, 10)], 'summary' => [...$this->finish($summary), 'activeDays' => count($activeDays)], 'weekly' => array_map($this->finish(...), array_values($weekly)), 'monthly' => array_map($this->finish(...), array_values($monthly)), 'daily' => array_values($daily), 'dailyFrom' => $dayStart, 'bestEfforts' => array_values($records), 'activities' => ['items' => array_map(static fn (array $r): array => self::activity($r, $now->getTimezone()), array_slice($rows, ($page - 1) * 50, 50)), 'page' => $page, 'pageSize' => 50, 'total' => count($rows)], 'notes' => ['统计涵盖筛选范围内全部跑步，记录列表每页 50 条；骑行和已删除记录不计入。', '平均配速 = 有有效距离与时长的运动总时长 / 总距离；平均心率按已记录心率的运动时长加权。', '周统计从周一开始；边界周只统计筛选范围，热力图最多展示范围末尾 366 天。', '最佳表现取已导入并计算的运动内最快片段，不等同于认证比赛成绩。训练时间与跑量表示训练量，不是生理恢复或伤病风险评分。']];
    }

    /** @param array<string, mixed> $row
     * @return array<string, mixed>
     */
    public static function activity(array $row, ?\DateTimeZone $timezone = null): array
    {
        $distance = (float) $row['distance'] / 1000;
        $seconds = (int) $row['movingTimeInSeconds'];

        return ['id' => $row['activityId'], 'name' => $row['name'], 'startAt' => new \DateTimeImmutable($row['startDateTime'], $timezone)->format(DATE_ATOM), 'sportType' => $row['sportType'], 'distanceKm' => round($distance, 3), 'movingSeconds' => $seconds, 'elevationM' => (float) $row['elevation'], 'paceSecondsPerKm' => $distance > 0 && $seconds > 0 ? round($seconds / $distance, 1) : null, 'averageHeartRate' => null !== $row['averageHeartRate'] && $row['averageHeartRate'] > 0 ? (float) $row['averageHeartRate'] : null, 'cadenceSpm' => null !== $row['averageCadence'] && $row['averageCadence'] > 0 ? (float) $row['averageCadence'] * 2 : null, 'device' => $row['deviceName']];
    }

    /** @return array<string, int|float|null> */
    private function emptyTotals(): array
    {
        return ['count' => 0, 'distanceKm' => 0.0, 'movingSeconds' => 0, 'elevationM' => 0.0, 'longestRunKm' => 0.0, 'heartRateCount' => 0, 'hrSeconds' => 0, 'hrSum' => 0.0, 'paceDistance' => 0.0, 'paceTime' => 0];
    }

    /** @param array<string, mixed> $totals
     * @param array<string, mixed> $row
     */
    private function add(array &$totals, array $row): void
    {
        $distance = max(0, (float) $row['distance'] / 1000);
        $seconds = max(0, (int) $row['movingTimeInSeconds']);
        ++$totals['count'];
        $totals['distanceKm'] += $distance;
        $totals['movingSeconds'] += $seconds;
        $totals['elevationM'] += max(0, (float) $row['elevation']);
        $totals['longestRunKm'] = max($totals['longestRunKm'], $distance);
        if ($distance > 0 && $seconds > 0) {
            $totals['paceDistance'] += $distance;
            $totals['paceTime'] += $seconds;
        }
        if (null !== $row['averageHeartRate'] && $row['averageHeartRate'] > 0 && $seconds > 0) {
            ++$totals['heartRateCount'];
            $totals['hrSeconds'] += $seconds;
            $totals['hrSum'] += (float) $row['averageHeartRate'] * $seconds;
        }
    }

    /** @param array<string, mixed> $row
     * @return array<string, mixed>
     */
    private function finish(array $row): array
    {
        $row['paceSecondsPerKm'] = $row['paceDistance'] > 0 ? round($row['paceTime'] / $row['paceDistance'], 1) : null;
        $row['averageHeartRate'] = $row['hrSeconds'] > 0 ? round($row['hrSum'] / $row['hrSeconds'], 1) : null;
        $row['distanceKm'] = round($row['distanceKm'], 3);
        unset($row['hrSeconds'], $row['hrSum'], $row['paceDistance'], $row['paceTime']);

        return $row;
    }
}
