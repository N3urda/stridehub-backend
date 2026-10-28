<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;

/** Structured observations and targets; no nutritional or medical targets are inferred. */
final class HealthDataInput
{
    public const array NUTRIENTS = ['energyKcal', 'proteinGrams', 'carbohydrateGrams', 'fatGrams'];

    /** @return array<string, mixed> */
    public static function defaults(string $resource): array
    {
        $common = ['notes' => '', 'source' => 'user', 'sourceLabel' => '', 'rationale' => ''];

        return match ($resource) {
            'meal-plans' => [...$common, 'date' => null, 'status' => 'active', 'targets' => self::emptyNutrients(), 'meals' => []],
            'meals' => [...$common, 'date' => null, 'mealType' => 'other', 'foods' => '', 'portion' => '', 'nutrients' => self::emptyNutrients(), 'nutritionSource' => 'unknown', 'planId' => null],
            'weight-goals' => [...$common, 'title' => '', 'startDate' => null, 'endDate' => null, 'startWeightKg' => null, 'targetWeightKg' => null, 'status' => 'active', 'milestones' => []],
            'measurements' => [...$common, 'date' => null, 'waistCm' => null, 'bodyFatPercent' => null],
            default => throw new TrainingError('记录类型不存在。', 404),
        };
    }

    /** @return array<string, null> */
    public static function emptyNutrients(): array
    {
        return array_fill_keys(self::NUTRIENTS, null);
    }

    /** @param array<string, mixed> $data
     * @return array<string, mixed>
     */
    public static function validate(string $resource, array $data): array
    {
        HealthContextInput::knownFields($data, array_keys(self::defaults($resource)), $resource);
        $data = array_replace(self::defaults($resource), $data);
        foreach (['notes' => 2000, 'sourceLabel' => 200, 'rationale' => 4000] as $key => $limit) {
            self::text($data[$key], $key, $limit);
        }
        self::choice($data['source'], ['user', 'ai', 'import'], 'source');
        if ('ai' === $data['source'] && '' === trim($data['rationale'])) {
            throw new TrainingError('AI 写入必须提供 rationale。');
        }
        if ('weight-goals' === $resource) {
            self::text($data['title'], 'title', 200, true);
            TrainingInput::date($data['startDate']);
            TrainingInput::date($data['endDate']);
            if ($data['endDate'] <= $data['startDate']) {
                throw new TrainingError('目标结束日期必须晚于开始日期。');
            }
            self::number($data['startWeightKg'], 'startWeightKg', 0.1, 1000, false);
            self::number($data['targetWeightKg'], 'targetWeightKg', 0.1, 1000, false);
            if ($data['targetWeightKg'] >= $data['startWeightKg']) {
                throw new TrainingError('减重目标必须低于起始体重。');
            }
            self::choice($data['status'], ['draft', 'active', 'paused', 'completed', 'cancelled'], 'status');
            self::items($data['milestones'], 30);
            $dates = [];
            foreach ($data['milestones'] as $milestone) {
                self::object($milestone, ['date', 'weightKg'], 'milestones');
                $date = TrainingInput::date($milestone['date'] ?? null);
                self::number($milestone['weightKg'] ?? null, 'milestones.weightKg', 0.1, 1000, false);
                if ($date < $data['startDate'] || $date > $data['endDate'] || isset($dates[$date])) {
                    throw new TrainingError('阶段目标日期必须唯一且位于目标期间内。');
                }
                $dates[$date] = true;
            }
            usort($data['milestones'], static fn (array $a, array $b): int => strcmp($a['date'], $b['date']));
        } else {
            TrainingInput::date($data['date']);
        }
        if ('meal-plans' === $resource) {
            self::choice($data['status'], ['draft', 'active', 'cancelled'], 'status');
            $data['targets'] = self::nutrients($data['targets']);
            self::items($data['meals'], 20);
            $ids = [];
            foreach ($data['meals'] as &$meal) {
                self::object($meal, ['id', 'mealType', 'foods', 'portion'], 'meals');
                $meal = array_replace(['mealType' => 'other', 'portion' => ''], $meal);
                $id = TrainingInput::id($meal['id'] ?? null);
                if (isset($ids[$id])) {
                    throw new TrainingError('餐次 ID 不能重复。');
                }
                $ids[$id] = true;
                self::meal($meal);
            }
            unset($meal);
        }
        if ('meals' === $resource) {
            self::meal($data);
            $data['nutrients'] = self::nutrients($data['nutrients']);
            self::choice($data['nutritionSource'], ['unknown', 'label', 'database', 'estimate'], 'nutritionSource');
            if ('unknown' === $data['nutritionSource'] && array_any($data['nutrients'], static fn (mixed $v): bool => null !== $v)) {
                throw new TrainingError('填写营养数值时必须标明 label、database 或 estimate 来源。');
            }
            if (null !== $data['planId']) {
                TrainingInput::id($data['planId']);
            }
        }
        if ('measurements' === $resource) {
            self::number($data['waistCm'], 'waistCm', 1, 400);
            self::number($data['bodyFatPercent'], 'bodyFatPercent', 0, 100);
            if (null === $data['waistCm'] && null === $data['bodyFatPercent']) {
                throw new TrainingError('至少填写一项实际测量。');
            }
            if ('ai' === $data['source']) {
                throw new TrainingError('身体测量来源应为 user 或 import，不能是 AI 推断。');
            }
        }

        return $data;
    }

    /** @return array<string, int|float|null> */
    public static function nutrients(mixed $value): array
    {
        self::object($value, self::NUTRIENTS, 'nutrients');
        $value = array_replace(self::emptyNutrients(), $value);
        foreach ($value as $key => $number) {
            self::number($number, $key, 0, 'energyKcal' === $key ? 30000 : 5000);
        }

        return $value;
    }

    /** @param array<string, mixed> $data */
    private static function meal(array $data): void
    {
        self::choice($data['mealType'] ?? null, ['breakfast', 'lunch', 'dinner', 'snack', 'fuel', 'other'], 'mealType');
        self::text($data['foods'] ?? null, 'foods', 2000, true);
        self::text($data['portion'] ?? null, 'portion', 500);
    }

    /** @param list<string> $fields */
    private static function object(mixed $value, array $fields, string $name): void
    {
        if (!is_array($value) || ([] !== $value && array_is_list($value))) {
            throw new TrainingError($name.' 必须为对象。');
        }
        HealthContextInput::knownFields($value, $fields, $name);
    }

    private static function items(mixed $value, int $limit): void
    {
        if (!is_array($value) || !array_is_list($value) || count($value) > $limit) {
            throw new TrainingError('条目必须是最多 '.$limit.' 项的列表。');
        }
    }

    /** @param list<string> $choices */
    private static function choice(mixed $value, array $choices, string $name): void
    {
        if (!in_array($value, $choices, true)) {
            throw new TrainingError($name.' 必须为 '.implode('、', $choices).'。');
        }
    }

    private static function text(mixed $value, string $name, int $max, bool $required = false): void
    {
        if (!is_string($value) || mb_strlen($value) > $max || ($required && '' === trim($value))) {
            throw new TrainingError($name.' 必须是'.($required ? '非空' : '').'文本，最多 '.$max.' 字符。');
        }
    }

    private static function number(mixed $value, string $name, float $min, float $max, bool $nullable = true): void
    {
        if (null === $value && $nullable) {
            return;
        }
        if ((!is_float($value) && !is_int($value)) || !is_finite((float) $value) || $value < $min || $value > $max) {
            throw new TrainingError($name.' 必须为 '.$min.'–'.$max.' 的数字。');
        }
    }
}
