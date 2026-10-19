<?php

declare(strict_types=1);

namespace App\Domain\Health;

use GuzzleHttp\Client;

final readonly class ZeppSleepClient
{
    public function __construct(private Client $client)
    {
    }

    /** @param array{userId: string, host: string, appToken: string} $credentials
     * @return list<mixed>
     */
    public function fetch(array $credentials, string $from, string $to): array
    {
        try {
            $response = $this->client->request('GET', 'https://'.$credentials['host'].'/v1/data/band_data.json', [
                'headers' => ['apptoken' => $credentials['appToken'], 'appname' => 'com.huami.midong', 'appplatform' => 'ios_phone', 'Accept' => 'application/json'],
                'query' => ['query_type' => 'summary', 'device_type' => 'ios_phone', 'userid' => $credentials['userId'], 'from_date' => $from, 'to_date' => $to],
                'allow_redirects' => false, 'http_errors' => false, 'connect_timeout' => 5, 'timeout' => 30, 'stream' => true,
            ]);
            $status = $response->getStatusCode();
            if (in_array($status, [401, 403], true)) {
                throw new ZeppSyncError('auth_required', 'Zepp 授权已失效或区域不匹配。', '请重新登录 Zepp 并更新服务器授权文件，然后重试。');
            }
            if (200 !== $status) {
                throw new ZeppSyncError('error', 'Zepp 暂时无法返回睡眠数据。', '请检查网络，稍后点击同步重试；已保存的数据会保留。');
            }
            $raw = \GuzzleHttp\Psr7\Utils::copyToString($response->getBody(), 8 * 1024 * 1024 + 1);
            if (strlen($raw) > 8 * 1024 * 1024) {
                throw new \RuntimeException();
            }
            $envelope = json_decode($raw, false, 32, JSON_THROW_ON_ERROR);
            if (!$envelope instanceof \stdClass) {
                throw new \RuntimeException();
            }
            $payload = json_decode($raw, true, 32, JSON_THROW_ON_ERROR);
            if (1 !== ($payload['code'] ?? null)) {
                $message = is_string($payload['message'] ?? null) ? $payload['message'] : '';
                if (preg_match('/token|auth|login|登录|授权/i', $message)) {
                    throw new ZeppSyncError('auth_required', 'Zepp 授权已失效。', '请重新登录 Zepp 并更新服务器授权文件，然后重试。');
                }
                throw new \RuntimeException();
            }
            if (!is_array($envelope->data ?? null) || count($envelope->data) > 5000) {
                throw new \RuntimeException();
            }

            return $payload['data'];
        } catch (ZeppSyncError $error) {
            throw $error;
        } catch (\Throwable) {
            // Do not surface provider bodies, request URLs, user IDs or exception strings.
            throw new ZeppSyncError('error', 'Zepp 网络或数据格式异常。', '请稍后点击同步重试；持续失败时检查网络和 Zepp 授权。');
        }
    }
}
