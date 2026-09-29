<?php

declare(strict_types=1);

namespace App\Domain\Training\Notification;

use App\Domain\Settings\SettingsRepository;
use App\Domain\Training\TrainingBriefing;
use App\Domain\Training\TrainingService;
use App\Infrastructure\Time\Clock\Clock;

final readonly class TrainingReminderService
{
    public function __construct(
        private TrainingService $training,
        private TrainingBriefing $briefing,
        private SettingsRepository $settings,
        private ReminderPlanner $planner,
        private ReminderDelivery $delivery,
        private Clock $clock,
    ) {
    }

    /** @return array<string, mixed> */
    public function run(bool $dryRun = false): array
    {
        $profile = $this->training->profile();
        $result = ['dryRun' => $dryRun, 'status' => 'ok', 'sent' => 0, 'failed' => 0, 'skipped' => 0, 'previews' => []];
        if (true !== $profile['notificationsEnabled']) {
            return [...$result, 'status' => 'disabled'];
        }
        $now = $this->clock->getCurrentDateTimeImmutable();
        $zone = new \DateTimeZone($profile['timezone']);
        $sessions = $this->training->list('sessions');
        // Include today's and tomorrow's habit without materializing or saving a planned session.
        $cursor = $now;
        $lastDate = $now->setTimezone($zone)->modify('+1 day')->format('Y-m-d');
        for ($count = 0; $count < 2; ++$count) {
            $habit = $this->briefing->habitualSession($profile, $cursor);
            if (null === $habit) {
                break;
            }
            $start = new \DateTimeImmutable($habit['startAt']);
            $date = $start->setTimezone($zone)->format('Y-m-d');
            if ($date > $lastDate) {
                break;
            }
            $sessions[] = [...$habit, 'id' => 'habit-'.$date];
            $cursor = $start->modify('+1 second');
        }
        $sessionsById = array_column($sessions, null, 'id');
        $channels = iterator_to_array($this->settings->integrations()->getConfiguredNotificationUrls(), false);
        foreach ($this->planner->due($profile, $sessions, $now) as $event) {
            $session = $sessionsById[$event['sessionId']];
            $briefing = 'feedback' === $event['type'] ? [] : $this->briefing->generateForSession($session);
            [$title, $message] = $this->message($event['type'], $session, $briefing, $zone);
            $deliveries = $this->delivery->deliver($event, $channels, $title, $message, ReminderFingerprint::fromBriefing($briefing), $dryRun);
            foreach ($deliveries as $delivery) {
                if ('sent' === $delivery['status']) {
                    ++$result['sent'];
                } elseif ('failed' === $delivery['status']) {
                    ++$result['failed'];
                } elseif ('preview' !== $delivery['status']) {
                    ++$result['skipped'];
                }
            }
            $result['previews'][] = ['type' => $event['type'], 'sessionId' => $event['sessionId'], 'expiresAt' => $event['expiresAt'], 'title' => $title, 'message' => $message, 'deliveries' => $deliveries];
        }
        if ([] === $channels) {
            $result['status'] = 'no_channels';
        }

        return $result;
    }

    /**
     * @param array<string, mixed> $session
     * @param array<string, mixed> $briefing
     *
     * @return array{string, string}
     */
    private function message(string $type, array $session, array $briefing, \DateTimeZone $zone): array
    {
        $title = match ($type) {
            'evening' => '明日跑步预览',
            'pre_run' => '跑前提醒',
            'weather_change' => '临出门建议有变化',
            'feedback' => '记录跑后体感',
            default => throw new \InvalidArgumentException('Unknown training reminder type.'),
        };
        $start = new \DateTimeImmutable($session['startAt'])->setTimezone($zone)->format('m-d H:i');
        $lines = [sprintf('%s · %s（%s）· %d 分钟', $session['title'], $start, $zone->getName(), $session['durationMinutes'])];
        if ('feedback' === $type) {
            $lines[] = '这节训练已标记完成，请补充跑后体感、实际穿衣和补给体验，供后续建议参考。';

            return [$title, implode("\n", $lines)];
        }
        if ('weather_change' === $type) {
            $lines[] = '与已送达的跑前提醒相比，天气条件或训练建议有明显变化。';
        }
        if ('available' !== ($briefing['forecast']['status'] ?? null)) {
            $lines[] = '天气暂不可用，不据此判断适合户外跑步；出门前查看当地实况与预警。';
        }
        if (isset($briefing['advice']['summary'])) {
            $lines[] = $briefing['advice']['summary'];
        }
        foreach (['clothing', 'warnings'] as $field) {
            foreach ($briefing['advice'][$field] ?? [] as $line) {
                $lines[] = $line;
            }
        }
        $lines[] = '可打开跑步助手查看完整依据、课表与调整选项。';

        return [$title, implode("\n", $lines)];
    }
}
