<?php

declare(strict_types=1);

namespace App\Domain\Health;

final readonly class ZeppDataParser
{
    public function __construct(private ZeppSleepParser $sleep)
    {
    }

    /** @param array<string,mixed> $row
     * @return array<string,mixed>|null
     */
    public function band(array $row): ?array
    {
        $date = $row['date_time'] ?? null;
        if (!self::validDate($date)) {
            return null;
        }
        $summary = $this->summary($row);
        if (null === $summary) {
            return null;
        }
        $offset = self::number($summary['tz'] ?? null, -43200, 50400);
        $zone = null !== $offset && 0 === (int) $offset % 60 ? sprintf('%s%02d:%02d', $offset < 0 ? '-' : '+', intdiv(abs((int) $offset), 3600), intdiv(abs((int) $offset) % 3600, 60)) : null;
        $record = $this->emptyDay($date);
        $record['timeZone'] = $zone;
        $steps = is_array($summary['stp'] ?? null) ? $summary['stp'] : [];
        $record['activity'] = [
            'steps' => self::number($steps['ttl'] ?? null, 0, 500000), 'distanceMeters' => self::number($steps['dis'] ?? null, 0, 1000000),
            'caloriesKcal' => self::number($steps['cal'] ?? null, 0, 50000), 'activeMinutes' => null,
            'walkingMinutes' => self::number($steps['wk'] ?? null, 0, 1440), 'runningMinutes' => self::number($steps['rn'] ?? null, 0, 1440),
            'stepGoal' => self::number($summary['goal'] ?? null, 1, 500000),
        ];
        if (null !== $record['activity']['walkingMinutes'] && null !== $record['activity']['runningMinutes']) {
            $record['activity']['activeMinutes'] = $record['activity']['walkingMinutes'] + $record['activity']['runningMinutes'];
        }
        $record['sleep'] = $this->sleep->parse($row);
        $record['heartRate']['restingBpm'] = self::number($summary['pai']['restHr'] ?? null, 20, 250);
        $rawHr = is_string($row['data_hr'] ?? null) ? base64_decode($row['data_hr'], true) : false;
        // Only the verified one-byte-per-minute format is decoded. Unknown formats remain in raw storage.
        if (false !== $rawHr && 1440 === strlen($rawHr) && null !== $zone) {
            $midnight = new \DateTimeImmutable($date.'T00:00:00'.$zone);
            foreach (unpack('C*', $rawHr) ?: [] as $index => $value) {
                if ($value >= 20 && $value <= 250) {
                    $record['samples']['heartRate'][] = ['at' => $midnight->modify('+'.($index - 1).' minutes')->format(DATE_ATOM), 'bpm' => $value];
                }
            }
        }
        $values = array_column($record['samples']['heartRate'], 'bpm');
        $record['heartRate'] = [...$record['heartRate'], 'averageBpm' => [] === $values ? null : round(array_sum($values) / count($values), 1), 'minBpm' => [] === $values ? null : min($values), 'maxBpm' => [] === $values ? null : max($values), 'sampleCount' => count($values)];
        $record['sourceFields'] = array_keys($summary);
        $record['sleepStages'] = is_array($summary['slp']['stage'] ?? null) ? $summary['slp']['stage'] : [];
        $record['activityStages'] = is_array($steps['stage'] ?? null) ? $steps['stage'] : [];
        $record['coverage'] = $this->coverage($record);

        return $record;
    }

    /** @param array<string,mixed> $row
     * @return array<string,mixed>|null
     */
    public function summary(array $row): ?array
    {
        $summary = $row['summary'] ?? null;
        if (is_string($summary)) {
            try {
                $decoded = base64_decode($summary, true);
                $summary = false === $decoded ? null : json_decode($decoded, true, 24, JSON_THROW_ON_ERROR);
            } catch (\Throwable) {
                return null;
            }
        }
        if (is_array($summary) && array_is_list($summary)) {
            $summary = 1 === count($summary) ? $summary[0] : null;
        }

        return is_array($summary) && !array_is_list($summary) ? $summary : null;
    }

    /** @return array<string,mixed> */
    public function emptyDay(string $date): array
    {
        return ['date' => $date, 'sourceDate' => $date, 'source' => 'zepp', 'timeZone' => null, 'sleep' => null,
            'activity' => ['steps' => null, 'distanceMeters' => null, 'caloriesKcal' => null, 'activeMinutes' => null],
            'heartRate' => ['restingBpm' => null, 'averageBpm' => null, 'minBpm' => null, 'maxBpm' => null, 'sampleCount' => 0],
            'oxygen' => ['averagePercent' => null, 'minPercent' => null, 'sampleCount' => 0],
            'stress' => ['average' => null, 'max' => null, 'sampleCount' => 0],
            'body' => ['weightKg' => null, 'bodyFatPercent' => null],
            'samples' => ['heartRate' => [], 'oxygen' => [], 'stress' => []], 'sleepStages' => [], 'activityStages' => [], 'sourceFields' => [], 'rawRecordIds' => [],
        ];
    }

    /** @param array<string,mixed> $record
     * @return array<string,string>
     */
    public function coverage(array $record): array
    {
        $coverage = [];
        foreach (['sleep', 'activity', 'heartRate', 'oxygen', 'stress', 'body'] as $key) {
            $values = $record[$key] ?? [];
            if (is_array($values)) {
                unset($values['sampleCount'], $values['stepGoal']);
            }
            $coverage[$key] = is_array($values) && [] !== array_filter($values, static fn (mixed $value): bool => null !== $value) ? 'available' : 'missing';
        }

        return $coverage;
    }

    public static function validDate(mixed $date): bool
    {
        return is_string($date) && 1 === preg_match('/\A\d{4}-\d{2}-\d{2}\z/', $date) && checkdate((int) substr($date, 5, 2), (int) substr($date, 8, 2), (int) substr($date, 0, 4));
    }

    public static function number(mixed $value, int $min, int $max): int|float|null
    {
        if ((!is_int($value) && !is_float($value) && !is_string($value)) || !is_numeric($value)) {
            return null;
        }
        $number = 0 + $value;

        return is_finite((float) $number) && $number >= $min && $number <= $max ? $number : null;
    }
}
