<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training\Weather;

use App\Domain\Training\Weather\ForecastProvider;
use App\Tests\Infrastructure\Time\Clock\PausedClock;
use GuzzleHttp\Client;
use GuzzleHttp\Exception\ConnectException;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Request;
use GuzzleHttp\Psr7\Response;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Cache\Adapter\ArrayAdapter;

class ForecastProviderTest extends TestCase
{
    public function testSelectsEntireSessionAcrossMidnightWithLocalOffsets(): void
    {
        $history = [];
        $provider = $this->provider([$this->response()], $history);
        $forecast = $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable('2026-09-26T15:30:00Z'), 120);

        self::assertSame('available', $forecast['status']);
        self::assertSame(['2026-09-26T23:00:00+08:00', '2026-09-27T00:00:00+08:00', '2026-09-27T01:00:00+08:00'], array_column($forecast['sessionHours'], 'time'));
        self::assertCount(7, $forecast['hours']);
        self::assertSame(22.0, $forecast['sessionHours'][0]['apparentTemperature']);
        self::assertSame('api.open-meteo.com', $history[0]['request']->getUri()->getHost());
        parse_str($history[0]['request']->getUri()->getQuery(), $query);
        self::assertSame('7', $query['forecast_days']);
        self::assertSame('unixtime', $query['timeformat']);
        self::assertSame('kmh', $query['wind_speed_unit']);
    }

    public function testCachesForecastAcrossDifferentSessionTimes(): void
    {
        $history = [];
        $provider = $this->provider([$this->response()], $history);
        $one = $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable('2026-09-26T23:30:00+08:00'), 60);
        $two = $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable('2026-09-27T01:30:00+08:00'), 90);

        self::assertSame('available', $two['status']);
        self::assertSame($one['fetchedAt'], $two['fetchedAt']);
        self::assertCount(1, $history);
    }

    public function testRejectsPastAndBeyondSevenCalendarDaysWithoutHttpRequest(): void
    {
        $history = [];
        $provider = $this->provider([], $history);
        foreach (['2026-09-26T05:59:00+08:00', '2026-10-03T08:00:00+08:00', '2026-10-02T23:30:00+08:00'] as $time) {
            $forecast = $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable($time), 60);
            self::assertSame('out_of_range', $forecast['status']);
            self::assertSame([], $forecast['hours']);
        }
        self::assertCount(0, $history);
    }

    public function testInvalidCoordinatesTimezoneAndDurationDoNotRequestForecast(): void
    {
        $history = [];
        $provider = $this->provider([], $history);
        $start = new \DateTimeImmutable('2026-09-26T23:30:00+08:00');
        foreach ([[NAN, 121.47, 'Asia/Shanghai', 60], [91.0, 121.47, 'Asia/Shanghai', 60], [31.23, INF, 'Asia/Shanghai', 60], [31.23, 121.47, 'invalid', 60], [31.23, 121.47, 'Asia/Shanghai', 0]] as [$lat, $lon, $timezone, $duration]) {
            self::assertSame('unavailable', $provider->forecast($lat, $lon, $timezone, $start, $duration)['status']);
        }
        self::assertCount(0, $history);
    }

    public function testHttpFailuresAreUnavailableAndNotCachedAsWeather(): void
    {
        $history = [];
        $provider = $this->provider([new ConnectException('offline', new Request('GET', 'https://api.open-meteo.com/v1/forecast')), $this->response()], $history);
        $start = new \DateTimeImmutable('2026-09-26T23:30:00+08:00');
        $failed = $provider->forecast(31.23, 121.47, 'Asia/Shanghai', $start, 60);
        self::assertSame('unavailable', $failed['status']);
        self::assertSame([], $failed['hours']);
        self::assertNull($failed['fetchedAt']);
        self::assertSame('available', $provider->forecast(31.23, 121.47, 'Asia/Shanghai', $start, 60)['status']);
    }

    public function testMalformedDataAndIncompleteSessionCoverageAreUnavailable(): void
    {
        foreach (['{not-json', '{}', json_encode(['hourly' => ['time' => [1790434800]]], JSON_THROW_ON_ERROR)] as $body) {
            $history = [];
            $provider = $this->provider([new Response(200, [], $body)], $history);
            self::assertSame('unavailable', $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable('2026-09-26T23:30:00+08:00'), 120)['status']);
        }
        $history = [];
        $provider = $this->provider([$this->response()], $history);
        self::assertSame('unavailable', $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable('2026-09-27T06:30:00+08:00'), 120)['status']);
    }

    public function testMissingOptionalMetricsRemainUnknownInsteadOfZero(): void
    {
        $history = [];
        $data = $this->data();
        unset($data['hourly']['uv_index']);
        $data['hourly']['precipitation_probability'][4] = null;
        $provider = $this->provider([new Response(200, [], json_encode($data, JSON_THROW_ON_ERROR))], $history);
        $forecast = $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable('2026-09-26T23:30:00+08:00'), 60);
        self::assertSame('available', $forecast['status']);
        self::assertNull($forecast['sessionHours'][0]['uvIndex']);
        self::assertNull($forecast['sessionHours'][0]['precipitationProbability']);
    }

    public function testInvalidMetricValuesStayUnknownAndMissingTemperaturesFailClosed(): void
    {
        $history = [];
        $data = $this->data();
        $data['hourly']['relative_humidity_2m'][3] = 140;
        $data['hourly']['uv_index'][3] = 'sunny';
        $data['hourly']['precipitation'][4] = -1;
        $provider = $this->provider([new Response(200, [], json_encode($data, JSON_THROW_ON_ERROR))], $history);
        $forecast = $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable('2026-09-26T23:00:00+08:00'), 60);
        self::assertSame('available', $forecast['status']);
        self::assertNull($forecast['sessionHours'][0]['humidity']);
        self::assertNull($forecast['sessionHours'][0]['uvIndex']);
        self::assertNull($forecast['sessionHours'][0]['precipitation']);
        $data['hourly']['temperature_2m'][3] = 1000;
        $data['hourly']['apparent_temperature'][3] = null;
        $provider = $this->provider([new Response(200, [], json_encode($data, JSON_THROW_ON_ERROR))], $history);
        self::assertSame('unavailable', $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable('2026-09-26T23:00:00+08:00'), 60)['status']);
    }

    public function testAlignsPrecedingHourRainAndGustsToTheHourOfTheRun(): void
    {
        $history = [];
        $data = $this->data();
        $data['hourly']['precipitation'][3] = 0;
        $data['hourly']['precipitation'][4] = 8;
        $data['hourly']['precipitation_probability'][4] = 90;
        $data['hourly']['wind_gusts_10m'][4] = 62;
        $provider = $this->provider([new Response(200, [], json_encode($data, JSON_THROW_ON_ERROR))], $history);
        $forecast = $provider->forecast(31.23, 121.47, 'Asia/Shanghai', new \DateTimeImmutable('2026-09-26T23:00:00+08:00'), 60);
        self::assertSame(8.0, $forecast['sessionHours'][0]['precipitation']);
        self::assertSame(90.0, $forecast['sessionHours'][0]['precipitationProbability']);
        self::assertSame(62.0, $forecast['sessionHours'][0]['windGusts']);
    }

    public function testPreservesDistinctRepeatedHoursAtDaylightSavingTransition(): void
    {
        $history = [];
        $data = $this->data('2026-11-01T00:00:00-04:00');
        $provider = $this->provider([new Response(200, [], json_encode($data, JSON_THROW_ON_ERROR))], $history, '2026-11-01T00:00:00-04:00');
        $forecast = $provider->forecast(40.7, -74.0, 'America/New_York', new \DateTimeImmutable('2026-11-01T01:30:00-04:00'), 120);
        self::assertSame(['2026-11-01T01:00:00-04:00', '2026-11-01T01:00:00-05:00', '2026-11-01T02:00:00-05:00'], array_column($forecast['sessionHours'], 'time'));
    }

    private function provider(array $responses, array &$history, string $now = '2026-09-26T06:00:00+08:00'): ForecastProvider
    {
        self::assertTrue(class_exists(ForecastProvider::class), 'Future forecast provider is not implemented.');
        $stack = HandlerStack::create(new MockHandler($responses));
        $stack->push(Middleware::history($history));

        return new ForecastProvider(new Client(['handler' => $stack]), new ArrayAdapter(), PausedClock::fromString($now));
    }

    private function response(): Response
    {
        return new Response(200, [], json_encode($this->data(), JSON_THROW_ON_ERROR));
    }

    private function data(string $from = '2026-09-26T20:00:00+08:00'): array
    {
        $hourly = [];
        for ($index = 0; $index < 12; ++$index) {
            $hourly['time'][] = new \DateTimeImmutable($from)->getTimestamp() + $index * 3600;
            foreach (['temperature_2m' => 20, 'apparent_temperature' => 22, 'relative_humidity_2m' => 65, 'precipitation_probability' => 10, 'precipitation' => 0, 'wind_speed_10m' => 8, 'wind_gusts_10m' => 12, 'weather_code' => 1, 'uv_index' => 0, 'is_day' => 0] as $metric => $value) {
                $hourly[$metric][] = $value;
            }
        }

        return ['hourly' => $hourly];
    }
}
