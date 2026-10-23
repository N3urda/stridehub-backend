<?php

declare(strict_types=1);

namespace App\Application;

use App\Infrastructure\ValueObject\String\KernelProjectDir;

final readonly class AndroidAppRelease
{
    public function __construct(private KernelProjectDir $projectDir)
    {
    }

    /** @return array{version: string, versionCode: int, minAndroid: string, filename: string, sha256: string, sizeBytes: int, downloadPath: string}|null */
    public function current(): ?array
    {
        $directory = $this->projectDir.'/public/downloads';
        $manifestPath = $directory.'/android-release.json';
        if (!is_readable($manifestPath)) {
            return null;
        }
        $contents = file_get_contents($manifestPath);
        $release = false !== $contents ? json_decode($contents, true) : null;
        if (!is_array($release)
            || !is_string($release['version'] ?? null)
            || !preg_match('/\A\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?\z/', $release['version'])
            || !is_int($release['versionCode'] ?? null) || $release['versionCode'] < 1
            || !is_string($release['minAndroid'] ?? null) || !preg_match('/\A\d+(?:\.\d+)?\z/', $release['minAndroid'])
            || !is_string($release['filename'] ?? null) || !preg_match('/\AStrideHub-[a-zA-Z0-9.-]+\.apk\z/', $release['filename'])
            || !is_string($release['sha256'] ?? null) || !preg_match('/\A[a-f0-9]{64}\z/', $release['sha256'])
            || !is_int($release['sizeBytes'] ?? null) || $release['sizeBytes'] < 1) {
            return null;
        }
        $apkPath = $directory.'/'.$release['filename'];
        if (is_link($apkPath) || !is_file($apkPath) || !is_readable($apkPath)
            || filesize($apkPath) !== $release['sizeBytes']
            || hash_file('sha256', $apkPath) !== $release['sha256']) {
            return null;
        }

        return [
            'version' => $release['version'],
            'versionCode' => $release['versionCode'],
            'minAndroid' => $release['minAndroid'],
            'filename' => $release['filename'],
            'sha256' => $release['sha256'],
            'sizeBytes' => $release['sizeBytes'],
            'downloadPath' => '/downloads/'.$release['filename'],
        ];
    }
}
