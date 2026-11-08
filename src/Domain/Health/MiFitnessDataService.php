<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Time\Clock\Clock;
use Symfony\Component\Lock\LockFactory;

/** Independent source records. Missing measurements never become zero. */
final readonly class MiFitnessDataService
{
    public const array TYPES = ['steps', 'calories', 'heart_rate', 'resting_heart_rate', 'spo2', 'stress', 'weight', 'abnormal_heart_beat', 'workouts'];

    public function __construct(private TrainingRepository $repository, private MiFitnessCredentials $credentials, private MiFitnessClient $client, private Clock $clock, private LockFactory $locks)
    {
    }

    /** @return array<string,mixed> */
    public function status(): array
    {
        $state = $this->repository->find('mi-fitness-data-sync', 'default') ?? [];
        $sources = [];
        foreach (self::TYPES as $type) {
            $sources[$type] = $state['sources'][$type] ?? ['state' => 'idle', 'count' => 0];
        }
        $status = $state['state'] ?? 'idle';
        if ('syncing' === $status && new \DateTimeImmutable($state['leaseUntil']) <= $this->clock->getCurrentDateTimeImmutable()) {
            $status = 'error';
        }

        return ['state' => $status, 'sources' => $sources, 'lastSuccessAt' => $state['lastSuccessAt'] ?? null, 'lastAttemptAt' => $state['lastAttemptAt'] ?? null];
    }

    /** @return array<string,mixed> */
    public function sync(int $days = 7): array
    {
        if ($days < 1 || $days > 90) {
            throw new TrainingError('同步天数必须在 1 到 90 之间。');
        }
        $lock = $this->locks->createLock('stridehub-mi-fitness-data', 600);
        if (!$lock->acquire()) {
            throw new TrainingError('小米多类型数据正在同步。', 409);
        }
        try {
            $now = $this->clock->getCurrentDateTimeImmutable();
            $old = $this->repository->find('mi-fitness-data-sync', 'default') ?? ['version' => 0];
            if ('syncing' === ($old['state'] ?? null) && new \DateTimeImmutable($old['leaseUntil']) > $now) {
                throw new TrainingError('小米多类型数据正在同步。', 409);
            }
            $credentials = $this->credentials->read();
            $account = hash('sha256', 'stridehub-mi-fitness:'.$credentials['userId']);
            $owner = $this->repository->find('mi-fitness-account', 'default');
            if (null !== $owner && !hash_equals($owner['accountKey'], $account)) {
                throw new MiFitnessSyncError('auth_required', '小米账号与已保存记录不一致。');
            }
            if (null === $owner) {
                $this->repository->save('mi-fitness-account', 'default', ['accountKey' => $account], 0);
            }
            $zone = new \DateTimeZone($this->repository->find('profile', 'default')['timezone'] ?? 'Asia/Shanghai');
            $to = $now->setTimezone($zone)->format('Y-m-d');
            $from = $now->setTimezone($zone)->modify('-'.($days - 1).' days')->format('Y-m-d');
            $state = $this->repository->save('mi-fitness-data-sync', 'default', [...$old, 'state' => 'syncing', 'lastAttemptAt' => $now->format(DATE_ATOM), 'leaseUntil' => $now->modify('+10 minutes')->format(DATE_ATOM), 'sources' => []], $old['version']);
            foreach (self::TYPES as $type) {
                $result = ['state' => 'error', 'count' => 0, 'imported' => 0, 'updated' => 0, 'unchanged' => 0, 'skipped' => 0];
                try {
                    if ($this->clock->getCurrentDateTimeImmutable() >= new \DateTimeImmutable($state['leaseUntil'])) {
                        throw new MiFitnessSyncError('error', '本轮同步已超时，请重试。');
                    }
                    // Buffer timezone differences, filter by the user's local calendar below.
                    $rows = $this->client->records($credentials, $type, new \DateTimeImmutable($from)->modify('-1 day')->format('Y-m-d'), new \DateTimeImmutable($to)->modify('+1 day')->format('Y-m-d'));
                    $this->repository->transactional(function () use ($rows, $type, $account, $zone, $now, $from, $to, $state, &$result): void {
                        if (($this->repository->find('mi-fitness-data-sync', 'default')['version'] ?? null) !== $state['version'] || $this->clock->getCurrentDateTimeImmutable() >= new \DateTimeImmutable($state['leaseUntil'])) {
                            throw new TrainingError('同步租约已改变，请刷新状态。', 409);
                        }
                        foreach ($rows as $row) {
                            if (!is_array($row) || !is_string($row['sourceId'] ?? null) || !preg_match('/^[a-f0-9]{64}$/D', $row['sourceId'])
                                || !is_string($row['sourceDeviceKey'] ?? null) || !preg_match('/^[a-f0-9]{64}$/D', $row['sourceDeviceKey'])
                                || !is_string($row['recordedAt'] ?? null) || !preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/D', $row['recordedAt']) || !is_array($row['values'] ?? null)) {
                                ++$result['skipped'];
                                continue;
                            }
                            try {
                                $at = new \DateTimeImmutable($row['recordedAt']);
                            } catch (\Throwable) {
                                ++$result['skipped'];
                                continue;
                            }
                            $date = $at->setTimezone($zone)->format('Y-m-d');
                            if ($date < $from || $date > $to || $at > $now) {
                                continue;
                            }
                            $id = hash('sha256', $account.':'.$type.':'.$row['sourceId']);
                            $payload = ['source' => 'mi_fitness', 'sourceLabel' => '小米运动健康', 'type' => $type, 'date' => $date,
                                'recordedAt' => $at->format(DATE_ATOM), 'sourceDeviceKey' => $row['sourceDeviceKey'], 'values' => $row['values']];
                            $existing = $this->repository->find('mi-fitness-record', $id);
                            $compare = $existing;
                            if (null !== $compare) {
                                unset($compare['id'], $compare['version'], $compare['updatedAt']);
                            }
                            ++$result['count'];
                            if ($compare === $payload) {
                                ++$result['unchanged'];
                                continue;
                            }
                            $this->repository->save('mi-fitness-record', $id, $payload, $existing['version'] ?? 0);
                            ++$result[null === $existing ? 'imported' : 'updated'];
                        }
                        if ($this->clock->getCurrentDateTimeImmutable() >= new \DateTimeImmutable($state['leaseUntil'])) {
                            throw new MiFitnessSyncError('error', '本轮同步已超时，当前类型未写入，请重试。');
                        }
                    });
                    $result['state'] = $result['skipped'] > 0 ? 'partial' : ($result['count'] > 0 ? 'success' : 'no_data');
                } catch (MiFitnessSyncError $error) {
                    $result = ['count' => 0, 'imported' => 0, 'updated' => 0, 'unchanged' => 0, 'skipped' => 0];
                    $result['state'] = $error->state;
                    $result['message'] = $error->getMessage();
                }
                $state['sources'][$type] = $result;
                // Version fencing rejects late writers after a new run claimed the lease.
                $state = $this->repository->save('mi-fitness-data-sync', 'default', $state, $state['version']);
            }
            $failed = array_filter($state['sources'], static fn (array $r): bool => !in_array($r['state'], ['success', 'no_data'], true));
            $state['state'] = [] === $failed ? 'success' : (count($failed) === count(self::TYPES) ? 'error' : 'partial');
            if ([] === $failed) {
                $state['lastSuccessAt'] = $now->format(DATE_ATOM);
            }
            $this->repository->save('mi-fitness-data-sync', 'default', $state, $state['version']);

            return $this->status();
        } finally {
            $lock->release();
        }
    }

    /** @return array<string,mixed> */
    public function records(string $type, ?string $from = null, ?string $to = null, int $offset = 0, int $limit = 100): array
    {
        if (!in_array($type, self::TYPES, true) || $offset < 0 || $limit < 1 || $limit > 200) {
            throw new TrainingError('数据类型或分页参数无效。');
        }
        HealthDataService::range($from, $to);
        $rows = array_values(array_filter($this->repository->list('mi-fitness-record'), static fn (array $r): bool => $r['type'] === $type && (null === $from || $r['date'] >= $from) && (null === $to || $r['date'] <= $to)));
        usort($rows, static fn (array $a, array $b): int => [$b['recordedAt'], $b['id']] <=> [$a['recordedAt'], $a['id']]);

        return ['records' => array_slice($rows, $offset, $limit), 'total' => count($rows), 'nextOffset' => $offset + $limit < count($rows) ? $offset + $limit : null];
    }

    /** @return array<string,mixed> */
    public function overview(?string $from = null, ?string $to = null): array
    {
        HealthDataService::range($from, $to);
        $counts = array_fill_keys(self::TYPES, 0);
        $minutes = [];
        foreach ($this->repository->list('mi-fitness-record') as $r) {
            if ((null !== $from && $r['date'] < $from) || (null !== $to && $r['date'] > $to)) {
                continue;
            }
            ++$counts[$r['type']];
            if (!in_array($r['type'], ['steps', 'calories'], true)) {
                continue;
            }
            $key = (string) intdiv(strtotime($r['recordedAt']), 60);
            $field = 'steps' === $r['type'] ? 'steps' : 'calories';
            $old = $minutes[$r['date']][$r['type']][$key] ?? null;
            if (null === $old || ($r['values'][$field] ?? -1) > ($old['values'][$field] ?? -1)) {
                $minutes[$r['date']][$r['type']][$key] = $r;
            }
        }
        krsort($minutes);
        $days = [];
        foreach ($minutes as $date => $types) {
            $sum = static function (string $type, string $key) use ($types): ?float {
                $rows = $types[$type] ?? [];
                if ([] === $rows) {
                    return null;
                }
                $total = 0.;
                foreach ($rows as $r) {
                    $v = $r['values'][$key] ?? null;
                    if ((!is_int($v) && !is_float($v)) || $v < 0 || !is_finite((float) $v)) {
                        return null;
                    } $total += $v;
                }

                return round($total, 3);
            };
            $days[] = ['date' => $date, 'source' => 'mi_fitness', 'steps' => $sum('steps', 'steps'), 'distanceMeters' => $sum('steps', 'distance'),
                'stepCalories' => $sum('steps', 'calories'), 'activityCalories' => $sum('calories', 'calories'), 'sampleMinutes' => count($types['steps'] ?? [])];
        }

        return ['counts' => $counts, 'daily' => $days, 'status' => $this->status(), 'methodology' => '同一分钟取较大单条记录，不叠加设备；步数流热量和独立热量分开，不与 Zepp 总数相加。未知字段保留源值，缺失不补零。'];
    }
}
