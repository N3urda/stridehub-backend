<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Domain\Training\TrainingService;

final readonly class BloodPressureService
{
    public function __construct(private TrainingRepository $repository, private TrainingService $training)
    {
    }

    /** @return list<array<string, mixed>> */
    public function list(?string $from = null, ?string $to = null): array
    {
        if (null !== $from) {
            TrainingInput::date($from);
        }
        if (null !== $to) {
            TrainingInput::date($to);
        }
        if (null !== $from && null !== $to && $from > $to) {
            throw new TrainingError('from 不能晚于 to。');
        }
        $zone = new \DateTimeZone($this->training->profile()['timezone']);
        $records = array_values(array_filter($this->repository->list('blood-pressure'), static function (array $record) use ($from, $to, $zone): bool {
            $date = new \DateTimeImmutable($record['measuredAt'])->setTimezone($zone)->format('Y-m-d');

            return (null === $from || $date >= $from) && (null === $to || $date <= $to);
        }));
        usort($records, static fn (array $a, array $b): int => (strtotime($b['measuredAt']) <=> strtotime($a['measuredAt'])) ?: strcmp($a['id'], $b['id']));

        return $records;
    }

    /** @return array<string, mixed> */
    public function get(string $id): array
    {
        return $this->repository->find('blood-pressure', TrainingInput::id($id)) ?? throw new TrainingError('血压记录不存在。', 404);
    }

    /** @param array<string, mixed> $patch
     * @return array<string, mixed>
     */
    public function save(string $id, array $patch): array
    {
        $id = TrainingInput::id($id);
        HealthContextInput::knownFields($patch, [...array_keys(BloodPressureInput::defaults()), 'version'], 'blood-pressure');
        $version = TrainingInput::version($patch['version'] ?? null);
        unset($patch['version']);

        return $this->repository->transactional(function () use ($id, $patch, $version): array {
            $current = $this->repository->find('blood-pressure', $id);
            $currentPayload = $current ?? [];
            unset($currentPayload['id'], $currentPayload['version'], $currentPayload['updatedAt']);
            if (null !== $current && 0 === $version && 1 === $current['version']) {
                // Retrying an acknowledged-or-lost create must not duplicate a measurement.
                if (BloodPressureInput::validate($patch) === $currentPayload) {
                    return $current;
                }
                throw new TrainingError('此 ID 已用于其他血压记录，请重新读取记录或使用新的 ID。', 409);
            }
            if (null !== $current && $current['version'] !== $version) {
                throw new TrainingError('血压记录已经改变，请重新读取最新 version 后再编辑。', 409);
            }
            if (null === $current && $version > 0) {
                throw new TrainingError('血压记录不存在。', 404);
            }
            $payload = BloodPressureInput::validate(array_replace($currentPayload, $patch));

            return $this->repository->save('blood-pressure', $id, $payload, $version);
        });
    }

    public function delete(string $id, int $version): void
    {
        if ($version < 1) {
            throw new TrainingError('删除记录的 version 必须大于 0。');
        }
        $this->get($id);
        $this->repository->delete('blood-pressure', $id, $version);
    }
}
