<?php

declare(strict_types=1);

namespace App\Domain\Health;

use GuzzleHttp\Client;

/** Bounded, read-only cloud requests. Tokens and provider bodies never enter diagnostics. */
final readonly class ZeppDataClient
{
    public const array EVENTS = ['all_day_stress', 'blood_oxygen', 'PaiHealthInfo', 'readiness', 'single_stress'];

    /** Verified request shapes; an empty collection means no measurements, not zero. */
    public const array EXTENDED_EVENTS = [
        'body_battery' => ['Charge', 'real_data'],
        'charge_stress' => ['Charge', 'stress_data'],
        'charge_insights' => ['Charge', 'insight_data'],
        'hrv_sdnn' => ['hrv_sdnn', 'real_data'],
        'hrv_rmssd' => ['HRVRMSSD', 'real_data'],
        'daily_health' => ['DailyHealth', 'summary'],
        'respiratory_rate' => ['RespiratoryRate', 'real_data'],
        'emotion' => ['Emotion', 'real_data'],
        'lactate_threshold' => ['LactateThreshold', 'summary'],
        'blood_pressure' => ['blood_pressure', 'real_data'],
        'second_heart_rate_files' => ['second_heart_rate', 'real_data'],
    ];
    public const array EXTRA_SOURCES = ['sport_load', 'vo2_max', 'weight', 'heart_rate'];

    public function __construct(private Client $client)
    {
    }

    /** @param array{userId:string, host:string, appToken:string, eventsUserId?:string} $credentials
     * @return list<mixed>
     */
    public function band(array $credentials, string $from, string $to): array
    {
        $payload = $this->request($credentials, $credentials['host'], '/v1/data/band_data.json', [
            'query_type' => 'detail', 'device_type' => 'ios_phone', 'userid' => $credentials['userId'], 'from_date' => $from, 'to_date' => $to,
        ]);
        if (1 !== ($payload['code'] ?? null) || !is_array($payload['data'] ?? null) || !array_is_list($payload['data'])) {
            throw new ZeppSyncError('error', 'Zepp 日常数据格式发生变化。', '已保存数据保留，请稍后重试。');
        }

        return $payload['data'];
    }

    /** @param array{userId:string, host:string, appToken:string, eventsUserId?:string} $credentials
     * @return list<mixed>
     */
    public function events(array $credentials, string $type, \DateTimeImmutable $from, \DateTimeImmutable $to, string $timezone, ?float $deadline = null): array
    {
        if (null !== $deadline && microtime(true) >= $deadline) {
            throw new ZeppSyncError('error', 'Zepp 本次同步达到时间上限。', '已获取数据会保存；请缩短天数后重试。');
        }
        $eventsUserId = $credentials['eventsUserId'] ?? $credentials['userId'];
        $limit = 1000;
        $query = ['from' => $from->getTimestamp() * 1000, 'to' => $to->getTimestamp() * 1000 - 1, 'limit' => $limit, 'timeZone' => $timezone];
        if (in_array($type, self::EVENTS, true)) {
            $path = '/users/'.$eventsUserId.'/events';
            $query['eventType'] = $type;
        } elseif (isset(self::EXTENDED_EVENTS[$type])) {
            [$query['eventType'], $query['subType']] = self::EXTENDED_EVENTS[$type];
            $query['reverse'] = 1;
            $path = 'second_heart_rate_files' === $type ? '/users/me/fileInfo/events' : '/v2/users/me/events';
        } elseif (in_array($type, ['sport_load', 'vo2_max'], true)) {
            $path = '/v2/watch/users/'.$eventsUserId.'/WatchSportStatistics/'.strtoupper($type);
            $limit = 900;
            $query = ['startDay' => $from->format('Y-m-d'), 'endDay' => $to->modify('-1 second')->format('Y-m-d'), 'limit' => $limit, 'isReverse' => 'true'];
        } elseif ('heart_rate' === $type) {
            $path = '/users/'.$eventsUserId.'/heartRate';
            $query = ['startTime' => $from->getTimestamp(), 'endTime' => $to->getTimestamp() - 1, 'limit' => $limit, 'type' => 2];
        } elseif ('weight' === $type) {
            $path = '/users/'.$eventsUserId.'/members/-1/weightRecords';
            $limit = 300;
            $query = ['fromTime' => $from->getTimestamp(), 'toTime' => $to->getTimestamp() - 1, 'limit' => $limit, 'isForward' => 0];
        } else {
            throw new \InvalidArgumentException('Unsupported event type.');
        }
        $payload = $this->request($credentials, $credentials['host'], $path, $query);
        if (!is_array($payload['items'] ?? null) || !array_is_list($payload['items'])) {
            throw new ZeppSyncError('error', 'Zepp 扩展数据格式异常。', '已保存数据保留，请稍后重试。');
        }
        if (count($payload['items']) >= $limit) {
            // Split on calendar days, retaining exact inclusive provider boundaries.
            // Never silently accept a full page as a complete result.
            $span = (int) $from->diff($to)->days;
            if ($span <= 1) {
                throw new ZeppSyncError('error', 'Zepp 单日数据达到读取上限。', '已保存数据保留；此来源尚未完整同步。');
            }
            $middle = $from->modify('+'.intdiv($span, 2).' days');

            return [...$this->events($credentials, $type, $from, $middle, $timezone, $deadline), ...$this->events($credentials, $type, $middle, $to, $timezone, $deadline)];
        }

        return $payload['items'];
    }

    /** Only health fields, never the account configuration or personal contact details.
     * @param array{userId:string, host:string, appToken:string, eventsUserId?:string} $credentials
     * @return array<string,mixed>
     */
    public function healthProfile(array $credentials): array
    {
        $payload = $this->request($credentials, $credentials['host'], '/huami.health.getUserInfo.json', ['userid' => $credentials['userId']], true);
        if (1 !== ($payload['code'] ?? null) || !is_array($payload['data'] ?? null)) {
            throw new ZeppSyncError('error', 'Zepp 健康资料格式异常。', '已保存资料保留，请稍后重试。');
        }
        $profile = [];
        foreach (['age', 'gender', 'height', 'weight', 'weight_float', 'daysportgoal', 'calories', 'distance'] as $field) {
            $value = $payload['data'][$field] ?? null;
            if (is_numeric($value) && is_finite((float) $value)) {
                $profile[$field] = $value;
            }
        }

        return $profile;
    }

    /** @param array{userId:string, host:string, appToken:string, eventsUserId?:string} $credentials
     * @param array<string, int|string> $query
     *
     * @return array<string, mixed>
     */
    private function request(array $credentials, string $host, string $path, array $query, bool $objectData = false): array
    {
        try {
            $response = $this->client->request('GET', 'https://'.$host.$path, [
                'headers' => ['apptoken' => $credentials['appToken'], 'appname' => 'com.huami.midong', 'appplatform' => 'ios_phone', 'Accept' => 'application/json'],
                'query' => $query, 'allow_redirects' => false, 'http_errors' => false, 'connect_timeout' => 5, 'timeout' => 30, 'stream' => false,
                'progress' => static function (float $total, float $downloaded, float $uploadTotal, float $uploaded): void {
                    if ($downloaded > 8 * 1024 * 1024) {
                        throw new \RuntimeException('Zepp response exceeded the size limit.');
                    }
                },
            ]);
            if (in_array($response->getStatusCode(), [401, 403], true)) {
                throw new ZeppSyncError('auth_required', '当前 Zepp 授权无法读取此数据源。', '请检查 Zepp 授权和账号区域；其他已同步数据仍可使用。');
            }
            if (200 !== $response->getStatusCode()) {
                throw new \RuntimeException();
            }
            $raw = \GuzzleHttp\Psr7\Utils::copyToString($response->getBody(), 8 * 1024 * 1024 + 1);
            if (strlen($raw) > 8 * 1024 * 1024) {
                throw new \RuntimeException();
            }
            $object = json_decode($raw, false, 32, JSON_THROW_ON_ERROR);
            if (!$object instanceof \stdClass) {
                throw new \RuntimeException();
            }
            foreach (['data', 'items'] as $field) {
                if (property_exists($object, $field) && !is_array($object->{$field}) && !($objectData && 'data' === $field && $object->{$field} instanceof \stdClass)) {
                    throw new \RuntimeException();
                }
            }

            return json_decode($raw, true, 32, JSON_THROW_ON_ERROR);
        } catch (ZeppSyncError $error) {
            throw $error;
        } catch (\Throwable) {
            throw new ZeppSyncError('error', 'Zepp 网络或数据格式异常。', '请稍后重试；已保存的数据会保留。');
        }
    }
}
