<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;

/** Bounded, read-only projections. Provider identifiers and encoded payloads stay private. */
final readonly class DailyCollectionReadModel
{
    public const LABELS = ['body_battery' => '身体电量', 'charge_stress' => '充电压力', 'charge_insights' => '充电洞察', 'hrv_sdnn' => 'HRV SDNN', 'hrv_rmssd' => 'HRV RMSSD', 'daily_health' => '每日活动', 'respiratory_rate' => '呼吸率', 'emotion' => '情绪', 'lactate_threshold' => '乳酸阈值', 'blood_pressure' => 'Zepp 血压', 'second_heart_rate_files' => '秒级心率文件索引', 'sport_load' => '训练负荷', 'vo2_max' => '最大摄氧量估计', 'weight' => 'Zepp 体重', 'heart_rate' => '心率来源', 'health_profile' => 'Zepp 个人资料', 'band_detail' => '睡眠与分钟心率', 'all_day_stress' => '全天压力', 'single_stress' => '单次压力', 'blood_oxygen' => '血氧来源', 'PaiHealthInfo' => 'PAI', 'readiness' => '准备度'];

    public function __construct(private TrainingRepository $repository, private WeightRecordService $weights)
    {
    }

    /** @return list<array<string,mixed>> */
    public function captures(string $from, string $to): array
    {
        $daily = array_column($this->weights->list($from, $to)['records'], null, 'date');
        $owners = array_column($this->repository->list('collection-projection'), null, 'id');
        $rows = array_values(array_filter($this->repository->list('collection-weight'), static fn (array $r): bool => $r['capture']['localDate'] >= $from && $r['capture']['localDate'] <= $to));
        foreach ($rows as &$row) {
            $date = $row['capture']['localDate'];
            $current = $daily[$date] ?? null;
            $owner = $owners[$date]['daily'] ?? null;
            $row['projectionStatus'] = $current != $owner ? 'manual_conflict' : 'daily_projection';
        }
        unset($row);
        usort($rows, static fn (array $a, array $b): int => [$b['capture']['measuredAt'], $b['id']] <=> [$a['capture']['measuredAt'], $a['id']]);

        return $rows;
    }

    /** @return list<array<string,mixed>> */
    public function summaries(string $from, string $to): array
    {
        $rows = [];
        foreach ($this->repository->list('zepp-raw') as $raw) {
            $summary = self::summarize($raw);
            // Undated profile snapshots remain discoverable and explicitly undated.
            if (null === $summary['date'] || ($summary['date'] >= $from && $summary['date'] <= $to)) {
                $rows[] = $summary;
            }
        }
        usort($rows, static fn (array $a, array $b): int => [$b['date'] ?? '', $b['id']] <=> [$a['date'] ?? '', $a['id']]);

        return $rows;
    }

    /** @param array<string,mixed> $raw
     * @return array<string,mixed>
     */
    public static function summarize(array $raw): array
    {
        $p = $raw['payload'] ?? [];
        $value = $p['value'] ?? [];
        if (is_string($value)) {
            $value = json_decode($value, true) ?? [];
        }
        $value = is_array($value) ? $value : [];
        $samples = is_array($value['samples'] ?? null) ? $value['samples'] : [];
        usort($samples, static fn (array $a, array $b): int => ($a['s'] ?? 0) <=> ($b['s'] ?? 0));
        $kind = $raw['kind'];
        $date = $raw['sourceDate'] ?? $p['dayId'] ?? $p['date_time'] ?? null;
        if (!ZeppDataParser::validDate($date)) {
            $stamp = $p['timestamp'] ?? $p['generatedTime'] ?? $value['startTime'] ?? null;
            $date = is_numeric($stamp) && (float) $stamp > 0 ? gmdate('Y-m-d', (int) ((float) $stamp > 100000000000 ? (float) $stamp / 1000 : $stamp)) : null;
        }
        $fields = match ($kind) {
            'hrv_sdnn' => ['sdnn' => ['SDNN', 'ms']], 'hrv_rmssd' => ['hrv' => ['RMSSD', 'ms']],
            'body_battery' => ['total' => ['身体电量', ''], 'mental' => ['精神电量', ''], 'physical' => ['身体电量分项', '']],
            'daily_health' => ['totalSteps' => ['步数', '步'], 'totalCalories' => ['热量', 'kcal'], 'totalBurningDuration' => ['燃脂时长（源值）', '单位未确认'], 'stepGoal' => ['步数目标', '步'], 'calorieGoal' => ['热量目标', 'kcal']],
            'lactate_threshold' => ['lactateThresholdHr' => ['乳酸阈值心率', 'bpm'], 'lactateThresholdPace' => ['乳酸阈值配速（源值）', '单位未确认']],
            'PaiHealthInfo' => ['totalPai' => ['累计 PAI', ''], 'dailyPai' => ['当日 PAI', ''], 'restHr' => ['静息心率', 'bpm']],
            'all_day_stress' => ['avgStress' => ['平均压力', ''], 'maxStress' => ['最高压力', '']],
            'sport_load' => ['currnetDayTrainLoad' => ['当日训练负荷', ''], 'wtlSum' => ['累计训练负荷', '']],
            'vo2_max' => ['vo2_max_run' => ['跑步 VO₂ max', 'mL/kg/min'], 'vo2_max_walking' => ['步行 VO₂ max', 'mL/kg/min']],
            default => [],
        };
        $metrics = [];
        foreach ($fields as $key => [$label, $unit]) {
            $values = [];
            foreach ([] === $samples ? [$p] : $samples as $sample) {
                $v = $sample[$key] ?? null;
                if (is_numeric($v) && is_finite((float) $v)) {
                    $values[] = (float) $v;
                }
            }
            if ([] !== $values) {
                $metrics[] = ['key' => $key, 'label' => $label, 'unit' => $unit, 'latest' => end($values), 'min' => min($values), 'max' => max($values), 'count' => count($values)];
            }
        }
        $note = match ($kind) {
            'respiratory_rate' => '已保留编码记录，尚未验证解码，不能据此展示呼吸率曲线。',
            'second_heart_rate_files' => '仅文件索引，不是已经下载的心率波形。',
            'charge_stress', 'charge_insights', 'emotion' => '来源已保存；编码或分类含义尚未验证，不推断医学含义。',
            'health_profile' => 'Zepp 资料快照不会覆盖服务器设置或 Android 冻结的计算资料。',
            'readiness' => '准备度编码已保存，尚未验证解码。温度校准不等于绝对体温；不展示未确认的恢复曲线。',
            'blood_oxygen' => 'ODI/OSA 指标不等于单次 SpO₂；已解析指标见每日记录。',
            default => [] === $metrics ? '已保存来源。已解析的核心指标见每日记录；未知字段不作推断。' : '设备来源摘要，仅供回顾；样本最小值和最大值不代表诊断。',
        };

        return ['id' => $raw['id'], 'kind' => $kind, 'label' => self::LABELS[$kind] ?? $kind, 'date' => $date, 'dateBasis' => null === $date ? 'undated' : (isset($raw['sourceDate']) || isset($p['dayId']) ? 'source_calendar' : 'utc_event'), 'fetchedAt' => $raw['fetchedAt'] ?? null, 'sampleCount' => count($samples), 'metrics' => $metrics, 'interpretation' => [] === $metrics ? 'availability_only' : 'scalar_summary', 'note' => $note];
    }

    /** @param list<array<string,mixed>> $rows
     * @return array<string,mixed>
     */
    public static function page(array $rows, int $offset, int $limit, ?string $snapshot): array
    {
        if ($offset < 0 || $offset > 100000 || $limit < 1 || $limit > 100) {
            throw new TrainingError('分页范围无效。');
        }
        $hash = hash('sha256', json_encode($rows, JSON_THROW_ON_ERROR));
        if (($offset > 0 && null === $snapshot) || (null !== $snapshot && !hash_equals($hash, $snapshot))) {
            throw new TrainingError('记录已变化，请从第一页重新读取。', 409);
        }
        $page = array_slice($rows, $offset, $limit);

        return ['records' => $page, 'total' => count($rows), 'offset' => $offset, 'snapshot' => $hash, 'nextOffset' => $offset + count($page) < count($rows) ? $offset + count($page) : null];
    }
}
