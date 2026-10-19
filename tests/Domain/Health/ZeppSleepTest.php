<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\SleepService;
use App\Domain\Health\ZeppCredentials;
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

final class ZeppSleepTest extends TestCase
{
    private string $directory;

    protected function setUp(): void
    {
        $this->directory = sys_get_temp_dir().'/stridehub-zepp-test-'.bin2hex(random_bytes(8));
        mkdir($this->directory.'/storage/zepp', 0700, true);
        unset($_ENV['ZEPP_CREDENTIALS_FILE'], $_SERVER['ZEPP_CREDENTIALS_FILE']);
        $this->credentials();
    }

    protected function tearDown(): void
    {
        if (is_file($this->directory.'/storage/zepp/credentials.json')) {
            unlink($this->directory.'/storage/zepp/credentials.json');
        }
        rmdir($this->directory.'/storage/zepp');
        rmdir($this->directory.'/storage');
        rmdir($this->directory);
    }

    public function testRealProtocolShapeNormalizesWakeDayOffsetAndNullableMeasurements(): void
    {
        $parser = new ZeppSleepParser();
        $parsed = $parser->parse(self::row());
        self::assertSame('2026-09-29', $parsed['date']);
        self::assertSame('2026-09-30', $parsed['sourceDate']);
        self::assertSame('+08:00', $parsed['timeZone']);
        self::assertSame('2026-09-28T23:00:00+08:00', $parsed['startedAt']);
        self::assertSame(450, $parsed['totalMinutes']);
        self::assertSame(60, $parsed['remMinutes']);
        self::assertNull($parsed['restingHeartRate']);
        self::assertSame(0, $parsed['wakeCount']);
        $unsupported = $parser->parse(self::row(['supRem' => false, 'dt' => 0]));
        self::assertNull($unsupported['remMinutes']);
        self::assertSame(390, $unsupported['totalMinutes']);
        $missing = $parser->parse(self::row(['dp' => null, 'wc' => null, 'ss' => 0]));
        self::assertNull($missing['deepMinutes']);
        self::assertNull($missing['totalMinutes']);
        self::assertNull($missing['wakeCount']);
        self::assertNull($missing['score']);
    }

    public function testEmptyInvalidAndUnsupportedPayloadsDoNotBecomeZeroHourNights(): void
    {
        $parser = new ZeppSleepParser();
        self::assertNull($parser->parse(self::row(['st' => 0, 'ed' => 0, 'dp' => 0, 'lt' => 0, 'dt' => 0])));
        self::assertNull($parser->parse(self::row(['dp' => 0, 'lt' => 0, 'dt' => 0])));
        self::assertNull($parser->parse(self::row(['dp' => 1000]))['totalMinutes']);
        self::assertNull($parser->parse(['date_time' => '2026-02-30', 'summary' => 'bad']));
        self::assertNull($parser->parse(['date_time' => '2026-09-30', 'summary' => 'bad']));
        self::assertNull($parser->parse(self::row(['ed' => strtotime('2026-09-28T22:00:00+08:00')])));
        $row = self::row();
        $decoded = json_decode(base64_decode($row['summary']), true);
        unset($decoded['tz']);
        $row['summary'] = base64_encode(json_encode($decoded));
        $parsed = $parser->parse($row);
        self::assertNull($parsed['timeZone']);
        self::assertSame('2026-09-30', $parsed['date']);
        self::assertStringEndsWith('+00:00', $parsed['endedAt']);
    }

    public function testSyncPersistsOnceUpdatesCorrectionsAndNeverOverwritesManualCheckIn(): void
    {
        [$service, $repository] = $this->service([
            self::response([self::row()]), self::response([self::row()]), self::response([self::row(['ss' => 88])]),
            self::response([self::row(['st' => 0, 'ed' => 0, 'dp' => 0, 'lt' => 0, 'dt' => 0])]),
        ]);
        $manual = $repository->save('check-ins', '2026-09-29', ['date' => '2026-09-29', 'sleepHours' => 6.5], 0);
        self::assertSame(1, $service->sync(90)['imported']);
        $first = $service->forDate('2026-09-29');
        self::assertSame(1, $service->sync(90)['unchanged']);
        self::assertSame($first, $service->forDate('2026-09-29'));
        self::assertSame(1, $service->sync(90)['updated']);
        self::assertSame(2, $service->forDate('2026-09-29')['version']);
        self::assertSame(88, $service->forDate('2026-09-29')['score']);
        self::assertSame('no_data', $service->sync(90)['state']);
        self::assertCount(1, $service->list());
        self::assertSame([], $service->list('2026-09-30', '2026-09-30'));
        self::assertSame($manual, $repository->find('check-ins', '2026-09-29'));
    }

    public function testAuthErrorAndRetryPreserveExistingDataAndNeverLeakProviderDetails(): void
    {
        [$service] = $this->service([self::response([self::row()]), new Response(401, [], 'SECRET ACCOUNT DETAIL'), self::response([self::row()])]);
        $service->sync();
        $failed = $service->sync();
        self::assertSame('auth_required', $failed['state']);
        self::assertNotNull($failed['lastSuccessAt']);
        self::assertSame(1, $failed['recordCount']);
        self::assertStringNotContainsString('SECRET', json_encode($failed));
        self::assertSame('success', $service->sync()['state']);
    }

    public function testNewAccountCannotMixIntoExistingSleepData(): void
    {
        [$service] = $this->service([self::response([self::row()])]);
        $service->sync();
        $this->credentials('another-user');
        self::assertSame('error', $service->sync()['state']);
        self::assertSame(1, $service->status()['recordCount']);
        self::assertStringContainsString('账号', $service->status()['lastError']);
    }

    public function testCredentialAllowlistPermissionsAndMissingConfig(): void
    {
        $credentials = new ZeppCredentials(KernelProjectDir::fromString($this->directory));
        $this->credentials(host: 'api-mifit.zepp.com.attacker.example');
        try {
            $credentials->read();
            self::fail('Host outside the Zepp allowlist must fail.');
        } catch (ZeppSyncError $error) {
            self::assertSame('error', $error->state);
        }
        $this->credentials();
        chmod($this->directory.'/storage/zepp/credentials.json', 0644);
        clearstatcache();
        try {
            $credentials->read();
            self::fail('World-readable credentials must fail.');
        } catch (ZeppSyncError $error) {
            self::assertSame('error', $error->state);
        }
        unlink($this->directory.'/storage/zepp/credentials.json');
        clearstatcache();
        [$service] = $this->service([]);
        self::assertSame('not_configured', $service->sync()['state']);
    }

    public function testRedirectIsNotFollowedAndInvalidProviderShapeIsSafe(): void
    {
        $history = [];
        $stack = HandlerStack::create(new MockHandler([new Response(302, ['Location' => 'https://attacker.example']), new Response(200, [], '{"code":1,"data":{}}')]));
        $stack->push(Middleware::history($history));
        $client = new ZeppSleepClient(new Client(['handler' => $stack]));
        $credentials = (new ZeppCredentials(KernelProjectDir::fromString($this->directory)))->read();
        foreach (range(1, 2) as $unused) {
            try {
                $client->fetch($credentials, '2026-09-01', '2026-09-30');
                self::fail('Malformed response must fail.');
            } catch (ZeppSyncError $error) {
                self::assertSame('error', $error->state);
            }
        }
        self::assertCount(2, $history);
        self::assertSame('api-mifit-cn3.zepp.com', $history[0]['request']->getUri()->getHost());
        self::assertFalse($history[0]['options']['allow_redirects']);
    }

    public function testParallelSyncAndBadRangeAreRejected(): void
    {
        $locks = new LockFactory(new InMemoryStore());
        [$service] = $this->service([], $locks);
        $lock = $locks->createLock('stridehub-zepp-sleep', 300);
        $lock->acquire();
        try {
            $service->sync();
            self::fail('Concurrent sync must fail.');
        } catch (TrainingError $error) {
            self::assertSame(409, $error->status);
        } finally {
            $lock->release();
        }
        $this->expectException(TrainingError::class);
        $service->list('2026-09-30', '2026-09-01');
    }

    public function testDailyLookupUsesProfileTimezoneAndSkipsFutureAndUnknownOffset(): void
    {
        [$service, $repository] = $this->service([self::response([self::row()])]);
        $service->sync();
        self::assertNotNull($service->forDate('2026-09-29', 'Asia/Shanghai'));
        self::assertNotNull($service->forDate('2026-09-28', 'America/Los_Angeles'));
        self::assertNull($service->forDate('2026-09-29', 'America/Los_Angeles'));
        $record = $service->forDate('2026-09-29');
        $repository->save('sleep', 'future', [...$record, 'endedAt' => '2026-10-01T07:00:00+08:00'], 0);
        self::assertNull($service->forDate('2026-10-01', 'Asia/Shanghai'));
        $repository->save('sleep', 'unknown-offset', [...$record, 'timeZone' => null, 'endedAt' => '2026-09-30T07:00:00+08:00'], 0);
        self::assertNull($service->forDate('2026-09-30', 'Asia/Shanghai'));
    }

    public function testSyncBuffersSourceDatesAndFiltersUsingActualWakeDate(): void
    {
        $today = self::row(['st' => strtotime('2026-09-29T23:00:00+08:00'), 'ed' => strtotime('2026-09-30T07:00:00+08:00')]);
        $today['date_time'] = '2026-10-01';
        [$service] = $this->service([self::response([self::row(), $today])]);
        $result = $service->sync(1);
        self::assertSame(1, $result['imported']);
        self::assertSame(1, $result['skipped']);
        self::assertSame('2026-10-01', $service->forDate('2026-09-30')['sourceDate']);
    }

    private function credentials(string $user = 'fixture-user', string $host = 'api-mifit-cn3.zepp.com'): void
    {
        file_put_contents($this->directory.'/storage/zepp/credentials.json', json_encode(['userId' => $user, 'host' => $host, 'appToken' => 'fixture-token']));
        chmod($this->directory.'/storage/zepp/credentials.json', 0600);
        clearstatcache();
    }

    /** @param list<Response> $responses
     * @return array{SleepService, TrainingRepository}
     */
    private function service(array $responses, ?LockFactory $locks = null): array
    {
        $clock = new ReminderTestClock('2026-09-30T00:00:00Z');
        $connection = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $connection->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $repository = new TrainingRepository($connection, $clock);
        $service = new SleepService($repository, new ZeppCredentials(KernelProjectDir::fromString($this->directory)), new ZeppSleepClient(new Client(['handler' => HandlerStack::create(new MockHandler($responses))])), new ZeppSleepParser(), $clock, $locks ?? new LockFactory(new InMemoryStore()));

        return [$service, $repository];
    }

    /** @param list<array<string, mixed>> $rows */
    private static function response(array $rows): Response
    {
        return new Response(200, [], json_encode(['code' => 1, 'data' => $rows]));
    }

    /** @param array<string, mixed> $sleep */
    private static function row(array $sleep = []): array
    {
        return ['date_time' => '2026-09-30', 'summary' => base64_encode(json_encode(['tz' => '28800', 'slp' => [...[
            'st' => strtotime('2026-09-28T23:00:00+08:00'), 'ed' => strtotime('2026-09-29T07:00:00+08:00'),
            'dp' => 90, 'lt' => 300, 'dt' => 60, 'wk' => 30, 'supRem' => true, 'ss' => 80, 'rhr' => 0, 'wc' => 0,
        ], ...$sleep]]))];
    }
}
