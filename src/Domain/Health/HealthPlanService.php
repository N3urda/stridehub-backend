<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;

final readonly class HealthPlanService
{
    public function __construct(private TrainingRepository $repository)
    {
    }

    /** @return list<array<string, mixed>> */
    public function list(): array
    {
        $plans = $this->repository->list('health-plans');
        usort($plans, static fn (array $a, array $b): int => strcmp($b['startDate'], $a['startDate']) ?: strcmp($a['id'], $b['id']));

        return $plans;
    }

    /** @return array<string, mixed> */
    public function get(string $id): array
    {
        return $this->repository->find('health-plans', TrainingInput::id($id)) ?? throw new TrainingError('计划不存在。', 404);
    }

    /** @param array<string, mixed> $patch
     * @return array<string, mixed>
     */
    public function save(string $id, array $patch): array
    {
        $id = TrainingInput::id($id);
        HealthContextInput::knownFields($patch, [...array_keys(HealthPlanInput::defaults()), 'version'], 'plan');
        $version = TrainingInput::version($patch['version'] ?? null);
        unset($patch['version']);

        return $this->repository->transactional(function () use ($id, $patch, $version): array {
            $current = $this->repository->find('health-plans', $id);
            $payload = $current ?? [];
            unset($payload['id'], $payload['version'], $payload['updatedAt']);
            if (null !== $current && 0 === $version && 1 === $current['version']) {
                if (HealthPlanInput::validate($patch) === $payload) {
                    return $current;
                }
                throw new TrainingError('此 ID 已用于其他计划，请重新读取或使用新的 ID。', 409);
            }
            if (null !== $current && $current['version'] !== $version) {
                throw new TrainingError('计划已经改变，请重新读取最新 version 后再编辑。', 409);
            }
            if (null === $current && $version > 0) {
                throw new TrainingError('计划不存在。', 404);
            }

            return $this->repository->save('health-plans', $id, HealthPlanInput::validate(array_replace($payload, $patch)), $version);
        });
    }

    public function delete(string $id, int $version): void
    {
        if ($version < 1) {
            throw new TrainingError('删除计划的 version 必须大于 0。');
        }
        $this->get($id);
        $this->repository->delete('health-plans', $id, $version);
    }
}
