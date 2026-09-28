<?php

declare(strict_types=1);

namespace App\Domain\Training;

final class TrainingInput
{
    /** @return array<string, mixed> */
    public static function defaults(string $kind): array
    {
        return match ($kind) {
            'profile' => ['timezone' => 'Asia/Shanghai', 'location' => null, 'thermalPreference' => 'neutral', 'usualStartTime' => '06:30', 'usualDurationMinutes' => 60, 'runningDays' => [], 'notificationsEnabled' => false, 'eveningReminderTime' => '20:00', 'preRunReminderMinutes' => 60],
            'sessions' => ['distanceKm' => null, 'status' => 'planned', 'raceId' => null, 'location' => null, 'steps' => [], 'fuelPlan' => [], 'notes' => '', 'activityId' => null, 'feedback' => null],
            'races' => ['targetTimeMinutes' => null, 'notes' => ''],
            'check-ins' => ['sleepHours' => null, 'pain' => false, 'notes' => ''],
            'fuel-logs' => ['sessionId' => null, 'carbsGrams' => 0, 'fluidMl' => 0, 'giComfort' => 'good', 'notes' => ''],
            default => throw new TrainingError('未知训练资源。', 404),
        };
    }

    public static function id(mixed $id): string
    {
        if (!is_string($id) || !preg_match('/^[a-zA-Z0-9_-]{1,80}$/D', $id)) {
            throw new TrainingError('id 必须为 1–80 位字母、数字、下划线或短横线。');
        }

        return $id;
    }

    public static function version(mixed $version): int
    {
        if (null === $version) {
            throw new TrainingError('修改需要提供当前 version。', 428);
        }
        if (!is_int($version) || $version < 0) {
            throw new TrainingError('version 必须为非负整数。');
        }

        return $version;
    }

    public static function date(mixed $date): string
    {
        if (!is_string($date) || !preg_match('/^(\d{4})-(\d{2})-(\d{2})$/D', $date, $parts) || !checkdate((int) $parts[2], (int) $parts[3], (int) $parts[1])) {
            throw new TrainingError('日期必须是有效的 YYYY-MM-DD。');
        }

        return $date;
    }

    /** @param array<string, mixed> $data
     * @return array<string, mixed>
     */
    public static function validate(string $kind, array $data): array
    {
        unset($data['id'], $data['version'], $data['updatedAt']);
        $data = array_replace(self::defaults($kind), $data);
        $fields = match ($kind) {
            'profile' => array_keys(self::defaults($kind)),
            'sessions' => [...array_keys(self::defaults($kind)), 'title', 'startAt', 'durationMinutes', 'type'],
            'races' => [...array_keys(self::defaults($kind)), 'name', 'date', 'distanceKm'],
            'check-ins' => [...array_keys(self::defaults($kind)), 'date', 'fatigue', 'soreness'],
            'fuel-logs' => [...array_keys(self::defaults($kind)), 'date', 'minute', 'item'],
            default => throw new TrainingError('未知训练资源。', 404),
        };
        if ($unknown = array_diff(array_keys($data), $fields)) {
            throw new TrainingError('未知字段：'.implode(', ', $unknown));
        }
        if (array_key_exists('notes', $data)) {
            self::text($data['notes'], 'notes', 4000, true);
        }
        if ('profile' === $kind) {
            self::text($data['timezone'], 'timezone', 80);
            if (!in_array($data['timezone'], \DateTimeZone::listIdentifiers(), true) && 'UTC' !== $data['timezone']) {
                throw new TrainingError('timezone 必须是有效的 IANA 时区。');
            }
            self::location($data['location']);
            self::choice($data['thermalPreference'], ['cold', 'neutral', 'warm'], 'thermalPreference');
            foreach (['usualStartTime', 'eveningReminderTime'] as $key) {
                if (!is_string($data[$key]) || !preg_match('/^([01]\d|2[0-3]):[0-5]\d$/D', $data[$key])) {
                    throw new TrainingError($key.' 必须为 HH:mm。');
                }
            }
            self::number($data['usualDurationMinutes'], 'usualDurationMinutes', 1, 1440, true);
            self::number($data['preRunReminderMinutes'], 'preRunReminderMinutes', 5, 240, true);
            if (!is_bool($data['notificationsEnabled'])) {
                throw new TrainingError('notificationsEnabled 必须为布尔值。');
            }
            self::items($data['runningDays'], 'runningDays', 7);
            foreach ($data['runningDays'] as $day) {
                self::number($day, 'runningDays', 1, 7, true);
            }
            $data['runningDays'] = array_values(array_unique($data['runningDays']));
        }
        if ('sessions' === $kind) {
            self::text($data['title'] ?? null, 'title', 200);
            $start = $data['startAt'] ?? null;
            if (!is_string($start) || !preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/D', $start)) {
                throw new TrainingError('startAt 必须包含时区，例如 2026-10-04T06:30:00+08:00。');
            }
            try {
                $date = new \DateTimeImmutable($start);
                if (false !== \DateTimeImmutable::getLastErrors() || $date->format('Y-m-d\TH:i:s') !== substr($start, 0, 19)) {
                    throw new \InvalidArgumentException();
                }
            } catch (\Exception) {
                throw new TrainingError('startAt 不是有效日期时间。');
            }
            $data['startAt'] = $date->format(DATE_ATOM);
            self::number($data['durationMinutes'] ?? null, 'durationMinutes', 1, 1440, true);
            if (null !== $data['distanceKm']) {
                self::number($data['distanceKm'], 'distanceKm', 0, 1000);
            }
            self::choice($data['type'] ?? null, ['easy', 'long', 'tempo', 'interval', 'recovery', 'race', 'rest'], 'type');
            self::choice($data['status'], ['planned', 'completed', 'skipped', 'cancelled'], 'status');
            self::location($data['location']);
            foreach (['steps', 'fuelPlan'] as $key) {
                self::items($data[$key], $key, 100);
                foreach ($data[$key] as $item) {
                    if (!is_array($item) || array_is_list($item)) {
                        throw new TrainingError($key.' 必须包含对象。');
                    }
                    if ('steps' === $key) {
                        self::text($item['kind'] ?? null, 'steps.kind', 80);
                        self::number($item['minutes'] ?? null, 'steps.minutes', 0.1, 1440);
                        if (array_key_exists('distanceKm', $item)) {
                            self::number($item['distanceKm'], 'steps.distanceKm', 0, 1000);
                        }
                        if (array_key_exists('target', $item)) {
                            self::text($item['target'], 'steps.target', 200, true);
                        }
                    } else {
                        self::text($item['item'] ?? null, 'fuelPlan.item', 200);
                        self::number($item['minute'] ?? null, 'fuelPlan.minute', 0, $data['durationMinutes']);
                        foreach (['carbsGrams' => 1000, 'fluidMl' => 10000] as $field => $max) {
                            if (array_key_exists($field, $item)) {
                                self::number($item[$field], $field, 0, $max);
                            }
                        }
                    }
                    $allowed = 'steps' === $key ? ['kind', 'minutes', 'distanceKm', 'target'] : ['minute', 'item', 'carbsGrams', 'fluidMl'];
                    if (array_diff(array_keys($item), $allowed)) {
                        throw new TrainingError($key.' 包含未知字段。');
                    }
                }
            }
            if (null !== $data['feedback']) {
                if (!is_array($data['feedback']) || (array_is_list($data['feedback']) && [] !== $data['feedback']) || array_diff(array_keys($data['feedback']), ['rpe', 'thermalFeeling', 'notes'])) {
                    throw new TrainingError('feedback 格式无效。');
                }
                foreach ($data['feedback'] as $key => $value) {
                    match ($key) {
                        'rpe' => self::number($value, 'rpe', 1, 10, true),
                        'thermalFeeling' => self::choice($value, ['cold', 'comfortable', 'hot'], 'thermalFeeling'),
                        'notes' => self::text($value, 'feedback.notes', 4000, true),
                        default => throw new TrainingError('feedback 包含未知字段。'),
                    };
                }
            }
            if (null !== $data['raceId']) {
                self::id($data['raceId']);
            }
            if (null !== $data['activityId']) {
                self::text($data['activityId'], 'activityId', 255);
            }
        }
        if ('races' === $kind) {
            self::text($data['name'] ?? null, 'name', 200);
            self::date($data['date'] ?? null);
            self::number($data['distanceKm'] ?? null, 'distanceKm', 0.1, 1000);
            if (null !== $data['targetTimeMinutes']) {
                self::number($data['targetTimeMinutes'], 'targetTimeMinutes', 1, 10000);
            }
        }
        if ('check-ins' === $kind) {
            self::date($data['date'] ?? null);
            if (null !== $data['sleepHours']) {
                self::number($data['sleepHours'], 'sleepHours', 0, 24);
            }
            foreach (['fatigue', 'soreness'] as $key) {
                self::number($data[$key] ?? null, $key, 1, 5, true);
            }
            if (!is_bool($data['pain'])) {
                throw new TrainingError('pain 必须为布尔值。');
            }
        }
        if ('fuel-logs' === $kind) {
            self::date($data['date'] ?? null);
            self::text($data['item'] ?? null, 'item', 200);
            self::number($data['minute'] ?? null, 'minute', 0, 1440);
            self::number($data['carbsGrams'], 'carbsGrams', 0, 1000);
            self::number($data['fluidMl'], 'fluidMl', 0, 10000);
            self::choice($data['giComfort'], ['good', 'mild', 'poor'], 'giComfort');
            if (null !== $data['sessionId']) {
                self::id($data['sessionId']);
            }
        }

        return $data;
    }

    private static function text(mixed $value, string $field, int $max, bool $empty = false): void
    {
        if (!is_string($value) || (!$empty && '' === trim($value)) || mb_strlen($value) > $max) {
            throw new TrainingError($field.' 必须为'.($empty ? '' : '非空').'文字，最多 '.$max.' 字。');
        }
    }

    private static function number(mixed $value, string $field, float $min, float $max, bool $integer = false): void
    {
        if ((!is_int($value) && !is_float($value)) || ($integer && !is_int($value)) || !is_finite((float) $value) || $value < $min || $value > $max) {
            throw new TrainingError($field.' 必须在 '.$min.'–'.$max.' 之间'.($integer ? '且为整数。' : '。'));
        }
    }

    /** @param list<string> $choices */
    private static function choice(mixed $value, array $choices, string $field): void
    {
        if (!in_array($value, $choices, true)) {
            throw new TrainingError($field.' 必须为 '.implode('/', $choices).'。');
        }
    }

    private static function items(mixed $value, string $field, int $max): void
    {
        if (!is_array($value) || !array_is_list($value) || count($value) > $max) {
            throw new TrainingError($field.' 必须为不超过 '.$max.' 条的列表。');
        }
    }

    private static function location(mixed $value): void
    {
        if (null === $value) {
            return;
        }
        if (!is_array($value) || array_diff(array_keys($value), ['label', 'latitude', 'longitude'])) {
            throw new TrainingError('location 格式无效。');
        }
        self::text($value['label'] ?? null, 'location.label', 120);
        self::number($value['latitude'] ?? null, 'latitude', -90, 90);
        self::number($value['longitude'] ?? null, 'longitude', -180, 180);
    }
}
