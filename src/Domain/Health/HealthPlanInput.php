<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;

final class HealthPlanInput
{
    /** @return array<string, mixed> */
    public static function defaults(): array
    {
        return ['title' => null, 'category' => 'general', 'goal' => null, 'startDate' => null, 'endDate' => null, 'status' => 'draft', 'items' => [], 'source' => 'user', 'sourceLabel' => '', 'rationale' => '', 'notes' => ''];
    }

    /** @param array<string, mixed> $data
     * @return array<string, mixed>
     */
    public static function validate(array $data): array
    {
        HealthContextInput::knownFields($data, array_keys(self::defaults()), 'plan');
        $data = array_replace(self::defaults(), $data);
        self::text($data['title'], 'title', 200);
        self::text($data['goal'], 'goal', 2000);
        foreach (['sourceLabel' => 200, 'rationale' => 10000, 'notes' => 4000] as $field => $limit) {
            self::text($data[$field], $field, $limit, true);
        }
        foreach (['category' => ['weight', 'sleep', 'nutrition', 'recovery', 'general'], 'status' => ['draft', 'active', 'paused', 'completed', 'cancelled'], 'source' => ['user', 'ai', 'clinician']] as $field => $allowed) {
            if (!in_array($data[$field], $allowed, true)) {
                throw new TrainingError($field.' 必须为 '.implode('、', $allowed).'。');
            }
        }
        if ('ai' === $data['source']) {
            self::text($data['rationale'], 'AI 计划的 rationale', 10000);
        }
        TrainingInput::date($data['startDate']);
        if (null !== $data['endDate']) {
            TrainingInput::date($data['endDate']);
            if ($data['endDate'] < $data['startDate']) {
                throw new TrainingError('endDate 不能早于 startDate。');
            }
        }
        if (!is_array($data['items']) || !array_is_list($data['items']) || count($data['items']) > 200) {
            throw new TrainingError('items 必须为最多 200 条的列表。');
        }
        $ids = [];
        foreach ($data['items'] as &$item) {
            if (!is_array($item) || array_is_list($item)) {
                throw new TrainingError('items 必须包含对象。');
            }
            HealthContextInput::knownFields($item, ['id', 'title', 'date', 'time', 'status', 'notes'], 'items');
            $item = array_replace(['date' => null, 'time' => null, 'status' => 'pending', 'notes' => ''], $item);
            $id = TrainingInput::id($item['id'] ?? null);
            if (isset($ids[$id])) {
                throw new TrainingError('items 不能包含重复 id。');
            }
            $ids[$id] = true;
            self::text($item['title'] ?? null, 'items.title', 500);
            self::text($item['notes'], 'items.notes', 2000, true);
            if (!in_array($item['status'], ['pending', 'completed', 'skipped'], true)) {
                throw new TrainingError('items.status 必须为 pending、completed、skipped。');
            }
            if (null !== $item['date']) {
                TrainingInput::date($item['date']);
                if ($item['date'] < $data['startDate'] || (null !== $data['endDate'] && $item['date'] > $data['endDate'])) {
                    throw new TrainingError('items.date 必须在计划日期范围内。');
                }
            }
            if (null !== $item['time'] && (!is_string($item['time']) || !preg_match('/^([01]\d|2[0-3]):[0-5]\d$/D', $item['time']))) {
                throw new TrainingError('items.time 必须为 HH:mm 或 null。');
            }
        }
        unset($item);

        return $data;
    }

    private static function text(mixed $value, string $field, int $limit, bool $allowEmpty = false): void
    {
        if (!is_string($value) || (!$allowEmpty && '' === trim($value)) || mb_strlen($value) > $limit) {
            throw new TrainingError($field.' 必须为'.($allowEmpty ? '' : '非空').'文本，最多 '.$limit.' 字符。');
        }
    }
}
