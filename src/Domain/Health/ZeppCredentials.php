<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Infrastructure\ValueObject\String\KernelProjectDir;

final readonly class ZeppCredentials
{
    public function __construct(private KernelProjectDir $projectDir)
    {
    }

    public function configured(): bool
    {
        return is_file($this->path());
    }

    /** @return array{userId: string, host: string, appToken: string} */
    public function read(): array
    {
        if (!$this->configured()) {
            throw new ZeppSyncError('not_configured', '尚未连接 Zepp 睡眠数据。', '请在服务器配置 Zepp 授权文件，再点击同步。');
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
            if (!is_array($data)
                || !is_string($data['userId'] ?? null) || !preg_match('/\A[A-Za-z0-9_-]{1,128}\z/', $data['userId'])
                || !is_string($data['host'] ?? null) || !preg_match('/\Aapi-mifit[a-z0-9-]*\.(?:zepp|huami)\.com\z/', $data['host'])
                || !is_string($data['appToken'] ?? null) || strlen($data['appToken']) < 1 || strlen($data['appToken']) > 4096
                || preg_match('/[\x00-\x20\x7f]/', $data['appToken'])) {
                throw new \RuntimeException();
            }

            return ['userId' => $data['userId'], 'host' => $data['host'], 'appToken' => $data['appToken']];
        } catch (\Throwable) {
            throw new ZeppSyncError('error', 'Zepp 授权文件不可用。', '请检查授权文件格式、路径及权限（仅所有者可读写，0600）。');
        }
    }

    private function path(): string
    {
        $override = $_ENV['ZEPP_CREDENTIALS_FILE'] ?? $_SERVER['ZEPP_CREDENTIALS_FILE'] ?? getenv('ZEPP_CREDENTIALS_FILE');

        return is_string($override) && '' !== $override ? $override : $this->projectDir.'/storage/zepp/credentials.json';
    }
}
