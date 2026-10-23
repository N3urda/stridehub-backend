<?php

declare(strict_types=1);

namespace App\Tests\Domain\Activity\DeleteActivity;

use App\Application\Import\FileImport\ImportActivityFiles\ImportActivityFiles;
use App\Application\Import\FileImport\ImportActivityFiles\ImportActivityFilesCommandHandler;
use App\Domain\Activity\ActivityId;
use App\Domain\Activity\DeleteActivity\DeleteActivity;
use App\Domain\Import\ImportMode;
use App\Domain\Mobile\MobileWorkflowService;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\CQRS\Command\Bus\CommandBus;
use App\Tests\ContainerTestCase;
use App\Tests\SpyOutput;
use Doctrine\DBAL\Connection;
use PHPUnit\Framework\Attributes\DataProvider;

final class DeleteActivityCommandHandlerTest extends ContainerTestCase
{
    protected function importMode(): ImportMode
    {
        return ImportMode::FILES;
    }

    /** @return iterable<string, array{bool, bool}> */
    public static function receiptStates(): iterable
    {
        yield 'unpolled receipt filename fallback' => [false, false];
        yield 'finalized receipt activity ID' => [true, false];
        yield 'legacy uppercase retry filename fallback' => [false, true];
    }

    #[DataProvider('receiptStates')]
    public function testSharedDeleteTombstonesReceiptsAndRemovesRetryBytes(bool $finalize, bool $legacyUppercaseFilename): void
    {
        $service = self::getContainer()->get(MobileWorkflowService::class);
        $repo = self::getContainer()->get(TrainingRepository::class);
        $storage = self::getContainer()->get('default.storage');
        $bytes = file_get_contents(__DIR__.'/../../../Domain/Import/FileParser/fixtures/activity.tcx');
        $receipt = $service->upload('DEMO.TCX', $bytes);
        $db = self::getContainer()->get(Connection::class);
        $db->insert('KeyValue', ['key' => 'lock.importData', 'value' => '{"lockAcquiredBy":"test"}']);
        self::getContainer()->get(ImportActivityFilesCommandHandler::class)->handle(new ImportActivityFiles(new SpyOutput()));
        $activityId = $db->fetchOne('SELECT activityId FROM FileImport WHERE status = ?', ['success']);
        self::assertIsString($activityId);
        if ($finalize) {
            self::assertSame('success', $service->import($receipt['id'])['state']);
        } else {
            self::assertSame('queued', $repo->find('mobile-import', $receipt['id'])['state']);
        }
        if ($legacyUppercaseFilename) {
            $internal = $repo->find('mobile-import', $receipt['id']);
            $filename = substr($internal['queuedFilename'], 0, -3).'TCX';
            $repo->save('mobile-import', $receipt['id'], [...$internal, 'queuedFilename' => $filename], $internal['version']);
        }
        // The web UI uses this shared command bus path, bypassing the mobile delete adapter.
        self::getContainer()->get(CommandBus::class)->dispatch(new DeleteActivity(ActivityId::fromString($activityId)));
        $deleted = $service->import($receipt['id']);
        self::assertSame('deleted', $deleted['state']);
        self::assertNull($deleted['activityId']);
        self::assertFalse($storage->fileExists('watch/.uploads/mobile-imports/'.$receipt['id']));
        self::assertSame($deleted, $service->retryImport($receipt['id']));
        self::assertSame($deleted, $service->upload('DEMO.TCX', $bytes));
        self::assertSame(0, (int) $db->fetchOne('SELECT count(*) FROM Activity'));
        self::assertSame(0, (int) $db->fetchOne('SELECT count(*) FROM FileImport'));
        self::assertSame([], $storage->listContents('watch', false)->filter(static fn ($file): bool => $file->isFile())->toArray());
    }
}
