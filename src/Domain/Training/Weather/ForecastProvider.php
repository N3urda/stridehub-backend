<?php

declare(strict_types=1);

namespace App\Domain\Training\Weather;

use App\Infrastructure\Time\Clock\Clock;
use GuzzleHttp\Client;
use Symfony\Contracts\Cache\CacheInterface;
use Symfony\Contracts\Cache\ItemInterface;

/** Future forecasts are intentionally independent of imported activities' historical weather. */
final readonly class ForecastProvider
{
    private const string ENDPOINT = 'https://api.open-meteo.com/v1/forecast';
    private const array METRICS = [
        'temperature_2m' => ['temperature', -100, 65],
        'apparent_temperature' => ['apparentTemperature', -120, 90],
        'relative_humidity_2m' => ['humidity', 0, 100],
        'precipitation_probability' => ['precipitationProbability', 0, 100],
        'precipitation' => ['precipitation', 0, 1000],
        'wind_speed_10m' => ['windSpeed', 0, 500],
        'wind_gusts_10m' => ['windGusts', 0, 500],
        'weather_code' => ['weatherCode', 0, 99],
        'uv_index' => ['uvIndex', 0, 30],
        'is_day' => ['isDay', 0, 1],
    ];

    public function __construct(
        private Client $client,
        private CacheInterface $cache,
        private Clock $clock,
    ) {
    }

    /** @return array<string, mixed> */
    public function forecast(float $latitude, float $longitude, string $timezone, \DateTimeImmutable $start, int $durationMinutes): array
    {
        $now = $this->clock->getCurrentDateTimeImmutable();
        $result = [
            'status' => 'unavailable',
            'source' => 'Open-Meteo',
            'fetchedAt' => null,
            'requestedAt' => $now->format(DATE_ATOM),
            'timezone' => $timezone,
            'hours' => [],
            'sessionHours' => [],
            'message' => '暂时无法获取完整天气预报，请在跑前查看当地实况。',
        ];
        if (!is_finite($latitude) || !is_finite($longitude) || abs($latitude) > 90 || abs($longitude) > 180 || $durationMinutes < 1 || $durationMinutes > 1440) {
            return array_replace($result, ['message' => '地点坐标或训练时长无效，无法查询天气。']);
        }
        try {
            $zone = new \DateTimeZone($timezone);
        } catch (\Exception) {
            return array_replace($result, ['message' => '时区无效，无法匹配训练时段的天气。']);
        }
        $today = $now->setTimezone($zone)->setTime(0, 0);
        $endTimestamp = $start->getTimestamp() + $durationMinutes * 60;
        if ($start < $now || $endTimestamp > $today->modify('+7 days')->getTimestamp()) {
            return array_replace($result, ['status' => 'out_of_range', 'message' => '训练时段不在当地今天起七天的未来预报范围内，请临近训练时再查看。']);
        }

        try {
            $key = 'training_forecast_'.hash('sha256', json_encode([$latitude, $longitude, $timezone, $today->format('Y-m-d')], JSON_THROW_ON_ERROR));
            $cached = $this->cache->get($key, function (ItemInterface $item) use ($latitude, $longitude, $timezone, $zone, $now): array {
                $item->expiresAfter(1800);
                $response = $this->client->request('GET', self::ENDPOINT, [
                    'connect_timeout' => 5,
                    'timeout' => 10,
                    'query' => [
                        'latitude' => $latitude,
                        'longitude' => $longitude,
                        'timezone' => $timezone,
                        'forecast_days' => 7,
                        'timeformat' => 'unixtime',
                        'temperature_unit' => 'celsius',
                        'wind_speed_unit' => 'kmh',
                        'precipitation_unit' => 'mm',
                        'hourly' => implode(',', array_keys(self::METRICS)),
                    ],
                ]);
                $body = (string) $response->getBody();
                if (200 !== $response->getStatusCode() || strlen($body) > 1000000) {
                    throw new \UnexpectedValueException('Invalid forecast response.');
                }
                $data = json_decode($body, true, 32, JSON_THROW_ON_ERROR);
                if (!is_array($data) || !is_array($data['hourly'] ?? null)) {
                    throw new \UnexpectedValueException('Missing hourly forecast.');
                }

                return ['hours' => $this->normalize($data['hourly'], $zone), 'fetchedAt' => $now->format(DATE_ATOM)];
            });
            $result['fetchedAt'] = $cached['fetchedAt'];
            $sessionHours = $this->selectHours($cached['hours'], $start->getTimestamp(), $endTimestamp);
            if (!$this->covers($sessionHours, $start->getTimestamp(), $endTimestamp)) {
                return $result;
            }

            return array_replace($result, [
                'status' => 'available',
                'hours' => $this->selectHours($cached['hours'], $start->getTimestamp() - 7200, $endTimestamp + 7200),
                'sessionHours' => $sessionHours,
                'message' => '已覆盖完整训练时段；逐小时预报可能变化，临出门再确认。',
            ]);
        } catch (\Throwable) {
            // Cache callbacks throw on errors: failed requests must not be cached as valid weather.
            return $result;
        }
    }

    /**
     * @param array<string, mixed> $hourly
     *
     * @return list<array<string, mixed>>
     */
    private function normalize(array $hourly, \DateTimeZone $timezone): array
    {
        if (!is_array($hourly['time'] ?? null) || !$hourly['time'] || count($hourly['time']) > 192) {
            throw new \UnexpectedValueException('Invalid forecast times.');
        }
        $hours = [];
        foreach ($hourly['time'] as $index => $timestamp) {
            if (!is_int($timestamp) || $timestamp < 0 || isset($hours[$timestamp])) {
                throw new \UnexpectedValueException('Invalid or duplicate forecast timestamp.');
            }
            $hour = ['time' => new \DateTimeImmutable('@'.$timestamp)->setTimezone($timezone)->format(DATE_ATOM)];
            foreach (self::METRICS as $metric => [$name, $minimum, $maximum]) {
                $metricIndex = $index;
                // Open-Meteo rain/probability/gusts at t describe the PRECEDING hour.
                // Normalize them to the interval beginning at hour.time so end-of-run rain is included.
                if (in_array($metric, ['precipitation', 'precipitation_probability', 'wind_gusts_10m'], true)) {
                    $metricIndex = ($hourly['time'][$index + 1] ?? null) === $timestamp + 3600 ? $index + 1 : -1;
                }
                $value = is_array($hourly[$metric] ?? null) ? ($hourly[$metric][$metricIndex] ?? null) : null;
                $value = (is_int($value) || is_float($value)) && is_finite((float) $value) && $value >= $minimum && $value <= $maximum ? (float) $value : null;
                if (in_array($name, ['weatherCode', 'isDay'], true) && null !== $value) {
                    $value = floor($value) === $value ? (int) $value : null;
                }
                $hour[$name] = 'isDay' === $name && null !== $value ? (bool) $value : $value;
            }
            if (null === ($hour['temperature'] ?? null) && null === ($hour['apparentTemperature'] ?? null)) {
                throw new \UnexpectedValueException('Missing temperature data.');
            }
            $hours[$timestamp] = $hour;
        }
        ksort($hours);

        return array_values($hours);
    }

    /**
     * @param list<array<string, mixed>> $hours
     *
     * @return list<array<string, mixed>>
     */
    private function selectHours(array $hours, int $start, int $end): array
    {
        return array_values(array_filter($hours, static function (array $hour) use ($start, $end): bool {
            $timestamp = new \DateTimeImmutable($hour['time'])->getTimestamp();

            return $timestamp < $end && $timestamp + 3600 > $start;
        }));
    }

    /** @param list<array<string, mixed>> $hours */
    private function covers(array $hours, int $start, int $end): bool
    {
        $covered = $start;
        foreach ($hours as $hour) {
            $timestamp = new \DateTimeImmutable($hour['time'])->getTimestamp();
            if ($timestamp > $covered) {
                return false;
            }
            $covered = $timestamp + 3600;
        }

        return $covered >= $end;
    }
}
