<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Time\Clock\Clock;
use Symfony\Component\Lock\LockFactory;

final readonly class MiFitnessService
{
    public function __construct(private TrainingRepository $repository, private MiFitnessCredentials $credentials, private MiFitnessClient $client, private MiFitnessSleepParser $parser, private Clock $clock, private LockFactory $locks, private ?MiFitnessDataService $data = null)
    {
    }

    /** @return array<string,mixed> */
    public function status(): array
    {
        $stored = $this->repository->find('mi-fitness-sync', 'default') ?? [];
        $configured = $this->credentials->configured();
        $state = $configured ? ($stored['state'] ?? 'idle') : 'not_configured';
        if ('syncing' === $state && new \DateTimeImmutable($stored['leaseUntil']) <= $this->clock->getCurrentDateTimeImmutable()) {
            $state = 'error';
        }

        $dataStatus = $this->data?->status();
        if ($configured && null !== $dataStatus && 'syncing' !== $state) {
            if ('syncing' === $dataStatus['state']) {
                $state = 'syncing';
            } elseif ('error' === $state && in_array($dataStatus['state'], ['success', 'partial'], true)) {
                $state = 'partial';
            } elseif (in_array($dataStatus['state'], ['error', 'partial'], true) && in_array($state, ['success', 'no_data'], true)) {
                $state = 'partial';
            }
        }

        return ['dataSync' => $dataStatus, 'configured' => $configured, 'state' => $state, 'source' => 'mi_fitness',
            'lastAttemptAt' => $stored['lastAttemptAt'] ?? null, 'sleepLastSuccessAt' => $stored['lastSuccessAt'] ?? null, 'lastSuccessAt' => null === $dataStatus ? ($stored['lastSuccessAt'] ?? null) : (isset($stored['lastSuccessAt'], $dataStatus['lastSuccessAt']) ? min($stored['lastSuccessAt'], $dataStatus['lastSuccessAt']) : null),
            'lastError' => 'syncing' === ($stored['state'] ?? null) && 'error' === $state ? '上次同步中断，请重试。' : ($stored['lastError'] ?? null),
            'recordCount' => count(array_filter($this->repository->list('sleep'), static fn (array $r): bool => 'mi_fitness' === ($r['source'] ?? null))),
            'imported' => $stored['imported'] ?? 0, 'updated' => $stored['updated'] ?? 0, 'unchanged' => $stored['unchanged'] ?? 0, 'skipped' => $stored['skipped'] ?? 0,
            'nextAction' => $configured ? '先在小米运动健康 App 上传数据，再同步；已有记录始终保留。' : '请在服务器配置小米连接器，详见 mi-fitness 接入文档。'];
    }

    /** @return array<string,mixed> */
    public function sync(int $days = 7): array
    {
        $lock = $this->locks->createLock('stridehub-mi-fitness-all', 900);
        if (!$lock->acquire()) {
            throw new TrainingError('小米数据正在同步，请稍后刷新。', 409);
        }
        try {
            if ('syncing' === $this->status()['state']) {
                throw new TrainingError('小米数据正在同步，请稍后刷新。', 409);
            }
            $this->syncSleep($days);
            $sleep = $this->repository->find('mi-fitness-sync', 'default');
            if (null !== $this->data && null !== $sleep && !in_array($sleep['state'] ?? 'error', ['not_configured', 'auth_required'], true)) {
                $this->data->sync($days);
            }

            return $this->status();
        } finally {
            $lock->release();
        }
    }

    /** @return array<string,mixed> */
    private function syncSleep(int $days = 7): array
    {
        if ($days < 1 || $days > 90) {
            throw new TrainingError('同步天数必须在 1 到 90 之间。');
        }
        $lock = $this->locks->createLock('stridehub-mi-fitness', 300);
        if (!$lock->acquire()) {
            throw new TrainingError('小米数据正在同步，请稍后刷新。', 409);
        }
        try {
            $now = $this->clock->getCurrentDateTimeImmutable();
            $stored = $this->repository->find('mi-fitness-sync', 'default') ?? ['version' => 0];
            if ('syncing' === ($stored['state'] ?? null) && new \DateTimeImmutable($stored['leaseUntil']) > $now) {
                throw new TrainingError('小米数据正在同步，请稍后刷新。', 409);
            }
            $state = $this->repository->save('mi-fitness-sync', 'default', [...$stored, 'state' => 'syncing', 'lastAttemptAt' => $now->format(DATE_ATOM), 'leaseUntil' => $now->modify('+5 minutes')->format(DATE_ATOM), 'lastError' => null, 'imported' => 0, 'updated' => 0, 'unchanged' => 0, 'skipped' => 0], $stored['version']);
            try {
                $credentials = $this->credentials->read();
                $account = hash('sha256', 'stridehub-mi-fitness:'.$credentials['userId']);
                $owner = $this->repository->find('mi-fitness-account', 'default');
                if (null !== $owner && !hash_equals($owner['accountKey'], $account)) {
                    throw new MiFitnessSyncError('auth_required', '小米账号与已保存记录不一致，请恢复原账号；不同账号需使用独立数据库。');
                }
                if (null === $owner) {
                    $this->repository->save('mi-fitness-account', 'default', ['accountKey' => $account], 0);
                }
                $zone = new \DateTimeZone($this->repository->find('profile', 'default')['timezone'] ?? 'Asia/Shanghai');
                $today = $now->setTimezone($zone)->setTime(0, 0);
                $from = $today->modify('-'.($days - 1).' days');
                // Bridge filters by start_at. Buffer up to 36h sleep plus offset changes.
                $rows = $this->client->fetch($credentials, $from->modify('-3 days')->format('Y-m-d'), $today->modify('+1 day')->format('Y-m-d'));
                $this->repository->transactional(function () use ($rows, $zone, $now, $from, $today, $account, &$state): void {
                    foreach ($rows as $row) {
                        $record = is_array($row) ? $this->parser->parse($row, $zone) : null;
                        if (null === $record) {
                            ++$state['skipped'];
                            continue;
                        }
                        if ($record['date'] < $from->format('Y-m-d') || $record['date'] > $today->format('Y-m-d') || new \DateTimeImmutable($record['endedAt']) > $now) {
                            continue;
                        }
                        $id = 'mi-'.hash('sha256', $account.':'.$record['sourceId']);
                        $existing = $this->repository->find('sleep', $id);
                        $comparable = $existing;
                        if (null !== $comparable) {
                            unset($comparable['id'], $comparable['version'], $comparable['updatedAt'], $comparable['lastSyncedAt']);
                        }
                        if ($comparable === $record) {
                            ++$state['unchanged'];
                            continue;
                        }
                        $this->repository->save('sleep', $id, [...$record, 'lastSyncedAt' => $now->format(DATE_ATOM)], $existing['version'] ?? 0);
                        ++$state[null === $existing ? 'imported' : 'updated'];
                    }
                    if (new \DateTimeImmutable($state['leaseUntil']) <= $this->clock->getCurrentDateTimeImmutable()) {
                        throw new MiFitnessSyncError('error', '同步超时，未写入本次数据，请缩短范围重试。');
                    }
                    $state['state'] = $state['skipped'] > 0 ? 'partial' : ($state['imported'] + $state['updated'] + $state['unchanged'] > 0 ? 'success' : 'no_data');
                    if ('partial' !== $state['state']) {
                        $state['lastSuccessAt'] = $now->format(DATE_ATOM);
                    }
                    $state['lastError'] = 'partial' === $state['state'] ? '部分睡眠记录无法解析，已保留有效记录和历史数据。' : null;
                    $state = $this->repository->save('mi-fitness-sync', 'default', $state, $state['version']);
                });
            } catch (MiFitnessSyncError $error) {
                $this->repository->save('mi-fitness-sync', 'default', [...$state, 'state' => $error->state, 'lastError' => $error->getMessage()], $state['version']);
            }

            return $this->status();
        } finally {
            $lock->release();
        }
    }
}
