<?php

declare(strict_types=1);

namespace App\Domain\Training;

use App\Domain\Health\ApplicableHealthConstraints;
use App\Domain\Health\HealthContextService;
use App\Infrastructure\Time\Clock\Clock;

final readonly class TrainingToday
{
    public function __construct(private TrainingService $training, private TrainingRepository $repository, private Clock $clock, private HealthContextService $health)
    {
    }

    /** @return array<string, mixed> */
    public function get(): array
    {
        $profile = $this->training->profile();
        $now = $this->clock->getCurrentDateTimeImmutable();
        $local = $now->setTimezone(new \DateTimeZone($profile['timezone']));
        $date = $local->format('Y-m-d');
        $sessions = $this->training->list('sessions', $date, $date);
        $checkIn = array_find($this->repository->list('check-ins'), static fn (array $record): bool => $record['date'] === $date);
        $health = new ApplicableHealthConstraints($this->health)->forDate($date);
        $pendingFeedback = array_values(array_filter($this->training->list('sessions', $local->modify('-6 days')->format('Y-m-d'), $date, 'completed'), static fn (array $session): bool => null === ($session['feedback'] ?? null) && strtotime($session['startAt']) <= $now->getTimestamp()));
        $quality = ['运动完成情况需要已导入的数据和明确关联；缺少记录不代表未运动或恢复良好。'];
        if ([] === $sessions) {
            $quality[] = '今天尚无已保存的课表，未自动生成训练或将其视为休息日。';
        }
        if (null === $checkIn) {
            $quality[] = '尚未填写今日身体状态，睡眠、疲劳、酸痛和疼痛均保持未知。';
        } else {
            foreach (['sleepHours' => '睡眠时长', 'fatigue' => '疲劳', 'soreness' => '酸痛', 'pain' => '疼痛'] as $field => $label) {
                if (null === ($checkIn[$field] ?? null)) {
                    $quality[] = '今日'.$label.'尚未填写，不能据此判断正常。';
                }
            }
        }
        if (0 === $health['version']) {
            $quality[] = '尚未保存健康背景，不能将空档案视作已获运动许可。';
        }

        return ['date' => $date, 'timezone' => $profile['timezone'], 'generatedAt' => $now->format(DATE_ATOM), 'sessions' => $sessions, 'checkIn' => $checkIn, 'health' => $health, 'pendingFeedback' => array_reverse($pendingFeedback), 'reconciliation' => $this->training->reconciliation(), 'dataQuality' => $quality];
    }
}
