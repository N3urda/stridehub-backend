<?php

declare(strict_types=1);

namespace App\Domain\Training;

use App\Infrastructure\ValueObject\Time\SerializableTimezone;
use Doctrine\DBAL\Connection;

final readonly class TrainingActivities
{
    public function __construct(private Connection $connection, private ?SerializableTimezone $timezone = null)
    {
    }

    /** @return list<array<string, mixed>> */
    public function list(): array
    {
        return array_map($this->hydrate(...), $this->connection->fetchAllAssociative("SELECT activityId, name, startDateTime, distance, movingTimeInSeconds, elapsedTimeInSeconds, averageHeartRate FROM Activity WHERE sportType IN ('Run','TrailRun','VirtualRun') AND (markedForDeletion IS NULL OR markedForDeletion = 0) ORDER BY startDateTime DESC LIMIT 500"));
    }

    /** @return list<array<string, mixed>> */
    public function near(\DateTimeImmutable $start, ?\DateTimeZone $sessionTimezone = null): array
    {
        $storageTimezone = $this->timezone ?? new \DateTimeZone(date_default_timezone_get());
        $sessionTimezone ??= $storageTimezone;
        $local = $start->setTimezone($sessionTimezone);
        $day = $local->setTime(0, 0);
        $bounds = [$day, $day->modify('+1 day'), $start->setTimestamp($start->getTimestamp() - 21600), $start->setTimestamp($start->getTimestamp() + 21600)];
        $parameters = array_map(static fn (\DateTimeImmutable $bound): string => $bound->setTimezone($storageTimezone)->format('Y-m-d H:i:s'), $bounds);
        $rows = $this->connection->fetchAllAssociative("SELECT activityId, name, startDateTime, distance, movingTimeInSeconds, elapsedTimeInSeconds, averageHeartRate FROM Activity WHERE sportType IN ('Run','TrailRun','VirtualRun') AND (markedForDeletion IS NULL OR markedForDeletion = 0) AND ((startDateTime >= ? AND startDateTime < ?) OR (startDateTime >= ? AND startDateTime <= ?)) ORDER BY startDateTime, activityId", $parameters);

        return array_map(function (array $row) use ($start, $local, $sessionTimezone): array {
            $activity = $this->hydrate($row);
            $activityStart = new \DateTimeImmutable($activity['startAt']);
            $offset = round(($activityStart->getTimestamp() - $start->getTimestamp()) / 60, 1);
            $reasons = [];
            if ($activityStart->setTimezone($sessionTimezone)->format('Y-m-d') === $local->format('Y-m-d')) {
                $reasons[] = '与计划位于同一训练日期（'.$sessionTimezone->getName().'）。';
            }
            if (abs($offset) <= 360) {
                $reasons[] = '开始时间在计划前后 6 小时内，可能为改时或跨午夜记录。';
            }

            return [...$activity, 'startOffsetMinutes' => $offset, 'matchReasons' => $reasons];
        }, $rows);
    }

    /** @return array<string, mixed>|null */
    public function find(string $id): ?array
    {
        $row = $this->connection->fetchAssociative("SELECT activityId, name, startDateTime, distance, movingTimeInSeconds, elapsedTimeInSeconds, averageHeartRate FROM Activity WHERE activityId = ? AND sportType IN ('Run','TrailRun','VirtualRun') AND (markedForDeletion IS NULL OR markedForDeletion = 0)", [$id]);

        return false === $row ? null : $this->hydrate($row);
    }

    /** @param array<string, mixed> $row
     * @return array<string, mixed>
     */
    private function hydrate(array $row): array
    {
        return ['id' => $row['activityId'], 'name' => $row['name'],
            // Dreeve stores activity startDateTime in its configured application timezone.
            'startAt' => new \DateTimeImmutable($row['startDateTime'], $this->timezone)->format(DATE_ATOM),
            'distanceKm' => round((float) $row['distance'] / 1000, 3),
            'durationMinutes' => round((int) $row['movingTimeInSeconds'] / 60, 2),
            'movingSeconds' => (int) $row['movingTimeInSeconds'],
            'elapsedSeconds' => null !== $row['elapsedTimeInSeconds'] && (int) $row['elapsedTimeInSeconds'] > 0 && (int) $row['elapsedTimeInSeconds'] >= (int) $row['movingTimeInSeconds'] ? (int) $row['elapsedTimeInSeconds'] : null,
            'averageHeartRate' => null !== $row['averageHeartRate'] && (int) $row['averageHeartRate'] > 0 ? (int) $row['averageHeartRate'] : null,
        ];
    }
}
