<?php

declare(strict_types=1);

namespace App\Domain\Health;

/** Read-only presentation of uploaded estimates; no synthetic manual measurements. */
final readonly class BodyCompositionReadModel
{
    public const BMI_SOURCE = 'https://www.nhc.gov.cn/wjw/c100378/202412/f3f58b54fbeb4c87b14164fac898bf08/files/1736390749000_59785.pdf';
    private const METRICS = [
        'WEIGHT' => ['体重', 'kg', 2], 'BMI' => ['采集时 BMI', '', 1], 'BODY_FAT' => ['体脂率', '%', 1],
        'MUSCLE' => ['肌肉率', '%', 2], 'MUSCLE_MASS' => ['肌肉重量', 'kg', 2], 'WATER' => ['水分', '%', 2],
        'WATER_MASS' => ['水分重量', 'kg', 2], 'PROTEIN' => ['蛋白质', '%', 2], 'BONE' => ['骨量', 'kg', 1],
        'BONE_PERCENT' => ['骨量比例', '%', 2], 'VISCERAL_FAT' => ['内脏脂肪等级', '级', 0], 'BMR' => ['基础代谢', 'kcal/天', 1],
        'FAT_MASS' => ['脂肪重量', 'kg', 2], 'LEAN_MASS' => ['去脂体重', 'kg', 2], 'LEAN_PERCENT' => ['去脂体重比例', '%', 2],
        'BODY_AGE' => ['身体年龄', '岁', 0], 'STANDARD_WEIGHT_MIN' => ['算法正常体重下限', 'kg', 2],
        'STANDARD_WEIGHT_MAX' => ['算法正常体重上限', 'kg', 2], 'FAT_INDEX' => ['体脂指数', '', 0],
        'OBESITY_DEGREE' => ['算法肥胖级别', '', 0], 'SCORE' => ['身体评分', '分', 0],
        'WEIGHT_CONTROL' => ['算法体重控制量', 'kg', 2], 'FAT_CONTROL' => ['算法脂肪控制量', 'kg', 2],
        'MUSCLE_CONTROL' => ['算法肌肉控制量', 'kg', 2], 'WATER_CONTROL' => ['算法水分控制量', 'kg', 2],
        'FMI' => ['脂肪质量指数 FMI', 'kg/m²', 2], 'FFMI' => ['去脂体重指数 FFMI', 'kg/m²', 2],
    ];

    public function __construct(private DailyCollectionReadModel $collection, private BodyHeightSelection $height)
    {
    }

    /** @return array<string,mixed> */
    public function read(string $from, string $to): array
    {
        $height = $this->height->get();
        $rows = array_values(array_filter($this->collection->captures($from, $to), static fn (array $r): bool => !$r['deleted']));

        return ['from' => $from, 'to' => $to, 'heightSelection' => $height,
            'records' => array_map(static fn (array $r): array => [...$r, 'composition' => self::present($r['capture'], $height)], $rows)];
    }

    /** @return array<string,mixed> */
    public function summary(string $from, string $to): array
    {
        $data = $this->read($from, $to);
        $latest = $data['records'][0] ?? null;

        return ['from' => $from, 'to' => $to, 'captureCount' => count($data['records']), 'latest' => null === $latest ? null : [
            'measurementId' => $latest['measurementId'], 'measuredAt' => $latest['capture']['measuredAt'], 'localDate' => $latest['capture']['localDate'],
            'source' => 'weight_capture', 'sourceLabel' => $latest['composition']['sourceLabel'], 'projectionStatus' => $latest['projectionStatus'],
            ...$latest['composition'],
        ], 'heightSelection' => array_diff_key($data['heightSelection'], ['candidates' => true]),
            'methodology' => '仅包含所选范围内未删除的逐次采集。体脂和体成分为设备/公式估算；评级为秤算法参考，不是诊断。原始采集快照保持不变。'];
    }

    /** @param array<string,mixed> $capture
     * @param array<string,mixed> $height
     *
     * @return array<string,mixed>
     */
    public static function present(array $capture, array $height): array
    {
        $derived = (array) ($capture['derived'] ?? []);
        $values = (array) ($derived['values'] ?? []);
        // Older uploads may only contain the top-level fat/BMI fields.
        foreach (['WEIGHT' => 'weightKg', 'BODY_FAT' => 'fatPercent', 'BMI' => 'bmi'] as $key => $field) {
            if (!array_key_exists($key, $values) && null !== self::number($capture[$field] ?? null)) {
                $values[$key] = $capture[$field];
            }
        }
        $grades = (array) ($derived['grades'] ?? []);
        $references = (array) ($derived['references'] ?? []);
        $scores = (array) ($derived['scores'] ?? []);
        $keys = array_unique([...array_keys($values), ...array_keys($grades), ...array_keys($references), ...array_keys($scores)]);
        $ordered = [...array_intersect(array_keys(self::METRICS), $keys), ...array_diff($keys, array_keys(self::METRICS))];
        $metrics = [];
        foreach ($ordered as $key) {
            [$label, $unit, $digits] = self::METRICS[$key] ?? [$key, '', 2];
            $grade = is_string($grades[$key] ?? null) ? $grades[$key] : null;
            $direction = in_array($grade, ['偏高', '过高', '极高', '超高', '偏胖', '肥胖', '极胖', '微胖', '偏重', '高于参考上限'], true) ? 'high'
                : (in_array($grade, ['偏低', '过低', '偏瘦', '偏轻', '低于参考下限'], true) ? 'low' : 'neutral');
            $metrics[] = ['key' => $key, 'label' => $label, 'value' => self::number($values[$key] ?? null), 'unit' => $unit, 'digits' => $digits,
                'kind' => 'WEIGHT' === $key ? 'weight_record' : ('BMI' === $key ? 'calculated' : 'estimate'), 'grade' => $grade,
                'reference' => is_string($references[$key] ?? null) ? $references[$key] : null, 'score' => self::number($scores[$key] ?? null),
                'direction' => $direction, 'highlight' => !in_array($key, ['BONE', 'BONE_PERCENT', 'BODY_AGE', 'BMR'], true) && 'neutral' !== $direction,
                'note' => match ($key) {
                    'BMR' => '静息能量估算，不是全天热量目标。', 'BONE', 'BONE_PERCENT' => '公式估算，不能判断骨密度或骨病。',
                    'BODY_AGE' => '算法比较值，不代表生理年龄或疾病。', 'PROTEIN' => '公式估算，不代表血液蛋白检验或每日蛋白质需求。',
                    'WEIGHT_CONTROL', 'FAT_CONTROL', 'MUSCLE_CONTROL', 'WATER_CONTROL' => '原算法与参考区间的差值，不是减重、增肌或补水处方。',
                    default => isset(self::METRICS[$key]) ? null : '未识别指标；数值与含义以原始采集算法为准，未推断单位。',
                }];
        }
        $profile = (array) ($derived['profile'] ?? []);
        $profile = [] === $profile ? (array) ($capture['profile'] ?? []) : $profile;
        $heightCm = self::number($profile['heightCm'] ?? null);
        $bmi = null;
        if ('report' === $height['status'] && null !== ($weight = self::number($capture['weightKg'] ?? null))) {
            $rawBmi = $weight / (($height['source']['heightCm'] / 100) ** 2);
            $bmi = ['value' => round($rawBmi, 2), 'heightCm' => $height['source']['heightCm'], 'source' => $height['source'],
                'classification' => self::adult($profile, $capture['localDate']) ? self::bmiClass($rawBmi) : null,
                'reference' => '中国成人 BMI：<18.5 体重过低；18.5–<24 正常；24–<28 超重；≥28 肥胖。',
                'referenceUrl' => self::BMI_SOURCE, 'note' => '使用本次体重与当前选择的报告身高计算；不修改采集时 BMI，不重算体脂或其他体成分。'];
        }

        return ['sourceLabel' => is_string($derived['source'] ?? null) ? $derived['source'] : '称重采集',
            'algorithmVersion' => $derived['algorithmVersion'] ?? null, 'calculationBasis' => $derived['calculationBasis'] ?? null,
            'bodyShape' => $derived['bodyShape'] ?? null, 'capturedHeightCm' => $heightCm,
            'weightKg' => self::number($capture['weightKg'] ?? null), 'bodyFatPercent' => self::number($values['BODY_FAT'] ?? null),
            'capturedBmi' => self::number($values['BMI'] ?? null), 'reportBmi' => $bmi, 'metrics' => $metrics];
    }

    public static function bmiClass(float $bmi): string
    {
        return match (true) {
            $bmi < 18.5 => '体重过低', $bmi < 24 => '正常', $bmi < 28 => '超重', default => '肥胖',
        };
    }

    private static function number(mixed $value): ?float
    {
        return (is_int($value) || is_float($value)) && is_finite((float) $value) ? (float) $value : null;
    }

    /** @param array<string,mixed> $profile */
    private static function adult(array $profile, string $date): bool
    {
        $age = self::number($profile['ageYears'] ?? $profile['age'] ?? null);
        if (null !== $age) {
            return $age >= 18;
        }
        $birthday = $profile['birthDate'] ?? $profile['birthday'] ?? null;
        if (!is_string($birthday) || !preg_match('/^\d{4}-\d{2}-\d{2}$/D', $birthday)) {
            return false;
        }
        $birth = \DateTimeImmutable::createFromFormat('!Y-m-d', $birthday);

        return false !== $birth && $birth->format('Y-m-d') === $birthday && $birthday <= $date && $birth->diff(new \DateTimeImmutable($date))->y >= 18;
    }
}
