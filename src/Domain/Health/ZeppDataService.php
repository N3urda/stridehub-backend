<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Time\Clock\Clock;
use Symfony\Component\Lock\LockFactory;

final readonly class ZeppDataService
{
    public function __construct(private TrainingRepository $repository, private ZeppCredentials $credentials, private ZeppDataClient $client, private ZeppDataParser $parser, private Clock $clock, private LockFactory $locks)
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
        $records = array_values(array_filter($this->repository->list('zepp-day'), static fn (array $record): bool => (null === $from || $record['date'] >= $from) && (null === $to || $record['date'] <= $to)));
        usort($records, static fn (array $a, array $b): int => $b['date'] <=> $a['date']);

        return $records;
    }

    /** @return array<string,mixed> */
    public function day(string $date): array
    {
        $record = $this->list($date, $date)[0] ?? throw new TrainingError('这一天尚未同步 Zepp 数据。', 404);
        $detail = $this->repository->find('zepp-detail', $record['id']);

        return [...$record, 'samples' => $detail['samples'] ?? [], 'sleepStages' => $detail['sleepStages'] ?? [], 'activityStages' => $detail['activityStages'] ?? []];
    }

    /** @return array<string,mixed> */
    public function raw(string $id): array
    {
        return $this->repository->find('zepp-raw', $id) ?? throw new TrainingError('原始数据不存在。', 404);
    }

    /** Searchable metadata makes even undated provider records discoverable without dumping private payloads.
     * @return array{records:list<array<string,mixed>>,next:?string}
     */
    public function sources(?string $kind = null, ?string $after = null, int $limit = 100): array
    {
        if ($limit < 1 || $limit > 200 || (null !== $after && !preg_match('/^[a-f0-9]{64}$/D', $after))) {
            throw new TrainingError('分页参数无效；limit 必须为 1 到 200。');
        }
        $rows = array_values(array_filter($this->repository->list('zepp-raw'), static fn (array $row): bool => (null === $kind || $kind === $row['kind']) && (null === $after || strcmp($row['id'], $after) > 0)));
        usort($rows, static fn (array $a, array $b): int => strcmp($a['id'], $b['id']));
        $page = array_slice($rows, 0, $limit);
        $metadata = array_map(static fn (array $row): array => ['id' => $row['id'], 'kind' => $row['kind'], 'fetchedAt' => $row['fetchedAt'], 'sourceDate' => $row['sourceDate'] ?? null], $page);

        return ['records' => $metadata, 'next' => count($rows) > $limit ? $page[$limit - 1]['id'] : null];
    }

    /** @return array<string,mixed> */
    public function status(): array
    {
        $state = $this->repository->find('zepp-sync', 'default') ?? [];
        $configured = $this->credentials->configured();
        $status = $configured ? ($state['state'] ?? 'idle') : 'not_configured';
        if ('syncing' === $status && new \DateTimeImmutable($state['lastAttemptAt']) < $this->clock->getCurrentDateTimeImmutable()->modify('-15 minutes')) {
            $status = 'error';
            $state['lastError'] = '上次同步中断，请重试。';
        }

        return ['configured' => $configured, 'state' => $status, 'lastAttemptAt' => $state['lastAttemptAt'] ?? null, 'lastSuccessAt' => $state['lastSuccessAt'] ?? null,
            'lastError' => $state['lastError'] ?? null, 'nextAction' => $state['nextAction'] ?? ($configured ? '同步前请先将手表数据上传到 Zepp 手机 App。' : '请配置服务器 Zepp 授权文件。'),
            'recordCount' => count($this->repository->list('zepp-day')), 'rawRecordCount' => $state['rawRecordCount'] ?? 0,
            'imported' => $state['imported'] ?? 0, 'updated' => $state['updated'] ?? 0, 'unchanged' => $state['unchanged'] ?? 0, 'skipped' => $state['skipped'] ?? 0,
            'sources' => $state['sources'] ?? [], 'requestedRange' => $state['requestedRange'] ?? null,
        ];
    }

    /** @return array<string,mixed> */
    public function sync(int $days = 30): array
    {
        if ($days < 1 || $days > 90) {
            throw new TrainingError('同步天数必须在 1 到 90 之间。');
        }
        $lock = $this->locks->createLock('stridehub-zepp-sleep', 900);
        if (!$lock->acquire()) {
            throw new TrainingError('正在同步 Zepp 数据，请稍后刷新。', 409);
        }
        try {
            $deadline = microtime(true) + 600; // Complete before the shared 15-minute lease expires.
            $now = $this->clock->getCurrentDateTimeImmutable();
            $profile = $this->repository->find('profile', 'default');
            $timezone = $profile['timezone'] ?? 'Asia/Shanghai';
            try {
                $zone = new \DateTimeZone($timezone);
            } catch (\Throwable) {
                $zone = new \DateTimeZone('Asia/Shanghai');
                $timezone = 'Asia/Shanghai';
            }
            $today = $now->setTimezone($zone)->setTime(0, 0);
            $from = $today->modify('-'.($days - 1).' days');
            $until = $today->modify('+1 day');
            $stored = $this->repository->find('zepp-sync', 'default') ?? ['version' => 0];
            $sleepStored = $this->repository->find('sleep-sync', 'zepp') ?? ['version' => 0];
            if ('syncing' === ($stored['state'] ?? null) && new \DateTimeImmutable($stored['lastAttemptAt']) > $now->modify('-15 minutes')) {
                throw new TrainingError('正在同步 Zepp 数据，请稍后刷新。', 409);
            }
            if ('syncing' === ($sleepStored['state'] ?? null) && new \DateTimeImmutable($sleepStored['leaseUntil'] ?? new \DateTimeImmutable($sleepStored['lastAttemptAt'])->modify('+5 minutes')->format(DATE_ATOM)) > $now) {
                throw new TrainingError('正在同步 Zepp 睡眠，请稍后刷新。', 409);
            }
            // Claim both records atomically. The legacy synchronizer checks this same DB version
            // and lease, so distinct container flock stores cannot bypass this ownership fence.
            [$state, $sleepClaim] = $this->repository->transactional(function () use ($stored, $sleepStored, $now, $from, $today): array {
                $state = $this->repository->save('zepp-sync', 'default', [...$stored, 'state' => 'syncing', 'lastAttemptAt' => $now->format(DATE_ATOM), 'lastError' => null, 'sources' => [], 'imported' => 0, 'updated' => 0, 'unchanged' => 0, 'skipped' => 0, 'requestedRange' => ['from' => $from->format('Y-m-d'), 'to' => $today->format('Y-m-d')]], $stored['version']);
                $sleepClaim = $this->repository->save('sleep-sync', 'zepp', [...$sleepStored, 'state' => 'syncing', 'lastAttemptAt' => $now->format(DATE_ATOM), 'leaseUntil' => $now->modify('+15 minutes')->format(DATE_ATOM), 'lastError' => null, 'nextAction' => '正在同步 Zepp 详细数据。'], $sleepStored['version']);

                return [$state, $sleepClaim];
            });
            try {
                $credentials = $this->credentials->read();
                $account = ZeppAccountGuard::claim($this->repository, $credentials['userId']);
                $state['accountKey'] = $account;
                $records = [];
                $sleepRecords = [];
                $raw = [];
                // Small chunks avoid silent provider truncation and keep responses bounded.
                for ($cursor = $from->modify('-1 day'); $cursor <= $until; $cursor = $end->modify('+1 day')) {
                    $end = min($cursor->modify('+6 days'), $until);
                    foreach ($this->client->band($credentials, $cursor->format('Y-m-d'), $end->format('Y-m-d')) as $row) {
                        if (!is_array($row)) {
                            ++$state['skipped'];
                            continue;
                        }
                        $id = $this->rawId($account, 'band', $row);
                        $raw[$id] = ['source' => 'zepp', 'kind' => 'band_detail', 'sourceDate' => $row['date_time'] ?? null, 'payload' => $row, 'decodedSummary' => $this->parser->summary($row)];
                        $record = $this->parser->band($row);
                        if (null === $record) {
                            ++$state['skipped'];
                            continue;
                        }
                        $sleep = $record['sleep'];
                        $record['sleep'] = null; // Sleep belongs to its local wake date, independently of activity/HR date.
                        if (null !== $sleep) {
                            $sleep['rawRecordIds'] = [$id];
                            $sleep['stages'] = $record['sleepStages'];
                            if (!isset($sleepRecords[$sleep['date']]) || ($sleep['totalMinutes'] ?? -1) > ($sleepRecords[$sleep['date']]['totalMinutes'] ?? -1)) {
                                $sleepRecords[$sleep['date']] = $sleep;
                            }
                        }
                        $record['sleepStages'] = [];
                        $record['rawRecordIds'] = [$id];
                        $record['fieldSources'] = [];
                        foreach (['activity', 'heartRate'] as $category) {
                            foreach ($record[$category] as $field => $value) {
                                if (null !== $value && 'sampleCount' !== $field) {
                                    $record['fieldSources'][$category.'.'.$field] = [$id];
                                }
                            }
                        }
                        foreach (['samples.heartRate' => $record['samples']['heartRate'], 'activityStages' => $record['activityStages']] as $field => $values) {
                            if ([] !== $values) {
                                $record['fieldSources'][$field] = [$id];
                            }
                        }
                        $date = $record['date'];
                        // Do not sum overlapping devices. Prefer richer observations, then fill only
                        // missing fields from other source rows and retain field-level attribution.
                        if (!isset($records[$date])) {
                            $records[$date] = $record;
                        } elseif ([$record['heartRate']['sampleCount'], $record['activity']['steps'] ?? -1] > [$records[$date]['heartRate']['sampleCount'], $records[$date]['activity']['steps'] ?? -1]) {
                            $records[$date] = $this->combineBands($record, $records[$date]);
                        } else {
                            $records[$date] = $this->combineBands($records[$date], $record);
                        }
                    }
                }
                $state['sources']['band'] = ['state' => 'success', 'message' => '睡眠、日活动和分钟心率已读取。'];
                foreach ($sleepRecords as $date => $sleep) {
                    $records[$date] ??= $this->parser->emptyDay($date);
                    $records[$date]['sleepStages'] = $sleep['stages'];
                    unset($sleep['stages']);
                    $records[$date]['sleep'] = $sleep;
                    $records[$date]['timeZone'] ??= $sleep['timeZone'];
                    if (null === $records[$date]['heartRate']['restingBpm'] && null !== $sleep['restingHeartRate']) {
                        $records[$date]['heartRate']['restingBpm'] = $sleep['restingHeartRate'];
                        $records[$date]['fieldSources']['heartRate.restingBpm'] = $sleep['rawRecordIds'];
                    }
                    $records[$date]['fieldSources']['sleep'] = $sleep['rawRecordIds'];
                    $records[$date]['fieldSources']['sleepStages'] = $sleep['rawRecordIds'];
                    $records[$date]['rawRecordIds'] = [...$records[$date]['rawRecordIds'], ...$sleep['rawRecordIds']];
                }
                foreach ([...ZeppDataClient::EVENTS, ...array_keys(ZeppDataClient::EXTENDED_EVENTS), ...ZeppDataClient::EXTRA_SOURCES] as $type) {
                    try {
                        $count = 0;
                        for ($cursor = $from; $cursor < $until; $cursor = $end) {
                            $end = min($cursor->modify('+7 days'), $until);
                            foreach ($this->client->events($credentials, $type, $cursor, $end, $timezone, $deadline) as $event) {
                                if (!is_array($event)) {
                                    continue;
                                }
                                $id = $this->rawId($account, $type, $event);
                                $raw[$id] = ['source' => 'zepp', 'kind' => $type, 'payload' => $event];
                                $datedEvent = $event;
                                // Watch statistics use dayId instead of an event timestamp.
                                if (in_array($type, ['sport_load', 'vo2_max'], true) && is_string($event['dayId'] ?? null) && ZeppDataParser::validDate($event['dayId'])) {
                                    $datedEvent['timestamp'] = new \DateTimeImmutable($event['dayId'], $zone)->getTimestamp() * 1000;
                                }
                                $this->applyEvent($records, $datedEvent, $type, $id, $zone);
                                ++$count;
                            }
                        }
                        $state['sources'][$type] = ['state' => 0 === $count ? 'no_data' : 'success', 'recordCount' => $count];
                    } catch (ZeppSyncError $error) {
                        $state['sources'][$type] = ['state' => $error->state, 'message' => $error->getMessage(), 'nextAction' => $error->nextAction];
                    }
                }
                try {
                    $healthProfile = $this->client->healthProfile($credentials);
                    if ([] !== $healthProfile) {
                        $id = $this->rawId($account, 'health_profile', $healthProfile);
                        $raw[$id] = ['source' => 'zepp', 'kind' => 'health_profile', 'payload' => $healthProfile];
                    }
                    $state['sources']['health_profile'] = ['state' => [] === $healthProfile ? 'no_data' : 'success', 'recordCount' => [] === $healthProfile ? 0 : 1];
                } catch (ZeppSyncError $error) {
                    $state['sources']['health_profile'] = ['state' => $error->state, 'message' => $error->getMessage(), 'nextAction' => $error->nextAction];
                }
                $this->repository->transactional(function () use ($raw, $records, $account, $now, $from, $today, $sleepClaim, &$state): void {
                    $newRaw = 0;
                    $sleepCounts = ['imported' => 0, 'updated' => 0, 'unchanged' => 0];
                    foreach ($raw as $id => $payload) {
                        if (null === $this->repository->find('zepp-raw', $id)) {
                            $this->repository->save('zepp-raw', $id, [...$payload, 'fetchedAt' => $now->format(DATE_ATOM)], 0);
                            ++$newRaw;
                        }
                    }
                    foreach ($records as $date => $record) {
                        if ($date < $from->format('Y-m-d') || $date > $today->format('Y-m-d')) {
                            continue;
                        }
                        $id = 'zepp-'.substr($account, 0, 16).'-'.$date;
                        $existing = $this->repository->find('zepp-day', $id);
                        $oldDetail = $this->repository->find('zepp-detail', $id);
                        $record['retainedFields'] = [];
                        $record['fieldSources'] ??= [];
                        // Missing readings do not erase an earlier valid measurement during partial sync.
                        if (null !== $existing) {
                            foreach (['sleep', 'activity', 'heartRate', 'oxygen', 'stress', 'body'] as $category) {
                                if (null === $record[$category] && null !== ($existing[$category] ?? null)) {
                                    $record[$category] = $existing[$category];
                                    $record['retainedFields'][] = $category;
                                    $record['fieldSources'][$category] = $existing['fieldSources'][$category] ?? $existing['rawRecordIds'];
                                } elseif (is_array($record[$category])) {
                                    foreach ($record[$category] as $field => $value) {
                                        if (null === $value && null !== ($existing[$category][$field] ?? null)) {
                                            $record[$category][$field] = $existing[$category][$field];
                                            $record['retainedFields'][] = $category.'.'.$field;
                                            $record['fieldSources'][$category.'.'.$field] = $existing['fieldSources'][$category.'.'.$field] ?? $existing['rawRecordIds'];
                                        }
                                    }
                                }
                                $previousSamples = $oldDetail['samples'][$category] ?? [];
                                if (isset($record['samples'][$category]) && [] === $record['samples'][$category] && [] !== $previousSamples) {
                                    $record['samples'][$category] = $previousSamples;
                                    $record[$category]['sampleCount'] = count($record['samples'][$category]);
                                    $record['retainedFields'][] = 'samples.'.$category;
                                    $record['fieldSources']['samples.'.$category] = $existing['fieldSources']['samples.'.$category] ?? $existing['rawRecordIds'];
                                }
                            }
                            foreach (['sleepStages', 'activityStages'] as $field) {
                                if ([] === $record[$field] && [] !== ($oldDetail[$field] ?? [])) {
                                    $record[$field] = $oldDetail[$field];
                                    $record['retainedFields'][] = $field;
                                    $record['fieldSources'][$field] = $existing['fieldSources'][$field] ?? $existing['rawRecordIds'];
                                }
                            }
                        }
                        $record['rawRecordIds'] = array_values(array_unique([...($existing['rawRecordIds'] ?? []), ...$record['rawRecordIds']]));
                        sort($record['rawRecordIds']);
                        $record['coverage'] = $this->parser->coverage($record);
                        $details = ['samples' => $record['samples'], 'sleepStages' => $record['sleepStages'], 'activityStages' => $record['activityStages']];
                        unset($record['samples'], $record['sleepStages'], $record['activityStages']);
                        $before = $existing;
                        unset($before['id'], $before['version'], $before['updatedAt'], $before['lastSyncedAt']);
                        if (json_encode($before, JSON_THROW_ON_ERROR) === json_encode($record, JSON_THROW_ON_ERROR)) {
                            ++$state['unchanged'];
                        } else {
                            $this->repository->save('zepp-day', $id, [...$record, 'lastSyncedAt' => $now->format(DATE_ATOM)], $existing['version'] ?? 0);
                            ++$state[null === $existing ? 'imported' : 'updated'];
                        }
                        $this->repository->save('zepp-detail', $id, $details, $oldDetail['version'] ?? 0);
                        if (null !== $record['sleep']) {
                            $sleep = $record['sleep'];
                            unset($sleep['rawRecordIds']);
                            $sleepId = 'zepp-'.substr($account, 0, 16).'-'.$sleep['sourceDate'];
                            $oldSleep = $this->repository->find('sleep', $sleepId);
                            $comparable = $oldSleep;
                            unset($comparable['id'], $comparable['version'], $comparable['updatedAt'], $comparable['lastSyncedAt']);
                            if ($comparable === $sleep) {
                                ++$sleepCounts['unchanged'];
                            } else {
                                $this->repository->save('sleep', $sleepId, [...$sleep, 'lastSyncedAt' => $now->format(DATE_ATOM)], $oldSleep['version'] ?? 0);
                                ++$sleepCounts[null === $oldSleep ? 'imported' : 'updated'];
                            }
                        }
                    }
                    $state['rawRecordCount'] = ($state['rawRecordCount'] ?? 0) + $newRaw;
                    $this->repository->save('sleep-sync', 'zepp', [...$sleepClaim, ...$sleepCounts, 'accountKey' => $account, 'state' => 0 === array_sum($sleepCounts) ? 'no_data' : 'success', 'lastAttemptAt' => $now->format(DATE_ATOM), 'lastSuccessAt' => $now->format(DATE_ATOM), 'lastError' => null, 'nextAction' => '睡眠已随 Zepp 详细数据同步保存。'], $sleepClaim['version']);
                    $partial = [] !== array_filter($state['sources'], static fn (array $source): bool => in_array($source['state'], ['auth_required', 'error'], true));
                    $state['state'] = $partial ? 'partial' : ([] === $records ? 'no_data' : 'success');
                    $state['lastSuccessAt'] = $now->format(DATE_ATOM);
                    $state['nextAction'] = $partial ? '日常数据已保存；部分扩展来源无法读取，详见各来源状态。缺失项不会记为零。' : '数据已保存，可供 AI 按日期读取。';
                    $state = $this->repository->save('zepp-sync', 'default', $state, $state['version']);
                });
            } catch (ZeppSyncError $error) {
                $this->repository->transactional(function () use ($state, $sleepClaim, $error): void {
                    $this->repository->save('zepp-sync', 'default', [...$state, 'state' => $error->state, 'lastError' => $error->getMessage(), 'nextAction' => $error->nextAction], $state['version']);
                    $this->repository->save('sleep-sync', 'zepp', [...$sleepClaim, 'state' => $error->state, 'lastError' => $error->getMessage(), 'nextAction' => $error->nextAction], $sleepClaim['version']);
                });
            }

            return $this->status();
        } finally {
            $lock->release();
        }
    }

    /** @param array<string,mixed> $row */
    private function rawId(string $account, string $type, array $row): string
    {
        return hash('sha256', $account.':'.$type.':'.json_encode($row, JSON_THROW_ON_ERROR));
    }

    /** @param array<string,mixed> $preferred
     * @param array<string,mixed> $other
     *
     * @return array<string,mixed>
     */
    private function combineBands(array $preferred, array $other): array
    {
        foreach (['activity', 'heartRate'] as $category) {
            foreach ($preferred[$category] as $field => $value) {
                if (null === $value && null !== ($other[$category][$field] ?? null)) {
                    $preferred[$category][$field] = $other[$category][$field];
                    $preferred['fieldSources'][$category.'.'.$field] = $other['fieldSources'][$category.'.'.$field] ?? $other['rawRecordIds'];
                }
            }
        }
        if ([] === $preferred['activityStages'] && [] !== $other['activityStages']) {
            $preferred['activityStages'] = $other['activityStages'];
            $preferred['fieldSources']['activityStages'] = $other['rawRecordIds'];
        }
        $preferred['rawRecordIds'] = array_values(array_unique([...$preferred['rawRecordIds'], ...$other['rawRecordIds']]));
        $preferred['sourceFields'] = array_values(array_unique([...$preferred['sourceFields'], ...$other['sourceFields']]));

        return $preferred;
    }

    /** @param array<string,array<string,mixed>> $records
     * @param array<string,mixed> $event
     */
    private function applyEvent(array &$records, array $event, string $type, string $rawId, \DateTimeZone $zone): void
    {
        $timestamp = ZeppDataParser::number($event['timestamp'] ?? null, 946684800000, 4102444800000);
        if (null === $timestamp) {
            return;
        }
        $at = new \DateTimeImmutable('@'.intdiv((int) $timestamp, 1000))->setTimezone($zone);
        $date = $at->format('Y-m-d');
        $records[$date] ??= $this->parser->emptyDay($date);
        $record = &$records[$date];
        $record['rawRecordIds'][] = $rawId;
        if ('all_day_stress' === $type) {
            $record['stress']['average'] = ZeppDataParser::number($event['avgStress'] ?? null, 0, 100);
            $record['stress']['max'] = ZeppDataParser::number($event['maxStress'] ?? null, 0, 100);
            foreach (['average', 'max'] as $field) {
                if (null !== $record['stress'][$field]) {
                    $record['fieldSources']['stress.'.$field] = [$rawId];
                }
            }
            $points = $event['data'] ?? [];
            if (is_string($points)) {
                $points = json_decode($points, true, 12);
            }
            foreach (is_array($points) ? $points : [] as $point) {
                if (!is_array($point)) {
                    continue;
                }
                $value = ZeppDataParser::number($point['value'] ?? null, 0, 100);
                $time = ZeppDataParser::number($point['time'] ?? null, 946684800000, 4102444800000);
                if (null !== $value && null !== $time) {
                    $record['samples']['stress'][] = ['at' => new \DateTimeImmutable('@'.intdiv((int) $time, 1000))->setTimezone($zone)->format(DATE_ATOM), 'value' => $value];
                }
            }
            $record['stress']['sampleCount'] = count($record['samples']['stress']);
            if ($record['stress']['sampleCount'] > 0) {
                $record['fieldSources']['samples.stress'][] = $rawId;
            }
        } elseif ('blood_oxygen' === $type) {
            $extra = $event['extra'] ?? [];
            if (is_string($extra)) {
                $extra = json_decode($extra, true, 12);
            }
            // ODI/OSA events are retained as source records, not spot SpO2 readings.
            $value = in_array($event['subType'] ?? 'click', ['click'], true) && is_array($extra)
                ? ZeppDataParser::number($extra['spo2'] ?? null, 1, 100) : null;
            if (null !== $value) {
                $record['samples']['oxygen'][] = ['at' => $at->format(DATE_ATOM), 'percent' => $value];
                $values = array_column($record['samples']['oxygen'], 'percent');
                $record['oxygen'] = ['averagePercent' => round(array_sum($values) / count($values), 1), 'minPercent' => min([$value, ...$values]), 'sampleCount' => count($values)];
                $record['fieldSources']['samples.oxygen'][] = $rawId;
                $record['fieldSources']['oxygen.averagePercent'] = $record['fieldSources']['samples.oxygen'];
                $record['fieldSources']['oxygen.minPercent'] = $record['fieldSources']['samples.oxygen'];
            }
        }
        // PAI, readiness, oxygen events/ODI and unknown fields remain losslessly accessible in rawRecordIds.
    }
}
