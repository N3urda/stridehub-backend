<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Infrastructure\ValueObject\String\KernelProjectDir;

/** Only the bridge key is stored here; Xiaomi account credentials stay in the bridge. */
final readonly class MiFitnessCredentials
{
    public function __construct(private KernelProjectDir $projectDir)
    {
    }

    private function path(): string
    {
        $path = $_ENV['MI_FITNESS_CREDENTIALS_FILE'] ?? $_SERVER['MI_FITNESS_CREDENTIALS_FILE'] ?? getenv('MI_FITNESS_CREDENTIALS_FILE');

        return is_string($path) && '' !== $path ? $path : $this->projectDir.'/storage/mi-fitness/credentials.json';
    }

    public function configured(): bool
    {
        return is_file($this->path());
    }

    /** @return array{baseUrl:string,apiKey:string,userId:string} */
    public function read(): array
    {
        if (!$this->configured()) {
            throw new MiFitnessSyncError('not_configured', '尚未配置小米连接器。');
        }
        try {
            $path = $this->path();
            if (!is_readable($path) || filesize($path) > 16384 || (fileperms($path) & 0077) !== 0) {
                throw new \RuntimeException();
            }
            $raw = file_get_contents($path);
            if (false === $raw) {
                throw new \RuntimeException();
            }
            $data = json_decode($raw, true, 8, JSON_THROW_ON_ERROR);
            foreach (['baseUrl', 'apiKey', 'userId'] as $key) {
                if (!is_string($data[$key] ?? null) || '' === $data[$key] || strlen($data[$key]) > 4096 || preg_match('/[\x00-\x20\x7f]/', $data[$key])) {
                    throw new \RuntimeException();
                }
            }
            $url = parse_url($data['baseUrl']);
            if (!is_array($url) || !isset($url['host']) || isset($url['pass']) || isset($url['user']) || isset($url['query']) || isset($url['fragment']) || !in_array($url['scheme'] ?? '', ['http', 'https'], true) || !in_array($url['path'] ?? '', ['', '/'], true)) {
                throw new \RuntimeException();
            }
            // Plain HTTP is limited to a local process or the documented private Compose service.
            if ('http' === $url['scheme'] && !in_array($url['host'], ['127.0.0.1', 'localhost', '[::1]', 'mi-fitness'], true)) {
                throw new \RuntimeException();
            }

            return ['baseUrl' => rtrim($data['baseUrl'], '/'), 'apiKey' => $data['apiKey'], 'userId' => $data['userId']];
        } catch (\Throwable) {
            throw new MiFitnessSyncError('error', '小米连接配置不可用，请核对地址、账号、API Key 及文件权限 0600。');
        }
    }
}
