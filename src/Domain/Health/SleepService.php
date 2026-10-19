<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Time\Clock\Clock;
use Symfony\Component\Lock\LockFactory;

final readonly class SleepService
{
    public function __construct(private TrainingRepository $repository, private ZeppCredentials $credentials, private ZeppSleepClient $client, private ZeppSleepParser $parser, private Clock $clock, private LockFactory $locks)
    {
    }

    /** @return list<array<string, mixed>> */
    public function list(?string $from = null, ?string $to = null): array
    {
        foreach ([$from, $to] as $date) {
            if (null !== $date && (!preg_match('/\A\d{4}-\d{2}-\d{2}\z/', $date) || !checkdate((int) substr($date, 5, 2), (int) substr($date, 8, 2), (int) substr($date, 0, 4)))) {
                throw new TrainingError('日期必须是 YYYY-MM-DD。');
            }
        }
        if (null !== $from && null !== $to && $from > $to) {
            throw new TrainingError('开始日期不能晚于结束日期。');
        }
        $records = array_values(array_filter($this->repository->list('sleep'), static fn (array $record): bool => (null === $from || $record['date'] >= $from) && (null === $to || $record['date'] <= $to)));
        usort($records, static fn (array $a, array $b): int => [$b['date'], $b['endedAt'] ?? '', $b['id']] <=> [$a['date'], $a['endedAt'] ?? '', $a['id']]);

        return $records;
    }

    /** @return array<string, mixed>|null */
    public function forDate(string $date, ?string $timezone = null): ?array
    {
        if (null === $timezone) {
            return $this->list($date, $date)[0] ?? null;
        }
        $this->list($date, $date); // Validate the requested date consistently with the history API.
        try {
            $zone = new \DateTimeZone($timezone);
        } catch (\Throwable) {
            throw new TrainingError('时区无效。');
        }
        $now = $this->clock->getCurrentDateTimeImmutable();
        $candidates = array_values(array_filter($this->repository->list('sleep'), static function (array $record) use ($zone, $date, $now): bool {
            if (null === ($record['timeZone'] ?? null) || null === ($record['endedAt'] ?? null)) {
                return false;
            }
            $endedAt = new \DateTimeImmutable($record['endedAt']);

            return $endedAt <= $now && $endedAt->setTimezone($zone)->format('Y-m-d') === $date;
        }));
        usort($candidates, static fn (array $a, array $b): int => new \DateTimeImmutable($b['endedAt']) <=> new \DateTimeImmutable($a['endedAt']));

        return $candidates[0] ?? null;
    }

    /** @return array<string, mixed> */
    public function status(): array
    {
        $stored = $this->repository->find('sleep-sync', 'zepp') ?? [];
        $configured = $this->credentials->configured();
        $state = $configured ? ($stored['state'] ?? 'idle') : 'not_configured';
        $lastAttemptAt = $stored['lastAttemptAt'] ?? null;
        // A terminated process cannot leave an unrecoverable "syncing" badge.
        if ('syncing' === $state && is_string($lastAttemptAt) && new \DateTimeImmutable($lastAttemptAt) < $this->clock->getCurrentDateTimeImmutable()->modify('-5 minutes')) {
            $state = 'error';
        }

        return [
            'configured' => $configured, 'state' => $state,
            'lastAttemptAt' => $lastAttemptAt, 'lastSuccessAt' => $stored['lastSuccessAt'] ?? null,
            'lastError' => !$configured ? null : ('error' === $state && 'syncing' === ($stored['state'] ?? '') ? '上次同步中断。' : ($stored['lastError'] ?? null)),
            'nextAction' => !$configured ? '请在服务器配置 Zepp 授权文件，再点击同步。' : ('error' === $state && 'syncing' === ($stored['state'] ?? '') ? '请点击同步重试。' : ($stored['nextAction'] ?? '点击同步可获取最新数据；手表需先同步到 Zepp 手机 App。')),
            'recordCount' => count($this->repository->list('sleep')),
            'imported' => $stored['imported'] ?? 0, 'updated' => $stored['updated'] ?? 0, 'unchanged' => $stored['unchanged'] ?? 0, 'skipped' => $stored['skipped'] ?? 0,
        ];
    }

    /** @return array<string, mixed> */
    public function sync(int $days = 30): array
    {
        if ($days < 1 || $days > 90) {
            throw new TrainingError('同步天数必须在 1 到 90 之间。');
        }
        $lock = $this->locks->createLock('stridehub-zepp-sleep', 300);
        if (!$lock->acquire()) {
            throw new TrainingError('正在同步 Zepp 睡眠，请稍后刷新状态。', 409);
        }
        try {
            $now = $this->clock->getCurrentDateTimeImmutable();
            $stored = $this->repository->find('sleep-sync', 'zepp') ?? ['version' => 0];
            // App and daemon may live in separate containers with separate /tmp flock stores.
            // The versioned DB claim below also fences those processes across the shared DB.
            if ('syncing' === ($stored['state'] ?? null) && new \DateTimeImmutable($stored['lastAttemptAt']) > $now->modify('-5 minutes')) {
                throw new TrainingError('正在同步 Zepp 睡眠，请稍后刷新状态。', 409);
            }
            $state = [...$stored, 'state' => 'syncing', 'lastAttemptAt' => $now->format(DATE_ATOM), 'lastError' => null, 'nextAction' => '正在连接 Zepp。', 'imported' => 0, 'updated' => 0, 'unchanged' => 0, 'skipped' => 0];
            $state = $this->repository->save('sleep-sync', 'zepp', $state, $stored['version']);
            try {
                $credentials = $this->credentials->read();
                $accountKey = hash('sha256', 'stridehub-zepp-sleep:'.$credentials['userId']);
                if (isset($stored['accountKey']) && !hash_equals($stored['accountKey'], $accountKey)) {
                    throw new ZeppSyncError('error', 'Zepp 账号与已保存的睡眠数据不一致。', '请恢复原账号授权；更换用户前需单独迁移或清理其睡眠数据，避免混入他人记录。');
                }
                $from = $now->modify('-'.($days - 1).' days')->format('Y-m-d');
                $to = $now->format('Y-m-d');
                // Actual Zepp rows can be keyed one day after the local wake date.
                // Buffer both source boundaries, then filter using the normalized wake date.
                $rows = $this->client->fetch($credentials, $now->modify('-'.$days.' days')->format('Y-m-d'), $now->modify('+1 day')->format('Y-m-d'));
                $state['accountKey'] = $accountKey;
                $records = [];
                foreach ($rows as $row) {
                    $record = is_array($row) ? $this->parser->parse($row) : null;
                    if (null === $record || $record['date'] < $from || $record['date'] > $to) {
                        ++$state['skipped'];
                        continue;
                    }
                    // Multiple devices may return the same day. Prefer the most complete/longest session deterministically.
                    $key = $record['sourceDate'];
                    if (!isset($records[$key]) || ($record['totalMinutes'] ?? -1) > ($records[$key]['totalMinutes'] ?? -1)) {
                        $records[$key] = $record;
                    }
                }
                $this->repository->transactional(function () use ($records, $accountKey, $now, &$state): void {
                    foreach ($records as $sourceDate => $record) {
                        $id = 'zepp-'.substr($accountKey, 0, 16).'-'.$sourceDate;
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
                    $state['state'] = [] === $records ? 'no_data' : 'success';
                    $state['lastSuccessAt'] = $now->format(DATE_ATOM);
                    $state['nextAction'] = [] === $records ? '所选日期内没有有效睡眠记录；请先将手表同步到 Zepp 手机 App，再重试。历史记录已保留。' : '已同步；手表需先同步到 Zepp 手机 App，后端才能读取新睡眠。';
                    $state = $this->repository->save('sleep-sync', 'zepp', $state, $state['version']);
                });
            } catch (ZeppSyncError $error) {
                $this->repository->save('sleep-sync', 'zepp', [...$state, 'state' => $error->state, 'lastError' => $error->getMessage(), 'nextAction' => $error->nextAction], $state['version']);
            }

            return $this->status();
        } finally {
            $lock->release();
        }
    }
}
