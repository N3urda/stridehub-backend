<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use GuzzleHttp\Client;
use GuzzleHttp\Psr7\Utils;

/** The browser receives only a QR image and public state; control keys stay server-side. */
final readonly class MiFitnessPairing
{
    public function __construct(private Client $client, private KernelProjectDir $projectDir)
    {
    }

    /** @return array{state:string,message:string,attemptId:?string,expiresAt:?string} */
    public function status(): array
    {
        if (!is_file($this->path())) {
            return ['state' => 'unavailable', 'message' => '小米连接服务尚未启动，请先完成服务器的小米连接服务部署。', 'attemptId' => null, 'expiresAt' => null];
        }

        return $this->state($this->request('GET', 'status'));
    }

    /** @return array{state:string,message:string,attemptId:?string,expiresAt:?string} */
    public function start(): array
    {
        return $this->state($this->request('POST', 'start', ['json' => new \stdClass()]));
    }

    /** @return array{state:string,message:string,attemptId:?string,expiresAt:?string} */
    public function cancel(string $attemptId): array
    {
        $this->validateAttempt($attemptId);

        return $this->state($this->request('POST', 'cancel', ['json' => ['attemptId' => $attemptId]]));
    }

    public function qr(string $attemptId): string
    {
        $this->validateAttempt($attemptId);
        $image = $this->request('GET', 'qr', ['query' => ['attemptId' => $attemptId]], true);
        if (!str_starts_with($image, "\x89PNG\r\n\x1a\n")) {
            throw new TrainingError('二维码暂时无法读取，请重新生成。', 503);
        }

        return $image;
    }

    private function validateAttempt(string $attemptId): void
    {
        if (!preg_match('/\A[a-f0-9]{32}\z/', $attemptId)) {
            throw new TrainingError('绑定会话无效，请重新生成二维码。');
        }
    }

    private function path(): string
    {
        $path = $_ENV['MI_FITNESS_PAIRING_FILE'] ?? $_SERVER['MI_FITNESS_PAIRING_FILE'] ?? getenv('MI_FITNESS_PAIRING_FILE');

        return is_string($path) && '' !== $path ? $path : $this->projectDir.'/storage/mi-fitness/pairing-control.json';
    }

    /** @return array{baseUrl:string,apiKey:string} */
    private function configuration(): array
    {
        try {
            $path = $this->path();
            clearstatcache(true, $path);
            if (!is_file($path) || !is_readable($path) || filesize($path) > 16384 || (fileperms($path) & 0077) !== 0) {
                throw new \RuntimeException();
            }
            $raw = file_get_contents($path);
            if (false === $raw) {
                throw new \RuntimeException();
            }
            $data = json_decode($raw, true, 8, JSON_THROW_ON_ERROR);
            foreach (['baseUrl', 'apiKey'] as $key) {
                if (!is_string($data[$key] ?? null) || '' === $data[$key] || strlen($data[$key]) > 4096 || preg_match('/[\x00-\x20\x7f]/', $data[$key])) {
                    throw new \RuntimeException();
                }
            }
            $url = parse_url($data['baseUrl']);
            if (!is_array($url) || !isset($url['host']) || isset($url['pass']) || isset($url['user']) || isset($url['query']) || isset($url['fragment'])
                || !in_array($url['scheme'] ?? '', ['http', 'https'], true) || !in_array($url['path'] ?? '', ['', '/'], true)
                || ('http' === $url['scheme'] && !in_array($url['host'], ['127.0.0.1', 'localhost', '[::1]', 'mi-fitness'], true))) {
                throw new \RuntimeException();
            }

            return ['baseUrl' => rtrim($data['baseUrl'], '/'), 'apiKey' => $data['apiKey']];
        } catch (\Throwable) {
            throw new TrainingError('小米连接服务尚未就绪，请检查服务部署和私有配置文件权限。', 503);
        }
    }

    /** @param array<string,mixed> $options */
    private function request(string $method, string $action, array $options = [], bool $image = false): string
    {
        $config = $this->configuration();
        try {
            $response = $this->client->request($method, $config['baseUrl'].'/api/stridehub/pair/'.$action, [...$options,
                'headers' => ['X-StrideHub-Control-Key' => $config['apiKey'], 'Accept' => $image ? 'image/png' : 'application/json'],
                'allow_redirects' => false, 'http_errors' => false, 'connect_timeout' => 3, 'timeout' => 'start' === $action ? 50 : 15, 'stream' => true,
            ]);
            if (409 === $response->getStatusCode()) {
                throw new TrainingError('绑定会话已变化，请刷新连接状态后重试。', 409);
            }
            if (410 === $response->getStatusCode()) {
                throw new TrainingError('二维码已过期，请重新生成。', 410);
            }
            if (200 !== $response->getStatusCode()) {
                throw new \RuntimeException();
            }
            $limit = $image ? 512 * 1024 : 16384;
            $body = Utils::copyToString($response->getBody(), $limit + 1);
            if (strlen($body) > $limit || ($image && 'image/png' !== strtolower(trim(explode(';', $response->getHeaderLine('Content-Type'))[0])))) {
                throw new \RuntimeException();
            }

            return $body;
        } catch (TrainingError $error) {
            throw $error;
        } catch (\Throwable) {
            throw new TrainingError('暂时无法连接小米授权服务，请稍后重试。', 503);
        }
    }

    /** @return array{state:string,message:string,attemptId:?string,expiresAt:?string} */
    private function state(string $body): array
    {
        try {
            $data = json_decode($body, true, 8, JSON_THROW_ON_ERROR);
            $messages = [
                'idle' => '点击连接小米运动健康，生成扫码绑定二维码。',
                'pending' => '请使用本人小米账号或米家 App 扫码，并在手机上确认授权。',
                'success' => '小米运动健康已绑定，可以同步最近 7 天的数据。',
                'expired' => '二维码已过期，请重新生成。',
                'cancelled' => '已取消本次绑定。',
                'error' => '小米授权未完成，请重新生成二维码后重试。',
            ];
            if (!is_array($data) || !is_string($data['state'] ?? null) || !isset($messages[$data['state']])) {
                throw new \RuntimeException();
            }
            $attempt = $data['attemptId'] ?? null;
            $expires = $data['expiresAt'] ?? null;
            if ((null !== $attempt && (!is_string($attempt) || !preg_match('/\A[a-f0-9]{32}\z/', $attempt)))
                || (null !== $expires && (!is_string($expires) || !preg_match('/\A\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})\z/', $expires)))
                || ('pending' === $data['state'] && (null === $attempt || null === $expires))) {
                throw new \RuntimeException();
            }
            $message = 'account_mismatch' === ($data['code'] ?? null)
                ? '扫码账号与原绑定账号不同，请使用原账号重新授权；已有数据和连接保持不变。'
                : $messages[$data['state']];

            return ['state' => $data['state'], 'message' => $message, 'attemptId' => $attempt, 'expiresAt' => $expires];
        } catch (\Throwable) {
            throw new TrainingError('小米授权服务响应异常，请刷新后重试。', 503);
        }
    }
}
