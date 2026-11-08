<?php

declare(strict_types=1);

namespace App\Domain\Health;

/** Source-neutral read model; never rewrites source sessions or sums duplicates. */
final readonly class SleepReconciler
{
    private const array STAGES = ['deepMinutes', 'lightMinutes', 'remMinutes', 'awakeMinutes'];
    private const array DETAILS = ['wakeCount', 'restingHeartRate', 'averageHeartRate', 'averageSpO2', 'averageRespiratoryRate'];

    /** @param list<array<string,mixed>> $records
     * @return array<string,mixed>
     */
    public static function combine(array $records): array
    {
        usort($records, static fn (array $a, array $b): int => [self::quality($b), $b['totalMinutes'] ?? -1, $b['endedAt'] ?? '', $b['id']]
            <=> [self::quality($a), $a['totalMinutes'] ?? -1, $a['endedAt'] ?? '', $a['id']]);
        $base = $records[0];
        $base['fieldSources'] = [];
        $base['contributingSources'] = [$base['source']];
        $base['sourceScores'] = [];
        $base['alternatives'] = [];
        foreach (['startedAt', 'endedAt', 'totalMinutes', 'score', ...self::STAGES, ...self::DETAILS] as $key) {
            if (null !== ($base[$key] ?? null)) {
                $base['fieldSources'][$key] = ['source' => $base['source'], 'id' => $base['id']];
            }
        }
        foreach ($records as $record) {
            $matching = $record['id'] === $base['id'] || self::sameNight($base, $record);
            if (null !== ($record['score'] ?? null)) {
                $base['sourceScores'][] = ['source' => $record['source'], 'id' => $record['id'], 'score' => $record['score'], 'sameNight' => $matching];
            }
            if ($record['id'] === $base['id']) {
                continue;
            }
            $base['alternatives'][] = ['id' => $record['id'], 'source' => $record['source'], 'sourceLabel' => $record['sourceLabel'] ?? $record['source'], 'sameNight' => $matching, 'totalMinutes' => $record['totalMinutes'] ?? null];
            if (!$matching || true === ($record['isIncomplete'] ?? null)) {
                continue;
            }
            // Stage blocks stay together. Never construct a stage chart from two devices.
            $keys = self::DETAILS;
            if (0 === count(array_filter(self::STAGES, static fn (string $key): bool => null !== ($base[$key] ?? null))) && self::stageCount($record) > 0
                && is_numeric($base['totalMinutes'] ?? null) && is_numeric($record['totalMinutes'] ?? null)
                && abs($base['totalMinutes'] - $record['totalMinutes']) <= 1) {
                $keys = [...$keys, ...self::STAGES];
            }
            foreach ($keys as $key) {
                if (null === ($base[$key] ?? null) && null !== ($record[$key] ?? null)) {
                    $base[$key] = $record[$key];
                    $base['fieldSources'][$key] = ['source' => $record['source'], 'id' => $record['id']];
                    $base['contributingSources'][] = $record['source'];
                }
            }
        }
        $base['contributingSources'] = array_values(array_unique($base['contributingSources']));
        $base['selectionReason'] = '按有效时长、时间范围和信息完整度选取主记录；同段缺失指标互补，分期整组保留，评分按来源保留。';

        return $base;
    }

    /** @param array<string,mixed> $record */
    private static function quality(array $record): int
    {
        $known = count(array_filter(self::DETAILS, static fn (string $key): bool => null !== ($record[$key] ?? null)));

        return (is_numeric($record['totalMinutes'] ?? null) && $record['totalMinutes'] > 0 ? 100 : 0)
            + (isset($record['startedAt'], $record['endedAt'], $record['timeZone']) ? 20 : 0)
            + self::stageCount($record) * 3 + $known - (true === ($record['isIncomplete'] ?? null) ? 50 : 0);
    }

    /** @param array<string,mixed> $record */
    private static function stageCount(array $record): int
    {
        return count(array_filter(['deepMinutes', 'lightMinutes', 'remMinutes'], static fn (string $key): bool => null !== ($record[$key] ?? null)));
    }

    /** @param array<string,mixed> $a
     * @param array<string,mixed> $b
     */
    private static function sameNight(array $a, array $b): bool
    {
        if (!isset($a['startedAt'], $a['endedAt'], $b['startedAt'], $b['endedAt'])) {
            return false;
        }
        $as = strtotime($a['startedAt']);
        $ae = strtotime($a['endedAt']);
        $bs = strtotime($b['startedAt']);
        $be = strtotime($b['endedAt']);
        if (false === $as || false === $ae || false === $bs || false === $be || $ae <= $as || $be <= $bs) {
            return false;
        }

        return abs($as - $bs) <= 5400 && abs($ae - $be) <= 5400 && min($ae, $be) - max($as, $bs) >= .8 * min($ae - $as, $be - $bs);
    }
}
