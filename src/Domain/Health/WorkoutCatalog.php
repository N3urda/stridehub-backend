<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\ValueObject\Time\SerializableTimezone;
use Doctrine\DBAL\Connection;

/** One read model for imported and cloud workouts, with explicit duplicate confirmations. */
final readonly class WorkoutCatalog
{
    private const array SPORT_GROUPS = [
        'Run' => ['Run', 'TrailRun', 'VirtualRun'],
        'Walk' => ['Walk', 'Hike'],
        'Ride' => ['Ride', 'VirtualRide', 'MountainBikeRide', 'GravelRide', 'EBikeRide', 'EMountainBikeRide', 'Velomobile'],
    ];

    public function __construct(private Connection $connection, private TrainingRepository $repository, private ?SerializableTimezone $timezone = null)
    {
    }

    /** @return list<array<string, mixed>> */
    public function all(): array
    {
        $rows = [];
        foreach ($this->connection->fetchAllAssociative('SELECT activityId, name, sportType, startDateTime, distance, movingTimeInSeconds, elapsedTimeInSeconds, averageHeartRate FROM Activity WHERE markedForDeletion IS NULL OR markedForDeletion = 0') as $row) {
            $rows[$row['activityId']] = [
                'id' => $row['activityId'], 'name' => $row['name'], 'sportType' => $row['sportType'],
                'startAt' => new \DateTimeImmutable($row['startDateTime'], $this->timezone)->format(DATE_ATOM),
                'distanceKm' => null === $row['distance'] ? null : round((float) $row['distance'] / 1000, 3),
                'movingSeconds' => null === $row['movingTimeInSeconds'] ? null : (int) $row['movingTimeInSeconds'],
                'elapsedSeconds' => null === $row['elapsedTimeInSeconds'] ? null : (int) $row['elapsedTimeInSeconds'],
                'averageHeartRate' => $row['averageHeartRate'] > 0 ? (float) $row['averageHeartRate'] : null,
                'sources' => [['source' => 'import', 'id' => $row['activityId']]], 'aliases' => [$row['activityId']],
                'duplicateCandidates' => [],
            ];
        }
        $links = array_column($this->repository->list('workout-links'), null, 'id');
        foreach ($this->repository->list('zepp-workout') as $cloud) {
            $type = match ((int) ($cloud['sportType'] ?? -1)) {
                1 => 'Run', 7 => 'TrailRun', 8 => 'VirtualRun', 6 => 'Walk', 9 => 'Ride', 10 => 'VirtualRide', 49 => 'WeightTraining', default => 'Other',
            };
            $id = $cloud['id'];
            $link = $links[$id] ?? null;
            if (null !== $link && isset($rows[$link['activityId']])) {
                $rows[$link['activityId']]['sources'][] = ['source' => 'zepp', 'id' => $id];
                $rows[$link['activityId']]['aliases'][] = $id;
                $rows[$link['activityId']]['sourceLink'] = $link;
                continue;
            }
            $rows[$id] = ['id' => $id, 'name' => $cloud['sportName'] ?? '运动', 'sportType' => $type,
                'startAt' => $cloud['startedAt'], 'distanceKm' => null === $cloud['distanceMeters'] ? null : round($cloud['distanceMeters'] / 1000, 3),
                'movingSeconds' => $cloud['durationSeconds'], 'elapsedSeconds' => $cloud['elapsedSeconds'],
                'averageHeartRate' => $cloud['averageHeartRateBpm'],
                'sources' => [['source' => 'zepp', 'id' => $id]], 'aliases' => [$id], 'duplicateCandidates' => [],
            ];
            foreach ($rows as $candidate) {
                if ('import' !== $candidate['sources'][0]['source'] || $candidate['sportType'] !== $type || count($candidate['sources']) > 1) {
                    continue;
                }
                if (abs(strtotime($candidate['startAt']) - strtotime($cloud['startedAt'])) <= 120
                    && null !== $candidate['distanceKm'] && null !== $rows[$id]['distanceKm']
                    && abs($candidate['distanceKm'] - $rows[$id]['distanceKm']) <= max(0.1, $candidate['distanceKm'] * 0.02)) {
                    $rows[$id]['duplicateCandidates'][] = $candidate['id'];
                }
            }
        }
        foreach ($rows as &$row) {
            $row['durationMinutes'] = null === $row['movingSeconds'] ? null : round($row['movingSeconds'] / 60, 2);
        }
        unset($row);
        usort($rows, static fn (array $a, array $b): int => strtotime($b['startAt']) <=> strtotime($a['startAt']) ?: strcmp($a['id'], $b['id']));

        return $rows;
    }

    /** @return array<string, mixed>|null */
    public function find(string $id): ?array
    {
        foreach ($this->all() as $row) {
            if (in_array($id, $row['aliases'], true)) {
                return $row;
            }
        }

        return null;
    }

    /** @return array<string, mixed> */
    public function overview(string $from, string $to, \DateTimeZone $zone, string $sportGroup = 'all'): array
    {
        HealthDataService::range($from, $to);
        if (!in_array($sportGroup, ['all', 'Run', 'Walk', 'Ride', 'Other'], true)) {
            throw new TrainingError('sportType 必须为 all、Run、Walk、Ride 或 Other。');
        }
        $groupedSports = array_merge(...array_values(self::SPORT_GROUPS));
        $rows = array_values(array_filter($this->all(), static function (array $r) use ($from, $to, $zone, $sportGroup, $groupedSports): bool {
            $date = new \DateTimeImmutable($r['startAt'])->setTimezone($zone)->format('Y-m-d');

            $matchesSport = match ($sportGroup) {
                'all' => true,
                'Other' => !in_array($r['sportType'], $groupedSports, true),
                default => in_array($r['sportType'], self::SPORT_GROUPS[$sportGroup], true),
            };

            return $date >= $from && $date <= $to && $matchesSport;
        }));
        $pending = count(array_filter($rows, static fn (array $r): bool => [] !== $r['duplicateCandidates']));
        $weeks = [];
        foreach ($rows as $row) {
            $day = new \DateTimeImmutable($row['startAt'])->setTimezone($zone);
            $week = $day->modify('monday this week')->format('Y-m-d');
            $weeks[$week][] = $row;
        }
        ksort($weeks);

        return ['records' => $rows, 'summary' => $this->summary($rows), 'pendingDuplicates' => $pending,
            'weeks' => array_map(fn (string $date, array $week): array => ['date' => $date, ...$this->summary($week)], array_keys($weeks), array_values($weeks)),
            'methodology' => '只有经确认的来源关联才合并。存在待核对重复时不显示累计总量。距离、时长缺失时不补零；导入记录为已关联运动的统计来源。'];
    }

    /** @param list<array<string, mixed>> $rows
     * @return array<string, mixed>
     */
    private function summary(array $rows): array
    {
        $pending = array_any($rows, static fn (array $r): bool => [] !== $r['duplicateCandidates']);
        $distance = [] !== $rows && !$pending && array_all($rows, static fn (array $r): bool => null !== $r['distanceKm']) ? array_sum(array_column($rows, 'distanceKm')) : null;
        $seconds = [] !== $rows && !$pending && array_all($rows, static fn (array $r): bool => null !== $r['movingSeconds']) ? array_sum(array_column($rows, 'movingSeconds')) : null;
        $heartRows = array_filter($rows, static fn (array $r): bool => null !== $r['averageHeartRate'] && $r['movingSeconds'] > 0);
        $hrSeconds = array_sum(array_column($heartRows, 'movingSeconds'));

        return ['count' => $pending ? null : count($rows), 'distanceKm' => null === $distance ? null : round($distance, 3), 'movingSeconds' => $seconds,
            'averageHeartRate' => !$pending && $hrSeconds > 0 ? round(array_sum(array_map(static fn (array $r): float => $r['averageHeartRate'] * $r['movingSeconds'], $heartRows)) / $hrSeconds, 1) : null];
    }

    /** @param array<string, mixed> $input
     * @return array<string, mixed>
     */
    public function link(string $cloudId, array $input): array
    {
        HealthContextInput::knownFields($input, ['activityId', 'version'], 'workout-link');
        TrainingInput::id($cloudId);
        $activityId = $input['activityId'] ?? null;
        // Imported activity IDs may exceed the 80-character IDs used by new records.
        if (!is_string($activityId) || strlen($activityId) > 255 || '' === $activityId) {
            throw new TrainingError('activityId 必须为实际导入运动的 ID。');
        }
        $version = TrainingInput::version($input['version'] ?? null);

        return $this->repository->transactional(function () use ($cloudId, $activityId, $version): array {
            if (null === $this->repository->find('zepp-workout', $cloudId) || false === $this->connection->fetchOne('SELECT activityId FROM Activity WHERE activityId = ? AND (markedForDeletion IS NULL OR markedForDeletion = 0)', [$activityId])) {
                throw new TrainingError('来源运动不存在。', 404);
            }
            $old = $this->repository->find('workout-links', $cloudId);
            if (null !== $old && 0 === $version && 1 === $old['version'] && $old['activityId'] === $activityId) {
                return $old;
            }
            if (false !== $this->connection->fetchOne('SELECT sessionId FROM TrainingActivityLink WHERE activityId = ?', [$cloudId])) {
                throw new TrainingError('云端记录已关联课表，请先解除该关联，再合并来源并重新关联。', 409);
            }
            foreach ($this->repository->list('workout-links') as $link) {
                if ($link['id'] !== $cloudId && $link['activityId'] === $activityId) {
                    throw new TrainingError('该导入记录已关联另一条云端运动。', 409);
                }
            }

            return $this->repository->save('workout-links', $cloudId, ['activityId' => $activityId], $version);
        });
    }

    /** @return array<string, mixed> */
    public function mapping(string $id): array
    {
        return $this->repository->find('workout-links', TrainingInput::id($id)) ?? throw new TrainingError('来源关联不存在。', 404);
    }

    public function unlink(string $id, int $version): void
    {
        $this->repository->transactional(function () use ($id, $version): void {
            $link = $this->mapping($id);
            if (false !== $this->connection->fetchOne('SELECT sessionId FROM TrainingActivityLink WHERE activityId IN (?, ?)', [$id, $link['activityId']])) {
                throw new TrainingError('运动仍关联课表，请先解除课表关联再拆分来源。', 409);
            }
            $this->repository->delete('workout-links', $id, $version);
        });
    }
}
