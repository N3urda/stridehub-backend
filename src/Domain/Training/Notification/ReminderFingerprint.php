<?php

declare(strict_types=1);

namespace App\Domain\Training\Notification;

/** Hash semantic changes, never request timestamps, hour labels or numeric wording in explanations. */
final class ReminderFingerprint
{
    /** @param array<string, mixed> $briefing */
    public static function fromBriefing(array $briefing): string
    {
        $forecast = $briefing['forecast'] ?? [];
        $advice = $briefing['advice'] ?? [];
        $clothing = $advice['clothing'] ?? [];
        $risks = $advice['riskFactors'] ?? [];
        sort($clothing);
        sort($risks);
        $hours = $forecast['sessionHours'] ?? [];
        $temperatures = [];
        $rain = $wind = $gusts = [];
        $missing = [];
        foreach ($hours as $hour) {
            if (null !== $temperature = $hour['apparentTemperature'] ?? $hour['temperature'] ?? null) {
                $temperatures[] = (float) $temperature;
            }
            foreach (['precipitation', 'windSpeed', 'windGusts', 'weatherCode'] as $key) {
                if (null === ($hour[$key] ?? null)) {
                    $missing[$key] = true;
                }
            }
            if (null !== ($hour['precipitation'] ?? null)) {
                $rain[] = (float) $hour['precipitation'];
            }
            if (null !== ($hour['windSpeed'] ?? null)) {
                $wind[] = (float) $hour['windSpeed'];
            }
            if (null !== ($hour['windGusts'] ?? null)) {
                $gusts[] = (float) $hour['windGusts'];
            }
        }
        $missing = array_keys($missing);
        sort($missing);

        return hash('sha256', json_encode([
            'availability' => $forecast['status'] ?? 'unavailable',
            'training' => $advice['training'] ?? 'unknown',
            'clothing' => $clothing, 'risks' => $risks, 'missing' => $missing,
            'coldest' => self::band([] !== $temperatures ? min($temperatures) : null, [0, 5, 12, 18, 28, 32]),
            'warmest' => self::band([] !== $temperatures ? max($temperatures) : null, [0, 5, 12, 18, 28, 32]),
            'rain' => self::band([] !== $rain ? max($rain) : null, [0.49, 7.49]),
            'wind' => self::band([] !== $wind ? max($wind) : null, [19.99, 39.99]),
            'gusts' => self::band([] !== $gusts ? max($gusts) : null, [29.99, 59.99]),
        ], JSON_THROW_ON_ERROR));
    }

    /** @param list<int|float> $boundaries */
    private static function band(?float $value, array $boundaries): int|string
    {
        if (null === $value) {
            return 'unknown';
        }
        foreach ($boundaries as $index => $maximum) {
            if ($value <= $maximum) {
                return $index;
            }
        }

        return count($boundaries);
    }
}
