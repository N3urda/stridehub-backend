<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Time\Clock\Clock;
use Symfony\Component\Lock\LockFactory;

final readonly class ZeppWorkoutService
{
    public function __construct(private TrainingRepository $repository, private ZeppCredentials $credentials, private ZeppWorkoutClient $client, private ZeppWorkoutParser $parser, private Clock $clock, private LockFactory $locks)
    {
    }

    /** @return list<array<string,mixed>> */
    public function list(?string $from = null, ?string $to = null): array
    {
        foreach ([$from, $to] as $date) {
            if (null !== $date && !ZeppDataParser::validDate($date)) {
                throw new TrainingError('日期必须是有效的 YYYY-MM-DD。');
            }
        }
        if (null !== $from && null !== $to && $from > $to) {
            throw new TrainingError('开始日期不能晚于结束日期。');
        }
        $records = array_values(array_filter($this->repository->list('zepp-workout'), static fn (array $record): bool => (null === $from || $record['date'] >= $from) && (null === $to || $record['date'] <= $to)));
        usort($records, static fn (array $a, array $b): int => $b['startedAt'] <=> $a['startedAt']);

        return $records;
    }

    /** @return array<string,mixed> */
    public function detail(string $id): array
    {
        $record = $this->repository->find('zepp-workout', $id) ?? throw new TrainingError('运动记录不存在。', 404);
        $detail = $this->repository->find('zepp-workout-detail', $id) ?? [];
        unset($detail['id'], $detail['version'], $detail['updatedAt']);

        return [...$record, ...$detail];
    }

    /** @return array<string,mixed> */
    public function source(string $id): array
    {
        return $this->repository->find('zepp-workout-source', $id) ?? throw new TrainingError('运动原始记录不存在。', 404);
    }

    /** @return array<string,mixed> */
    public function status(): array
    {
        $state = $this->repository->find('zepp-workout-sync', 'default') ?? [];
        $configured = $this->credentials->configured();
        $status = $configured ? ($state['state'] ?? 'idle') : 'not_configured';
        if ('syncing' === $status && new \DateTimeImmutable($state['leaseUpdatedAt'] ?? $state['lastAttemptAt']) < $this->clock->getCurrentDateTimeImmutable()->modify('-15 minutes')) {
            $status = 'error';
            $state['lastError'] = '上次运动同步中断，请重试。';
        }

        return ['configured' => $configured, 'state' => $status, 'recordCount' => count($this->repository->list('zepp-workout')),
            'lastAttemptAt' => $state['lastAttemptAt'] ?? null, 'lastSuccessAt' => $state['lastSuccessAt'] ?? null, 'lastError' => $state['lastError'] ?? null,
            'nextAction' => $state['nextAction'] ?? '请先在 Zepp 手机 App 上传手表数据，再同步运动。', 'requestedRange' => $state['requestedRange'] ?? null,
            'imported' => $state['imported'] ?? 0, 'updated' => $state['updated'] ?? 0, 'unchanged' => $state['unchanged'] ?? 0, 'skipped' => $state['skipped'] ?? 0,
            'detailsFetched' => $state['detailsFetched'] ?? 0, 'detailsFailed' => $state['detailsFailed'] ?? 0, 'detailsPartial' => $state['detailsPartial'] ?? 0, 'sampleCount' => $state['sampleCount'] ?? 0, 'pages' => $state['pages'] ?? 0,
        ];
    }

    /** @return array<string,mixed> */
    public function sync(int $days = 30): array
    {
        if ($days < 1 || $days > 90) {
            throw new TrainingError('同步天数必须在 1 到 90 之间。');
        }
        $lock = $this->locks->createLock('stridehub-zepp-workouts', 900);
        if (!$lock->acquire()) {
            throw new TrainingError('运动正在同步，请稍后刷新。', 409);
        }
        try {
            $now = $this->clock->getCurrentDateTimeImmutable();
            try {
                $zone = new \DateTimeZone($this->repository->find('profile', 'default')['timezone'] ?? 'Asia/Shanghai');
            } catch (\Throwable) {
                $zone = new \DateTimeZone('Asia/Shanghai');
            }
            $today = $now->setTimezone($zone)->setTime(0, 0);
            $from = $today->modify('-'.($days - 1).' days');
            $until = $today->modify('+1 day');
            $stored = $this->repository->find('zepp-workout-sync', 'default') ?? ['version' => 0];
            if ('syncing' === ($stored['state'] ?? null) && new \DateTimeImmutable($stored['leaseUpdatedAt'] ?? $stored['lastAttemptAt']) > $now->modify('-15 minutes')) {
                throw new TrainingError('运动正在同步，请稍后刷新。', 409);
            }
            $state = $this->repository->save('zepp-workout-sync', 'default', [...$stored, 'state' => 'syncing', 'lastAttemptAt' => $now->format(DATE_ATOM), 'leaseUpdatedAt' => $now->format(DATE_ATOM), 'lastError' => null,
                'imported' => 0, 'updated' => 0, 'unchanged' => 0, 'skipped' => 0, 'detailsFetched' => 0, 'detailsFailed' => 0, 'detailsPartial' => 0, 'sampleCount' => 0, 'pages' => 0,
                'requestedRange' => ['from' => $from->format('Y-m-d'), 'to' => $today->format('Y-m-d')]], $stored['version']);
            try {
                $credentials = $this->credentials->read();
                $account = ZeppAccountGuard::claim($this->repository, $credentials['userId']);
                $state['accountKey'] = $account;
                // Persist account ownership before any records, including an interrupted sync.
                $state = $this->repository->save('zepp-workout-sync', 'default', $state, $state['version']);
                $cursor = null;
                $seen = [];
                for ($page = 0; $page < 100; ++$page) {
                    $result = $this->client->history($credentials, $cursor);
                    ++$state['pages'];
                    $older = false;
                    foreach ($result['records'] as $row) {
                        $record = is_array($row) ? $this->parser->summary($row, $zone) : null;
                        if (null === $record) {
                            ++$state['skipped'];
                            continue;
                        }
                        $startedAt = new \DateTimeImmutable($record['startedAt']);
                        if ($startedAt < $from) {
                            $older = true;
                            continue;
                        }
                        if ($startedAt >= $until || isset($seen[$record['sourceId']])) {
                            continue;
                        }
                        $seen[$record['sourceId']] = true;
                        $id = 'zepp-'.substr($account, 0, 16).'-'.$record['sourceId'];
                        $existing = $this->repository->find('zepp-workout', $id);
                        $oldDetail = $this->repository->find('zepp-workout-detail', $id);
                        $sourceDetail = $oldDetail['sourceDetail'] ?? null;
                        $streams = $oldDetail['streams'] ?? [];
                        $detailError = null;
                        $retainedFields = [];
                        foreach ($record as $field => $value) {
                            if (null === $value && null !== ($existing[$field] ?? null)) {
                                $record[$field] = $existing[$field];
                                $retainedFields[] = $field;
                            }
                        }
                        try {
                            $fetchedDetail = $this->client->detail($credentials, $record['sourceId'], is_string($row['source'] ?? null) ? $row['source'] : 'run.mi.com');
                            if ((string) ($fetchedDetail['trackid'] ?? '') !== $record['sourceId']) {
                                throw new ZeppSyncError('error', 'Zepp 返回的运动详情与列表不一致。', '请稍后重试；原有轨迹保留。');
                            }
                            $sourceDetail = $fetchedDetail;
                            $streams = $this->parser->streams($sourceDetail, $record['startedAt'], $record['endedAt']);
                            foreach ($oldDetail['streams'] ?? [] as $kind => $oldSamples) {
                                if ([] !== $oldSamples && [] === ($streams[$kind] ?? [])) {
                                    $streams[$kind] = $oldSamples;
                                    $retainedFields[] = 'streams.'.$kind;
                                }
                            }
                            ++$state['detailsFetched'];
                        } catch (ZeppSyncError $error) {
                            ++$state['detailsFailed'];
                            $detailError = $error->state;
                        }
                        $record['detailState'] = null === $detailError ? ([] === $retainedFields ? 'available' : 'partial') : (null === $sourceDetail ? $detailError : 'stale');
                        $record['retainedFields'] = $retainedFields;
                        if ([] !== $retainedFields) {
                            ++$state['detailsPartial'];
                        }
                        foreach ($record['streamCounts'] as $kind => $_) {
                            $record['streamCounts'][$kind] = count($streams[$kind] ?? []);
                        }
                        $state['sampleCount'] += array_sum($record['streamCounts']);
                        $snapshots = [];
                        foreach ([['summary', $oldDetail['sourceSummary'] ?? null], ['detail', $oldDetail['sourceDetail'] ?? null], ['summary', $row], ['detail', $sourceDetail]] as [$kind, $payload]) {
                            if (is_array($payload)) {
                                $snapshotId = hash('sha256', $id.':'.$kind.':'.json_encode($payload, JSON_THROW_ON_ERROR));
                                $snapshots[$snapshotId] = ['source' => 'zepp', 'workoutId' => $id, 'kind' => $kind, 'payload' => $payload, 'fetchedAt' => $now->format(DATE_ATOM)];
                            }
                        }
                        $sourceIds = array_values(array_unique([...($oldDetail['sourceRecordIds'] ?? []), ...array_keys($snapshots)]));
                        sort($sourceIds);
                        $detail = ['streams' => $streams, 'sourceSummary' => $row, 'sourceDetail' => $sourceDetail, 'sourceRecordIds' => $sourceIds,
                            'sourceDetailFetchedAt' => null === $detailError ? $now->format(DATE_ATOM) : ($oldDetail['sourceDetailFetchedAt'] ?? null)];
                        $this->repository->transactional(function () use ($id, $record, $existing, $oldDetail, $detail, $snapshots, $now, &$state): void {
                            // Claim CAS and heartbeat share this transaction with all workout
                            // writes. A replacement owner invalidates this stale response before
                            // any raw source or normalized measurement can be committed.
                            $state['leaseUpdatedAt'] = $this->clock->getCurrentDateTimeImmutable()->format(DATE_ATOM);
                            $state = $this->repository->save('zepp-workout-sync', 'default', $state, $state['version']);
                            foreach ($snapshots as $snapshotId => $snapshot) {
                                if (null === $this->repository->find('zepp-workout-source', $snapshotId)) {
                                    $this->repository->save('zepp-workout-source', $snapshotId, $snapshot, 0);
                                }
                            }
                            $before = $existing;
                            unset($before['id'], $before['version'], $before['updatedAt'], $before['lastSyncedAt']);
                            $beforeDetail = $oldDetail;
                            unset($beforeDetail['id'], $beforeDetail['version'], $beforeDetail['updatedAt'], $beforeDetail['sourceDetailFetchedAt']);
                            $afterDetail = $detail;
                            unset($afterDetail['sourceDetailFetchedAt']);
                            if (json_encode($before, JSON_THROW_ON_ERROR) === json_encode($record, JSON_THROW_ON_ERROR) && json_encode($beforeDetail, JSON_THROW_ON_ERROR) === json_encode($afterDetail, JSON_THROW_ON_ERROR)) {
                                ++$state['unchanged'];
                            } else {
                                $this->repository->save('zepp-workout', $id, [...$record, 'lastSyncedAt' => $now->format(DATE_ATOM)], $existing['version'] ?? 0);
                                $this->repository->save('zepp-workout-detail', $id, $detail, $oldDetail['version'] ?? 0);
                                ++$state[null === $existing ? 'imported' : 'updated'];
                            }
                            $state = $this->repository->save('zepp-workout-sync', 'default', $state, $state['version']);
                        });
                        $lock->refresh();
                    }
                    $state = $this->repository->save('zepp-workout-sync', 'default', $state, $state['version']);
                    if ($older || null === $result['next'] || [] === $result['records']) {
                        break;
                    }
                    if ((null !== $cursor && $result['next'] >= $cursor) || 99 === $page) {
                        throw new ZeppSyncError('error', 'Zepp 运动分页未正常结束。', '本次已读取的记录保留，请缩短同步范围后重试。');
                    }
                    $cursor = $result['next'];
                }
                $state['state'] = $state['detailsFailed'] > 0 || $state['skipped'] > 0 || $state['detailsPartial'] > 0 ? 'partial' : ([] === $seen ? 'no_data' : 'success');
                $state['lastSuccessAt'] = $now->format(DATE_ATOM);
                $state['nextAction'] = 'partial' === $state['state'] ? '摘要已保存；部分详情未能更新，可再次同步重试。' : '运动摘要、原始详情和实测轨迹已保存，AI 可按运动读取。';
                $this->repository->save('zepp-workout-sync', 'default', $state, $state['version']);
            } catch (ZeppSyncError $error) {
                $this->repository->save('zepp-workout-sync', 'default', [...$state, 'state' => $error->state, 'lastError' => $error->getMessage(), 'nextAction' => $error->nextAction], $state['version']);
            }

            return $this->status();
        } finally {
            $lock->release();
        }
    }
}
