<?php

declare(strict_types=1);

namespace App\Domain\Running;

use App\Domain\Activity\SportType\SportType;
use App\Domain\Settings\AthleteHasNotBeenConfigured;
use App\Domain\Settings\SettingsRepository;
use App\Domain\Training\TrainingError;
use App\Infrastructure\Serialization\Json;
use App\Infrastructure\ValueObject\Time\SerializableDateTime;
use App\Infrastructure\ValueObject\Time\SerializableTimezone;
use Doctrine\DBAL\Connection;

final readonly class RunningDetails
{
    public function __construct(private Connection $db, private RunAnalysis $analysis, private SettingsRepository $settings, private ?SerializableTimezone $timezone = null)
    {
    }

    /** @return array<string, mixed> */
    public function get(string $id): array
    {
        $row = $this->db->fetchAssociative('SELECT * FROM Activity WHERE activityId = ? AND '.RunningOverview::FILTER, [$id]);
        if (false === $row) {
            throw new TrainingError('跑步记录不存在。', 404);
        }
        $streams = [];
        $warnings = [];
        foreach ($this->db->fetchAllAssociative('SELECT streamType, data FROM ActivityStream WHERE activityId = ?', [$id]) as $stream) {
            if (!in_array($stream['streamType'], ['time', 'distance', 'velocity_smooth', 'heartrate', 'cadence', 'altitude', 'watts', 'moving'], true)) {
                continue;
            }
            if (!is_string($stream['data'])) {
                $warnings[] = '部分传感器记录为空，请检查原始运动文件。';
                continue;
            }
            try {
                $data = Json::uncompressAndDecode($stream['data']);
                if (is_array($data) && array_is_list($data)) {
                    $streams[$stream['streamType']] = $data;
                }
            } catch (\App\Infrastructure\Exception\CorruptedData) {
                $warnings[] = '部分传感器记录无法解码，请重新导入原始运动文件。';
            }
        }
        $zoneBounds = [];
        try {
            $general = $this->settings->general();
            $on = SerializableDateTime::fromString($row['startDateTime']);
            $maximum = $general->getAthlete()->getMaxHeartRate($on);
            foreach ($general->getHeartRateZoneConfiguration()->getHeartRateZonesFor(SportType::from($row['sportType']), $on)->getZones() as $zone) {
                $range = $zone->getRangeInBpm($maximum);
                $zoneBounds[] = [$range[0], $range[1]];
            }
        } catch (AthleteHasNotBeenConfigured) {
            $warnings[] = '尚未配置运动员心率基准，暂不划分训练心率区间；仍可查看实际 bpm 分布。';
        }
        $splits = array_map(static function (array $r): array {
            $km = (float) $r['distance'] / 1000;

            return ['number' => (int) $r['splitNumber'], 'distanceKm' => $km, 'movingSeconds' => (int) $r['movingTimeInSeconds'], 'elapsedSeconds' => (int) $r['elapsedTimeInSeconds'], 'paceSecondsPerKm' => $km > 0 ? round($r['movingTimeInSeconds'] / $km, 1) : null, 'elevationM' => (float) $r['elevationDifference'], 'gapPaceSecondsPerKm' => null === $r['gapPaceInSecondsPerKm'] ? null : (float) $r['gapPaceInSecondsPerKm']];
        }, $this->db->fetchAllAssociative("SELECT * FROM ActivitySplit WHERE activityId = ? AND unitSystem = 'metric' ORDER BY splitNumber", [$id]));
        $laps = array_map(static fn (array $r): array => ['number' => (int) $r['lapNumber'], 'name' => $r['name'], 'distanceKm' => (float) $r['distance'] / 1000, 'movingSeconds' => (int) $r['movingTimeInSeconds'], 'averageHeartRate' => null === $r['averageHeartRate'] ? null : (float) $r['averageHeartRate']], $this->db->fetchAllAssociative('SELECT * FROM ActivityLap WHERE activityId = ? ORDER BY lapNumber', [$id]));
        $analysis = $this->analysis->analyze($streams, $zoneBounds);
        $analysis['notes'] = [...$warnings, ...$analysis['notes'], '心率区间沿用运动当天的 Dreeve 运动员设置；若最大心率来自年龄公式，区间也属于估算。缺少速度流时不从 GPS 猜测瞬时配速。'];

        return ['activity' => RunningOverview::activity($row, $this->timezone), 'analysis' => $analysis, 'splits' => $splits, 'laps' => $laps, 'splitSummary' => $this->splitSummary($splits), 'availableStreams' => array_keys($streams), 'detailPath' => '/activities/'.rawurlencode($id)];
    }

    /** @param list<array<string, mixed>> $splits
     * @return array<string, float|null>
     */
    private function splitSummary(array $splits): array
    {
        $paces = array_column(array_values(array_filter($splits, static fn (array $s): bool => $s['distanceKm'] >= 0.95 && $s['distanceKm'] <= 1.05 && $s['movingSeconds'] > 0)), 'paceSecondsPerKm');
        $cv = null;
        if (count($paces) >= 2) {
            $mean = array_sum($paces) / count($paces);
            $cv = sqrt(array_sum(array_map(static fn (float $v): float => ($v - $mean) ** 2, $paces)) / count($paces)) / $mean * 100;
        }
        $total = array_sum(array_column($splits, 'distanceKm'));
        $half = $total / 2;
        $covered = $a = $b = 0.0;
        foreach ($splits as $split) {
            $km = $split['distanceKm'];
            if ($km <= 0 || $split['movingSeconds'] <= 0) {
                continue;
            }
            $first = max(0, min($km, $half - $covered));
            $a += $split['movingSeconds'] * $first / $km;
            $b += $split['movingSeconds'] * ($km - $first) / $km;
            $covered += $km;
        }

        return ['paceVariationPercent' => null === $cv ? null : round($cv, 2), 'secondHalfPaceChangePercent' => $total >= 2 && $a > 0 && $covered >= $total ? round(($b / $a - 1) * 100, 2) : null, 'fastestFullKmPace' => [] !== $paces ? min($paces) : null];
    }
}
