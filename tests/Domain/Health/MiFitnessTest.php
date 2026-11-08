<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\MiFitnessClient;
use App\Domain\Health\MiFitnessCredentials;
use App\Domain\Health\MiFitnessService;
use App\Domain\Health\MiFitnessSleepParser;
use App\Domain\Health\MiFitnessSyncError;
use App\Domain\Health\SleepService;
use App\Domain\Health\ZeppCredentials;
use App\Domain\Health\ZeppSleepClient;
use App\Domain\Health\ZeppSleepParser;
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

final class MiFitnessTest extends TestCase
{
    private string $directory;

    protected function setUp(): void
    {
        $this->directory = sys_get_temp_dir().'/stridehub-mi-test-'.bin2hex(random_bytes(6));
        mkdir($this->directory, 0700);
        $_ENV['MI_FITNESS_CREDENTIALS_FILE'] = $this->directory.'/credentials.json';
        $this->credentials();
    }

    protected function tearDown(): void
    {
        unlink($this->directory.'/credentials.json');
        rmdir($this->directory);
        unset($_ENV['MI_FITNESS_CREDENTIALS_FILE']);
    }

    private function credentials(string $user = 'fixture'): void
    {
        file_put_contents($this->directory.'/credentials.json', json_encode(['baseUrl' => 'http://127.0.0.1:8321', 'apiKey' => 'test-secret', 'userId' => $user]));
        chmod($this->directory.'/credentials.json', 0600);
        clearstatcache();
    }

    private static function row(array $patch = []): array
    {
        return [...['sleep_id' => 'device-night', 'start_at' => '2026-11-04T23:00:00+08:00', 'end_at' => '2026-11-05T07:00:00+08:00', 'duration_minutes' => 480, 'time_asleep_minutes' => 450, 'time_awake_minutes' => 30, 'sleep_score' => 85, 'is_nap' => 0, 'stages' => [['stage' => 'deep', 'minutes' => 90], ['stage' => 'light', 'minutes' => 300], ['stage' => 'rem', 'minutes' => 60]]], ...$patch];
    }

    private static function responses(array $rows): array
    {
        return array_map(static fn (array $body): Response => new Response(200, [], json_encode($body)), [['user_id' => 'fixture'], ['status' => 'ok', 'data_types_synced' => ['sleep']], ['status' => 'ok', 'count' => count($rows), 'data' => $rows], ['user_id' => 'fixture']]);
    }

    private function services(array $responses): array
    {
        $clock = new ReminderTestClock('2026-11-06T00:00:00Z');
        $db = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $db->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $repo = new TrainingRepository($db, $clock);
        $locks = new LockFactory(new InMemoryStore());
        $dir = KernelProjectDir::fromString($this->directory);
        $http = new Client(['handler' => HandlerStack::create(new MockHandler($responses))]);

        return [new MiFitnessService($repo, new MiFitnessCredentials($dir), new MiFitnessClient($http), new MiFitnessSleepParser(), $clock, $locks), $repo,
            new SleepService($repo, new ZeppCredentials($dir), new ZeppSleepClient($http), new ZeppSleepParser(), $clock, $locks)];
    }

    public function testCompletenessIdempotenceCorrectionsAndFailurePreserveSources(): void
    {
        [$mi, $repo, $sleep] = $this->services([...self::responses([self::row()]), ...self::responses([self::row()]), ...self::responses([self::row(['sleep_score' => 88])]), new Response(401)]);
        $repo->save('sleep', 'zepp-original', ['source' => 'zepp', 'sourceLabel' => 'Zepp', 'date' => '2026-11-05', 'endedAt' => '2026-11-05T08:00:00+08:00', 'timeZone' => '+08:00', 'totalMinutes' => 500], 0);
        $repo->save('check-ins', '2026-11-05', ['date' => '2026-11-05', 'sleepHours' => 6], 0);
        self::assertSame(1, $mi->sync()['imported']);
        self::assertSame(1, $mi->sync()['unchanged']);
        self::assertSame(1, $mi->sync()['updated']);
        self::assertSame('mi_fitness', $sleep->forDate('2026-11-05')['source']);
        self::assertSame(88, $sleep->forDate('2026-11-05', 'Asia/Shanghai')['score']);
        self::assertCount(1, $sleep->daily());
        self::assertCount(2, $sleep->list());
        self::assertSame('auth_required', $mi->sync()['state']);
        self::assertSame(88, $sleep->daily()[0]['score']);
        self::assertSame(6, $repo->find('check-ins', '2026-11-05')['sleepHours']);
        self::assertSame(500, $repo->find('sleep', 'zepp-original')['totalMinutes']);
    }

    public function testNapsDoNotReplaceMainSleepAndEmptyResponseDoesNotEraseHistory(): void
    {
        $nap = self::row(['sleep_id' => 'nap', 'is_nap' => 1, 'start_at' => '2026-11-05T13:00:00+08:00', 'end_at' => '2026-11-05T14:00:00+08:00', 'time_asleep_minutes' => 60, 'time_awake_minutes' => 0, 'stages' => []]);
        [$mi, $repo, $sleep] = $this->services([...self::responses([self::row(), $nap]), ...self::responses([])]);
        self::assertSame(2, $mi->sync()['imported']);
        self::assertCount(1, $sleep->daily());
        self::assertCount(2, $sleep->list());
        self::assertFalse($sleep->forDate('2026-11-05')['isNap']);
        self::assertSame('no_data', $mi->sync()['state']);
        self::assertCount(2, $sleep->list());
        $this->credentials('another');
        self::assertSame('auth_required', $mi->sync()['state']);
        self::assertCount(2, $sleep->list());
    }

    public function testMalformedAndPartialCloudResponsesCannotBecomeSuccessfulEmptySync(): void
    {
        [$mi] = $this->services([new Response(200, [], '{"user_id":"fixture"}'), new Response(200, [], '{"status":"partial","data_types_synced":[]}')]);
        self::assertSame('error', $mi->sync()['state']);
        self::assertNull($mi->status()['lastSuccessAt']);
        [$mi] = $this->services(self::responses([self::row(['time_asleep_minutes' => 9999])]));
        self::assertSame('partial', $mi->sync()['state']);
        self::assertSame(1, $mi->status()['skipped']);
        self::assertNull($mi->status()['lastSuccessAt']);
    }

    public function testParserPreservesUnknownsAndWakeDateAndRejectsImpossibleTimes(): void
    {
        $parser = new MiFitnessSleepParser();
        $zone = new \DateTimeZone('Asia/Shanghai');
        $row = $parser->parse(self::row(['stages' => [], 'sleep_score' => 0]), $zone);
        self::assertSame('2026-11-05', $row['date']);
        self::assertNull($row['deepMinutes']);
        self::assertNull($row['score']);
        self::assertNull($row['restingHeartRate']);
        self::assertNull($parser->parse(self::row(['start_at' => '2026-11-04T23:00:00']), $zone));
        self::assertNull($parser->parse(self::row(['end_at' => '2026-11-04T20:00:00+08:00']), $zone));
    }

    public function testHttpContractAndAccountGuard(): void
    {
        $history = [];
        $stack = HandlerStack::create(new MockHandler(self::responses([self::row()])));
        $stack->push(Middleware::history($history));
        $client = new MiFitnessClient(new Client(['handler' => $stack]));
        $credentials = new MiFitnessCredentials(KernelProjectDir::fromString($this->directory))->read();
        self::assertCount(1, $client->fetch($credentials, '2026-11-01', '2026-11-06'));
        self::assertSame('test-secret', $history[0]['request']->getHeaderLine('X-API-Key'));
        self::assertSame(['sleep'], json_decode((string) $history[1]['request']->getBody(), true)['data_types']);
        self::assertFalse($history[1]['options']['allow_redirects']);
        $client = new MiFitnessClient(new Client(['handler' => HandlerStack::create(new MockHandler([new Response(200, [], '{"user_id":"other"}')]))]));
        $this->expectException(MiFitnessSyncError::class);
        $client->fetch($credentials, '2026-11-01', '2026-11-06');
    }
}
