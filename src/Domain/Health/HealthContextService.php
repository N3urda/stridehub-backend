<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;

final readonly class HealthContextService
{
    public function __construct(private TrainingRepository $repository)
    {
    }

    /** @return array<string, mixed> */
    public function get(): array
    {
        return $this->repository->find('health-context', 'default') ?? [...HealthContextInput::defaults(), 'id' => 'default', 'version' => 0, 'updatedAt' => null];
    }

    /** @param array<string, mixed> $patch
     * @return array<string, mixed>
     */
    public function update(array $patch): array
    {
        HealthContextInput::knownFields($patch, [...array_keys(HealthContextInput::defaults()), 'version'], 'context');
        $version = TrainingInput::version($patch['version'] ?? null);
        $current = $this->get();
        if ($version !== $current['version']) {
            throw new TrainingError('健康背景已经改变，请重新读取最新 version 后再编辑。', 409);
        }
        unset($patch['version'], $current['id'], $current['version'], $current['updatedAt']);
        $payload = HealthContextInput::validate(array_replace($current, $patch));

        return $this->repository->save('health-context', 'default', $payload, $version);
    }
}
