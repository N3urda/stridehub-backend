<?php

declare(strict_types=1);

namespace App\Domain\Health;

final readonly class ZeppSleepParser
{
    /** The private cloud API's summary is base64 JSON; no raw account/device payload is persisted.
     * @param array<string, mixed> $row
     *
     * @return array<string, mixed>|null
     */
    public function parse(array $row): ?array
    {
        $date = $row['date_time'] ?? null;
        if (!is_string($date) || !preg_match('/\A\d{4}-\d{2}-\d{2}\z/', $date) || !checkdate((int) substr($date, 5, 2), (int) substr($date, 8, 2), (int) substr($date, 0, 4))) {
            return null;
        }
        $summary = $row['summary'] ?? null;
        if (is_string($summary)) {
            try {
                $decoded = base64_decode($summary, true);
                $summary = false === $decoded ? null : json_decode($decoded, true, 24, JSON_THROW_ON_ERROR);
            } catch (\JsonException) {
                return null;
            }
        }
        if (is_array($summary) && array_is_list($summary)) {
            $summary = 1 === count($summary) ? $summary[0] : null;
        }
        if (!is_array($summary) || !is_array($summary['slp'] ?? null)) {
            return null;
        }
        $sleep = $summary['slp'];
        $start = $this->number($sleep['st'] ?? null, 946684800, 4102444800);
        $end = $this->number($sleep['ed'] ?? null, 946684800, 4102444800);
        if (null !== $start && null !== $end && ($end <= $start || $end - $start > 129600)) {
            return null;
        }
        $deep = $this->number($sleep['dp'] ?? null, 0, 2160);
        $light = $this->number($sleep['lt'] ?? null, 0, 2160);
        // A device explicitly without REM support has unknown REM, not measured zero REM.
        $rem = false === ($sleep['supRem'] ?? null) ? null : $this->number($sleep['dt'] ?? null, 0, 2160);
        $total = null !== $deep && null !== $light && (null !== $rem || false === ($sleep['supRem'] ?? null)) ? $deep + $light + ($rem ?? 0) : null;
        if (null === $start && null === $end && (null === $total || $total <= 0)) {
            return null;
        }
        if (null !== $total && ($total <= 0 || $total > 2160)) {
            return null;
        }
        if (!in_array(null, [$start, $end, $total], true) && $total > intdiv($end - $start, 60) + 1) {
            // Preserve source stages and timestamps, but never publish an impossible derived total.
            $total = null;
        }
        $offset = $this->number($summary['tz'] ?? null, -43200, 50400);
        $zone = null !== $offset && 0 === $offset % 60 ? sprintf('%s%02d:%02d', $offset < 0 ? '-' : '+', intdiv(abs($offset), 3600), intdiv(abs($offset) % 3600, 60)) : null;
        $timezone = new \DateTimeZone($zone ?? 'UTC');
        $startedAt = null === $start ? null : new \DateTimeImmutable('@'.$start)->setTimezone($timezone)->format(DATE_ATOM);
        $endedAt = null === $end ? null : new \DateTimeImmutable('@'.$end)->setTimezone($timezone)->format(DATE_ATOM);

        return [
            'sourceDate' => $date,
            'date' => null !== $zone && null !== $endedAt ? substr($endedAt, 0, 10) : $date,
            'source' => 'zepp', 'sourceLabel' => 'Zepp / Amazfit', 'timeZone' => $zone,
            'startedAt' => $startedAt, 'endedAt' => $endedAt,
            'totalMinutes' => $total, 'deepMinutes' => $deep, 'lightMinutes' => $light, 'remMinutes' => $rem,
            'awakeMinutes' => $this->number($sleep['wk'] ?? null, 0, 2160),
            'score' => $this->number($sleep['ss'] ?? null, 1, 100),
            'restingHeartRate' => $this->number($sleep['rhr'] ?? null, 20, 250),
            'wakeCount' => $this->number($sleep['wc'] ?? null, 0, 1000),
        ];
    }

    private function number(mixed $value, int $minimum, int $maximum): ?int
    {
        if ((!is_int($value) && !is_string($value)) || !preg_match('/\A-?\d+\z/', (string) $value)) {
            return null;
        }
        $number = filter_var($value, FILTER_VALIDATE_INT);

        return false !== $number && $number >= $minimum && $number <= $maximum ? $number : null;
    }
}
