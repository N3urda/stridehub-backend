<?php

declare(strict_types=1);

namespace App\Domain\Training;

use App\Domain\Health\WorkoutCatalog;
use App\Infrastructure\ValueObject\Time\SerializableTimezone;
use Doctrine\DBAL\Connection;

final readonly class TrainingActivities
{
    public function __construct(private Connection $connection, private ?SerializableTimezone $timezone = null, private ?WorkoutCatalog $catalog = null)
    {
    }

    /** @return list<array<string, mixed>> */
    public function list(): array
    {
        if ($this->catalog instanceof WorkoutCatalog) {
            return array_slice(array_values(array_filter($this->catalog->all(), $this->isRun(...))), 0, 500);
        }

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
        if ($this->catalog instanceof WorkoutCatalog) {
            $activities = array_values(array_filter($this->catalog->all(), fn (array $r): bool => $this->isRun($r) && (new \DateTimeImmutable($r['startAt'])->setTimezone($sessionTimezone)->format('Y-m-d') === $local->format('Y-m-d') || abs(strtotime($r['startAt']) - $start->getTimestamp()) <= 21600)));
            usort($activities, static fn (array $a, array $b): int => strtotime($a['startAt']) <=> strtotime($b['startAt']) ?: strcmp($a['id'], $b['id']));
        } else {
            $rows = $this->connection->fetchAllAssociative("SELECT activityId, name, startDateTime, distance, movingTimeInSeconds, elapsedTimeInSeconds, averageHeartRate FROM Activity WHERE sportType IN ('Run','TrailRun','VirtualRun') AND (markedForDeletion IS NULL OR markedForDeletion = 0) AND ((startDateTime >= ? AND startDateTime < ?) OR (startDateTime >= ? AND startDateTime <= ?)) ORDER BY startDateTime, activityId", $parameters);
            $activities = array_map($this->hydrate(...), $rows);
        }

        return array_map(function (array $activity) use ($start, $local, $sessionTimezone): array {
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
        }, $activities);
    }

    /** @return array<string, mixed>|null */
    public function find(string $id): ?array
    {
        if ($this->catalog instanceof WorkoutCatalog) {
            $row = $this->catalog->find($id);

            return null !== $row && $this->isRun($row) ? $row : null;
        }
        $row = $this->connection->fetchAssociative("SELECT activityId, name, startDateTime, distance, movingTimeInSeconds, elapsedTimeInSeconds, averageHeartRate FROM Activity WHERE activityId = ? AND sportType IN ('Run','TrailRun','VirtualRun') AND (markedForDeletion IS NULL OR markedForDeletion = 0)", [$id]);

        return false === $row ? null : $this->hydrate($row);
    }

    /** @param array<string, mixed> $row */
    private function isRun(array $row): bool
    {
        return in_array($row['sportType'], ['Run', 'TrailRun', 'VirtualRun'], true);
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
