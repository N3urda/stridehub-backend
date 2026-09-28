<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training\Notification;

use App\Domain\Settings\IntegrationsSettings;
use App\Domain\Settings\SettingsRepository;
use App\Domain\Training\Advice\RunningAdvice;
use App\Domain\Training\Notification\ReminderDelivery;
use App\Domain\Training\Notification\ReminderPlanner;
use App\Domain\Training\Notification\TrainingReminderService;
use App\Domain\Training\TrainingActivities;
use App\Domain\Training\TrainingBriefing;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Domain\Training\TrainingService;
use App\Domain\Training\Weather\ForecastProvider;
use Doctrine\DBAL\DriverManager;
use GuzzleHttp\Client;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\Lock\Store\InMemoryStore;

final class TrainingReminderServiceTest extends TestCase
{
    private TrainingRepository $repository;
    private ReminderTestClock $clock;
    private ReminderTestSender $sender;
    private TrainingReminderService $service;

    protected function setUp(): void
    {
        $connection = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $connection->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $this->clock = new ReminderTestClock('2026-10-04T05:35:00+08:00');
        $this->repository = new TrainingRepository($connection, $this->clock);
        $training = new TrainingService($this->repository, new TrainingActivities($connection), $this->clock);
        $weather = new ForecastProvider(new Client(['handler' => HandlerStack::create(new MockHandler([new \RuntimeException('offline')]))]), new ArrayAdapter(), $this->clock);
        $briefing = new TrainingBriefing($training, $this->repository, $weather, new RunningAdvice(), $this->clock);
        $settings = $this->createStub(SettingsRepository::class);
        $settings->method('integrations')->willReturn(IntegrationsSettings::fromArray(['notifications' => ['services' => ['ntfy://example.invalid/private-key']]]));
        $this->sender = new ReminderTestSender();
        $this->service = new TrainingReminderService($training, $briefing, $settings, new ReminderPlanner(), new ReminderDelivery($this->repository, $this->sender, $this->clock, new LockFactory(new InMemoryStore())), $this->clock);
    }

    public function testDryRunPreviewsUnknownWeatherWithoutAnySendOrDeliveryWrites(): void
    {
        $this->profile(['location' => ['name' => 'Test', 'latitude' => 31, 'longitude' => 121]]);
        $this->session();
        $result = $this->service->run(true);
        self::assertTrue($result['dryRun']);
        self::assertSame(0, $result['sent']);
        self::assertCount(1, $result['previews']);
        self::assertStringContainsString('天气暂不可用', $result['previews'][0]['message']);
        self::assertStringNotContainsString('支持按计划', $result['previews'][0]['message']);
        self::assertSame([], $this->repository->list('deliveries'));
        self::assertSame([], $this->sender->calls);
        self::assertStringNotContainsString('private-key', json_encode($result));
    }

    public function testNormalRunSendsOnceAndOptOutPreventsLaterSends(): void
    {
        $this->profile();
        $this->session();
        self::assertSame(1, $this->service->run()['sent']);
        self::assertSame(0, $this->service->run()['sent']);
        self::assertCount(1, $this->sender->calls);
        $profile = $this->repository->find('profile', 'default');
        $this->repository->save('profile', 'default', [...$profile, 'notificationsEnabled' => false], $profile['version']);
        self::assertSame('disabled', $this->service->run()['status']);
        self::assertCount(1, $this->sender->calls);
    }

    public function testHabitualSessionIsPreviewedButAnExplicitRestDaySuppressesIt(): void
    {
        $this->profile(['runningDays' => [7]]);
        $result = $this->service->run(true);
        self::assertCount(1, $result['previews']);
        self::assertStringStartsWith('habit-', $result['previews'][0]['sessionId']);
        self::assertSame([], $this->repository->list('sessions'));
        $this->session(['type' => 'rest']);
        self::assertSame([], $this->service->run(true)['previews']);
    }

    public function testHabitualTomorrowAndExplicitLaterSessionDoNotUseWrongBriefing(): void
    {
        $this->clock->now = '2026-10-03T20:05:00+08:00';
        $this->profile(['runningDays' => [7]]);
        $this->session(['startAt' => '2026-10-06T07:00:00+08:00', 'title' => 'Later run']);
        $result = $this->service->run(true);
        self::assertCount(1, $result['previews']);
        self::assertSame('evening', $result['previews'][0]['type']);
        self::assertStringContainsString('10-04 06:30', $result['previews'][0]['message']);
        self::assertStringNotContainsString('Later run', $result['previews'][0]['message']);
    }

    public function testFeedbackPromptDoesNotFetchFutureWeatherForACompletedSession(): void
    {
        $this->clock->now = '2026-10-04T07:40:00+08:00';
        $this->profile();
        $this->session(['status' => 'completed']);
        $result = $this->service->run(true);
        self::assertSame('feedback', $result['previews'][0]['type']);
        self::assertStringContainsString('跑后体感', $result['previews'][0]['message']);
        self::assertStringNotContainsString('天气暂不可用', $result['previews'][0]['message']);
    }

    private function profile(array $overrides = []): void
    {
        $this->repository->save('profile', 'default', array_replace(TrainingInput::defaults('profile'), ['notificationsEnabled' => true], $overrides), 0);
    }

    private function session(array $overrides = []): void
    {
        $this->repository->save('sessions', 'test-run', array_replace(TrainingInput::defaults('sessions'), ['title' => '晨跑', 'startAt' => '2026-10-04T06:30:00+08:00', 'durationMinutes' => 60, 'type' => 'easy'], $overrides), 0);
    }
}
