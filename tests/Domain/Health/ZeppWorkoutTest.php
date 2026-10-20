<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\ZeppCredentials;
use App\Domain\Health\ZeppWorkoutClient;
use App\Domain\Health\ZeppWorkoutParser;
use App\Domain\Health\ZeppWorkoutService;
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

final class ZeppWorkoutTest extends TestCase
{
    private string $directory;

    protected function setUp(): void
    {
        $this->directory = sys_get_temp_dir().'/stridehub-zepp-workouts-'.bin2hex(random_bytes(8));
        mkdir($this->directory.'/storage/zepp', 0700, true);
        unset($_ENV['ZEPP_CREDENTIALS_FILE'], $_SERVER['ZEPP_CREDENTIALS_FILE']);
        file_put_contents($this->directory.'/storage/zepp/credentials.json', json_encode(['userId' => 'fixture-user', 'host' => 'api-mifit-cn3.zepp.com', 'appToken' => 'fixture-token']));
        chmod($this->directory.'/storage/zepp/credentials.json', 0600);
    }

    protected function tearDown(): void
    {
        unlink($this->directory.'/storage/zepp/credentials.json');
        rmdir($this->directory.'/storage/zepp');
        rmdir($this->directory.'/storage');
        rmdir($this->directory);
    }

    public function testDecoderKeepsIrregularMeasuredTimestampsAndCorrectUnits(): void
    {
        $parser = new ZeppWorkoutParser();
        $record = $parser->summary(self::summary(), new \DateTimeZone('Asia/Shanghai'));
        $streams = $parser->streams(self::detail(), $record['startedAt'], $record['endedAt']);
        self::assertSame(5000, $record['distanceMeters']);
        self::assertSame('2026-09-30', $record['date']);
        self::assertSame([100.0, 105.0, 103.0], array_column($streams['heartRate'], 'bpm'));
        self::assertSame('2026-09-30T08:00:09+08:00', $streams['heartRate'][2]['at']);
        self::assertSame(12.5, $streams['distance'][1]['meters']);
        self::assertSame(1.1, $streams['stride'][0]['meters']);
        self::assertSame(160, $streams['cadence'][0]['stepsPerMinute']);
        self::assertEquals(31.000001, $streams['location'][1]['latitude']);
        self::assertEquals(121.000002, $streams['location'][1]['longitude']);
        self::assertEquals(10, $streams['altitude'][0]['meters']);
        self::assertSame([], $streams['power']);
        self::assertCount(2, $streams['pace']);
        self::assertSame(500.0, $streams['pace'][0]['secondsPerKilometer']);
    }

    public function testPaginationDetailPreservationAndIdempotentSync(): void
    {
        $responses = [];
        for ($i = 0; $i < 3; ++$i) {
            $responses[] = self::response(['summary' => [self::summary()], 'next' => -1]);
            $responses[] = 2 === $i ? new Response(403, [], 'PROVIDER SECRET') : self::response(self::detail());
        }
        [$service] = $this->service($responses);
        self::assertSame('success', $service->sync(2)['state']);
        self::assertSame(1, $service->status()['recordCount']);
        $record = $service->list()[0];
        self::assertArrayNotHasKey('sourceDetail', $record);
        self::assertSame(3, $record['streamCounts']['heartRate']);
        self::assertSame(self::detail(), $service->detail($record['id'])['sourceDetail']);
        self::assertSame('future-value', $service->detail($record['id'])['sourceSummary']['unmapped']);
        self::assertCount(1, $service->list('2026-09-30', '2026-09-30'));
        self::assertSame([], $service->list('2026-09-23', '2026-09-29'));
        $first = $service->detail($record['id']);
        $result = $service->sync(2);
        $second = $service->detail($record['id']);
        unset($first['version'], $second['version']);
        self::assertSame(json_encode($first), json_encode($second));
        self::assertSame(1, $result['unchanged']);
        self::assertSame(1, $service->list()[0]['version']);
        self::assertSame('partial', $service->sync(2)['state']);
        self::assertSame('stale', $service->list()[0]['detailState']);
        self::assertSame(self::detail(), $service->detail($record['id'])['sourceDetail']);
        self::assertStringNotContainsString('fixture-token', json_encode($service->status()));
        self::assertStringNotContainsString('PROVIDER SECRET', json_encode($service->status()));
    }

    public function testHistoryFailureAndAccountFenceDoNotDestroyRecords(): void
    {
        [$service, $repository] = $this->service([self::response(['summary' => [self::summary()], 'next' => -1]), self::response(self::detail()), new Response(401, [], 'secret')]);
        $service->sync(2);
        $previous = $service->list();
        self::assertSame('auth_required', $service->sync(2)['state']);
        self::assertSame($previous, $service->list());
        $repository->save('sleep-sync', 'zepp', ['accountKey' => 'different-account'], 0);
        self::assertSame('error', $service->sync(2)['state']);
        self::assertSame($previous, $service->list());
    }

    public function testClientUsesCountAndTrackCursorWithoutFollowingRedirects(): void
    {
        $history = [];
        $stack = HandlerStack::create(new MockHandler([self::response(['summary' => [], 'next' => 1200])]));
        $stack->push(Middleware::history($history));
        $client = new ZeppWorkoutClient(new Client(['handler' => $stack]));
        self::assertSame(1200, $client->history(['userId' => 'fixture-user', 'host' => 'api-mifit-cn3.zepp.com', 'appToken' => 'fixture-token'], 1300)['next']);
        parse_str($history[0]['request']->getUri()->getQuery(), $query);
        self::assertSame('50', $query['count']);
        self::assertSame('1300', $query['trackid']);
        self::assertFalse($history[0]['options']['allow_redirects']);
    }

    public function testPartialSuccessfulPayloadRetainsKnownMeasurementsAndSourceSnapshots(): void
    {
        $partialSummary = self::summary();
        $partialSummary['dis'] = null;
        $partialSummary['avg_heart_rate'] = null;
        [$service] = $this->service([
            self::response(['summary' => [self::summary()], 'next' => -1]), self::response(self::detail()),
            self::response(['summary' => [$partialSummary], 'next' => -1]), self::response(['trackid' => self::summary()['trackid']]),
        ]);
        $service->sync(2);
        $id = $service->list()[0]['id'];
        $old = $service->detail($id);
        $service->sync(2);
        $current = $service->detail($id);
        self::assertSame('partial', $current['detailState']);
        self::assertSame(5000, $current['distanceMeters']);
        self::assertSame(140, $current['averageHeartRateBpm']);
        self::assertSame($old['streams'], $current['streams']);
        self::assertContains('streams.heartRate', $current['retainedFields']);
        self::assertContains('distanceMeters', $current['retainedFields']);
        self::assertCount(4, $current['sourceRecordIds']);
        self::assertContains(self::detail(), array_column(array_map($service->source(...), $current['sourceRecordIds']), 'payload'));
    }

    public function testInvalidOrEmptyGpsSampleDoesNotInventPositionOrShiftAltitude(): void
    {
        $parser = new ZeppWorkoutParser();
        $summary = $parser->summary(self::summary(), new \DateTimeZone('Asia/Shanghai'));
        foreach (['bad,bad', ''] as $gap) {
            $streams = $parser->streams(['time' => '0;4;4', 'longitude_latitude' => '3100000000,12100000000;'.$gap.';100,200', 'altitude' => '1000;;1200'], $summary['startedAt'], $summary['endedAt']);
            self::assertCount(1, $streams['location']);
            self::assertCount(2, $streams['altitude']);
            self::assertSame('2026-09-30T08:00:08+08:00', $streams['altitude'][1]['at']);
        }
    }

    public function testEmptyHeartRateTimeDeltaMeansOneSecondAndStillAccumulatesValues(): void
    {
        $parser = new ZeppWorkoutParser();
        $summary = $parser->summary(self::summary(), new \DateTimeZone('Asia/Shanghai'));
        $streams = $parser->streams(['heart_rate' => '0,100;,5;,-2', 'speed' => '0,2;,2.5'], $summary['startedAt'], $summary['endedAt']);
        self::assertSame([100.0, 105.0, 103.0], array_column($streams['heartRate'], 'bpm'));
        self::assertSame('2026-09-30T08:00:02+08:00', $streams['heartRate'][2]['at']);
        self::assertCount(2, $streams['speed']);
    }

    public function testReplacedSyncClaimCannotWriteAnyStaleWorkoutOrRawSource(): void
    {
        $repository = null;
        [$service, $repository] = $this->service([
            self::response(['summary' => [self::summary()], 'next' => -1]),
            static function () use (&$repository): Response {
                $claim = $repository->find('zepp-workout-sync', 'default');
                $repository->save('zepp-workout-sync', 'default', [...$claim, 'state' => 'success'], $claim['version']);

                return self::response(self::detail());
            },
        ]);
        try {
            $service->sync(2);
            self::fail('A stale owner must fail its transaction before writing records.');
        } catch (TrainingError $error) {
            self::assertSame(409, $error->status);
        }
        self::assertSame([], $service->list());
        self::assertSame([], $repository->list('zepp-workout-source'));
        self::assertSame('success', $service->status()['state']);
    }

    /** @param list<Response> $responses
     * @return array{ZeppWorkoutService,TrainingRepository}
     */
    private function service(array $responses): array
    {
        $clock = new ReminderTestClock('2026-09-30T08:00:00Z');
        $connection = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $connection->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $repository = new TrainingRepository($connection, $clock);

        return [new ZeppWorkoutService($repository, new ZeppCredentials(KernelProjectDir::fromString($this->directory)), new ZeppWorkoutClient(new Client(['handler' => HandlerStack::create(new MockHandler($responses))])), new ZeppWorkoutParser(), $clock, new LockFactory(new InMemoryStore())), $repository];
    }

    /** @param array<string,mixed> $data */
    private static function response(array $data): Response
    {
        return new Response(200, [], json_encode(['code' => 1, 'data' => $data]));
    }

    /** @return array<string,mixed> */
    private static function summary(): array
    {
        return ['trackid' => strtotime('2026-09-30T08:00:00+08:00'), 'end_time' => strtotime('2026-09-30T09:00:00+08:00'), 'type' => 1, 'dis' => 5000, 'run_time' => 3600,
            'calorie' => 300, 'avg_heart_rate' => 140, 'max_heart_rate' => 170, 'source' => 'run.mi.com', 'unmapped' => 'future-value'];
    }

    /** @return array<string,mixed> */
    private static function detail(): array
    {
        return ['trackid' => self::summary()['trackid'], 'heart_rate' => '0,100;4,5;5,-2', 'time' => '0;4', 'longitude_latitude' => '3100000000,12100000000;100,200',
            'altitude' => '1000;1200', 'gait' => '0,0,110,160;4,0,120,164', 'speed' => '0,2;4,2.5', 'currentDistance' => '0,0;4,1250', 'futureStream' => 'opaque'];
    }
}
