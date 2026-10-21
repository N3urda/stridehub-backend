<?php

declare(strict_types=1);

namespace App\Infrastructure\Mcp;

use App\Domain\Health\BloodPressureService;
use App\Domain\Health\DailyCollectionReadModel;
use App\Domain\Health\HealthContextService;
use App\Domain\Health\HealthDataService;
use App\Domain\Health\HealthWorkspace;
use App\Domain\Health\WeightRecordService;
use App\Domain\Health\WorkoutCatalog;
use App\Domain\Health\ZeppWorkoutService;
use App\Domain\Running\RunningDetails;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingFeedback;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Domain\Training\TrainingService;
use App\Infrastructure\Time\Clock\Clock;
use Mcp\Schema\Content\TextContent;
use Mcp\Schema\Result\CallToolResult;

/** Task-oriented adapter over the same domain services used by the data pages. */
final readonly class McpWorkspace
{
    public function __construct(
        private HealthWorkspace $workspace,
        private TrainingRepository $repository,
        private TrainingService $training,
        private TrainingFeedback $feedback,
        private HealthDataService $data,
        private HealthContextService $context,
        private WeightRecordService $weights,
        private BloodPressureService $pressure,
        private WorkoutCatalog $workouts,
        private ZeppWorkoutService $zepp,
        private RunningDetails $running,
        private Clock $clock,
        private DailyCollectionReadModel $collection,
    ) {
    }

    /** @param array<string, mixed> $arguments */
    public function call(string $tool, array $arguments): CallToolResult
    {
        try {
            $data = match ($tool) {
                'get_overview' => $this->overview($arguments['days'] ?? 14),
                'get_history' => $this->history($arguments),
                'get_workout_details' => $this->details($arguments),
                'get_operation' => $this->operation($arguments['requestId']),
                default => $this->mutate($tool, $arguments),
            };
            $result = ['ok' => true, 'data' => $data];
        } catch (TrainingError $error) {
            $result = ['ok' => false, 'error' => ['code' => match ($error->status) {
                409 => 'conflict', 404 => 'not_found', 428 => 'version_required', default => 'invalid_input',
            }, 'message' => $error->getMessage(), 'recovery' => 409 === $error->status
                ? '重新读取受影响的记录并核对差异；不要自动覆盖。修改参数后使用新的 requestId。'
                : '核对工具参数和记录 ID/version 后重试。']];
        } catch (\Throwable) {
            // Never serialize SQL, credentials, raw health data, or a stack trace to a model.
            $result = ['ok' => false, 'error' => ['code' => 'internal_error', 'message' => '操作未能完成。',
                'recovery' => '写入操作请先用 get_operation 查询 requestId；需要重试时保留原 ID 和参数。']];
        }

        return new CallToolResult([new TextContent(json_encode($result, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE))], !$result['ok'], $result);
    }

    /** @return array<string, mixed> */
    private function overview(int $days): array
    {
        $today = $this->today();
        $from = new \DateTimeImmutable($today)->modify('-'.($days - 1).' days')->format('Y-m-d');
        $w = $this->workspace->read($from, $today);
        $context = $w['healthContext'];
        // Reports may contain long medical text; overview exposes identities, details are opt-in.
        $context['reports'] = array_map(static fn (array $r): array => array_intersect_key($r, array_flip(['id', 'title', 'reportDate', 'sourceLabel', 'needsReview'])), $context['reports']);
        $end = new \DateTimeImmutable($today)->modify('+7 days')->format('Y-m-d');
        $upcoming = $this->training->list('sessions', $today, $end);
        $nutrition = $this->data->list('meal-plans', $today, $end);

        return ['today' => $today, 'timezone' => $w['timezone'], 'from' => $from, 'to' => $today, 'generatedAt' => $w['generatedAt'],
            'profile' => $w['profile'], 'healthContext' => $context,
            'body' => ['weight' => $w['weight']['records'][0] ?? null, 'weightTrend' => array_slice($w['weight']['trend'], -7),
                'measurements' => array_slice($w['measurements'], 0, 3), 'bloodPressure' => array_slice($w['bloodPressure'], 0, 3),
                'sleep' => array_slice($w['sleep'], 0, 3), 'dailyActivity' => array_slice($w['daily'], 0, 3)],
            'weightVersion' => $w['weight']['version'], 'weightGoals' => $w['weight']['goals'],
            'workouts' => [...array_diff_key($w['workouts'], ['records' => true]), 'recent' => array_slice($w['workouts']['records'], 0, 3)],
            'nutrition' => ['days' => array_slice($w['nutrition']['days'], -7), 'methodology' => $w['nutrition']['methodology']],
            'upcoming' => ['from' => $today, 'to' => $end, 'training' => array_slice($upcoming, 0, 20), 'trainingTotal' => count($upcoming),
                'nutrition' => array_slice($nutrition, 0, 8), 'nutritionTotal' => count($nutrition)],
            'coverage' => $w['coverage'], 'missingData' => array_keys(array_filter([
                'weight_in_range' => [] === $w['weight']['records'], 'measurements_in_range' => [] === $w['measurements'],
                'workouts_in_range' => [] === $w['workouts']['records'], 'meals_in_range' => [] === $w['nutrition']['meals'],
                'health_reports' => [] === $context['reports'],
            ])), 'next' => '明细使用 get_history；计划与实测分开。null 表示未知，不代表 0。概况中的身体记录仅覆盖请求日期范围。'];
    }

    /** @param array<string, mixed> $args
     * @return array<string, mixed>
     */
    private function history(array $args): array
    {
        $w = $this->workspace->read($args['from'] ?? null, $args['to'] ?? null);
        $records = match ($args['topic']) {
            'weight_captures' => $this->collection->captures($w['from'], $w['to']),
            'zepp_summaries' => $this->collection->summaries($w['from'], $w['to']),
            'training' => $w['sessions'], 'workouts' => $w['workouts']['records'], 'meals' => $w['nutrition']['meals'],
            'meal_plans' => $w['nutrition']['plans'], 'weight' => $w['weight']['records'], 'weight_goals' => $w['weight']['goals'],
            'measurements' => $w['measurements'], 'blood_pressure' => $w['bloodPressure'], 'sleep' => $w['sleep'],
            'daily_activity' => $w['daily'], 'check_ins' => $w['checkIns'], 'reports' => $w['healthContext']['reports'],
            'constraints' => $w['healthContext']['constraints'], default => throw new TrainingError('topic 无效。'),
        };
        $snapshot = self::fingerprint([$args['topic'], $w['from'], $w['to'], $records, $w['weight']['version'], $w['healthContext']['version']]);
        $offset = $args['offset'] ?? 0;
        if (($offset > 0 && !isset($args['snapshot'])) || (isset($args['snapshot']) && !hash_equals($snapshot, $args['snapshot']))) {
            throw new TrainingError('分页快照已变化或缺失，请从 offset=0 重新读取。', 409);
        }
        $items = array_slice($records, $offset, $args['limit'] ?? 20);
        $next = $offset + count($items);

        return ['topic' => $args['topic'], 'from' => $w['from'], 'to' => $w['to'], 'timezone' => $w['timezone'],
            'dateFilterApplied' => !in_array($args['topic'], ['reports', 'constraints', 'weight_goals'], true),
            'records' => $items, 'total' => count($records), 'offset' => $offset, 'nextOffset' => $next < count($records) ? $next : null,
            'snapshot' => $snapshot, 'weightVersion' => $w['weight']['version'], 'healthContextVersion' => $w['healthContext']['version']];
    }

    /** @param array<string, mixed> $args
     * @return array<string, mixed>
     */
    private function details(array $args): array
    {
        $workout = $this->workouts->find($args['workoutId']) ?? throw new TrainingError('运动不存在。', 404);
        $details = [];
        $pages = [];
        foreach ($workout['sources'] as $source) {
            if ('zepp' === $source['source']) {
                $detail = $this->zepp->detail($source['id']);
                unset($detail['sourceDetail'], $detail['sourceSummary']);
                foreach ($detail['streams'] ?? [] as $key => $stream) {
                    if (is_array($stream)) {
                        $pages['zepp.'.$key] = $this->samplePage($stream, $args);
                    }
                }
                unset($detail['streams']);
                $details['zepp'] = $detail;
            } elseif (in_array($workout['sportType'], ['Run', 'TrailRun', 'VirtualRun'], true)) {
                $detail = $this->running->get($source['id']);
                foreach (['timeline', 'scatter'] as $key) {
                    $pages['import.'.$key] = $this->samplePage($detail['analysis'][$key], $args);
                    unset($detail['analysis'][$key]);
                }
                foreach (['laps', 'splits'] as $key) {
                    $pages['import.'.$key] = $this->samplePage($detail[$key], [...$args, 'includeSamples' => true]);
                    unset($detail[$key]);
                }
                $details['import'] = $detail;
            }
        }
        $comparisons = [];
        foreach ($this->training->list('sessions') as $session) {
            if (array_intersect($session['activityIds'], $workout['aliases'])) {
                $comparison = $this->training->comparison($session['id']);
                unset($comparison['candidates']);
                $comparisons[] = $comparison;
            }
        }

        return ['workout' => $workout, 'details' => $details, 'samples' => $pages, 'planComparisons' => $comparisons];
    }

    /** @param array<mixed> $records
     * @param array<string, mixed> $args
     *
     * @return array<string, mixed>
     */
    private function samplePage(array $records, array $args): array
    {
        $offset = $args['offset'] ?? 0;
        $items = ($args['includeSamples'] ?? false) ? array_slice($records, $offset, $args['limit'] ?? 50) : [];
        $next = $offset + count($items);

        return ['total' => count($records), 'included' => $args['includeSamples'] ?? false, 'offset' => $offset, 'records' => $items, 'nextOffset' => $next < count($records) ? $next : null];
    }

    /** @param array<string, mixed> $args
     * @return array<string, mixed>
     */
    private function mutate(string $tool, array $args): array
    {
        $id = TrainingInput::id($args['requestId'] ?? null);
        $fingerprint = self::fingerprint([$tool, $args]);

        return $this->repository->transactional(function () use ($tool, $args, $id, $fingerprint): array {
            if ($receipt = $this->repository->find('mcp-operations', $id)) {
                if (!hash_equals($receipt['fingerprint'], $fingerprint)) {
                    throw new TrainingError('requestId 已用于不同的操作。请核验原操作，为新的修改使用新 ID。', 409);
                }

                return ['requestId' => $id, 'replayed' => true, 'committedAt' => $receipt['updatedAt'], 'result' => $receipt['result']];
            }
            $result = match ($tool) {
                'save_plan' => $this->savePlan($args), 'record_progress' => $this->recordProgress($args),
                'update_health_profile' => $this->updateProfile($args), 'remove_record' => $this->remove($args),
                'resolve_workout_sources' => $this->resolveSources($args), default => throw new TrainingError('未知工具。'),
            };
            $receipt = $this->repository->save('mcp-operations', $id, ['tool' => $tool, 'fingerprint' => $fingerprint, 'reason' => $args['reason'] ?? null, 'result' => $result], 0);

            return ['requestId' => $id, 'replayed' => false, 'committedAt' => $receipt['updatedAt'], 'result' => $result];
        });
    }

    /** @return array<string, mixed> */
    private function operation(string $id): array
    {
        $receipt = $this->repository->find('mcp-operations', TrainingInput::id($id));

        return null === $receipt ? ['found' => false, 'requestId' => $id] : ['found' => true, 'requestId' => $id,
            'tool' => $receipt['tool'], 'committedAt' => $receipt['updatedAt'], 'result' => $receipt['result'],
            'note' => '此为提交时的回执，当前记录可能已被后续操作修改。'];
    }

    /** @param array<string, mixed> $args
     * @return array<string, mixed>
     */
    private function savePlan(array $args): array
    {
        $this->requireChanges($args, ['training', 'nutrition', 'weightGoals']);
        $result = [];
        if (!empty($args['training'])) {
            $result['training'] = $this->training->batch($this->records($args, 'training'));
        }
        foreach (['nutrition' => 'meal-plans', 'weightGoals' => 'weight-goals'] as $key => $resource) {
            if (!empty($args[$key])) {
                $rows = array_map(static fn (array $r): array => [...$r, 'source' => 'ai', 'rationale' => $args['reason']], $this->records($args, $key));
                $result[$key] = $this->data->batch($resource, $rows);
            }
        }

        return $result;
    }

    /** @param array<string, mixed> $args
     * @return array<string, mixed>
     */
    private function recordProgress(array $args): array
    {
        $this->requireChanges($args, ['weight', 'measurements', 'meals', 'bloodPressure', 'checkIns', 'training']);
        $result = [];
        foreach (['measurements', 'meals'] as $key) {
            if (!empty($args[$key])) {
                $result[$key] = $this->data->batch($key, array_map(static fn (array $r): array => [...$r, 'source' => 'user'], $this->records($args, $key)));
            }
        }
        foreach ($this->records($args, 'bloodPressure') as $row) {
            $id = $row['id'];
            unset($row['id']);
            $result['bloodPressure'][] = $this->pressure->save($id, $row);
        }
        foreach ($this->records($args, 'checkIns') as $row) {
            $id = $row['id'];
            unset($row['id']);
            $result['checkIns'][] = $this->training->update('check-ins', $id, $row);
        }
        foreach ($args['training'] ?? [] as $row) {
            $id = $row['id'];
            $version = $row['version'];
            $saved = null;
            $this->requireChanges($row, ['feedback', 'activityIds', 'status'], true);
            if (isset($row['feedback'])) {
                $saved = $this->feedback->save($id, ['version' => $version, 'feedback' => $row['feedback']]);
                $version = $saved['version'];
            }
            $patch = array_intersect_key($row, array_flip(['activityIds', 'status']));
            if ([] !== $patch) {
                $saved = $this->training->update('sessions', $id, ['version' => $version, ...$patch]);
            }
            if (null === $saved) {
                throw new TrainingError('训练实际至少提供反馈、状态或运动关联。');
            }
            $result['training'][] = $saved;
        }
        // Last: settings cache invalidation occurs during this domain write.
        if (isset($args['weight'])) {
            $weight = $args['weight'];
            $date = $weight['date'];
            unset($weight['date']);
            $saved = $this->weights->save($date, $weight);
            $result['weight'] = array_find($saved['records'], static fn (array $r): bool => $r['date'] === $date);
            $result['weightVersion'] = $saved['version'];
        }

        return $result;
    }

    /** @param array<string, mixed> $args
     * @return array<string, mixed>
     */
    private function updateProfile(array $args): array
    {
        $this->requireChanges($args, ['profile', 'healthContext']);
        $result = [];
        if (isset($args['profile'])) {
            $result['profile'] = $this->training->update('profile', 'default', $args['profile']);
        }
        if (isset($args['healthContext'])) {
            $patch = $args['healthContext'];
            $current = $this->context->get();
            foreach (['reports', 'constraints'] as $key) {
                if (isset($patch[$key])) {
                    $merged = array_column($current[$key], null, 'id');
                    $seen = [];
                    foreach ($patch[$key] as $item) {
                        if (isset($seen[$item['id']])) {
                            throw new TrainingError('同一批次不能重复更新相同的报告或限制。');
                        }
                        $seen[$item['id']] = true;
                        $merged[$item['id']] = $item;
                    }
                    $patch[$key] = array_values($merged);
                }
            }
            $result['healthContext'] = $this->context->update($patch);
        }

        return $result;
    }

    /** @param array<string, mixed> $args
     * @return array<string, mixed>
     */
    private function remove(array $args): array
    {
        $type = $args['recordType'];
        $id = $args['id'];
        $version = $args['version'];
        if ('weight' === $type) {
            $this->weights->delete($id, $version);
        } else {
            if (!is_int($version) || $version < 1) {
                throw new TrainingError('删除需要记录当前的正整数 version。');
            }
            match ($type) {
                'blood_pressure' => $this->pressure->delete($id, $version),
                'training', 'check_in' => $this->training->delete('training' === $type ? 'sessions' : 'check-ins', $id, $version),
                default => $this->data->delete(match ($type) {
                    'meal' => 'meals', 'meal_plan' => 'meal-plans', 'weight_goal' => 'weight-goals', 'measurement' => 'measurements',
                    default => throw new TrainingError('recordType 无效。'),
                }, $id, $version),
            };
        }

        return ['deleted' => true, 'recordType' => $type, 'id' => $id];
    }

    /** @param array<string, mixed> $args
     * @return array<string, mixed>
     */
    private function resolveSources(array $args): array
    {
        if ('merge' === $args['action']) {
            return $this->workouts->link($args['zeppId'], ['activityId' => $args['activityId'] ?? null, 'version' => $args['version']]);
        }
        $this->workouts->unlink($args['zeppId'], $args['version']);

        return ['split' => true, 'zeppId' => $args['zeppId']];
    }

    /** @param array<string, mixed> $args
     * @return list<array<string, mixed>>
     */
    private function records(array $args, string $key): array
    {
        $result = [];
        foreach ($args[$key] ?? [] as $index => $row) {
            if (($row['version'] ?? 0) > 0 && !isset($row['id'])) {
                throw new TrainingError('更新已有记录必须提供 id。');
            }
            $result[] = [...$row, 'id' => $row['id'] ?? 'mcp-'.substr(hash('sha256', $args['requestId'].'/'.$key.'/'.$index), 0, 40), 'version' => $row['version'] ?? 0];
        }

        return $result;
    }

    /** @param array<string, mixed> $args
     * @param list<string> $keys
     */
    private function requireChanges(array $args, array $keys, bool $allowEmpty = false): void
    {
        $changes = array_intersect_key($args, array_flip($keys));
        if ([] === $changes || (!$allowEmpty && !array_any($changes, static fn (mixed $v): bool => !empty($v)))) {
            throw new TrainingError('至少提供一项要保存的修改。');
        }
        $count = array_sum(array_map(static fn (mixed $v): int => is_array($v) && array_is_list($v) ? count($v) : 1, $changes));
        if ($count > 100) {
            throw new TrainingError('一次操作最多保存 100 条记录。');
        }
    }

    private function today(): string
    {
        return $this->clock->getCurrentDateTimeImmutable()->setTimezone(new \DateTimeZone($this->training->profile()['timezone']))->format('Y-m-d');
    }

    private static function fingerprint(mixed $data): string
    {
        $normalize = static function (mixed $value) use (&$normalize): mixed {
            if (!is_array($value)) {
                return $value;
            }
            if (!array_is_list($value)) {
                ksort($value);
            }

            return array_map($normalize, $value);
        };

        return hash('sha256', json_encode($normalize($data), JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION));
    }
}
