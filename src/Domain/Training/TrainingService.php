<?php

declare(strict_types=1);

namespace App\Domain\Training;

use App\Infrastructure\Time\Clock\Clock;
use Ramsey\Uuid\Uuid;

final readonly class TrainingService
{
    public function __construct(private TrainingRepository $repository, private TrainingActivities $activities, private Clock $clock)
    {
    }

    /** @return array<string, mixed> */
    public function profile(): array
    {
        return $this->repository->find('profile', 'default') ?? [...TrainingInput::defaults('profile'), 'id' => 'default', 'version' => 0, 'updatedAt' => null];
    }

    /** @return array<string, mixed> */
    public function get(string $kind, string $id): array
    {
        TrainingInput::defaults($kind);

        return $this->repository->find($kind, TrainingInput::id($id)) ?? throw new TrainingError('记录不存在。', 404);
    }

    /** @return list<array<string, mixed>> */
    public function list(string $kind, ?string $from = null, ?string $to = null, ?string $status = null): array
    {
        TrainingInput::defaults($kind);
        if (null !== $from) {
            TrainingInput::date($from);
        }
        if (null !== $to) {
            TrainingInput::date($to);
        }
        if (null !== $from && null !== $to && $from > $to) {
            throw new TrainingError('from 不能晚于 to。');
        }
        if (null !== $status && !in_array($status, ['planned', 'completed', 'skipped', 'cancelled'], true)) {
            throw new TrainingError('status 无效。');
        }
        $timezone = new \DateTimeZone($this->profile()['timezone']);
        $items = array_values(array_filter($this->repository->list($kind), static function (array $row) use ($from, $to, $status, $timezone): bool {
            $date = isset($row['startAt']) ? new \DateTimeImmutable($row['startAt'])->setTimezone($timezone)->format('Y-m-d') : ($row['date'] ?? '');

            return (null === $from || $date >= $from) && (null === $to || $date <= $to) && (null === $status || ($row['status'] ?? null) === $status);
        }));
        usort($items, static fn (array $a, array $b): int => isset($a['startAt']) ? strtotime($a['startAt']) <=> strtotime($b['startAt']) : ($a['date'] ?? '') <=> ($b['date'] ?? ''));

        return $items;
    }

    /** @param array<string, mixed> $data
     * @return array<string, mixed>
     */
    public function create(string $kind, array $data): array
    {
        if (isset($data['version']) && 0 !== $data['version']) {
            throw new TrainingError('创建记录的 version 只能为 0。');
        }
        $id = TrainingInput::id($data['id'] ?? ('check-ins' === $kind ? TrainingInput::date($data['date'] ?? null) : Uuid::uuid4()->toString()));

        return $this->write($kind, $id, $data, 0);
    }

    /** @param array<string, mixed> $data
     * @return array<string, mixed>
     */
    public function update(string $kind, string $id, array $data): array
    {
        $version = TrainingInput::version($data['version'] ?? null);

        return $this->write($kind, TrainingInput::id($id), $data, $version);
    }

    /** @param array<string, mixed> $data
     * @return array<string, mixed>
     */
    private function write(string $kind, string $id, array $data, int $version): array
    {
        return $this->repository->transactional(function () use ($kind, $id, $data, $version): array {
            $old = $this->repository->find($kind, $id);
            $validateLinks = null === $old || array_key_exists('activityIds', $data) || array_key_exists('activityId', $data);
            if (null !== $old && $old['version'] !== $version) {
                throw new TrainingError('记录已被修改，请重新读取最新版本。', 409);
            }
            if (null === $old && $version > 0) {
                throw new TrainingError('记录不存在。', 404);
            }
            if ('sessions' === $kind) {
                $data = $this->sessionLinkPatch($data);
            }
            $payload = TrainingInput::validate($kind, array_replace($old ?? [], $data));
            if ('sessions' === $kind) {
                if (null !== $payload['raceId'] && null === $this->repository->find('races', $payload['raceId'])) {
                    throw new TrainingError('关联的比赛不存在。');
                }
                if ([] !== $payload['activityIds']) {
                    if ($validateLinks) {
                        foreach ($payload['activityIds'] as $activityId) {
                            if (null === $this->activities->find($activityId)) {
                                throw new TrainingError('关联运动不存在、已删除或不是跑步记录：'.$activityId);
                            }
                        }
                    }
                    if ('rest' === $payload['type']) {
                        throw new TrainingError('休息日不能关联跑步记录。');
                    }
                    $payload['status'] = 'completed';
                }
            }
            if ('fuel-logs' === $kind && null !== $payload['sessionId'] && null === $this->repository->find('sessions', $payload['sessionId'])) {
                throw new TrainingError('补给记录关联的课次不存在。');
            }

            return $this->repository->save($kind, $id, $payload, $version);
        });
    }

    /** @param array<mixed> $items
     * @return list<array<string, mixed>>
     */
    public function batch(array $items): array
    {
        if (!array_is_list($items) || count($items) < 1 || count($items) > 100) {
            throw new TrainingError('sessions 必须包含 1–100 节训练。');
        }

        return $this->repository->transactional(function () use ($items): array {
            $result = [];
            $ids = [];
            foreach ($items as $item) {
                if (!is_array($item) || array_is_list($item)) {
                    throw new TrainingError('每节训练必须为对象。');
                }
                $id = TrainingInput::id($item['id'] ?? null);
                $version = TrainingInput::version($item['version'] ?? null);
                if (in_array($id, $ids, true)) {
                    throw new TrainingError('批量请求包含重复 ID。');
                }
                $old = $this->repository->find('sessions', $id);
                if (null !== $old && $old['version'] !== $version) {
                    throw new TrainingError('记录已被修改，请重新读取最新版本。', 409);
                }
                if (null === $old && $version > 0) {
                    throw new TrainingError('记录不存在。', 404);
                }
                $ids[] = $id;
            }
            // Release only this batch's claims, then validate the complete new set.
            // Any collision or validation error rolls back both records and claims.
            $this->repository->releaseSessionActivityClaims($ids);
            foreach ($items as $item) {
                $result[] = $this->write('sessions', $item['id'], $item, $item['version']);
            }

            return $result;
        });
    }

    public function delete(string $kind, string $id, int $version): void
    {
        $this->repository->transactional(function () use ($kind, $id, $version): void {
            $this->get($kind, $id);
            foreach (['races' => ['sessions', 'raceId'], 'sessions' => ['fuel-logs', 'sessionId']] as $parent => [$child, $field]) {
                if ($kind === $parent && array_any($this->repository->list($child), static fn (array $record): bool => ($record[$field] ?? null) === $id)) {
                    throw new TrainingError('此记录仍有关联数据，请先解除关联。', 409);
                }
            }
            $this->repository->delete($kind, $id, $version);
        });
    }

    /** @return array<string, mixed> */
    public function comparison(string $id): array
    {
        $session = $this->get('sessions', $id);
        $actualActivities = [];
        $quality = [];
        $missing = false;
        foreach ($session['activityIds'] as $activityId) {
            $activity = $this->activities->find($activityId);
            if (null === $activity) {
                $missing = true;
                $quality[] = '关联记录不存在、已删除或不再是跑步：'.$activityId.'；实际汇总不完整，需核对关联。';
            } else {
                $actualActivities[] = $activity;
            }
        }
        $actual = $this->aggregate($actualActivities);
        $overlap = $this->hasOverlap($actualActivities);
        if ($overlap) {
            $quality[] = '关联记录的时间存在重叠，可能包含重复记录；当前为原始记录相加，未推断去重结果，暂不计算计划差值。';
        }
        if (count($actualActivities) > 1) {
            $quality[] = '多个文件只相加已记录的运动时长；文件之间的空档不计入运动。汇总不能证明间歇或分段目标达标。';
            if (array_any($actualActivities, static fn (array $activity): bool => null === $activity['elapsedSeconds'])) {
                $quality[] = '部分记录缺少总经过时长，无法完整检查录制时间重叠。';
            }
        }
        if ([] === $actualActivities) {
            $quality[] = '尚无可用的关联实际运动，不能判断完成质量。';
        } elseif (array_any($actualActivities, static fn (array $activity): bool => null === $activity['averageHeartRate'])) {
            $quality[] = '部分或全部记录缺少心率；平均心率仅按有测量值的记录及其运动时长加权，不能代表缺失部分。';
        }
        $quality[] = '距离、时长与平均心率来自已导入记录；不估算强度达标、睡眠、HRV 或生理恢复评分。';
        $linked = array_merge([], ...array_column($this->repository->list('sessions'), 'activityIds'));
        $candidates = array_values(array_filter($this->activities->near(new \DateTimeImmutable($session['startAt']), new \DateTimeZone($this->profile()['timezone'])), static fn (array $activity): bool => !in_array($activity['id'], $linked, true)));
        $delta = null;
        $suggestion = '导入运动后可关联记录，对照计划与实际；不会把未测量的数据当作完成。';
        if ($missing || $overlap) {
            $suggestion = '关联记录不完整或时间重叠，请先核对记录；当前不以部分或重复汇总判断训练完成质量。';
        } elseif (null !== $actual) {
            $delta = ['distanceKm' => null === $session['distanceKm'] ? null : round($actual['distanceKm'] - $session['distanceKm'], 3), 'durationMinutes' => round($actual['durationMinutes'] - $session['durationMinutes'], 2)];
            $suggestion = $actual['durationMinutes'] > $session['durationMinutes'] * 1.2 ? '实际时长明显超过计划；结合跑后体感检查下一次训练安排。' : '训练已关联。结合计划差异和跑后体感决定是否调整后续课表。';
        } elseif ('planned' === $session['status'] && strtotime($session['startAt']) + $session['durationMinutes'] * 60 < $this->clock->getCurrentDateTimeImmutable()->getTimestamp()) {
            $suggestion = '这节课的计划时间已过。先检查是否有未导入的运动；如确实漏训，可改期或标记跳过，避免机械补齐所有跑量。';
        }

        if ([] !== $candidates && null === $actual && !$missing) {
            $suggestion .= ' 找到时间相关的候选记录；请确认实际完成的文件，系统不会自动选择或标为完成。';
        }

        return ['planned' => $session, 'actual' => $actual, 'actualActivities' => $actualActivities, 'delta' => $delta, 'candidates' => $candidates, 'suggestion' => $suggestion, 'dataQuality' => $quality];
    }

    /** @return array{items: list<array<string, mixed>>} */
    public function reconciliation(?string $from = null, ?string $to = null): array
    {
        $zone = new \DateTimeZone($this->profile()['timezone']);
        $now = $this->clock->getCurrentDateTimeImmutable();
        $to ??= $now->setTimezone($zone)->format('Y-m-d');
        TrainingInput::date($to);
        $from ??= new \DateTimeImmutable($to, $zone)->modify('-7 days')->format('Y-m-d');
        $items = [];
        foreach ($this->list('sessions', $from, $to) as $session) {
            if ('rest' === $session['type'] || !in_array($session['status'], ['planned', 'completed'], true)) {
                continue;
            }
            if ('planned' === $session['status'] && strtotime($session['startAt']) + $session['durationMinutes'] * 60 > $now->getTimestamp()) {
                continue;
            }
            if ([] !== $session['activityIds'] && array_all($session['activityIds'], fn (string $id): bool => null !== $this->activities->find($id))) {
                continue;
            }
            $items[] = $this->comparison($session['id']);
        }

        return ['items' => $items];
    }

    /** @param array<string, mixed> $patch
     * @return array<string, mixed>
     */
    private function sessionLinkPatch(array $patch): array
    {
        if (array_key_exists('activityIds', $patch)) {
            if (!is_array($patch['activityIds']) || !array_is_list($patch['activityIds'])) {
                throw new TrainingError('activityIds 必须为列表。');
            }
            $first = $patch['activityIds'][0] ?? null;
            if (array_key_exists('activityId', $patch) && $patch['activityId'] !== $first) {
                throw new TrainingError('activityId 必须等于 activityIds 的第一项，空列表对应 null。');
            }
            $patch['activityId'] = $first;
        } elseif (array_key_exists('activityId', $patch)) {
            $patch['activityIds'] = null === $patch['activityId'] ? [] : [$patch['activityId']];
        }

        return $patch;
    }

    /** @param list<array<string, mixed>> $activities
     * @return array<string, mixed>|null
     */
    private function aggregate(array $activities): ?array
    {
        if ([] === $activities) {
            return null;
        }
        $seconds = $distance = $weightedHeartRate = $heartRateSeconds = 0;
        $elapsed = 0;
        $earliest = $activities[0]['startAt'];
        foreach ($activities as $activity) {
            $seconds += $activity['movingSeconds'];
            $distance += $activity['distanceKm'];
            if (null !== $activity['averageHeartRate'] && $activity['movingSeconds'] > 0) {
                $weightedHeartRate += $activity['averageHeartRate'] * $activity['movingSeconds'];
                $heartRateSeconds += $activity['movingSeconds'];
            }
            $elapsed = null === $elapsed || null === $activity['elapsedSeconds'] ? null : $elapsed + $activity['elapsedSeconds'];
            if (strtotime($activity['startAt']) < strtotime($earliest)) {
                $earliest = $activity['startAt'];
            }
        }

        return ['id' => $activities[0]['id'], 'activityIds' => array_column($activities, 'id'), 'name' => 1 === count($activities) ? $activities[0]['name'] : '合计 '.count($activities).' 条跑步记录', 'startAt' => $earliest, 'distanceKm' => round($distance, 3), 'durationMinutes' => round($seconds / 60, 2), 'movingSeconds' => $seconds, 'elapsedSeconds' => $elapsed, 'averageHeartRate' => $heartRateSeconds > 0 ? round($weightedHeartRate / $heartRateSeconds, 1) : null, 'aggregated' => count($activities) > 1];
    }

    /** @param list<array<string, mixed>> $activities */
    private function hasOverlap(array $activities): bool
    {
        usort($activities, static fn (array $a, array $b): int => strtotime($a['startAt']) <=> strtotime($b['startAt']));
        $lastEnd = null;
        foreach ($activities as $activity) {
            $start = strtotime($activity['startAt']);
            if (null !== $lastEnd && $start < $lastEnd) {
                return true;
            }
            // Movement is a lower bound when elapsed recording duration is unavailable.
            $lastEnd = $start + ($activity['elapsedSeconds'] ?? $activity['movingSeconds']);
        }

        return false;
    }
}
