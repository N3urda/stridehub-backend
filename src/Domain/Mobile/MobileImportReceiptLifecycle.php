<?php

declare(strict_types=1);

namespace App\Domain\Mobile;

use App\Domain\Activity\ActivityId;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\ValueObject\String\Path;
use Doctrine\DBAL\Connection;
use League\Flysystem\FilesystemOperator;

/** Shared activity deletion hook, including imports whose mobile receipt has not been polled. */
final readonly class MobileImportReceiptLifecycle
{
    public function __construct(private TrainingRepository $repository, private Connection $connection, private FilesystemOperator $defaultStorage)
    {
    }

    public function deleteForActivity(ActivityId $activityId): void
    {
        $id = (string) $activityId;
        $filenames = $this->connection->fetchFirstColumn('SELECT originalFilename FROM FileImport WHERE activityId = ?', [$id]);
        foreach ($this->repository->list('mobile-import') as $receipt) {
            $filename = Path::fromString($receipt['queuedFilename'])->getFilename();
            if (($receipt['activityId'] ?? null) !== $id && !in_array($filename, $filenames, true)) {
                continue;
            }
            // Keep a deduplication tombstone even after the source bytes and activity disappear.
            $this->repository->save('mobile-import', $receipt['id'], [...$receipt, 'state' => 'deleted', 'activityId' => null, 'error' => '关联运动及原始文件已删除。'], $receipt['version']);
            $this->defaultStorage->delete('watch/.uploads/mobile-imports/'.$receipt['id']);
        }
    }
}
