<?php

declare(strict_types=1);

namespace App\Domain\Training\Notification;

/** Select bounded local-time windows; explicit scheduled sessions override habitual running days. */
final readonly class ReminderPlanner
{
    /**
     * @param array<string, mixed>       $profile
     * @param list<array<string, mixed>> $sessions
     *
     * @return list<array<string, string>>
     */
    public function due(array $profile, array $sessions, \DateTimeImmutable $now): array
    {
        if (true !== ($profile['notificationsEnabled'] ?? false)) {
            return [];
        }
        $zone = new \DateTimeZone($profile['timezone']);
        $localNow = $now->setTimezone($zone);
        [$hour, $minute] = array_map(intval(...), explode(':', $profile['eveningReminderTime']));
        $evening = $localNow->setTime($hour, $minute, 0);
        $tomorrow = $localNow->modify('+1 day')->format('Y-m-d');
        $events = [];
        foreach ($sessions as $session) {
            if ('rest' === $session['type'] || in_array($session['status'], ['skipped', 'cancelled'], true)) {
                continue;
            }
            $start = new \DateTimeImmutable($session['startAt'])->setTimezone($zone);
            $occurrence = $start->setTimezone(new \DateTimeZone('UTC'))->format(DATE_ATOM);
            $add = static function (string $type, \DateTimeImmutable $scheduled, \DateTimeImmutable $expires) use (&$events, $session, $occurrence, $now): void {
                if ($now < $scheduled || $now >= $expires) {
                    return;
                }
                $events[] = ['type' => $type, 'sessionId' => $session['id'], 'occurrence' => $occurrence, 'scheduledAt' => $scheduled->format(DATE_ATOM), 'expiresAt' => $expires->format(DATE_ATOM)];
            };
            if ('completed' === $session['status']) {
                if (null === ($session['feedback'] ?? null)) {
                    $end = $start->setTimestamp($start->getTimestamp() + $session['durationMinutes'] * 60);
                    $add('feedback', $end, $end->setTimestamp($end->getTimestamp() + 86400));
                }
                continue;
            }
            if ('planned' !== $session['status'] || $start <= $now) {
                continue;
            }
            if ($start->format('Y-m-d') === $tomorrow) {
                $add('evening', $evening, $evening->setTimestamp($evening->getTimestamp() + 1800));
            }
            $add('pre_run', $start->setTimestamp($start->getTimestamp() - $profile['preRunReminderMinutes'] * 60), $start);
            $add('weather_change', $start->setTimestamp($start->getTimestamp() - 900), $start);
        }

        return $events;
    }
}
