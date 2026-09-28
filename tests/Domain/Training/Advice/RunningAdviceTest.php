<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training\Advice;

use App\Domain\Training\Advice\RunningAdvice;
use PHPUnit\Framework\TestCase;

class RunningAdviceTest extends TestCase
{
    public function testUsesWorstConditionsDuringEntireRunAndExplainsIntensity(): void
    {
        $forecast = $this->forecast([['apparentTemperature' => 23], ['apparentTemperature' => 34, 'humidity' => 85]]);
        $advice = $this->engine()->advise($this->session(['durationMinutes' => 120, 'type' => 'interval']), [], $forecast);
        self::assertSame('consider_easier', $advice['training']);
        self::assertStringContainsString('34', implode(' ', $advice['reasons']));
        self::assertStringContainsString('间歇', implode(' ', $advice['reasons']));
        self::assertStringContainsString('透气', implode(' ', $advice['clothing']));
    }

    public function testLowRainProbabilityAloneNeverBecomesSevereRain(): void
    {
        $advice = $this->engine()->advise($this->session(), [], $this->forecast([['precipitationProbability' => 20, 'precipitation' => 0]]));
        self::assertSame('keep', $advice['training']);
        self::assertStringNotContainsString('强降雨', implode(' ', $advice['warnings']));
        self::assertSame([], $advice['alternatives']);
    }

    public function testColdWindAndDarknessChangeClothingWithReasons(): void
    {
        $advice = $this->engine()->advise($this->session(), [], $this->forecast([['apparentTemperature' => 1, 'windSpeed' => 28, 'isDay' => false]]));
        self::assertStringContainsString('保暖', implode(' ', $advice['clothing']));
        self::assertStringContainsString('防风', implode(' ', $advice['clothing']));
        self::assertStringContainsString('反光', implode(' ', $advice['clothing']));
        self::assertStringContainsString('28', implode(' ', $advice['reasons']));
    }

    public function testThunderstormOverridesComfortPreferenceAndSuggestsOnlyVerifiedBetterWindows(): void
    {
        $forecast = $this->forecast([['weatherCode' => 95], ['weatherCode' => 95], [], []]);
        $advice = $this->engine()->advise($this->session(), ['thermalPreference' => 'warm'], $forecast);
        self::assertSame('indoor_or_reschedule', $advice['training']);
        self::assertStringContainsString('雷暴', implode(' ', $advice['warnings']));
        self::assertCount(1, $advice['alternatives']);
        self::assertSame('2026-09-27T10:00:00+08:00', $advice['alternatives'][0]['startAt']);
        self::assertNotEmpty($advice['alternatives'][0]['reasons']);
    }

    public function testDoesNotSuggestAlternateWindowsWhenCoverageIsMissingOrPast(): void
    {
        $forecast = $this->forecast([['weatherCode' => 95]]);
        $forecast['hours'][] = $this->hour('2026-09-27T06:00:00+08:00');
        $advice = $this->engine()->advise($this->session(['durationMinutes' => 120]), [], $forecast);
        self::assertSame([], $advice['alternatives']);
        self::assertSame('unknown', $advice['training']);
    }

    public function testThermalPreferenceAndActualFeedbackPersonalizeClothingTransparently(): void
    {
        $engine = $this->engine();
        $forecast = $this->forecast([['apparentTemperature' => 16]]);
        $neutral = $engine->advise($this->session(), ['thermalPreference' => 'neutral'], $forecast);
        $cold = $engine->advise($this->session(), ['thermalPreference' => 'cold'], $forecast, null, [['thermalFeeling' => 'cold'], ['thermalFeeling' => 'cold']]);
        self::assertNotSame($neutral['clothing'], $cold['clothing']);
        self::assertStringContainsString('怕冷', implode(' ', $cold['personalization']));
        self::assertStringContainsString('2', implode(' ', $cold['personalization']));
    }

    public function testCheckInCanEaseTrainingWithoutInventingAReadinessScore(): void
    {
        $advice = $this->engine()->advise($this->session(['type' => 'tempo']), [], $this->forecast([[]]), ['sleepHours' => 4.5, 'fatigue' => 4, 'soreness' => 2, 'pain' => false]);
        self::assertSame('consider_easier', $advice['training']);
        self::assertStringContainsString('睡眠', implode(' ', $advice['reasons']));
        self::assertArrayNotHasKey('readinessScore', $advice);
    }

    public function testUnavailableWeatherExplainsMissingInputsWithoutInventingForecast(): void
    {
        $advice = $this->engine()->advise($this->session(), [], ['status' => 'unavailable', 'hours' => [], 'message' => '暂时无法获取预报。']);
        self::assertSame('unknown', $advice['training']);
        self::assertSame([], $advice['alternatives']);
        self::assertNotEmpty($advice['dataQuality']);
        self::assertStringContainsString('跑前', implode(' ', $advice['dataQuality']));
    }

    public function testMissingWeatherMetricsAreDisclosedAndPainIsNotAWeatherReschedule(): void
    {
        $advice = $this->engine()->advise($this->session(), [], $this->forecast([['precipitation' => null, 'uvIndex' => null]]), ['sleepHours' => 8, 'fatigue' => 1, 'soreness' => 1, 'pain' => true]);
        self::assertSame('consider_easier', $advice['training']);
        self::assertStringContainsString('疼痛', implode(' ', $advice['warnings']));
        self::assertStringContainsString('降水量', implode(' ', $advice['dataQuality']));
        self::assertSame([], $advice['alternatives']);
    }

    public function testRestDayDoesNotBecomeAnOutdoorTrainingRecommendation(): void
    {
        $advice = $this->engine()->advise($this->session(['type' => 'rest']), [], $this->forecast([['weatherCode' => 95]]));
        self::assertSame('keep', $advice['training']);
        self::assertSame([], $advice['clothing']);
        self::assertSame([], $advice['alternatives']);
        self::assertStringContainsString('休息', $advice['summary']);
    }

    public function testAlternativeWindowsUseTheLocationOffsetAfterDaylightSavingChanges(): void
    {
        $forecast = $this->forecast([]);
        $forecast['timezone'] = 'America/New_York';
        $forecast['requestedAt'] = '2026-11-01T00:00:00-04:00';
        $forecast['hours'] = [
            array_replace($this->hour('2026-11-01T00:00:00-04:00'), ['weatherCode' => 95]),
            array_replace($this->hour('2026-11-01T01:00:00-04:00'), ['weatherCode' => 95]),
            $this->hour('2026-11-01T01:00:00-05:00'),
            $this->hour('2026-11-01T02:00:00-05:00'),
        ];
        $advice = $this->engine()->advise($this->session(['startAt' => '2026-11-01T00:00:00-04:00']), [], $forecast);
        self::assertSame('2026-11-01T01:00:00-05:00', $advice['alternatives'][0]['startAt']);
    }

    public function testStableRiskFactorsSupportMeaningfulReminderChanges(): void
    {
        $advice = $this->engine()->advise($this->session(), [], $this->forecast([['precipitation' => 8.5, 'weatherCode' => 95]]), ['sleepHours' => 4, 'fatigue' => 4, 'soreness' => 1, 'pain' => true]);
        self::assertSame('indoor_or_reschedule', $advice['training']);
        self::assertArrayHasKey('riskFactors', $advice);
        self::assertContains('heavy_rain', $advice['riskFactors']);
        self::assertContains('storm', $advice['riskFactors']);
        self::assertContains('pain', $advice['riskFactors']);
        self::assertContains('fatigue', $advice['riskFactors']);
        self::assertContains('poor_sleep', $advice['riskFactors']);
        self::assertSame([], $advice['alternatives']);
        self::assertStringContainsString('疼痛', $advice['summary']);
    }

    private function engine(): RunningAdvice
    {
        self::assertTrue(class_exists(RunningAdvice::class), 'Explainable running advice is not implemented.');

        return new RunningAdvice();
    }

    private function session(array $changes = []): array
    {
        return array_replace(['title' => '轻松跑', 'startAt' => '2026-09-27T08:00:00+08:00', 'durationMinutes' => 60, 'distanceKm' => 8, 'type' => 'easy'], $changes);
    }

    private function forecast(array $changes): array
    {
        $hours = [];
        foreach ($changes as $index => $change) {
            $hours[] = array_replace($this->hour(sprintf('2026-09-27T%02d:00:00+08:00', 8 + $index)), $change);
        }

        return ['status' => 'available', 'source' => 'Open-Meteo', 'fetchedAt' => '2026-09-27T07:00:00+08:00', 'requestedAt' => '2026-09-27T07:00:00+08:00', 'timezone' => 'Asia/Shanghai', 'hours' => $hours];
    }

    private function hour(string $time): array
    {
        return ['time' => $time, 'temperature' => 18.0, 'apparentTemperature' => 18.0, 'humidity' => 60.0, 'precipitationProbability' => 10.0, 'precipitation' => 0.0, 'windSpeed' => 8.0, 'windGusts' => 12.0, 'weatherCode' => 1, 'uvIndex' => 2.0, 'isDay' => true];
    }
}
