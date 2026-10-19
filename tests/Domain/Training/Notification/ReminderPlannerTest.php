<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training\Notification;

use App\Domain\Training\Notification\ReminderPlanner;
use App\Domain\Training\TrainingInput;
use PHPUnit\Framework\TestCase;

final class ReminderPlannerTest extends TestCase
{
    public function testEveningUsesProfileTimezoneAndHasNoLateCatchUp(): void
    {
        $session = $this->session('2026-10-04T06:30:00+08:00');
        $events = $this->due([$session], '2026-10-03T12:05:00Z');
        self::assertSame(['evening'], array_column($events, 'type'));
        self::assertSame('2026-10-03T20:30:00+08:00', $events[0]['expiresAt']);
        self::assertSame([], $this->due([$session], '2026-10-03T12:30:00Z'));
        self::assertSame([], $this->due([$session], '2026-10-04T04:05:00Z'));
    }

    public function testPreRunRemainsDueUntilStartAndNeverAfterStart(): void
    {
        $session = $this->session('2026-10-04T06:30:00+08:00');
        self::assertSame([], $this->due([$session], '2026-10-04T05:29:00+08:00'));
        self::assertSame(['pre_run'], array_column($this->due([$session], '2026-10-04T05:30:00+08:00'), 'type'));
        self::assertSame(['pre_run', 'weather_change'], array_column($this->due([$session], '2026-10-04T06:25:00+08:00'), 'type'));
        self::assertSame([], $this->due([$session], '2026-10-04T06:30:00+08:00'));
    }

    public function testOptOutRestCancelledAndSkippedNeverNotify(): void
    {
        $session = $this->session('2026-10-04T06:30:00+08:00');
        foreach ([['notificationsEnabled' => false]] as $profile) {
            self::assertSame([], $this->due([$session], '2026-10-04T05:35:00+08:00', $profile));
        }
        foreach ([['type' => 'rest'], ['status' => 'cancelled'], ['status' => 'skipped']] as $change) {
            self::assertSame([], $this->due([array_replace($session, $change)], '2026-10-04T05:35:00+08:00'));
        }
    }

    public function testCompletedWithoutFeedbackPromptsOnlyAfterEndAndWithinOneDay(): void
    {
        $session = [...$this->session('2026-10-04T06:30:00+08:00'), 'status' => 'completed'];
        self::assertSame([], $this->due([$session], '2026-10-04T07:00:00+08:00'));
        self::assertSame(['feedback'], array_column($this->due([$session], '2026-10-04T07:35:00+08:00'), 'type'));
        self::assertSame([], $this->due([$session], '2026-10-05T07:30:00+08:00'));
        self::assertSame([], $this->due([[...$session, 'feedback' => ['effort' => 4]]], '2026-10-04T07:35:00+08:00'));
    }

    public function testDifferentStartTimesHaveDifferentOccurrenceKeysAndLocalTomorrowRespectsDst(): void
    {
        $first = $this->due([$this->session('2026-10-25T06:30:00+01:00')], '2026-10-24T20:05:00+02:00', ['timezone' => 'Europe/Brussels']);
        $second = $this->due([$this->session('2026-10-25T07:30:00+01:00')], '2026-10-24T20:05:00+02:00', ['timezone' => 'Europe/Brussels']);
        self::assertCount(1, $first);
        self::assertNotSame($first[0]['occurrence'], $second[0]['occurrence']);
    }

    public function testSpringForwardPreRunLeadUsesElapsedMinutes(): void
    {
        $session = $this->session('2026-03-29T03:30:00+02:00');
        $events = $this->due([$session], '2026-03-29T01:45:00+01:00', ['timezone' => 'Europe/Brussels']);
        self::assertSame(['pre_run'], array_column($events, 'type'));
        self::assertSame('2026-03-29T01:30:00+01:00', $events[0]['scheduledAt']);
    }

    public function testFallBackSessionEndAndFeedbackExpiryUseElapsedTime(): void
    {
        $session = [...$this->session('2026-10-25T02:30:00+02:00'), 'status' => 'completed'];
        $events = $this->due([$session], '2026-10-25T02:40:00+01:00', ['timezone' => 'Europe/Brussels']);
        self::assertSame(['feedback'], array_column($events, 'type'));
        self::assertSame('2026-10-25T02:30:00+01:00', $events[0]['scheduledAt']);
        self::assertSame('2026-10-26T02:30:00+01:00', $events[0]['expiresAt']);
    }

    public function testFeedbackWindowIsTwentyFourElapsedHoursAcrossSpringForward(): void
    {
        $session = [...$this->session('2026-03-28T02:30:00+01:00'), 'status' => 'completed'];
        $events = $this->due([$session], '2026-03-28T04:00:00+01:00', ['timezone' => 'Europe/Brussels']);
        self::assertSame('2026-03-29T04:30:00+02:00', $events[0]['expiresAt']);
    }

    private function due(array $sessions, string $now, array $profile = []): array
    {
        return new ReminderPlanner()->due(array_replace(TrainingInput::defaults('profile'), ['notificationsEnabled' => true], $profile), $sessions, new \DateTimeImmutable($now));
    }

    private function session(string $startAt): array
    {
        return ['id' => 'run-one', 'startAt' => $startAt, 'durationMinutes' => 60, 'status' => 'planned', 'type' => 'easy', 'feedback' => null];
    }
}
