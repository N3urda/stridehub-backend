<?php

declare(strict_types=1);

namespace App\Domain\Health;

use GuzzleHttp\Client;

/** Bounded, read-only cloud requests. Tokens and provider bodies never enter diagnostics. */
final readonly class ZeppDataClient
{
    public const array EVENTS = ['all_day_stress', 'blood_oxygen', 'PaiHealthInfo', 'readiness'];

    public function __construct(private Client $client)
    {
    }

    /** @param array{userId:string, host:string, appToken:string} $credentials
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

    /** @param array{userId:string, host:string, appToken:string} $credentials
     * @return list<mixed>
     */
    public function events(array $credentials, string $type, \DateTimeImmutable $from, \DateTimeImmutable $to, string $timezone): array
    {
        if (!in_array($type, self::EVENTS, true)) {
            throw new \InvalidArgumentException('Unsupported event type.');
        }
        // This API uses the global events host, independently of the regional band host.
        $payload = $this->request($credentials, 'api-mifit.zepp.com', '/users/'.$credentials['userId'].'/events', [
            'eventType' => $type, 'from' => $from->getTimestamp() * 1000, 'to' => $to->getTimestamp() * 1000 - 1, 'limit' => 1000, 'timeZone' => $timezone,
        ]);
        if (!is_array($payload['items'] ?? null) || !array_is_list($payload['items']) || count($payload['items']) >= 1000) {
            throw new ZeppSyncError('error', 'Zepp 扩展数据格式异常或达到单次上限。', '已保存数据保留；请缩短同步范围后重试。');
        }

        return $payload['items'];
    }

    /** @param array{userId:string, host:string, appToken:string} $credentials
     * @param array<string, int|string> $query
     *
     * @return array<string, mixed>
     */
    private function request(array $credentials, string $host, string $path, array $query): array
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
                if (property_exists($object, $field) && !is_array($object->{$field})) {
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
