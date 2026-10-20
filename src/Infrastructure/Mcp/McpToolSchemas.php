<?php

declare(strict_types=1);

namespace App\Infrastructure\Mcp;

use App\Infrastructure\ValueObject\String\KernelProjectDir;

/** Reuses field contracts, not HTTP routes; clients only see task-oriented MCP tools. */
final readonly class McpToolSchemas
{
    public function __construct(private KernelProjectDir $root)
    {
    }

    /** @return array<string, array{title: string, description: string, readOnly: bool, input: array<string, mixed>}> */
    public function definitions(): array
    {
        $date = ['type' => 'string', 'format' => 'date', 'description' => '个人档案时区的 YYYY-MM-DD。'];
        $id = ['type' => 'string', 'pattern' => '^[a-zA-Z0-9_-]{1,80}$'];
        $version = ['type' => 'integer', 'minimum' => 0, 'description' => '更新必须使用最近读取的 version；创建为 0。'];
        $request = ['requestId' => [...$id, 'description' => '一次用户操作的稳定 ID。响应丢失时用相同 ID 和原参数重试，或 get_operation 查询。不可把旧 ID 用于新的修改。']];
        $reason = ['type' => 'string', 'minLength' => 1, 'maxLength' => 4000, 'description' => '本次修改依据及用户要求；不把 AI 推断写成实测。'];
        $session = $this->record('training', 'Session', ['updatedAt', 'feedback', 'activityId', 'activityIds']);
        $session['properties']['status']['enum'] = ['planned', 'skipped', 'cancelled'];
        $mealPlan = $this->record('health', 'MealPlanPatch', ['source', 'rationale']);
        $goal = $this->record('health', 'WeightGoalPatch', ['source', 'rationale']);
        $meal = $this->record('health', 'MealPatch', ['source', 'rationale']);
        $measurement = $this->record('health', 'BodyMeasurementPatch', ['source', 'rationale']);
        $pressure = $this->record('health', 'BloodPressurePatch');
        $checkIn = $this->record('training', 'CheckIn');
        $weight = $this->schema('health', 'WeightPatch');
        $weight['properties']['date'] = $date;
        $weight['properties']['version']['description'] = 'get_overview 或 get_history(weight) 返回的 weightVersion 字符串。';
        $weight['required'][] = 'date';
        unset($weight['description']);
        $feedback = $this->schema('training', 'Feedback');
        $progress = self::object(['id' => $id, 'version' => $version, 'feedback' => $feedback,
            'activityIds' => ['type' => 'array', 'maxItems' => 20, 'uniqueItems' => true, 'items' => ['type' => 'string', 'maxLength' => 255], 'description' => '完整替换关联运动；空列表显式解除关联。'],
            'status' => ['enum' => ['planned', 'completed', 'skipped', 'cancelled']],
        ], ['id', 'version']);
        $profile = $this->record('training', 'Profile', ['id', 'notificationsEnabled', 'eveningReminderTime', 'preRunReminderMinutes']);
        $profile['required'] = ['version'];
        $context = self::object(['version' => $version, 'reports' => self::items($this->schema('health', 'Report'), 30),
            'constraints' => self::items($this->schema('health', 'Constraint'), 50),
            'lifestylePreferences' => ['type' => 'array', 'maxItems' => 30, 'items' => ['type' => 'string', 'maxLength' => 500]],
            'notes' => ['type' => 'string', 'maxLength' => 10000],
        ], ['version']);
        $context['description'] = 'reports/constraints 按 ID 增量合并，保留其他条目；更新单条需提供该条完整内容。偏好列表整体替换；省略字段保留。';

        return [
            'get_overview' => $this->definition('身体与计划概况', '先调用本工具。精简读取身体状态、运动表现、饮食、减重目标、未来训练与缺失数据。不会同步设备或生成计划。', true,
                self::object(['days' => ['type' => 'integer', 'minimum' => 1, 'maximum' => 90, 'default' => 14]])),
            'get_history' => $this->definition('查询记录与计划明细', '按主题和日期读取完整记录、ID、version；更新前先读。默认最近 30 天，最多 366 天；reports/constraints/weight_goals 不按日期过滤。分页必须携带首次返回的 from/to/snapshot，变动时会要求重读。', true,
                self::object(['topic' => ['enum' => ['training', 'workouts', 'meals', 'meal_plans', 'weight', 'weight_goals', 'measurements', 'blood_pressure', 'sleep', 'daily_activity', 'check_ins', 'reports', 'constraints']],
                    'from' => $date, 'to' => $date, 'offset' => ['type' => 'integer', 'minimum' => 0, 'default' => 0],
                    'limit' => ['type' => 'integer', 'minimum' => 1, 'maximum' => 50, 'default' => 20], 'snapshot' => ['type' => 'string', 'pattern' => '^[a-f0-9]{64}$'],
                ], ['topic'])),
            'get_workout_details' => $this->definition('单次运动表现与计划对账', '读取统一运动身份、导入分析或 Zepp 明细，以及关联训练的计划/实际对比。原始采样仅按需分页返回，不把重复来源相加。', true,
                self::object(['workoutId' => ['type' => 'string', 'minLength' => 1, 'maxLength' => 255], 'includeSamples' => ['type' => 'boolean', 'default' => false],
                    'offset' => ['type' => 'integer', 'minimum' => 0, 'default' => 0], 'limit' => ['type' => 'integer', 'minimum' => 1, 'maximum' => 200, 'default' => 50],
                ], ['workoutId'])),
            'save_plan' => $this->definition('保存训练、饮食与减重计划', '用户授权保存后使用。一次事务保存整份计划：任一项失败全部回滚；未列出的计划保留。创建可省略 ID；修改传当前 ID/version。支持同一 requestId 安全重试。', false,
                self::object([...$request, 'reason' => $reason, 'training' => self::items($session, 50), 'nutrition' => self::items($mealPlan, 31), 'weightGoals' => self::items($goal, 10)], ['requestId', 'reason'])),
            'record_progress' => $this->definition('记录真实身体、饮食与训练表现', '仅保存用户提供或已读取的真实记录，不将计划值填入实际。可一次记录体重、腰围体脂、餐食、血压、主观状态、训练反馈/运动关联；整批事务。营养估算必须标明 estimate。', false,
                self::object([...$request, 'weight' => $weight, 'measurements' => self::items($measurement, 30), 'meals' => self::items($meal, 50),
                    'bloodPressure' => self::items($pressure, 30), 'checkIns' => self::items($checkIn, 31), 'training' => self::items($progress, 30),
                ], ['requestId'])),
            'update_health_profile' => $this->definition('更新健康背景与训练偏好', '保存用户提供的偏好、报告摘要、限制及训练时间。报告原文、医生意见与 AI 解读分开；不会启用通知。先读取 profile/context 版本；报告和限制按 ID 合并。', false,
                self::object([...$request, 'reason' => $reason, 'profile' => $profile, 'healthContext' => $context], ['requestId', 'reason'])),
            'remove_record' => $this->definition('删除指定记录', '只在用户要求删除时使用；必须明确记录类型、ID 和最近读取的版本。整次操作可按 requestId 回查；会检查被其他记录引用的情况。', false,
                self::object([...$request, 'recordType' => ['enum' => ['meal', 'meal_plan', 'weight_goal', 'measurement', 'training', 'check_in', 'blood_pressure', 'weight']],
                    'id' => $id, 'version' => ['oneOf' => [$version, ['type' => 'string', 'pattern' => '^[a-f0-9]{64}$']], 'description' => '体重使用 weightVersion，其他类型使用记录 version。'],
                ], ['requestId', 'recordType', 'id', 'version'])),
            'resolve_workout_sources' => $this->definition('核对同一次运动的来源', '核实原始明细后才能合并 Zepp 与文件导入来源；候选相近不代表相同。合并保留源记录、仅计一次；拆分前解除训练关联。', false,
                self::object([...$request, 'action' => ['enum' => ['merge', 'split']], 'zeppId' => $id,
                    'activityId' => ['type' => 'string', 'minLength' => 1, 'maxLength' => 255], 'version' => $version,
                ], ['requestId', 'action', 'zeppId', 'version'])),
            'get_operation' => $this->definition('核验保存结果', '响应丢失、连接中断或需要核验时，按 requestId 读取已提交的操作收据。found=false 表示尚无已提交收据；安全重试仍须复用原 ID 和完全相同参数。', true,
                self::object($request, ['requestId'])),
        ];
    }

    /** @param array<string, mixed> $properties
     * @param list<string> $required
     *
     * @return array<string, mixed>
     */
    private static function object(array $properties, array $required = []): array
    {
        return ['type' => 'object', 'additionalProperties' => false, 'properties' => $properties, 'required' => $required];
    }

    /** @param array<string, mixed> $schema
     * @return array<string, mixed>
     */
    private static function items(array $schema, int $max): array
    {
        return ['type' => 'array', 'maxItems' => $max, 'items' => $schema];
    }

    /** @param array<string, mixed> $input
     * @return array{title: string, description: string, readOnly: bool, input: array<string, mixed>}
     */
    private function definition(string $title, string $description, bool $readOnly, array $input): array
    {
        return compact('title', 'description', 'readOnly', 'input');
    }

    /** @param list<string> $remove
     * @return array<string, mixed>
     */
    private function record(string $document, string $name, array $remove = []): array
    {
        $schema = $this->schema($document, $name);
        foreach (['updatedAt', ...$remove] as $key) {
            unset($schema['properties'][$key]);
        }
        if (!in_array('id', $remove, true)) {
            $schema['properties']['id'] = ['type' => 'string', 'pattern' => '^[a-zA-Z0-9_-]{1,80}$', 'description' => '创建可省略，由服务根据 requestId 分配；更新需原 ID。'];
        }
        $schema['properties']['version'] = ['type' => 'integer', 'minimum' => 0, 'description' => '创建可省略（0）；更新必须提供最近读取的版本。'];
        $createRequired = match ($name) {
            'Session' => ['title', 'startAt', 'durationMinutes', 'type'], 'CheckIn' => ['date'],
            'MealPlanPatch' => ['date'], 'MealPatch' => ['date', 'foods'],
            'WeightGoalPatch' => ['title', 'startDate', 'endDate', 'startWeightKg', 'targetWeightKg'],
            'BodyMeasurementPatch' => ['date'], 'BloodPressurePatch' => ['measuredAt', 'systolic', 'diastolic'], default => [],
        };
        $schema['required'] = [];
        if ([] !== $createRequired) {
            $schema['if'] = ['properties' => ['version' => ['const' => 0]]];
            $schema['then'] = ['required' => $createRequired];
            $schema['else'] = ['required' => ['id', 'version']];
            $schema['description'] = '创建必填：'.implode('、', $createRequired).'。更新只需 ID、version 和要修改的字段；嵌套列表完整替换。';
        }
        $schema['additionalProperties'] = false;

        return $schema;
    }

    /** @return array<string, mixed> */
    private function schema(string $document, string $name): array
    {
        $file = $this->root.'/docs/stridehub/'.('health' === $document ? 'health-' : '').'openapi.json';
        $spec = json_decode((string) file_get_contents($file), true, 512, JSON_THROW_ON_ERROR);

        return $this->resolve($spec['components']['schemas'][$name], $spec);
    }

    /** @param array<string, mixed> $schema
     * @param array<string, mixed> $spec
     *
     * @return array<string, mixed>
     */
    private function resolve(array $schema, array $spec): array
    {
        if (isset($schema['$ref'])) {
            $name = basename($schema['$ref']);
            $schema = array_replace($spec['components']['schemas'][$name], array_diff_key($schema, ['$ref' => true]));
        }
        foreach ($schema as $key => $value) {
            if (!is_array($value)) {
                continue;
            }
            if (in_array($key, ['properties', '$defs'], true)) {
                $schema[$key] = array_map(fn (array $s): array => $this->resolve($s, $spec), $value);
            } elseif (in_array($key, ['allOf', 'anyOf', 'oneOf'], true)) {
                $schema[$key] = array_map(fn (array $s): array => $this->resolve($s, $spec), $value);
            } elseif ('items' === $key) {
                $schema[$key] = $this->resolve($value, $spec);
            }
        }

        return $schema;
    }
}
