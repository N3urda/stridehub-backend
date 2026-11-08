<?php

declare(strict_types=1);

namespace App\Domain\Health;

use GuzzleHttp\Client;

/** Contract: HUAYUE1024/mi-fitness-mcp-cn @ 1767b4921ad94e38a68ac77cbb0bdf809a92864d. */
final readonly class MiFitnessClient
{
    public function __construct(private Client $client)
    {
    }

    /** @param array{baseUrl:string,apiKey:string,userId:string} $credentials
     * @return list<mixed>
     */
    public function fetch(array $credentials, string $from, string $to): array
    {
        $this->verifyAccount($credentials);
        $sync = $this->request($credentials, 'POST', '/api/sync', ['json' => ['data_types' => ['sleep'], 'start_date' => $from, 'end_date' => $to, 'force_full_sync' => true, 'background' => false]]);
        if ('ok' !== ($sync['status'] ?? null) || !is_array($sync['data_types_synced'] ?? null) || !in_array('sleep', $sync['data_types_synced'], true)) {
            throw new MiFitnessSyncError('error', '小米云端同步未完整完成；已有睡眠保留，请在连接器检查授权或稍后重试。');
        }
        $data = $this->request($credentials, 'GET', '/api/sleep', ['query' => ['start_date' => $from, 'end_date' => $to, 'include_naps' => 'true']]);
        $this->verifyAccount($credentials);
        if ('ok' !== ($data['status'] ?? null) || !is_array($data['data'] ?? null) || !array_is_list($data['data']) || count($data['data']) > 5000 || ($data['count'] ?? null) !== count($data['data'])) {
            throw new MiFitnessSyncError('error', '小米连接器返回的数据格式不兼容。');
        }

        return $data['data'];
    }

    /** @param array{baseUrl:string,apiKey:string,userId:string} $credentials
     * @return list<mixed>
     */
    public function records(array $credentials, string $kind, string $from, string $to): array
    {
        $this->verifyAccount($credentials);
        $data = $this->request($credentials, 'GET', '/api/stridehub/records', ['query' => ['kind' => $kind, 'start_date' => $from, 'end_date' => $to]]);
        $this->verifyAccount($credentials);
        if ('ok' !== ($data['status'] ?? null) || ($data['kind'] ?? null) !== $kind || !is_array($data['data'] ?? null)
            || !array_is_list($data['data']) || count($data['data']) > 30000 || ($data['count'] ?? null) !== count($data['data'])) {
            throw new MiFitnessSyncError('error', '小米来源记录格式不兼容。');
        }

        return $data['data'];
    }

    /** @param array{baseUrl:string,apiKey:string,userId:string} $credentials */
    private function verifyAccount(array $credentials): void
    {
        $status = $this->request($credentials, 'GET', '/api/status', ['query' => ['deep' => 'false']]);
        if (!is_string($status['user_id'] ?? null) || !hash_equals($credentials['userId'], $status['user_id'])) {
            throw new MiFitnessSyncError('auth_required', '小米连接器账号与配置不一致，请核对账号授权。');
        }
    }

    /** @param array{baseUrl:string,apiKey:string,userId:string} $credentials
     * @param array<string,mixed> $options
     *
     * @return array<string,mixed>
     */
    private function request(array $credentials, string $method, string $path, array $options): array
    {
        try {
            $response = $this->client->request($method, $credentials['baseUrl'].$path, [...$options,
                'headers' => ['X-API-Key' => $credentials['apiKey'], 'Accept' => 'application/json'],
                'allow_redirects' => false, 'http_errors' => false, 'connect_timeout' => 5, 'timeout' => 'POST' === $method ? 180 : ('/api/stridehub/records' === $path ? 45 : 15), 'stream' => true,
            ]);
            if (in_array($response->getStatusCode(), [401, 403], true)) {
                throw new MiFitnessSyncError('auth_required', '小米连接器授权已失效，请重新连接。');
            }
            if (200 !== $response->getStatusCode()) {
                throw new \RuntimeException();
            }
            $body = \GuzzleHttp\Psr7\Utils::copyToString($response->getBody(), 8 * 1024 * 1024 + 1);
            if (strlen($body) > 8 * 1024 * 1024) {
                throw new \RuntimeException();
            }
            $decoded = json_decode($body, true, 32, JSON_THROW_ON_ERROR);
            if (!is_array($decoded) || array_is_list($decoded)) {
                throw new \RuntimeException();
            }

            return $decoded;
        } catch (MiFitnessSyncError $error) {
            throw $error;
        } catch (\Throwable) {
            throw new MiFitnessSyncError('error', '小米连接器网络或响应异常，已有记录保留。');
        }
    }
}
