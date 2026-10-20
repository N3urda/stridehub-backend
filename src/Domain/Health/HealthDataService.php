<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;

final readonly class HealthDataService
{
    public function __construct(private TrainingRepository $repository)
    {
    }

    /** @return list<array<string, mixed>> */
    public function list(string $resource, ?string $from = null, ?string $to = null): array
    {
        HealthDataInput::defaults($resource);
        self::range($from, $to);
        $rows = array_values(array_filter($this->repository->list($resource), static fn (array $r): bool => (null === $from || ($r['endDate'] ?? $r['date']) >= $from) && (null === $to || ($r['startDate'] ?? $r['date']) <= $to)));
        usort($rows, static fn (array $a, array $b): int => strcmp($b['date'] ?? $b['startDate'], $a['date'] ?? $a['startDate']) ?: strcmp($a['id'], $b['id']));

        return $rows;
    }

    public static function range(?string $from, ?string $to): void
    {
        foreach ([$from, $to] as $date) {
            if (null !== $date) {
                TrainingInput::date($date);
            }
        }
        if (null !== $from && null !== $to && $from > $to) {
            throw new TrainingError('开始日期不能晚于结束日期。');
        }
    }

    /** @return array<string, mixed> */
    public function get(string $resource, string $id): array
    {
        HealthDataInput::defaults($resource);

        return $this->repository->find($resource, TrainingInput::id($id)) ?? throw new TrainingError('记录不存在。', 404);
    }

    /** @param array<string, mixed> $patch
     * @return array<string, mixed>
     */
    public function save(string $resource, string $id, array $patch): array
    {
        HealthContextInput::knownFields($patch, [...array_keys(HealthDataInput::defaults($resource)), 'version'], $resource);
        TrainingInput::id($id);
        if ('batch' === $id) {
            throw new TrainingError('batch 是保留路径，不能用作记录 ID。');
        }
        $version = TrainingInput::version($patch['version'] ?? null);
        unset($patch['version']);

        return $this->repository->transactional(function () use ($resource, $id, $patch, $version): array {
            $old = $this->repository->find($resource, $id);
            $payload = $old ?? [];
            unset($payload['id'], $payload['version'], $payload['updatedAt']);
            if (null !== $old && 0 === $version && 1 === $old['version'] && HealthDataInput::validate($resource, $patch) === $payload) {
                return $old;
            }
            if (null !== $old && $old['version'] !== $version) {
                throw new TrainingError('记录已改变，请读取最新版本后核对修改。', 409);
            }
            if (null === $old && $version > 0) {
                throw new TrainingError('记录不存在。', 404);
            }
            if (null === $old && null !== $this->repository->find('health-data-deleted', hash('sha256', $resource.'/'.$id))) {
                throw new TrainingError('此 ID 已删除，请使用新的 ID。', 409);
            }
            $data = HealthDataInput::validate($resource, array_replace($payload, $patch));
            if ('meals' === $resource && null !== $data['planId']) {
                $plan = $this->get('meal-plans', $data['planId']);
                if ($plan['date'] !== $data['date']) {
                    throw new TrainingError('实际饮食与关联计划必须为同一天。');
                }
            }
            if ('meal-plans' === $resource && null !== $old && $old['date'] !== $data['date']) {
                foreach ($this->list('meals') as $meal) {
                    if ($meal['planId'] === $id) {
                        throw new TrainingError('已有实际饮食引用此计划，不能修改日期。', 409);
                    }
                }
            }
            $saved = $this->repository->save($resource, $id, $data, $version);
            $this->revision($resource, $id, $saved, 'save');

            return $saved;
        });
    }

    /** @param array<array-key, mixed> $records
     * @return list<array<string, mixed>>
     */
    public function batch(string $resource, array $records): array
    {
        if (!array_is_list($records) || count($records) < 1 || count($records) > 100) {
            throw new TrainingError('records 必须包含 1–100 条记录。');
        }

        return $this->repository->transactional(function () use ($resource, $records): array {
            $result = $ids = [];
            foreach ($records as $record) {
                if (!is_array($record)) {
                    throw new TrainingError('records 必须包含对象。');
                }
                $id = TrainingInput::id($record['id'] ?? null);
                if (isset($ids[$id])) {
                    throw new TrainingError('批量记录 ID 不能重复。');
                }
                $ids[$id] = true;
                unset($record['id']);
                $result[] = $this->save($resource, $id, $record);
            }

            return $result;
        });
    }

    public function delete(string $resource, string $id, int $version): void
    {
        $this->repository->transactional(function () use ($resource, $id, $version): void {
            $old = $this->get($resource, $id);
            if ('meal-plans' === $resource && array_any($this->list('meals'), static fn (array $m): bool => $m['planId'] === $id)) {
                throw new TrainingError('实际饮食仍引用此计划，不能删除。', 409);
            }
            $this->repository->delete($resource, $id, $version);
            $this->revision($resource, $id, [...$old, 'version' => $version + 1], 'delete');
            $this->repository->save('health-data-deleted', hash('sha256', $resource.'/'.$id), ['resource' => $resource, 'recordId' => $id], 0);
        });
    }

    /** @return list<array<string, mixed>> */
    public function history(string $resource, string $id): array
    {
        HealthDataInput::defaults($resource);
        TrainingInput::id($id);
        $rows = array_values(array_filter($this->repository->list('health-data-history'), static fn (array $r): bool => $r['resource'] === $resource && $r['recordId'] === $id));
        usort($rows, static fn (array $a, array $b): int => $b['recordVersion'] <=> $a['recordVersion']);

        return $rows;
    }

    /** @param array<string, mixed> $record */
    private function revision(string $resource, string $id, array $record, string $action): void
    {
        $this->repository->save('health-data-history', hash('sha256', $resource.'/'.$id.'/'.$record['version']), [
            'resource' => $resource, 'recordId' => $id, 'recordVersion' => $record['version'], 'action' => $action, 'record' => $record,
        ], 0);
    }
}
