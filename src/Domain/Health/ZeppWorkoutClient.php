<?php

declare(strict_types=1);

namespace App\Domain\Health;

use GuzzleHttp\Client;

/** Read-only Zepp requests; upstream bodies and credentials never enter diagnostics. */
final readonly class ZeppWorkoutClient
{
    public function __construct(private Client $client)
    {
    }

    /** @param array{userId:string,host:string,appToken:string} $credentials
     * @return array{records:list<mixed>,next:?int}
     */
    public function history(array $credentials, ?int $before = null): array
    {
        $query = ['userid' => $credentials['userId'], 'source' => 'run.mi.com', 'count' => 50, 'need_sub_data' => 1];
        if (null !== $before) {
            $query['trackid'] = $before;
        }
        $data = $this->request($credentials, 'history', $query);
        $records = $data['summary'] ?? null;
        if (!is_array($records) || !array_is_list($records)) {
            throw new ZeppSyncError('error', 'Zepp 运动列表格式发生变化。', '已保存的运动仍可查看，请稍后重试。');
        }
        $next = filter_var($data['next'] ?? -1, FILTER_VALIDATE_INT);
        if (false === $next) {
            throw new ZeppSyncError('error', 'Zepp 运动分页格式发生变化。', '请稍后重试。');
        }

        return ['records' => $records, 'next' => $next > 0 ? $next : null];
    }

    /** @param array{userId:string,host:string,appToken:string} $credentials
     * @return array<string,mixed>
     */
    public function detail(array $credentials, string $id, string $source): array
    {
        return $this->request($credentials, 'detail', ['userid' => $credentials['userId'], 'trackid' => $id, 'source' => $source]);
    }

    /** @param array{userId:string,host:string,appToken:string} $credentials
     * @param array<string,int|string> $query
     *
     * @return array<string,mixed>
     */
    private function request(array $credentials, string $endpoint, array $query): array
    {
        try {
            $response = $this->client->request('GET', 'https://'.$credentials['host'].'/v1/sport/run/'.$endpoint.'.json', [
                'headers' => ['apptoken' => $credentials['appToken'], 'appname' => 'com.huami.midong', 'appplatform' => 'ios_phone', 'v' => '2.0', 'Accept' => 'application/json'],
                'query' => $query, 'allow_redirects' => false, 'http_errors' => false, 'connect_timeout' => 5, 'timeout' => 30, 'stream' => false,
                // Buffered cURL enforces a total deadline; PHP streaming can wait for EOF
                // on an upstream keep-alive response. Abort oversized transfers early.
                'progress' => static function (float $total, float $downloaded): void {
                    if ($total > 16 * 1024 * 1024 || $downloaded > 16 * 1024 * 1024) {
                        throw new \RuntimeException('Response exceeds size limit.');
                    }
                },
            ]);
            if (in_array($response->getStatusCode(), [401, 403], true)) {
                throw new ZeppSyncError('auth_required', '当前 Zepp 授权无法读取运动数据。', '请检查服务器 Zepp 授权及账号区域；已保存数据会保留。');
            }
            if (200 !== $response->getStatusCode()) {
                throw new \RuntimeException();
            }
            $raw = \GuzzleHttp\Psr7\Utils::copyToString($response->getBody(), 16 * 1024 * 1024 + 1);
            if (strlen($raw) > 16 * 1024 * 1024) {
                throw new \RuntimeException();
            }
            $payload = json_decode($raw, true, 32, JSON_THROW_ON_ERROR);
            if (!is_array($payload) || 1 !== ($payload['code'] ?? null) || !is_array($payload['data'] ?? null)) {
                throw new \RuntimeException();
            }

            return $payload['data'];
        } catch (ZeppSyncError $error) {
            throw $error;
        } catch (\Throwable) {
            throw new ZeppSyncError('error', 'Zepp 运动网络或数据格式异常。', '请稍后重试；已保存的运动和轨迹会保留。');
        }
    }
}
