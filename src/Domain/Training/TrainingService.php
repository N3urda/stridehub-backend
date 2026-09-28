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
            if (null !== $old && $old['version'] !== $version) {
                throw new TrainingError('记录已被修改，请重新读取最新版本。', 409);
            }
            if (null === $old && $version > 0) {
                throw new TrainingError('记录不存在。', 404);
            }
            $payload = TrainingInput::validate($kind, array_replace($old ?? [], $data));
            if ('sessions' === $kind) {
                if (null !== $payload['raceId'] && null === $this->repository->find('races', $payload['raceId'])) {
                    throw new TrainingError('关联的比赛不存在。');
                }
                if (null !== $payload['activityId']) {
                    if (null === $this->activities->find($payload['activityId'])) {
                        throw new TrainingError('关联运动不存在或不是跑步记录。');
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
                $ids[] = $id;
                $result[] = $this->write('sessions', $id, $item, $version);
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
        $actual = null !== $session['activityId'] ? $this->activities->find($session['activityId']) : null;
        $linked = array_column($this->repository->list('sessions'), 'activityId');
        $candidates = array_values(array_filter($this->activities->near(new \DateTimeImmutable($session['startAt'])), static fn (array $activity): bool => !in_array($activity['id'], $linked, true)));
        $delta = null;
        $suggestion = '导入运动后可关联记录，对照计划与实际；不会把未测量的数据当作完成。';
        if (null !== $actual) {
            $delta = ['distanceKm' => null === $session['distanceKm'] ? null : round($actual['distanceKm'] - $session['distanceKm'], 3), 'durationMinutes' => round($actual['durationMinutes'] - $session['durationMinutes'], 2)];
            $suggestion = $actual['durationMinutes'] > $session['durationMinutes'] * 1.2 ? '实际时长明显超过计划；结合跑后体感检查下一次训练安排。' : '训练已关联。结合计划差异和跑后体感决定是否调整后续课表。';
        } elseif ('planned' === $session['status'] && strtotime($session['startAt']) + $session['durationMinutes'] * 60 < $this->clock->getCurrentDateTimeImmutable()->getTimestamp()) {
            $suggestion = '这节课的计划时间已过。先检查是否有未导入的运动；如确实漏训，可改期或标记跳过，避免机械补齐所有跑量。';
        }

        return ['planned' => $session, 'actual' => $actual, 'delta' => $delta, 'candidates' => $candidates, 'suggestion' => $suggestion,
            'dataQuality' => null === $actual ? ['尚未关联实际运动，不能判断完成质量。'] : (null === $actual['averageHeartRate'] ? ['运动缺少心率，不估算强度达标或生理恢复。'] : ['配速、时长与平均心率来自已导入运动；不包含睡眠或 HRV 恢复评分。'])];
    }
}
