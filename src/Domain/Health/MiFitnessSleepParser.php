<?php

declare(strict_types=1);

namespace App\Domain\Health;

final readonly class MiFitnessSleepParser
{
    /** @param array<string,mixed> $row
     * @return array<string,mixed>|null
     */
    public function parse(array $row, \DateTimeZone $zone): ?array
    {
        if (!is_string($row['sleep_id'] ?? null) || '' === $row['sleep_id'] || strlen($row['sleep_id']) > 512) {
            return null;
        }
        foreach (['start_at', 'end_at'] as $key) {
            if (!is_string($row[$key] ?? null) || !preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/D', $row[$key])) {
                return null;
            }
        }
        try {
            $start = new \DateTimeImmutable($row['start_at']);
            $end = new \DateTimeImmutable($row['end_at']);
        } catch (\Throwable) {
            return null;
        }
        if ($start->format('Y-m-d\TH:i:s') !== substr($row['start_at'], 0, 19) || $end->format('Y-m-d\TH:i:s') !== substr($row['end_at'], 0, 19)) {
            return null;
        }
        $elapsed = ($end->getTimestamp() - $start->getTimestamp()) / 60;
        $total = $this->minutes($row['time_asleep_minutes'] ?? null);
        if ($elapsed <= 0 || $elapsed > 2160 || null === $total || $total <= 0 || $total > $elapsed + 1 || !in_array($row['is_nap'] ?? null, [true, false, 0, 1], true)) {
            return null;
        }
        $stages = ['deep' => null, 'light' => null, 'rem' => null, 'awake' => null];
        if (isset($row['stages']) && (!is_array($row['stages']) || !array_is_list($row['stages']))) {
            return null;
        }
        foreach ($row['stages'] ?? [] as $stage) {
            if (!is_array($stage) || !is_string($stage['stage'] ?? null) || !array_key_exists($stage['stage'], $stages) || null === ($minutes = $this->minutes($stage['minutes'] ?? null))) {
                return null;
            }
            $stages[$stage['stage']] = ($stages[$stage['stage']] ?? 0) + $minutes;
        }
        if (array_sum($stages) > $elapsed + 1) {
            return null;
        }
        $score = $row['sleep_score'] ?? null;

        return ['source' => 'mi_fitness', 'sourceLabel' => '小米运动健康', 'sourceId' => $row['sleep_id'],
            'date' => $end->setTimezone($zone)->format('Y-m-d'), 'sourceDate' => $start->format('Y-m-d'), 'timeZone' => $zone->getName(),
            'startedAt' => $start->setTimezone($zone)->format(DATE_ATOM), 'endedAt' => $end->setTimezone($zone)->format(DATE_ATOM),
            'totalMinutes' => $total, 'deepMinutes' => $stages['deep'], 'lightMinutes' => $stages['light'], 'remMinutes' => $stages['rem'],
            'awakeMinutes' => $this->minutes($row['time_awake_minutes'] ?? null),
            'score' => is_int($score) && $score >= 1 && $score <= 100 ? $score : null,
            'restingHeartRate' => null, 'wakeCount' => is_int($row['wake_count'] ?? null) && $row['wake_count'] >= 0 && $row['wake_count'] <= 500 ? $row['wake_count'] : null,
            'isIncomplete' => is_bool($row['is_incomplete'] ?? null) ? $row['is_incomplete'] : null,
            'sleepSegments' => $this->segments($row['sleep_segments'] ?? [], $start, $end),
            'rawExtensions' => $this->extensions($row['raw_extensions'] ?? []), 'isNap' => (bool) $row['is_nap'],
            'stages' => $row['stages'] ?? [], 'stageFormat' => 'duration_totals'];
    }

    /** @return list<array<string,mixed>> */
    private function segments(mixed $rows, \DateTimeImmutable $start, \DateTimeImmutable $end): array
    {
        if (!is_array($rows) || !array_is_list($rows) || count($rows) > 2000) {
            return [];
        }
        $segments = [];
        $last = $start;
        foreach ($rows as $row) {
            if (!is_array($row) || !is_string($row['startedAt'] ?? null) || !is_string($row['endedAt'] ?? null)
                || !in_array($row['stage'] ?? null, ['awake', 'deep', 'light', 'rem', 'unclassified', 'unknown'], true)) {
                return [];
            }
            foreach (['startedAt', 'endedAt'] as $key) {
                if (!preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/D', $row[$key])) {
                    return [];
                }
            }
            try {
                $a = new \DateTimeImmutable($row['startedAt']);
                $b = new \DateTimeImmutable($row['endedAt']);
            } catch (\Throwable) {
                return [];
            }
            if ($a < $last || $b > $end || $b <= $a) {
                return [];
            }
            $segments[] = ['startedAt' => $a->format(DATE_ATOM), 'endedAt' => $b->format(DATE_ATOM), 'stage' => $row['stage']];
            $last = $b;
        }

        return $segments;
    }

    /** @return array<string,mixed> */
    private function extensions(mixed $raw): array
    {
        $value = is_array($raw) ? ($raw['total_snore_time']['value'] ?? null) : null;

        return (is_int($value) || is_float($value)) && $value >= 0 ? ['total_snore_time' => ['value' => $value, 'unit' => null]] : [];
    }

    private function minutes(mixed $value): ?int
    {
        return is_int($value) && $value >= 0 && $value <= 2160 ? $value : null;
    }
}
