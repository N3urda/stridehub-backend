<?php

declare(strict_types=1);

namespace App\Infrastructure\Http;

use App\Application\AppVersion;
use App\Infrastructure\Config\DevMode;
use App\Infrastructure\ValueObject\Identifier\UuidFactory;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use Symfony\Component\Asset\VersionStrategy\VersionStrategyInterface;

final readonly class AssetVersioningStrategy implements VersionStrategyInterface
{
    private const string FORMAT = '%s?%s';
    private const array STATIC_EXTENSIONS = ['css', 'js', 'mjs', 'png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'avif', 'ico', 'woff', 'woff2', 'ttf', 'otf', 'eot'];

    public function __construct(
        private DevMode $devMode,
        private UuidFactory $uuidFactory,
        private KernelProjectDir $projectDir,
    ) {
    }

    public function getVersion(string $path): string
    {
        if ($this->devMode->isEnabled()) {
            return $this->uuidFactory->random();
        }

        // Keep the empty-path page metadata and unavailable assets on the app version.
        $fallback = AppVersion::getSemanticVersion();
        if (!$this->isStaticAssetPath($path)) {
            return $fallback;
        }
        $publicDirectory = realpath($this->projectDir.'/public');
        if (false === $publicDirectory) {
            return $fallback;
        }
        $assetPath = $publicDirectory.'/'.ltrim($path, '/');
        $resolvedPath = realpath($assetPath);
        if (false === $resolvedPath || is_link($assetPath)
            || !str_starts_with($resolvedPath, $publicDirectory.'/')
            || !$this->isStaticAssetPath(substr($resolvedPath, strlen($publicDirectory) + 1))
            || !is_file($resolvedPath) || !is_readable($resolvedPath)) {
            return $fallback;
        }

        // A file may disappear during a deployment between the checks and this read.
        $hash = @hash_file('sha256', $resolvedPath);

        return false !== $hash ? $hash : $fallback;
    }

    private function isStaticAssetPath(string $path): bool
    {
        return '' !== $path
            && !str_starts_with($path, '//')
            && !preg_match('/[\x00-\x1f\x7f\\\\?#%:]/', $path)
            && !preg_match('#(?:^|/)\.#', $path)
            && in_array(strtolower(pathinfo($path, PATHINFO_EXTENSION)), self::STATIC_EXTENSIONS, true);
    }

    public function applyVersion(string $path): string
    {
        $versionized = \sprintf(self::FORMAT, ltrim($path, '/'), $this->getVersion($path));

        if ($path && '/' === $path[0]) {
            return '/'.$versionized;
        }

        return $versionized;
    }
}
