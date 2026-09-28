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
        return array_map($this->hydrate(...), $this->connection->fetchAllAssociative("SELECT activityId, name, startDateTime, distance, movingTimeInSeconds, averageHeartRate FROM Activity WHERE sportType IN ('Run','TrailRun','VirtualRun') AND (markedForDeletion IS NULL OR markedForDeletion = 0) ORDER BY startDateTime DESC LIMIT 500"));
    }

    /** @return list<array<string, mixed>> */
    public function near(\DateTimeImmutable $start): array
    {
        $local = $start->setTimezone($this->timezone ?? new \DateTimeZone(date_default_timezone_get()));

        return array_map($this->hydrate(...), $this->connection->fetchAllAssociative("SELECT activityId, name, startDateTime, distance, movingTimeInSeconds, averageHeartRate FROM Activity WHERE sportType IN ('Run','TrailRun','VirtualRun') AND (markedForDeletion IS NULL OR markedForDeletion = 0) AND startDateTime >= ? AND startDateTime <= ? ORDER BY startDateTime", [$local->setTimestamp($local->getTimestamp() - 7200)->format('Y-m-d H:i:s'), $local->setTimestamp($local->getTimestamp() + 7200)->format('Y-m-d H:i:s')]));
    }

    /** @return array<string, mixed>|null */
    public function find(string $id): ?array
    {
        $row = $this->connection->fetchAssociative("SELECT activityId, name, startDateTime, distance, movingTimeInSeconds, averageHeartRate FROM Activity WHERE activityId = ? AND sportType IN ('Run','TrailRun','VirtualRun') AND (markedForDeletion IS NULL OR markedForDeletion = 0)", [$id]);

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
            'averageHeartRate' => null === $row['averageHeartRate'] ? null : (int) $row['averageHeartRate'],
        ];
    }
}
