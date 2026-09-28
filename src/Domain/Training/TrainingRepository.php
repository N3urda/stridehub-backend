<?php

declare(strict_types=1);

namespace App\Domain\Training;

use App\Infrastructure\Time\Clock\Clock;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\Exception\UniqueConstraintViolationException;

final readonly class TrainingRepository
{
    public function __construct(private Connection $connection, private Clock $clock)
    {
    }

    /** @return list<array<string, mixed>> */
    public function list(string $kind): array
    {
        return array_map($this->hydrate(...), $this->connection->fetchAllAssociative('SELECT * FROM TrainingRecord WHERE kind = ? ORDER BY id', [$kind]));
    }

    /** @return array<string, mixed>|null */
    public function find(string $kind, string $id): ?array
    {
        $row = $this->connection->fetchAssociative('SELECT * FROM TrainingRecord WHERE kind = ? AND id = ?', [$kind, $id]);

        return false === $row ? null : $this->hydrate($row);
    }

    /** @param array<string, mixed> $payload
     * @return array<string, mixed>
     */
    public function save(string $kind, string $id, array $payload, int $expectedVersion): array
    {
        unset($payload['id'], $payload['version'], $payload['updatedAt']);
        $now = $this->clock->getCurrentDateTimeImmutable()->format(DATE_ATOM);
        $naturalKey = match ($kind) {
            'check-ins' => $payload['date'] ?? null,
            'sessions' => $payload['activityId'] ?? null,
            default => null,
        };
        $data = ['payload' => json_encode($payload, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE), 'version' => $expectedVersion + 1, 'updatedAt' => $now, 'naturalKey' => $naturalKey];
        try {
            if (0 === $expectedVersion) {
                $this->connection->insert('TrainingRecord', ['kind' => $kind, 'id' => $id, ...$data]);
            } elseif (1 !== $this->connection->update('TrainingRecord', $data, ['kind' => $kind, 'id' => $id, 'version' => $expectedVersion])) {
                throw new TrainingError('记录已经改变，请重新读取最新版本后再编辑。', 409);
            }
        } catch (UniqueConstraintViolationException) {
            throw new TrainingError('ID、每日状态或关联运动已经存在，请先读取已有记录。', 409);
        }

        return [...$payload, 'id' => $id, 'version' => $expectedVersion + 1, 'updatedAt' => $now];
    }

    public function delete(string $kind, string $id, int $expectedVersion): void
    {
        if (1 !== $this->connection->delete('TrainingRecord', ['kind' => $kind, 'id' => $id, 'version' => $expectedVersion])) {
            throw new TrainingError('记录已经改变，请重新读取最新版本后再删除。', 409);
        }
    }

    public function transactional(callable $operation): mixed
    {
        return $this->connection->transactional(static fn (Connection $connection): mixed => $operation());
    }

    /** @param array<string, mixed> $row
     * @return array<string, mixed>
     */
    private function hydrate(array $row): array
    {
        return [...json_decode($row['payload'], true, 512, JSON_THROW_ON_ERROR), 'id' => $row['id'], 'version' => (int) $row['version'], 'updatedAt' => $row['updatedAt']];
    }
}
