<?php

declare(strict_types=1);

namespace App\Domain\Health;

/** Decode measured samples only: no interpolation and no zero-filling absent sensors. */
final readonly class ZeppWorkoutParser
{
    /** @param array<string,mixed> $row
     * @return array<string,mixed>|null
     */
    public function summary(array $row, \DateTimeZone $zone): ?array
    {
        $id = (string) ($row['trackid'] ?? '');
        $start = $this->number($row['start_time'] ?? $row['trackid'] ?? null, 946684800, 4102444800);
        $end = $this->number($row['end_time'] ?? null, 946684800, 4102444800);
        if (!preg_match('/\A[0-9]{1,16}\z/', $id) || null === $start || null === $end || $end <= $start) {
            return null;
        }
        $startedAt = new \DateTimeImmutable('@'.(int) $start)->setTimezone($zone);
        $type = $this->number($row['type'] ?? null, 0, 100000);
        $sport = is_string($row['sport_title'] ?? null) && '' !== trim($row['sport_title']) ? mb_substr($row['sport_title'], 0, 80) : match ((int) $type) {
            1 => '跑步', 6 => '步行', 7 => '越野跑', 8 => '跑步机', 9 => '骑行', 10 => '室内骑行', 13 => '登山', 14 => '泳池游泳', 15 => '开放水域游泳', 22 => '徒步', 49 => '力量训练', default => '运动',
        };
        $duration = $this->number($row['run_time'] ?? null, 1, 604800) ?? $end - $start;
        $distance = $this->number($row['dis'] ?? null, 0, 1000000);

        return ['source' => 'zepp', 'sourceId' => $id, 'date' => $startedAt->format('Y-m-d'), 'startedAt' => $startedAt->format(DATE_ATOM),
            'endedAt' => new \DateTimeImmutable('@'.(int) $end)->setTimezone($zone)->format(DATE_ATOM), 'sportType' => $type, 'sportName' => $sport,
            'durationSeconds' => $duration, 'elapsedSeconds' => $end - $start, 'distanceMeters' => $distance,
            'caloriesKcal' => $this->number($row['calorie'] ?? null, 0, 100000), 'averageHeartRateBpm' => $this->number($row['avg_heart_rate'] ?? null, 1, 300),
            'maxHeartRateBpm' => $this->number($row['max_heart_rate'] ?? null, 1, 300),
            'averageSpeedMetersPerSecond' => null === $distance ? null : round($distance / $duration, 3),
            'averageCadenceSpm' => $this->number($row['avg_frequency'] ?? null, 1, 400),
            'ascentMeters' => $this->number($row['altitude_ascend'] ?? null, 0, 100000), 'descentMeters' => $this->number($row['altitude_descend'] ?? null, 0, 100000),
            'trainingLoad' => $this->number($row['exercise_load'] ?? null, 0, 10000), 'aerobicTrainingEffect' => $this->number($row['te'] ?? null, 0, 5),
            'anaerobicTrainingEffect' => $this->number($row['anaerobic_te'] ?? null, 0, 5), 'vo2Max' => $this->number($row['VO2_max'] ?? null, 1, 100),
            'totalSteps' => $this->number($row['total_step'] ?? null, 0, 1000000), 'detailState' => 'missing',
            'streamCounts' => array_fill_keys(['heartRate', 'pace', 'speed', 'cadence', 'altitude', 'location', 'distance', 'power', 'stride'], 0),
        ];
    }

    /** @param array<string,mixed> $detail
     * @return array<string,list<array<string,int|float|string>>>
     */
    public function streams(array $detail, string $startedAt, string $endedAt): array
    {
        $start = new \DateTimeImmutable($startedAt);
        $end = new \DateTimeImmutable($endedAt)->getTimestamp();
        $streams = array_fill_keys(['heartRate', 'pace', 'speed', 'cadence', 'altitude', 'location', 'distance', 'power', 'stride'], []);
        /** @var array<string,array{string,string,int,int,int,bool}> $fields */
        $fields = ['heart_rate' => ['heartRate', 'bpm', 1, 300, 1, true], 'speed' => ['speed', 'metersPerSecond', 0, 100, 1, false],
            'currentDistance' => ['distance', 'meters', 0, 1000000, 100, false], 'power_meter' => ['power', 'watts', 0, 5000, 1, false]];
        foreach ($fields as $field => [$name, $unit, $min, $max, $scale, $deltaValue]) {
            $time = 0;
            $value = 0;
            foreach ($this->samples($detail[$field] ?? null) as $sample) {
                $parts = explode(',', $sample);
                if (2 !== count($parts) || ('' !== $parts[0] && !is_numeric($parts[0])) || !is_numeric($parts[1])) {
                    continue;
                }
                // Zepp omits the time delta for the common one-second interval.
                $time += '' === $parts[0] ? 1 : (int) $parts[0];
                $value = $deltaValue ? $value + (float) $parts[1] : (float) $parts[1];
                $at = $this->at($time, $start, $end);
                $normalized = $this->number($value / $scale, $min, $max);
                if (null !== $at && null !== $normalized) {
                    $streams[$name][] = ['at' => $at, $unit => $normalized];
                    if ('speed' === $name && $normalized > 0) {
                        $streams['pace'][] = ['at' => $at, 'secondsPerKilometer' => round(1000 / $normalized, 2)];
                    }
                }
            }
        }
        $time = 0;
        foreach ($this->samples($detail['gait'] ?? null) as $sample) {
            $parts = explode(',', $sample);
            if (count($parts) < 4 || !is_numeric($parts[0])) {
                continue;
            }
            $time += (int) $parts[0];
            if (null === ($at = $this->at($time, $start, $end))) {
                continue;
            }
            if (null !== ($cadence = $this->number($parts[3], 1, 400))) {
                $streams['cadence'][] = ['at' => $at, 'stepsPerMinute' => $cadence];
            }
            if (null !== ($stride = $this->number($parts[2], 1, 500))) {
                $streams['stride'][] = ['at' => $at, 'meters' => $stride / 100];
            }
        }
        $times = $this->samples($detail['time'] ?? null);
        $positions = $this->samples($detail['longitude_latitude'] ?? null);
        $altitudes = $this->samples($detail['altitude'] ?? null);
        $time = $latitude = $longitude = 0;
        $positionKnown = true;
        foreach ($times as $index => $delta) {
            if (!is_numeric($delta)) {
                continue;
            }
            $time += (int) $delta;
            $position = explode(',', $positions[$index] ?? '');
            $validPosition = 2 === count($position) && is_numeric($position[0]) && is_numeric($position[1]);
            if ($validPosition && $positionKnown) {
                $latitude += (float) $position[0];
                $longitude += (float) $position[1];
            } else {
                // A missing delta cannot be reconstructed without inventing a location.
                $positionKnown = false;
            }
            if (null === ($at = $this->at($time, $start, $end))) {
                continue;
            }
            if ($validPosition && $positionKnown && null !== $this->number($latitude / 1e8, -90, 90) && null !== $this->number($longitude / 1e8, -180, 180) && (0 != $latitude || 0 != $longitude)) {
                $streams['location'][] = ['at' => $at, 'latitude' => $latitude / 1e8, 'longitude' => $longitude / 1e8];
            }
            if (null !== ($altitude = $this->number($altitudes[$index] ?? null, -100000, 1200000))) {
                $streams['altitude'][] = ['at' => $at, 'meters' => $altitude / 100];
            }
        }

        return $streams;
    }

    /** @return list<string> */
    private function samples(mixed $raw): array
    {
        // Empty positions are meaningful; removing them would shift the sensor timeline.
        if (!is_string($raw) || '' === $raw || strlen($raw) > 8 * 1024 * 1024) {
            return [];
        }
        $samples = explode(';', $raw);
        if ('' === end($samples)) {
            array_pop($samples);
        }

        return $samples;
    }

    private function at(int $time, \DateTimeImmutable $start, int $end): ?string
    {
        $timestamp = $time >= 946684800 ? $time : $start->getTimestamp() + $time;
        if ($timestamp < $start->getTimestamp() || $timestamp > $end + 60) {
            return null;
        }

        return $start->setTimestamp($timestamp)->format(DATE_ATOM);
    }

    private function number(mixed $value, int|float $min, int|float $max): int|float|null
    {
        if (!is_numeric($value) || !is_finite((float) $value) || $value < $min || $value > $max) {
            return null;
        }

        return $value + 0;
    }
}
