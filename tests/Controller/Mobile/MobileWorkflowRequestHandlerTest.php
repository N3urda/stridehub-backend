<?php

declare(strict_types=1);

namespace App\Tests\Controller\Mobile;

use App\Application\Import\FileImport\ImportActivityFiles\ImportActivityFiles;
use App\Application\Import\FileImport\ImportActivityFiles\ImportActivityFilesCommandHandler;
use App\Domain\Api\Token;
use App\Domain\Import\ImportMode;
use App\Domain\Mobile\MobileWorkflowService;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use App\Tests\Controller\Admin\AdminWebTestCase;
use App\Tests\SpyOutput;
use Doctrine\DBAL\Connection;
use Symfony\Component\HttpFoundation\File\UploadedFile;
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\Lock\Store\FlockStore;

final class MobileWorkflowRequestHandlerTest extends AdminWebTestCase
{
    private string $token;

    protected function prepareEnvironment(): void
    {
        parent::prepareEnvironment();
        $_SERVER['IMPORT_MODE'] = $_ENV['IMPORT_MODE'] = 'files';
        $this->token = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $this->token;
    }

    public function testManualCrudPreservesPartialFieldsAndDetectsWebChangesAndDeletionEffects(): void
    {
        $created = $this->call('POST', 'activities', $this->manual());
        self::assertResponseStatusCodeSame(201);
        self::assertContains('duration', $created['capabilities']['editableFields']);
        self::assertFalse($created['capabilities']['enrich']);
        $id = $created['id'];
        $this->call('POST', 'activities/'.$id.'/enrich', ['version' => $created['version']]);
        self::assertResponseStatusCodeSame(422);
        $saved = $this->call('PUT', 'activities/'.$id, ['version' => $created['version'], 'name' => 'DEMO updated']);
        self::assertResponseIsSuccessful();
        self::assertSame($created['fields']['duration'], $saved['fields']['duration']);
        self::assertSame('DEMO updated', $saved['fields']['name']);
        $this->call('PUT', 'activities/'.$id, ['version' => $created['version'], 'name' => 'stale']);
        self::assertResponseStatusCodeSame(409);
        $this->call('PUT', 'activities/'.$id, ['name' => 'missing']);
        self::assertResponseStatusCodeSame(428);
        $db = self::getContainer()->get(Connection::class);
        $db->update('Activity', ['name' => 'DEMO web edit'], ['activityId' => $id]);
        $this->call('DELETE', 'activities/'.$id.'?version='.$saved['version']);
        self::assertResponseStatusCodeSame(409);
        $current = $this->call('GET', 'activities/'.$id);
        self::assertNotEmpty($current['capabilities']['deleteEffects']);
        $db->insert('TrainingActivityLink', ['activityId' => $id, 'sessionId' => 'DEMO']);
        $this->call('DELETE', 'activities/'.$id.'?version='.$current['version']);
        self::assertResponseStatusCodeSame(409);
        $db->delete('TrainingActivityLink', ['activityId' => $id]);
        $this->call('DELETE', 'activities/'.$id.'?version='.$current['version']);
        self::assertResponseStatusCodeSame(204);
        $this->call('GET', 'activities/'.$id);
        self::assertResponseStatusCodeSame(404);
    }

    public function testImportedAndCloudCapabilitiesDoNotAdvertiseManualFields(): void
    {
        $db = self::getContainer()->get(Connection::class);
        $id = $db->fetchOne('SELECT activityId FROM Activity LIMIT 1');
        $db->update('Activity', ['importSource' => 'fitFile'], ['activityId' => $id]);
        $imported = $this->call('GET', 'activities/'.rawurlencode($id));
        self::assertResponseIsSuccessful();
        self::assertContains('deviceName', $imported['capabilities']['editableFields']);
        self::assertNotContains('duration', $imported['capabilities']['editableFields']);
        $this->call('PUT', 'activities/'.rawurlencode($id), ['version' => $imported['version'], 'distance' => 1]);
        self::assertResponseStatusCodeSame(422);
        self::getContainer()->get(TrainingRepository::class)->save('zepp-workout', 'zepp-demo', ['startedAt' => '2026-10-23T12:00:00+08:00'], 0);
        $cloud = $this->call('GET', 'activities/zepp-demo');
        self::assertFalse($cloud['capabilities']['edit']);
        self::assertFalse($cloud['capabilities']['delete']);
        $this->call('DELETE', 'activities/zepp-demo?version=1');
        self::assertResponseStatusCodeSame(403);
    }

    public function testStravaModeDisablesManualCreationAndEditing(): void
    {
        $manual = $this->call('POST', 'activities', $this->manual());
        $this->withImportMode(ImportMode::STRAVA_API);
        self::assertFalse($this->call('GET', 'activity-options')['canCreate']);
        $this->call('POST', 'activities', $this->manual());
        self::assertResponseStatusCodeSame(403);
        $current = $this->call('GET', 'activities/'.$manual['id']);
        self::assertFalse($current['capabilities']['edit']);
        self::assertSame([], $current['capabilities']['editableFields']);
        $this->call('PUT', 'activities/'.$manual['id'], ['version' => $current['version'], 'name' => 'DEMO forbidden']);
        self::assertResponseStatusCodeSame(403);
    }

    public function testPaginationUsesTieBreakerAndOverviewCoversWholeRange(): void
    {
        $one = $this->call('POST', 'activities', $this->manual());
        $two = $this->call('POST', 'activities', [...$this->manual(), 'name' => 'DEMO two']);
        $first = $this->call('GET', 'workouts?from=2026-10-23&to=2026-10-23&page=1&limit=1');
        $second = $this->call('GET', 'workouts?from=2026-10-23&to=2026-10-23&page=2&limit=1');
        self::assertSame(2, $first['pagination']['total']);
        self::assertSame(2, $first['summary']['count']);
        self::assertTrue($first['pagination']['hasMore']);
        self::assertFalse($second['pagination']['hasMore']);
        $ids = [$one['id'], $two['id']];
        sort($ids);
        self::assertSame($ids, [$first['records'][0]['id'], $second['records'][0]['id']]);
        self::assertSame($first, $this->call('GET', 'workouts?from=2026-10-23&to=2026-10-23&page=1&limit=1'));
        $this->call('GET', 'workouts?page=1.5');
        self::assertResponseStatusCodeSame(422);
        $this->call('GET', 'workouts?limit=101');
        self::assertResponseStatusCodeSame(422);
    }

    public function testSportGroupsFilterBeforeOverviewAndPagination(): void
    {
        foreach (['Run', 'TrailRun', 'VirtualRun', 'Walk', 'Hike', 'Ride', 'VirtualRide', 'GravelRide', 'Workout'] as $sport) {
            $this->call('POST', 'activities', [...$this->manual(), 'sportType' => $sport]);
            self::assertResponseStatusCodeSame(201);
        }
        $query = 'workouts?from=2026-10-23&to=2026-10-23&limit=2&sportType=';
        $run = $this->call('GET', $query.'Run');
        self::assertSame(3, $run['pagination']['total']);
        self::assertSame(2, $run['pagination']['pages']);
        self::assertSame(3, $run['summary']['count']);
        self::assertSame(3, $run['weeks'][0]['count']);
        self::assertCount(2, $run['records']);
        self::assertSame(0, $run['pendingDuplicates']);
        $last = $this->call('GET', $query.'Run&page=2');
        self::assertCount(1, $last['records']);
        self::assertFalse($last['pagination']['hasMore']);
        self::assertSame(2, $this->call('GET', $query.'Walk')['summary']['count']);
        self::assertSame(3, $this->call('GET', $query.'Ride')['summary']['count']);
        $other = $this->call('GET', $query.'Other');
        self::assertSame(1, $other['summary']['count']);
        self::assertSame('Workout', $other['records'][0]['sportType']);
        self::assertSame(9, $this->call('GET', $query.'all')['pagination']['total']);
        $this->call('GET', $query.'invalid');
        self::assertResponseStatusCodeSame(422);
    }

    public function testMalformedAndUnknownBodiesCannotDispatchCommands(): void
    {
        foreach (['[]', 'null', '{', '"text"'] as $body) {
            $this->client->request('POST', '/api/v1/mobile/sync', server: $this->headers(), content: $body);
            self::assertResponseStatusCodeSame(422);
        }
        $this->call('POST', 'activities', [...$this->manual(), 'dispatchCommand' => 'DeleteActivity']);
        self::assertResponseStatusCodeSame(422);
        $this->call('POST', 'activities', [...$this->manual(), 'isCommute' => 'true']);
        self::assertResponseStatusCodeSame(422);
        $this->call('POST', 'sync', ['kind' => 'daily', 'days' => '30']);
        self::assertResponseStatusCodeSame(422);
        $this->client->request('POST', '/api/v1/mobile/sync', server: ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token, 'CONTENT_TYPE' => 'text/plain'], content: '{}');
        self::assertResponseStatusCodeSame(415);
        $this->client->request('POST', '/api/v1/mobile/sync', server: $this->headers(), content: str_repeat(' ', 65537));
        self::assertResponseStatusCodeSame(413);
        $this->call('PUT', 'sync', []);
        self::assertResponseStatusCodeSame(405);
    }

    public function testDurableSyncDeduplicatesAndWorkerReportsRealResult(): void
    {
        $job = $this->call('POST', 'sync', ['kind' => 'daily', 'days' => 3]);
        self::assertResponseStatusCodeSame(202);
        self::assertSame('queued', $job['state']);
        $again = $this->call('POST', 'sync', ['kind' => 'daily', 'days' => 90]);
        self::assertSame($job['id'], $again['id']);
        self::assertSame(3, $again['days']);
        $workerLock = $this->locks()->createLock('mobile-workflows-worker');
        self::assertTrue($workerLock->acquire());
        self::assertSame(0, self::getContainer()->get(MobileWorkflowService::class)->runQueued());
        $workerLock->release();
        self::assertSame(1, self::getContainer()->get(MobileWorkflowService::class)->runQueued());
        $finished = $this->call('GET', 'sync/'.$job['id']);
        self::assertSame('failed', $finished['state']);
        self::assertSame('not_configured', $finished['result']['state']);
        self::assertNotNull($finished['finishedAt']);
        self::assertSame(0, self::getContainer()->get(MobileWorkflowService::class)->runQueued());
    }

    public function testRunningSyncIsOnlyInterruptedWhenItsWorkerLockIsGone(): void
    {
        $repo = self::getContainer()->get(TrainingRepository::class);
        $job = $repo->save('mobile-sync', 'DEMO-job', ['kind' => 'daily', 'days' => 3, 'state' => 'running', 'result' => null, 'error' => null], 0);
        $lock = $this->locks()->createLock('mobile-sync-job-'.$job['id'], 1800);
        self::assertTrue($lock->acquire());
        self::assertSame('running', $this->call('GET', 'sync/'.$job['id'])['state']);
        $lock->release();
        self::assertSame('interrupted', $this->call('GET', 'sync/'.$job['id'])['state']);
    }

    public function testUploadReceiptReportsQueueThenRealFailureAndRetryWithoutDuplicatingPending(): void
    {
        $temp = tempnam(sys_get_temp_dir(), 'native-import');
        file_put_contents($temp, 'DEMO corrupt FIT');
        try {
            $upload = function () use ($temp): array {
                $this->client->request('POST', '/api/v1/mobile/imports', files: ['file' => new UploadedFile($temp, 'DEMO.fit', 'application/octet-stream', null, true)], server: $this->headers());
                self::assertResponseStatusCodeSame(202);

                return json_decode($this->client->getResponse()->getContent(), true, flags: JSON_THROW_ON_ERROR);
            };
            $receipt = $upload();
            self::assertSame('queued', $receipt['state']);
            self::assertNull($receipt['activityId']);
            self::assertSame($receipt['id'], $upload()['id']);
            self::assertSame($receipt, $this->call('POST', 'imports/'.$receipt['id'].'/retry', []));
            $repo = self::getContainer()->get(TrainingRepository::class);
            $internal = $repo->find('mobile-import', $receipt['id']);
            $db = self::getContainer()->get(Connection::class);
            $db->insert('FileImport', ['fileImportId' => 'DEMO-file', 'originalFilename' => $internal['queuedFilename'], 'source' => 'fitFile', 'status' => 'failed', 'errorMessage' => 'DEMO parse failure', 'importedOn' => '2026-10-23 12:00:00']);
            $filesystem = self::getContainer()->get('default.storage');
            $filesystem->delete('watch/'.$internal['queuedFilename']);
            $failed = $this->call('GET', 'imports/'.$receipt['id']);
            self::assertSame('failed', $failed['state']);
            self::assertSame('DEMO parse failure', $failed['error']);
            $retried = $this->call('POST', 'imports/'.$receipt['id'].'/retry', []);
            self::assertSame($receipt['id'], $retried['id']);
            self::assertSame('queued', $retried['state']);
            self::assertSame(2, $retried['attempt']);
            self::assertSame($retried, $this->call('POST', 'imports/'.$receipt['id'].'/retry', []));
        } finally {
            unlink($temp);
        }
    }

    public function testReceiptReadsActualParserSuccessAndDeletionRemovesAllImportedData(): void
    {
        $service = self::getContainer()->get(MobileWorkflowService::class);
        $bytes = file_get_contents(__DIR__.'/../../Domain/Import/FileParser/fixtures/activity.tcx');
        $receipt = $service->upload('DEMO.TCX', $bytes);
        self::assertSame('queued', $receipt['state']);
        $repo = self::getContainer()->get(TrainingRepository::class);
        $queued = $repo->find('mobile-import', $receipt['id']);
        self::getContainer()->get('default.storage')->delete('watch/'.$queued['queuedFilename']);
        self::assertSame('interrupted', $service->import($receipt['id'])['state']);
        $receipt = $service->retryImport($receipt['id']);
        self::assertSame(2, $receipt['attempt']);
        self::assertStringEndsWith('-2.tcx', $repo->find('mobile-import', $receipt['id'])['queuedFilename']);
        $db = self::getContainer()->get(Connection::class);
        $db->insert('KeyValue', ['key' => 'lock.importData', 'value' => '{"lockAcquiredBy":"test"}']);
        self::getContainer()->get(ImportActivityFilesCommandHandler::class)->handle(new ImportActivityFiles(new SpyOutput()));
        $imported = $this->call('GET', 'imports/'.$receipt['id']);
        self::assertResponseIsSuccessful();
        self::assertSame('success', $imported['state']);
        self::assertNotNull($imported['activityId']);
        self::assertSame($imported, $service->upload('DEMO renamed.tcx', $bytes));
        self::assertSame($imported, $this->call('POST', 'imports/'.$receipt['id'].'/retry', []));
        $id = $imported['activityId'];
        self::assertGreaterThan(0, $db->fetchOne('SELECT count(*) FROM ActivityStream WHERE activityId = ?', [$id]));
        self::assertGreaterThan(0, $db->fetchOne('SELECT count(*) FROM ActivityLap WHERE activityId = ?', [$id]));
        $activity = $this->call('GET', 'activities/'.$id);
        $streams = $this->call('GET', 'activities/'.$id.'/streams');
        self::assertResponseIsSuccessful();
        self::assertNotEmpty($streams['streams']['latlng']);
        self::assertNotEmpty($streams['streams']['time']);
        self::assertIsNumeric($streams['laps'][0]['distanceInMeter']);
        self::assertStringContainsString('no-store', $this->client->getResponse()->headers->get('Cache-Control'));
        $this->call('DELETE', 'activities/'.$id.'?version='.$activity['version']);
        self::assertResponseStatusCodeSame(204);
        $deleted = $this->call('GET', 'imports/'.$receipt['id']);
        self::assertSame('deleted', $deleted['state']);
        self::assertNull($deleted['activityId']);
        self::assertFalse(self::getContainer()->get('default.storage')->fileExists('watch/.uploads/mobile-imports/'.$receipt['id']));
        self::assertSame('deleted', $service->retryImport($receipt['id'])['state']);
        foreach (['Activity', 'ActivityStream', 'ActivityLap', 'FileImport'] as $table) {
            self::assertSame(0, (int) $db->fetchOne('SELECT count(*) FROM '.$table.' WHERE activityId = ?', [$id]));
        }
    }

    public function testMissingQueueFileIsInterruptedAndStagingRetryDoesNotDuplicateFile(): void
    {
        $service = self::getContainer()->get(MobileWorkflowService::class);
        $receipt = $service->upload('DEMO.fit', 'DEMO lost content');
        $repo = self::getContainer()->get(TrainingRepository::class);
        $record = $repo->find('mobile-import', $receipt['id']);
        $filesystem = self::getContainer()->get('default.storage');
        $filesystem->delete('watch/'.$record['queuedFilename']);
        self::assertSame('interrupted', $service->import($receipt['id'])['state']);
        $retried = $service->retryImport($receipt['id']);
        self::assertSame('queued', $retried['state']);
        self::assertSame(2, $retried['attempt']);
        $record = $repo->find('mobile-import', $receipt['id']);
        $repo->save('mobile-import', $record['id'], [...$record, 'state' => 'staging'], $record['version']);
        self::assertSame('interrupted', $service->import($receipt['id'])['state']);
        $restored = $service->retryImport($receipt['id']);
        self::assertSame(2, $restored['attempt']);
        self::assertSame('queued', $restored['state']);
        self::assertCount(1, $filesystem->listContents('watch', false)->filter(fn ($file): bool => $file->isFile())->toArray());
    }

    private function locks(): LockFactory
    {
        return new LockFactory(new FlockStore(self::getContainer()->get(KernelProjectDir::class).'/watch/.uploads/mobile-locks'));
    }

    private function manual(): array
    {
        return ['name' => 'DEMO native run', 'sportType' => 'Run', 'startDateTime' => '2026-10-23T12:00', 'duration' => ['minutes' => 30], 'distance' => 5.0, 'elevation' => 0.0];
    }

    private function headers(): array
    {
        return ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token, 'CONTENT_TYPE' => 'application/json'];
    }

    private function call(string $method, string $path, ?array $input = null): array
    {
        $this->client->request($method, '/api/v1/mobile/'.$path, server: $this->headers(), content: null === $input ? null : json_encode((object) $input, JSON_THROW_ON_ERROR));

        return 204 === $this->client->getResponse()->getStatusCode() ? [] : json_decode($this->client->getResponse()->getContent(), true, flags: JSON_THROW_ON_ERROR);
    }
}
