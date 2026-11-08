<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Infrastructure\ValueObject\String\KernelProjectDir;

/** Original reports stay outside the public web root and are immutable once saved. */
final readonly class ReportAttachmentStore
{
    public const int MAX_BYTES = 20971520;

    public function __construct(private HealthContextService $context, private KernelProjectDir $projectDir)
    {
    }

    /** @return array<string, mixed> */
    public function metadata(string $id): array
    {
        $path = $this->path($id);
        if (!is_file($path)) {
            return ['available' => false];
        }

        return ['available' => true, 'filename' => $id.'.pdf', 'sizeBytes' => filesize($path), 'sha256' => hash_file('sha256', $path)];
    }

    public function path(string $id): string
    {
        TrainingInput::id($id);
        if (!array_any($this->context->get()['reports'], static fn (array $report): bool => $report['id'] === $id)) {
            throw new TrainingError('报告不存在。', 404);
        }

        return $this->projectDir.'/storage/files/health-reports/'.$id.'.pdf';
    }

    /** @return array<string, mixed> */
    public function save(string $id, string $bytes): array
    {
        $path = $this->path($id);
        if (strlen($bytes) > self::MAX_BYTES) {
            throw new TrainingError('PDF 不能超过 20 MiB。', 413);
        }
        if (!str_starts_with($bytes, '%PDF-') || !str_contains(substr($bytes, -2048), '%%EOF')) {
            throw new TrainingError('请提供完整的 PDF 文件。');
        }
        $directory = dirname($path);
        if (!is_dir($directory) && !mkdir($directory, 0700, true) && !is_dir($directory)) {
            throw new \RuntimeException('无法创建报告存储目录。');
        }
        $lock = fopen($directory.'/.lock', 'c');
        if (false === $lock) {
            throw new \RuntimeException('无法锁定报告存储目录。');
        }
        $temporary = null;
        try {
            if (!flock($lock, LOCK_EX)) {
                throw new \RuntimeException('无法锁定报告存储目录。');
            }
            if (is_file($path)) {
                if (!hash_equals((string) hash_file('sha256', $path), hash('sha256', $bytes))) {
                    throw new TrainingError('这份报告已有不同的原件；请为新报告建立独立记录。', 409);
                }

                return $this->metadata($id);
            }
            $temporary = tempnam($directory, '.upload-');
            if (false === $temporary || strlen($bytes) !== file_put_contents($temporary, $bytes)) {
                throw new \RuntimeException('无法保存报告原件。');
            }
            chmod($temporary, 0600);
            if (!rename($temporary, $path)) {
                throw new \RuntimeException('无法保存报告原件。');
            }
            clearstatcache(true, $path);

            return $this->metadata($id);
        } finally {
            if (is_string($temporary) && is_file($temporary)) {
                unlink($temporary);
            }
            flock($lock, LOCK_UN);
            fclose($lock);
        }
    }
}
