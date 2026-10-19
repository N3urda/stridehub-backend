<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\BloodPressureService;
use App\Domain\Health\DailyHealthObservations;
use App\Domain\Health\HealthContextService;
use App\Domain\Health\SleepService;
use App\Domain\Health\ZeppCredentials;
use App\Domain\Health\ZeppSleepClient;
use App\Domain\Health\ZeppSleepParser;
use App\Domain\Training\Advice\RunningAdvice;
use App\Domain\Training\TrainingActivities;
use App\Domain\Training\TrainingBriefing;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Domain\Training\TrainingService;
use App\Domain\Training\TrainingToday;
use App\Domain\Training\Weather\ForecastProvider;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use App\Tests\Domain\Training\Notification\ReminderTestClock;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use GuzzleHttp\Client;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\Lock\Store\InMemoryStore;

final class DailyHealthObservationsTest extends TestCase
{
    private Connection $connection;
    private TrainingRepository $repository;
    private TrainingService $training;
    private DailyHealthObservations $observations;
    private ReminderTestClock $clock;
    private Client $http;
    private mixed $originalCredentials;

    protected function setUp(): void
    {
        $this->originalCredentials = $_ENV['ZEPP_CREDENTIALS_FILE'] ?? null;
        $_ENV['ZEPP_CREDENTIALS_FILE'] = sys_get_temp_dir().'/stridehub-observations-test-no-credentials-'.bin2hex(random_bytes(8));
        $this->connection = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $this->connection->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $this->connection->executeStatement('CREATE TABLE TrainingActivityLink (activityId VARCHAR(255) NOT NULL PRIMARY KEY, sessionId VARCHAR(80) NOT NULL)');
        $this->connection->executeStatement('CREATE TABLE Activity (activityId TEXT PRIMARY KEY, name TEXT, startDateTime TEXT, distance INTEGER, movingTimeInSeconds INTEGER, elapsedTimeInSeconds INTEGER, averageHeartRate INTEGER, sportType TEXT, markedForDeletion INTEGER)');
        $this->clock = new ReminderTestClock('2026-10-19T00:30:00Z');
        $this->repository = new TrainingRepository($this->connection, $this->clock);
        $this->training = new TrainingService($this->repository, new TrainingActivities($this->connection), $this->clock);
        $this->repository->save('profile', 'default', TrainingInput::defaults('profile'), 0);
        $this->http = new Client(['handler' => HandlerStack::create(new MockHandler([]))]);
        $sleep = new SleepService($this->repository, new ZeppCredentials(KernelProjectDir::fromString('/tmp/stridehub-observations-test')), new ZeppSleepClient($this->http), new ZeppSleepParser(), $this->clock, new LockFactory(new InMemoryStore()));
        $this->observations = new DailyHealthObservations(new BloodPressureService($this->repository, $this->training), $sleep, $this->clock, $this->training);
    }

    protected function tearDown(): void
    {
        if (null === $this->originalCredentials) {
            unset($_ENV['ZEPP_CREDENTIALS_FILE']);
        } else {
            $_ENV['ZEPP_CREDENTIALS_FILE'] = $this->originalCredentials;
        }
        $this->connection->close();
    }

    public function testOnlyLatestAlreadyMeasuredPressureOnRequestedLocalDayIsUsed(): void
    {
        $this->pressure('yesterday', '2026-10-18T23:59:59+08:00');
        $this->pressure('early', '2026-10-19T06:00:00+08:00');
        $this->pressure('latest', '2026-10-19T00:00:00Z');
        $this->pressure('later-today', '2026-10-19T09:00:00+08:00');
        $this->pressure('tomorrow', '2026-10-20T07:00:00+08:00');
        $before = $this->storedRows();
        self::assertSame('latest', $this->observations->forDate('2026-10-19')['bloodPressure']['id']);
        self::assertSame('yesterday', $this->observations->forDate('2026-10-18')['bloodPressure']['id']);
        self::assertNull($this->observations->forDate('2026-10-20')['bloodPressure']);
        self::assertSame($before, $this->storedRows());
    }

    public function testEndedSleepIsMatchedByWakeInstantInTrainingTimezone(): void
    {
        $this->sleep('night', self::sleepPayload());
        self::assertSame('night', $this->observations->forDate('2026-10-19')['sleep']['id']);
        $this->training->update('profile', 'default', ['version' => 1, 'timezone' => 'America/New_York']);
        $result = $this->observations->forDate('2026-10-18');
        self::assertSame('night', $result['sleep']['id']);
        self::assertSame('+08:00', $result['sleep']['timeZone'], 'The device source offset is preserved, not relabelled as the profile timezone.');
        self::assertNull($this->observations->forDate('2026-10-19')['sleep']);
    }

    /** @param array<string, mixed> $patch */
    #[DataProvider('unusableSleeps')]
    public function testUncertainOrFutureSleepCannotStandInForKnownTodaysSleep(array $patch): void
    {
        $this->sleep('unusable', [...self::sleepPayload(), ...$patch]);
        self::assertNull($this->observations->forDate('2026-10-19')['sleep']);
    }

    public static function unusableSleeps(): iterable
    {
        yield 'wake time absent' => [['endedAt' => null]];
        yield 'source timezone absent' => [['timeZone' => null]];
        yield 'wake time has not happened' => [['endedAt' => '2026-10-19T10:00:00+08:00']];
    }

    public function testTodayKeepsManualSleepPainAndPlanUnchangedWhenDeviceSleepDiffers(): void
    {
        $this->pressure('morning', '2026-10-19T07:30:00+08:00');
        $this->sleep('night', self::sleepPayload());
        $manual = $this->repository->save('check-ins', '2026-10-19', [...TrainingInput::defaults('check-ins'), 'date' => '2026-10-19', 'sleepHours' => 4.5, 'fatigue' => 4, 'pain' => true, 'notes' => '自己的体感'], 0);
        $this->session('today', '2026-10-19T18:30:00+08:00');
        $before = $this->storedRows();
        $today = new TrainingToday($this->training, $this->repository, $this->clock, new HealthContextService($this->repository), $this->observations)->get();
        self::assertSame($manual, $today['checkIn']);
        self::assertSame(420, $today['observations']['sleep']['totalMinutes']);
        self::assertSame('morning', $today['observations']['bloodPressure']['id']);
        self::assertSame('planned', $today['sessions'][0]['status']);
        self::assertSame($before, $this->storedRows());
    }

    public function testBriefingsDoNotReuseTodaysObservationsForPastOrFutureSessions(): void
    {
        $this->pressure('morning', '2026-10-19T07:30:00+08:00');
        $this->sleep('night', self::sleepPayload());
        $this->repository->save('check-ins', '2026-10-19', [...TrainingInput::defaults('check-ins'), 'date' => '2026-10-19', 'sleepHours' => 4.5, 'pain' => true], 0);
        $weather = new ForecastProvider($this->http, new ArrayAdapter(), $this->clock);
        $briefing = new TrainingBriefing($this->training, $this->repository, $weather, new RunningAdvice(), $this->clock, new HealthContextService($this->repository), $this->observations);
        $this->session('yesterday', '2026-10-18T18:00:00+08:00');
        $this->session('today', '2026-10-19T18:00:00+08:00');
        $this->session('tomorrow', '2026-10-20T18:00:00+08:00');
        $before = $this->storedRows();
        $today = $briefing->generate('today');
        self::assertSame('morning', $today['observations']['bloodPressure']['id']);
        self::assertSame('night', $today['observations']['sleep']['id']);
        self::assertSame(4.5, $today['checkIn']['sleepHours']);
        self::assertTrue($today['checkIn']['pain']);
        foreach (['yesterday', 'tomorrow'] as $id) {
            $result = $briefing->generate($id);
            self::assertNull($result['observations']['bloodPressure']);
            self::assertNull($result['observations']['sleep']);
            self::assertNull($result['checkIn']);
        }
        self::assertSame($before, $this->storedRows());
    }

    public function testMissingObservationsStayUnknownWithoutCreatingMeasurements(): void
    {
        $before = $this->storedRows();
        $result = $this->observations->forDate('2026-10-19');
        self::assertNull($result['bloodPressure']);
        self::assertNull($result['sleep']);
        self::assertSame('not_configured', $result['sleepSync']['state']);
        self::assertSame($before, $this->storedRows());
    }

    private function pressure(string $id, string $at): void
    {
        new BloodPressureService($this->repository, $this->training)->save($id, ['version' => 0, 'measuredAt' => $at, 'systolic' => 120, 'diastolic' => 80]);
    }

    /** @param array<string, mixed> $payload */
    private function sleep(string $id, array $payload): void
    {
        $this->repository->save('sleep', $id, $payload, 0);
    }

    private function session(string $id, string $at): void
    {
        $this->repository->save('sessions', $id, [...TrainingInput::defaults('sessions'), 'title' => 'Fixture easy run', 'type' => 'easy', 'startAt' => $at, 'durationMinutes' => 30], 0);
    }

    /** @return array<string, mixed> */
    private static function sleepPayload(): array
    {
        return ['sourceDate' => '2026-10-19', 'date' => '2026-10-19', 'source' => 'zepp', 'sourceLabel' => 'Zepp / Amazfit', 'timeZone' => '+08:00', 'startedAt' => '2026-10-18T23:00:00+08:00', 'endedAt' => '2026-10-19T06:00:00+08:00', 'totalMinutes' => 420, 'deepMinutes' => 100, 'lightMinutes' => 260, 'remMinutes' => 60, 'awakeMinutes' => null, 'score' => null, 'restingHeartRate' => null, 'wakeCount' => null, 'lastSyncedAt' => '2026-10-19T00:30:00+00:00'];
    }

    /** @return list<array<string, mixed>> */
    private function storedRows(): array
    {
        return $this->connection->fetchAllAssociative('SELECT * FROM TrainingRecord ORDER BY kind,id');
    }
}
