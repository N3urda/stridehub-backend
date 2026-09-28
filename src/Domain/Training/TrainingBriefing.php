<?php

declare(strict_types=1);

namespace App\Domain\Training;

use App\Domain\Health\ApplicableHealthConstraints;
use App\Domain\Health\HealthContextService;
use App\Domain\Training\Advice\RunningAdvice;
use App\Domain\Training\Weather\ForecastProvider;
use App\Infrastructure\Time\Clock\Clock;

final readonly class TrainingBriefing
{
    public function __construct(private TrainingService $training, private TrainingRepository $repository, private ForecastProvider $weather, private RunningAdvice $advice, private Clock $clock, private ?HealthContextService $health = null)
    {
    }

    /** @param array<string, mixed>|null $sessionOverride Trusted internally generated habitual session
     * @return array<string, mixed>
     */
    public function generate(?string $sessionId = null, ?array $sessionOverride = null): array
    {
        $profile = $this->training->profile();
        $now = $this->clock->getCurrentDateTimeImmutable();
        $zone = new \DateTimeZone($profile['timezone']);
        $session = null;
        if (null !== $sessionOverride) {
            $session = $sessionOverride;
        } elseif (null !== $sessionId) {
            $session = $this->training->get('sessions', $sessionId);
        } else {
            foreach ($this->training->list('sessions', status: 'planned') as $candidate) {
                if (strtotime($candidate['startAt']) + $candidate['durationMinutes'] * 60 >= $now->getTimestamp()) {
                    $session = $candidate;
                    break;
                }
            }
            $habit = $this->habitualSession($profile, $now);
            if (null !== $habit && (null === $session || strtotime($habit['startAt']) < strtotime($session['startAt']))) {
                $session = $habit;
            }
        }
        $forecast = ['status' => 'unavailable', 'source' => 'Open-Meteo', 'fetchedAt' => null, 'timezone' => $profile['timezone'], 'hours' => [], 'sessionHours' => [], 'message' => '尚未配置跑步地点，无法查询天气。'];
        $healthDate = null === $session ? $now->setTimezone($zone)->format('Y-m-d') : new \DateTimeImmutable($session['startAt'])->setTimezone($zone)->format('Y-m-d');
        $health = new ApplicableHealthConstraints($this->health ?? new HealthContextService($this->repository))->forDate($healthDate);
        $result = ['session' => $session, 'forecast' => $forecast, 'advice' => ['summary' => '添加下一次训练或设置每周跑步习惯。', 'clothing' => [], 'reasons' => [], 'warnings' => [], 'alternatives' => [], 'training' => 'unknown', 'personalization' => [], 'dataQuality' => []], 'checkIn' => null, 'health' => $health, 'generatedAt' => $now->format(DATE_ATOM)];
        if (null === $session) {
            return [...$result, 'advice' => $this->withHealth($result['advice'], $health)];
        }
        $start = new \DateTimeImmutable($session['startAt']);
        $location = $session['location'] ?? $profile['location'];
        if (null !== $location && 'rest' !== $session['type']) {
            $forecast = $this->weather->forecast((float) $location['latitude'], (float) $location['longitude'], $profile['timezone'], $start, $session['durationMinutes']);
        }
        // A current self-report must never masquerade as the condition on a future day.
        $trainingDay = $start->setTimezone($zone)->format('Y-m-d');
        $today = $now->setTimezone($zone)->format('Y-m-d');
        $checkIn = null;
        if ($trainingDay === $today) {
            foreach ($this->repository->list('check-ins') as $record) {
                if ($record['date'] === $trainingDay) {
                    $checkIn = $record;
                }
            }
        }
        $feedback = array_values(array_filter($this->training->list('sessions'), static fn (array $row): bool => 'completed' === $row['status'] && null !== $row['feedback'] && strtotime($row['startAt']) <= $now->getTimestamp()));

        return [...$result, 'forecast' => $forecast, 'checkIn' => $checkIn, 'advice' => $this->withHealth($this->advice->advise($session, $profile, $forecast, $checkIn, array_slice($feedback, -20)), $health)];
    }

    /** @param array<string, mixed> $advice
     * @param array{version: int, constraints: list<array<string, mixed>>} $health
     *
     * @return array<string, mixed>
     */
    private function withHealth(array $advice, array $health): array
    {
        if ([] === $health['constraints']) {
            return $advice;
        }
        $advice['conditionsTraining'] = $advice['training'];
        $advice['training'] = 'review_constraints';
        $priorWarning = in_array($advice['conditionsTraining'], ['keep', 'unknown'], true) ? '' : $advice['summary'].' ';
        $advice['summary'] = $priorWarning.'本次日期存在有效健康或个人约束，请先核对原文与本次安排，再决定是否执行训练。';
        $advice['warnings'][] = '系统仅展示已记录的约束，不解释医学含义；天气、穿衣和改期信息不代表约束已获确认。';
        $advice['dataQuality'][] = '复核日期经过不会自动解除约束；请结合来源和后续意见核对。';

        return $advice;
    }

    /** @param array<string, mixed> $session
     * @return array<string, mixed>
     */
    public function generateForSession(array $session): array
    {
        return $this->generate(sessionOverride: $session);
    }

    /** @param array<string, mixed> $profile
     * @return array<string, mixed>|null
     */
    public function habitualSession(array $profile, \DateTimeImmutable $now): ?array
    {
        $localNow = $now->setTimezone(new \DateTimeZone($profile['timezone']));
        $occupied = array_map(static fn (array $row): string => new \DateTimeImmutable($row['startAt'])->setTimezone($localNow->getTimezone())->format('Y-m-d'), $this->repository->list('sessions'));
        for ($day = 0; $day <= 7; ++$day) {
            $date = $localNow->modify('+'.$day.' days');
            if (!in_array((int) $date->format('N'), $profile['runningDays'], true) || in_array($date->format('Y-m-d'), $occupied, true)) {
                continue;
            }
            $start = new \DateTimeImmutable($date->format('Y-m-d').' '.$profile['usualStartTime'], $localNow->getTimezone());
            if ($start <= $now) {
                continue;
            }

            return [...TrainingInput::defaults('sessions'), 'title' => '按习惯安排的轻松跑', 'startAt' => $start->format(DATE_ATOM), 'durationMinutes' => $profile['usualDurationMinutes'], 'type' => 'easy', 'habitual' => true];
        }

        return null;
    }
}
