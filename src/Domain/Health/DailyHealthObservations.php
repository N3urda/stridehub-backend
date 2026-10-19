<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingService;
use App\Infrastructure\Time\Clock\Clock;

/** Read-only observations. They never replace a self-report or grant training clearance. */
final readonly class DailyHealthObservations
{
    public function __construct(private BloodPressureService $bloodPressure, private SleepService $sleep, private Clock $clock, private TrainingService $training)
    {
    }

    /** @return array<string, mixed> */
    public function forDate(string $date): array
    {
        $now = $this->clock->getCurrentDateTimeImmutable()->getTimestamp();
        $pressure = array_find($this->bloodPressure->list($date, $date), static fn (array $row): bool => strtotime($row['measuredAt']) <= $now);
        $sleep = $this->sleep->forDate($date, $this->training->profile()['timezone']);
        if (null !== $sleep && (null === $sleep['endedAt'] || strtotime($sleep['endedAt']) > $now || null === $sleep['timeZone'])) {
            // A source row date without its actual wake time/offset cannot establish today's sleep.
            $sleep = null;
        }

        return ['bloodPressure' => $pressure, 'sleep' => $sleep, 'sleepSync' => $this->sleep->status()];
    }
}
