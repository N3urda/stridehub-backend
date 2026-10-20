<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\SleepService;
use App\Domain\Health\ZeppCredentials;
use App\Domain\Health\ZeppDataClient;
use App\Domain\Health\ZeppDataParser;
use App\Domain\Health\ZeppDataService;
use App\Domain\Health\ZeppSleepClient;
use App\Domain\Health\ZeppSleepParser;
use App\Domain\Health\ZeppSyncError;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use App\Tests\Domain\Training\Notification\ReminderTestClock;
use Doctrine\DBAL\DriverManager;
use GuzzleHttp\Client;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\Lock\Store\InMemoryStore;

final class ZeppDataTest extends TestCase
{
    private string $directory;

    protected function setUp(): void
    {
        $this->directory = sys_get_temp_dir().'/stridehub-zepp-detail-'.bin2hex(random_bytes(8));
        mkdir($this->directory.'/storage/zepp', 0700, true);
        unset($_ENV['ZEPP_CREDENTIALS_FILE'], $_SERVER['ZEPP_CREDENTIALS_FILE']);
        file_put_contents($this->directory.'/storage/zepp/credentials.json', json_encode(['userId' => 'fixture-user', 'host' => 'api-mifit-cn3.zepp.com', 'appToken' => 'fixture-token']));
        chmod($this->directory.'/storage/zepp/credentials.json', 0600);
    }

    protected function tearDown(): void
    {
        if (is_file($this->directory.'/fixture.db')) {
            unlink($this->directory.'/fixture.db');
        }
        unlink($this->directory.'/storage/zepp/credentials.json');
        rmdir($this->directory.'/storage/zepp');
        rmdir($this->directory.'/storage');
        rmdir($this->directory);
    }

    public function testMinuteHeartRateDoesNotTurnNoReadingsIntoZeroAndPreservesLocalTime(): void
    {
        $parser = new ZeppDataParser(new ZeppSleepParser());
        $record = $parser->band(self::row());
        self::assertSame(3, $record['heartRate']['sampleCount']);
        self::assertSame(80.0, $record['heartRate']['averageBpm']);
        self::assertSame('2026-09-30T00:02:00+08:00', $record['samples']['heartRate'][0]['at']);
        self::assertSame('missing', $record['coverage']['oxygen']);
        self::assertNull($record['oxygen']['averagePercent']);
        self::assertSame(4200, $record['activity']['steps']);
        self::assertSame(35, $record['activity']['activeMinutes']);
        $unknown = self::row();
        $unknown['data_hr'] = base64_encode('unknown format');
        self::assertSame(0, $parser->band($unknown)['heartRate']['sampleCount']);
        self::assertSame('missing', $parser->band(['date_time' => '2026-09-30', 'summary' => ['goal' => 10000]])['coverage']['activity']);
    }

    public function testProviderRedirectAndObjectShapedCollectionsAreRejectedWithoutLeaking(): void
    {
        $history = [];
        $stack = HandlerStack::create(new MockHandler([new Response(302, ['Location' => 'https://example.invalid']), new Response(200, [], '{"code":1,"data":{}}')]));
        $stack->push(Middleware::history($history));
        $client = new ZeppDataClient(new Client(['handler' => $stack]));
        $credentials = new ZeppCredentials(KernelProjectDir::fromString($this->directory))->read();
        foreach (range(1, 2) as $unused) {
            try {
                $client->band($credentials, '2026-09-29', '2026-09-30');
                self::fail('A redirect or object collection must not become empty successful data.');
            } catch (ZeppSyncError $error) {
                self::assertSame('error', $error->state);
                self::assertStringNotContainsString('fixture-token', $error->getMessage());
            }
        }
        self::assertCount(2, $history);
        self::assertFalse($history[0]['options']['allow_redirects']);
    }

    public function testDetailedSyncKeepsSourceAndSeparatesSleepWakeDateFromDailyActivity(): void
    {
        [$service, $repository] = $this->service(self::responses());
        $result = $service->sync(2);
        self::assertSame('partial', $result['state']);
        self::assertSame(2, $result['recordCount']);
        self::assertSame(1, $result['rawRecordCount']);
        $days = $service->list();
        self::assertNull($days[0]['sleep']);
        self::assertSame(4200, $days[0]['activity']['steps']);
        self::assertSame(450, $days[1]['sleep']['totalMinutes']);
        self::assertNull($days[1]['activity']['steps']);
        self::assertArrayNotHasKey('samples', $days[0]);
        self::assertCount(3, $service->day('2026-09-30')['samples']['heartRate']);
        self::assertSame(self::row(), $service->raw($days[0]['rawRecordIds'][0])['payload']);
        self::assertCount(1, $repository->list('sleep'));
        self::assertStringNotContainsString('fixture-token', json_encode($result));
        self::assertStringNotContainsString('PROVIDER SECRET', json_encode($result));
    }

    public function testRepeatSyncIsIdempotentAndProviderFailurePreservesRecords(): void
    {
        [$service] = $this->service([...self::responses(), ...self::responses(), new Response(401, [], 'PROVIDER SECRET')]);
        $service->sync(2);
        self::assertSame(2, $service->sync(2)['unchanged']);
        self::assertSame(1, $service->status()['rawRecordCount']);
        $previous = $service->list();
        self::assertSame('auth_required', $service->sync(2)['state']);
        self::assertSame($previous, $service->list());
        self::assertNotNull($service->status()['lastSuccessAt']);
    }

    public function testFailedSyncStatusCommitsForIndependentDatabaseReaders(): void
    {
        [$service] = $this->service([new Response(401)], $this->directory.'/fixture.db');
        self::assertSame('auth_required', $service->sync(2)['state']);
        $independent = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'path' => $this->directory.'/fixture.db']);
        foreach (['zepp-sync', 'sleep-sync'] as $kind) {
            $payload = json_decode($independent->fetchOne('SELECT payload FROM TrainingRecord WHERE kind = ?', [$kind]), true);
            self::assertSame('auth_required', $payload['state']);
        }
        $independent->close();
    }

    public function testAccountFenceIncludesOldSleepInstallation(): void
    {
        [$service, $repository] = $this->service([]);
        $repository->save('sleep-sync', 'zepp', ['accountKey' => hash('sha256', 'different-account')], 0);
        self::assertSame('error', $service->sync(2)['state']);
        self::assertSame([], $service->list());
    }

    public function testPartialFieldsRetainExistingSamplesStagesAndMeasurements(): void
    {
        $row = self::row();
        $summary = json_decode(base64_decode($row['summary']), true);
        $summary['slp']['stage'] = [['start' => 1, 'stop' => 2, 'mode' => 4]];
        $summary['stp']['stage'] = [['start' => 3, 'stop' => 4, 'mode' => 1]];
        $row['summary'] = $summary;
        $partial = ['date_time' => '2026-09-30', 'summary' => ['tz' => 28800, 'pai' => ['restHr' => 62], 'stp' => ['ttl' => 4300]]];
        $wakeDay = ['date_time' => '2026-09-29', 'summary' => ['tz' => 28800, 'stp' => ['ttl' => 4000]]];
        [$service] = $this->service([
            new Response(200, [], json_encode(['code' => 1, 'data' => [$row]])), ...array_fill(0, 4, new Response(403)),
            new Response(200, [], json_encode(['code' => 1, 'data' => [$partial, $wakeDay]])), ...array_fill(0, 4, new Response(403)),
        ]);
        $service->sync(2);
        $before = $service->day('2026-09-30');
        $sleepBefore = $service->day('2026-09-29');
        $service->sync(2);
        $after = $service->day('2026-09-30');
        self::assertSame(62, $after['heartRate']['restingBpm']);
        self::assertSame(3, $after['heartRate']['sampleCount']);
        self::assertSame($before['samples'], $after['samples']);
        self::assertSame($before['heartRate']['averageBpm'], $after['heartRate']['averageBpm']);
        self::assertSame(4300, $after['activity']['steps']);
        self::assertSame($before['activity']['distanceMeters'], $after['activity']['distanceMeters']);
        self::assertSame($before['activityStages'], $after['activityStages']);
        self::assertSame($sleepBefore['sleepStages'], $service->day('2026-09-29')['sleepStages']);
        self::assertContains('samples.heartRate', $after['retainedFields']);
    }

    public function testMultipleDevicesFillMissingFieldsWithoutAddingOverlappingMeasurements(): void
    {
        $heart = self::row();
        $summary = json_decode(base64_decode($heart['summary']), true);
        unset($summary['stp']);
        $heart['summary'] = $summary;
        $steps = ['date_time' => '2026-09-30', 'summary' => ['tz' => 28800, 'stp' => ['ttl' => 5000, 'dis' => 3500]]];
        [$service] = $this->service([new Response(200, [], json_encode(['code' => 1, 'data' => [$steps, $heart]])), ...array_fill(0, 4, new Response(403))]);
        $service->sync(2);
        $day = $service->day('2026-09-30');
        self::assertSame(5000, $day['activity']['steps']);
        self::assertSame(3, $day['heartRate']['sampleCount']);
        self::assertCount(2, $day['rawRecordIds']);
        self::assertNotSame($day['fieldSources']['activity.steps'], $day['fieldSources']['heartRate.averageBpm']);
        self::assertSame($steps, $service->raw($day['fieldSources']['activity.steps'][0])['payload']);
    }

    public function testLegacyImporterWithIndependentLockStoreCannotEnterDetailedDatabaseClaim(): void
    {
        $legacy = null;
        $blocked = false;
        $interleaved = static function () use (&$legacy, &$blocked): Response {
            try {
                $legacy->sync(2);
            } catch (TrainingError $error) {
                $blocked = 409 === $error->status;
            }

            return self::bandResponse();
        };
        [$service, $repository] = $this->service([$interleaved, ...array_fill(0, 4, new Response(403))]);
        // Ten minutes after the detailed claim, the old five-minute timeout must not win.
        $legacy = new SleepService($repository, new ZeppCredentials(KernelProjectDir::fromString($this->directory)), new ZeppSleepClient(new Client(['handler' => HandlerStack::create(new MockHandler([]))])), new ZeppSleepParser(), new ReminderTestClock('2026-09-30T08:10:00Z'), new LockFactory(new InMemoryStore()));
        $service->sync(2);
        self::assertTrue($blocked);
        self::assertCount(1, $repository->list('sleep'));
    }

    public function testWorkoutAccountOwnershipBlocksBothDailyAndLegacySleepImports(): void
    {
        [$service, $repository] = $this->service([]);
        $repository->save('zepp-workout-sync', 'default', ['accountKey' => hash('sha256', 'stridehub-zepp-sleep:other-account')], 0);
        self::assertSame('error', $service->sync(2)['state']);
        $legacy = new SleepService($repository, new ZeppCredentials(KernelProjectDir::fromString($this->directory)), new ZeppSleepClient(new Client(['handler' => HandlerStack::create(new MockHandler([]))])), new ZeppSleepParser(), new ReminderTestClock('2026-09-30T08:00:00Z'), new LockFactory(new InMemoryStore()));
        self::assertSame('error', $legacy->sync(2)['state']);
        self::assertSame([], $repository->list('sleep'));
        self::assertSame([], $service->list());
    }

    public function testEventsRetainUnknownFieldsAndExposeMeasuredValuesOnly(): void
    {
        $at = strtotime('2026-09-30T12:00:00+08:00') * 1000;
        [$service] = $this->service([
            self::bandResponse(), new Response(200, [], json_encode(['items' => [['timestamp' => $at, 'avgStress' => 20, 'maxStress' => 40, 'data' => json_encode([['time' => $at, 'value' => 20]])]]])),
            new Response(200, [], json_encode(['items' => [['timestamp' => $at, 'extra' => ['spo2' => 98], 'unmapped' => 'preserved']]])),
            new Response(200, [], '{"items":[]}'), new Response(200, [], '{"items":[]}'),
        ]);
        self::assertSame('success', $service->sync(2)['state']);
        $day = $service->day('2026-09-30');
        self::assertSame(20, $day['stress']['average']);
        self::assertSame(98, $day['oxygen']['averagePercent']);
        self::assertSame('available', $day['coverage']['oxygen']);
        self::assertCount(3, $day['rawRecordIds']);
        self::assertSame('unavailable', $service->status()['sources']['body']['state']);
    }

    /** @param list<Response|callable> $responses
     * @return array{ZeppDataService,TrainingRepository}
     */
    private function service(array $responses, ?string $databasePath = null): array
    {
        $clock = new ReminderTestClock('2026-09-30T08:00:00Z');
        $connection = DriverManager::getConnection(null === $databasePath ? ['driver' => 'pdo_sqlite', 'memory' => true] : ['driver' => 'pdo_sqlite', 'path' => $databasePath]);
        $connection->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $repository = new TrainingRepository($connection, $clock);

        return [new ZeppDataService($repository, new ZeppCredentials(KernelProjectDir::fromString($this->directory)), new ZeppDataClient(new Client(['handler' => HandlerStack::create(new MockHandler($responses))])), new ZeppDataParser(new ZeppSleepParser()), $clock, new LockFactory(new InMemoryStore())), $repository];
    }

    /** @return list<Response> */
    private static function responses(): array
    {
        return [self::bandResponse(), ...array_fill(0, 4, new Response(403, [], 'PROVIDER SECRET'))];
    }

    private static function bandResponse(): Response
    {
        return new Response(200, [], json_encode(['code' => 1, 'data' => [self::row()]]));
    }

    /** @return array<string,mixed> */
    private static function row(): array
    {
        return ['date_time' => '2026-09-30', 'data_hr' => base64_encode(chr(255).chr(254).chr(60).chr(80).chr(100).str_repeat(chr(0), 1435)), 'data' => 'uninterpreted-source', 'summary' => base64_encode(json_encode([
            'tz' => '28800', 'stp' => ['ttl' => 4200, 'dis' => 3000, 'cal' => 220, 'wk' => 30, 'rn' => 5],
            'slp' => ['st' => strtotime('2026-09-28T23:00:00+08:00'), 'ed' => strtotime('2026-09-29T07:00:00+08:00'), 'dp' => 90, 'lt' => 300, 'dt' => 60, 'wk' => 30, 'supRem' => true, 'ss' => 80, 'rhr' => 0, 'wc' => 0],
        ]))];
    }
}
