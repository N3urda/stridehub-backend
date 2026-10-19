<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;

final class HealthContextInput
{
    /** @return array<string, mixed> */
    public static function defaults(): array
    {
        return ['reports' => [], 'constraints' => [], 'lifestylePreferences' => [], 'notes' => ''];
    }

    /** @param array<string, mixed> $data
     * @return array<string, mixed>
     */
    public static function validate(array $data): array
    {
        self::knownFields($data, array_keys(self::defaults()), 'context');
        $data = array_replace(self::defaults(), $data);
        $reports = [];
        $reportIds = [];
        $findingCount = 0;
        foreach (self::items($data['reports'], 'reports', 30) as $input) {
            $report = self::object($input, 'reports');
            self::knownFields($report, ['id', 'title', 'reportDate', 'sourceLabel', 'findings', 'clinicianAdvice', 'aiInterpretation', 'needsReview', 'notes'], 'reports');
            $report = array_replace(['findings' => [], 'clinicianAdvice' => [], 'aiInterpretation' => '', 'needsReview' => true, 'notes' => ''], $report);
            $id = TrainingInput::id($report['id'] ?? null);
            if (isset($reportIds[$id])) {
                throw new TrainingError('reports 包含重复 id。');
            }
            $reportIds[$id] = true;
            self::text($report['title'] ?? null, 'reports.title', 200);
            TrainingInput::date($report['reportDate'] ?? null);
            self::text($report['sourceLabel'] ?? null, 'reports.sourceLabel', 200);
            self::text($report['notes'], 'reports.notes', 4000, true);
            self::text($report['aiInterpretation'], 'reports.aiInterpretation', 10000, true);
            if (!is_bool($report['needsReview'])) {
                throw new TrainingError('reports.needsReview 必须为布尔值。');
            }
            $report['findings'] = array_map(self::finding(...), self::items($report['findings'], 'reports.findings', 200));
            $findingCount += count($report['findings']);
            if ($findingCount > 1000) {
                throw new TrainingError('所有 reports 合计最多 1000 项 findings。');
            }
            $report['clinicianAdvice'] = self::texts($report['clinicianAdvice'], 'reports.clinicianAdvice', 30, 2000);
            $reports[] = $report;
        }
        $constraints = [];
        $constraintIds = [];
        foreach (self::items($data['constraints'], 'constraints', 50) as $input) {
            $constraint = self::object($input, 'constraints');
            self::knownFields($constraint, ['id', 'description', 'sourceType', 'sourceReportId', 'validFrom', 'reviewOn', 'status'], 'constraints');
            $constraint = array_replace(['sourceReportId' => null, 'validFrom' => null, 'reviewOn' => null, 'status' => 'active'], $constraint);
            $id = TrainingInput::id($constraint['id'] ?? null);
            if (isset($constraintIds[$id])) {
                throw new TrainingError('constraints 包含重复 id。');
            }
            $constraintIds[$id] = true;
            self::text($constraint['description'] ?? null, 'constraints.description', 2000);
            self::choice($constraint['sourceType'] ?? null, ['clinician', 'user'], 'constraints.sourceType');
            self::choice($constraint['status'], ['active', 'inactive'], 'constraints.status');
            if (null !== $constraint['sourceReportId'] && !isset($reportIds[TrainingInput::id($constraint['sourceReportId'])])) {
                throw new TrainingError('constraints.sourceReportId 必须指向此次保存后仍存在的报告。');
            }
            foreach (['validFrom', 'reviewOn'] as $key) {
                if (null !== $constraint[$key]) {
                    TrainingInput::date($constraint[$key]);
                }
            }
            if (null !== $constraint['validFrom'] && null !== $constraint['reviewOn'] && $constraint['reviewOn'] < $constraint['validFrom']) {
                throw new TrainingError('constraints.reviewOn 不能早于 validFrom。');
            }
            $constraints[] = $constraint;
        }
        self::text($data['notes'], 'notes', 10000, true);

        return ['reports' => $reports, 'constraints' => $constraints, 'lifestylePreferences' => self::texts($data['lifestylePreferences'], 'lifestylePreferences', 30, 500), 'notes' => $data['notes']];
    }

    /** @param array<string, mixed> $data
     * @param list<string> $allowed
     */
    public static function knownFields(array $data, array $allowed, string $field): void
    {
        if ($unknown = array_diff(array_keys($data), $allowed)) {
            throw new TrainingError($field.' 包含未知字段：'.implode(', ', $unknown));
        }
    }

    /** @return array<string, mixed> */
    private static function finding(mixed $input): array
    {
        $finding = self::object($input, 'findings');
        self::knownFields($finding, ['name', 'valueText', 'unit', 'referenceRangeText', 'flag', 'page'], 'findings');
        $finding = array_replace(['unit' => '', 'referenceRangeText' => '', 'flag' => 'unknown', 'page' => null], $finding);
        self::text($finding['name'] ?? null, 'findings.name', 200);
        self::text($finding['valueText'] ?? null, 'findings.valueText', 500);
        self::text($finding['unit'], 'findings.unit', 80, true);
        self::text($finding['referenceRangeText'], 'findings.referenceRangeText', 500, true);
        self::choice($finding['flag'], ['normal', 'high', 'low', 'abnormal', 'unknown'], 'findings.flag');
        if (null !== $finding['page'] && (!is_int($finding['page']) || $finding['page'] < 1 || $finding['page'] > 10000)) {
            throw new TrainingError('findings.page 必须为 null 或 1–10000 的整数。');
        }

        return $finding;
    }

    /** @return array<string, mixed> */
    private static function object(mixed $value, string $field): array
    {
        if ($value instanceof \stdClass) {
            return get_object_vars($value);
        }
        if (!is_array($value) || array_is_list($value)) {
            throw new TrainingError($field.' 必须包含对象。');
        }

        return $value;
    }

    /** @return list<mixed> */
    private static function items(mixed $value, string $field, int $max): array
    {
        if (!is_array($value) || !array_is_list($value) || count($value) > $max) {
            throw new TrainingError($field.' 必须为最多 '.$max.' 项的列表。');
        }

        return $value;
    }

    /** @return list<string> */
    private static function texts(mixed $value, string $field, int $count, int $length): array
    {
        $items = self::items($value, $field, $count);
        foreach ($items as $item) {
            self::text($item, $field, $length);
        }

        return $items;
    }

    private static function text(mixed $value, string $field, int $max, bool $allowEmpty = false): void
    {
        if (!is_string($value) || (!$allowEmpty && '' === trim($value)) || mb_strlen($value) > $max) {
            throw new TrainingError($field.' 必须为'.($allowEmpty ? '最多' : '非空且最多').' '.$max.' 字符的文本。');
        }
    }

    /** @param list<string> $allowed */
    private static function choice(mixed $value, array $allowed, string $field): void
    {
        if (!in_array($value, $allowed, true)) {
            throw new TrainingError($field.' 必须为 '.implode('、', $allowed).'。');
        }
    }
}
