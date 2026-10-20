<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingService;
use App\Infrastructure\Time\Clock\Clock;

final readonly class HealthWorkspace
{
    public function __construct(
        private HealthDataService $data,
        private WeightRecordService $weights,
        private TrainingService $training,
        private WorkoutCatalog $workouts,
        private HealthContextService $context,
        private SleepService $sleep,
        private BloodPressureService $pressure,
        private ZeppDataService $zepp,
        private HealthPlanService $plans,
        private Clock $clock,
    ) {
    }

    /** @return array<string, mixed> */
    public function read(?string $from, ?string $to): array
    {
        $profile = $this->training->profile();
        $zone = new \DateTimeZone($profile['timezone']);
        $today = $this->clock->getCurrentDateTimeImmutable()->setTimezone($zone)->format('Y-m-d');
        $to ??= $today;
        HealthDataService::range($from, $to);
        $from ??= new \DateTimeImmutable($to)->modify('-29 days')->format('Y-m-d');
        if (new \DateTimeImmutable($from)->diff(new \DateTimeImmutable($to))->days > 365) {
            throw new TrainingError('单次读取最多 366 天。');
        }
        $weights = $this->weights->list(null, min($to, $today));
        $weightRows = array_values(array_filter($weights['records'], static fn (array $r): bool => $r['date'] >= $from));
        $plans = $this->data->list('meal-plans', $from, $to);
        $meals = $this->data->list('meals', $from, $to);
        $days = [];
        for ($date = $from; $date <= $to; $date = new \DateTimeImmutable($date)->modify('+1 day')->format('Y-m-d')) {
            $logs = array_values(array_filter($meals, static fn (array $r): bool => $r['date'] === $date));
            $plan = array_find($plans, static fn (array $r): bool => $r['date'] === $date && 'active' === $r['status']);
            $actual = $known = $coverage = $delta = [];
            foreach (HealthDataInput::NUTRIENTS as $key) {
                $values = array_values(array_filter(array_column(array_column($logs, 'nutrients'), $key), static fn (mixed $v): bool => null !== $v));
                $coverage[$key] = count($values);
                $known[$key] = [] === $values ? null : round(array_sum($values), 2);
                $actual[$key] = [] !== $logs && count($values) === count($logs) ? $known[$key] : null;
                $target = $plan['targets'][$key] ?? null;
                $delta[$key] = null === $actual[$key] || null === $target ? null : round($actual[$key] - $target, 2);
            }
            $days[] = ['date' => $date, 'mealCount' => count($logs), 'targets' => $plan['targets'] ?? HealthDataInput::emptyNutrients(), 'actualLogged' => $actual,
                'knownSubtotal' => $known, 'coverage' => $coverage, 'delta' => $delta, 'planId' => $plan['id'] ?? null,
                'estimatedMeals' => count(array_filter($logs, static fn (array $r): bool => 'estimate' === $r['nutritionSource']))];
        }
        $trends = [];
        foreach (array_reverse($weightRows) as $row) {
            $start = new \DateTimeImmutable($row['date'])->modify('-6 days')->format('Y-m-d');
            $window = array_filter($weights['records'], static fn (array $r): bool => $r['date'] >= $start && $r['date'] <= $row['date']);
            $trends[] = [...$row, 'average7DaysKg' => round(array_sum(array_column($window, 'weightKg')) / count($window), 3), 'sampleDays' => count($window)];
        }
        $goals = $this->data->list('weight-goals');
        foreach ($goals as &$goal) {
            $eligible = array_values(array_filter($weights['records'], static fn (array $r): bool => $r['date'] >= $goal['startDate']));
            $latest = $eligible[0] ?? null;
            $goal['progress'] = ['measuredAt' => $latest['date'] ?? null, 'currentWeightKg' => $latest['weightKg'] ?? null,
                'changeKg' => null === $latest ? null : round($latest['weightKg'] - $goal['startWeightKg'], 3),
                'remainingKg' => null === $latest ? null : round($latest['weightKg'] - $goal['targetWeightKg'], 3),
                'percent' => null === $latest ? null : round(($goal['startWeightKg'] - $latest['weightKg']) / ($goal['startWeightKg'] - $goal['targetWeightKg']) * 100, 1)];
        }
        unset($goal);
        $sleep = $this->sleep->list($from, $to);
        $daily = $this->zepp->list($from, $to);
        $workouts = $this->workouts->overview($from, $to, $zone);

        return ['from' => $from, 'to' => $to, 'today' => $today, 'timezone' => $zone->getName(), 'generatedAt' => $this->clock->getCurrentDateTimeImmutable()->format(DATE_ATOM),
            'profile' => $profile, 'healthContext' => $this->context->get(), 'sleep' => $sleep, 'daily' => $daily,
            'bloodPressure' => $this->pressure->list($from, $to), 'measurements' => $this->data->list('measurements', $from, $to),
            'weight' => ['records' => $weightRows, 'version' => $weights['version'], 'unit' => 'kg', 'trend' => $trends, 'goals' => $goals],
            'nutrition' => ['days' => $days, 'plans' => $plans, 'meals' => $meals, 'methodology' => 'actualLogged 仅表示已记录餐次，不代表全天摄入。任一餐缺失该营养值时合计为 null，已知小计单独保留。补给仅在 meals 中记录一次时计入，旧 fuel-logs 不重复累计。'],
            'workouts' => $workouts, 'sessions' => $this->training->list('sessions', $from, $to),
            'checkIns' => $this->training->list('check-ins', $from, $to), 'healthPlans' => $this->plans->list(),
            'coverage' => ['sleepDays' => count($sleep), 'deviceDays' => count($daily), 'weightDays' => count($weightRows), 'meals' => count($meals), 'pendingWorkoutDuplicates' => $workouts['pendingDuplicates']],
            'sync' => $this->zepp->status(),
        ];
    }
}
