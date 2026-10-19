<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;

final class BloodPressureInput
{
    /** @return array<string, mixed> */
    public static function defaults(): array
    {
        return ['measuredAt' => null, 'systolic' => null, 'diastolic' => null, 'pulse' => null, 'arm' => 'unknown', 'posture' => 'unknown', 'notes' => ''];
    }

    /** @param array<string, mixed> $data
     * @return array<string, mixed>
     */
    public static function validate(array $data): array
    {
        HealthContextInput::knownFields($data, array_keys(self::defaults()), 'blood-pressure');
        $data = array_replace(self::defaults(), $data);
        $data['measuredAt'] = self::measuredAt($data['measuredAt']);
        foreach (['systolic', 'diastolic', 'pulse'] as $field) {
            if ('pulse' === $field && null === $data[$field]) {
                continue;
            }
            // These are data-entry bounds only, not clinical classifications.
            if (!is_int($data[$field]) || $data[$field] < 1 || $data[$field] > 400) {
                throw new TrainingError($field.' 必须为 1–400 的整数。');
            }
        }
        if ($data['systolic'] <= $data['diastolic']) {
            throw new TrainingError('收缩压必须大于舒张压，请核对测量值。');
        }
        foreach (['arm' => ['unknown', 'left', 'right'], 'posture' => ['unknown', 'seated', 'standing', 'lying']] as $field => $values) {
            if (!in_array($data[$field], $values, true)) {
                throw new TrainingError($field.' 必须为 '.implode('、', $values).'。');
            }
        }
        if (!is_string($data['notes']) || mb_strlen($data['notes']) > 2000) {
            throw new TrainingError('notes 必须为最多 2000 字符的文本。');
        }

        return $data;
    }

    public static function measuredAt(mixed $value): string
    {
        if (!is_string($value) || !preg_match('/^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(Z|[+-](?:0\d|1[0-3]):[0-5]\d|[+-]14:00)$/D', $value, $match)
            || !checkdate((int) $match[2], (int) $match[3], (int) $match[1])) {
            throw new TrainingError('measuredAt 必须为有效且含时区偏移的 ISO 8601 时间，例如 2026-10-19T07:30:00+08:00。');
        }

        return new \DateTimeImmutable($value)->format(DATE_ATOM);
    }
}
