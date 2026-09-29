<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training\Notification;

use App\Domain\Training\Notification\ReminderFingerprint;
use PHPUnit\Framework\TestCase;

final class ReminderFingerprintTest extends TestCase
{
    public function testTimestampsHourlyOrderAndSmallNumericDriftDoNotTriggerChanges(): void
    {
        $one = $this->briefing();
        $two = $one;
        $two['generatedAt'] = '2026-10-04T06:20:00+08:00';
        $two['forecast']['fetchedAt'] = '2026-10-04T06:20:00+08:00';
        $two['forecast']['sessionHours'][0]['time'] = '2026-10-04T07:00:00+08:00';
        $two['forecast']['sessionHours'][0]['apparentTemperature'] = 22.6;
        $two['advice']['reasons'] = ['体感约22.6°C'];
        $two['advice']['alternatives'] = [['startAt' => '2026-10-04T08:00:00+08:00']];
        self::assertSame(ReminderFingerprint::fromBriefing($one), ReminderFingerprint::fromBriefing($two));
        $two['forecast']['sessionHours'][] = $two['forecast']['sessionHours'][0];
        self::assertSame(ReminderFingerprint::fromBriefing($one), ReminderFingerprint::fromBriefing($two));
    }

    public function testSignificantWeatherAdviceOrAvailabilityChangeAffectsFingerprint(): void
    {
        $original = $this->briefing();
        foreach (['weather', 'advice', 'availability'] as $change) {
            $new = $original;
            if ('weather' === $change) {
                $new['forecast']['sessionHours'][0]['precipitation'] = 12;
            } elseif ('advice' === $change) {
                $new['advice']['training'] = 'indoor_or_reschedule';
                $new['advice']['riskFactors'] = ['storm'];
            } else {
                $new['forecast']['status'] = 'unavailable';
            }
            self::assertNotSame(ReminderFingerprint::fromBriefing($original), ReminderFingerprint::fromBriefing($new));
        }
    }

    private function briefing(): array
    {
        return ['generatedAt' => '2026-10-04T05:30:00+08:00', 'forecast' => ['status' => 'available', 'fetchedAt' => '2026-10-04T05:00:00+08:00', 'sessionHours' => [['time' => '2026-10-04T06:00:00+08:00', 'apparentTemperature' => 22, 'temperature' => 22, 'precipitation' => 0, 'windSpeed' => 10, 'windGusts' => 15, 'weatherCode' => 0]]], 'advice' => ['training' => 'keep', 'clothing' => ['轻薄透气'], 'riskFactors' => [], 'reasons' => ['体感约22°C']]];
    }
}
