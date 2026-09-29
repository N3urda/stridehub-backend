<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training;

use App\Domain\Training\Advice\RunningAdvice;
use App\Domain\Training\TrainingActivities;
use App\Domain\Training\TrainingBriefing;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Domain\Training\TrainingService;
use App\Domain\Training\Weather\ForecastProvider;
use App\Tests\Domain\Training\Notification\ReminderTestClock;
use Doctrine\DBAL\DriverManager;
use GuzzleHttp\Client;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Cache\Adapter\ArrayAdapter;

final class TrainingBriefingHealthTest extends TestCase
{
    private TrainingRepository $repository;
    private TrainingBriefing $briefing;

    protected function setUp(): void
    {
        $connection = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $connection->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        $connection->executeStatement('CREATE TABLE TrainingActivityLink (activityId VARCHAR(255) NOT NULL PRIMARY KEY, sessionId VARCHAR(80) NOT NULL)');
        $clock = new ReminderTestClock('2026-09-28T16:30:00Z');
        $this->repository = new TrainingRepository($connection, $clock);
        $training = new TrainingService($this->repository, new TrainingActivities($connection), $clock);
        $weather = new ForecastProvider(new Client(['handler' => HandlerStack::create(new MockHandler([]))]), new ArrayAdapter(), $clock);
        // Existing five-argument construction must continue to read health constraints.
        $this->briefing = new TrainingBriefing($training, $this->repository, $weather, new RunningAdvice(), $clock);
        $this->repository->save('profile', 'default', TrainingInput::defaults('profile'), 0);
        $this->repository->save('health-context', 'default', ['reports' => [], 'constraints' => [
            ['id' => 'tomorrow', 'description' => '从指定日期开始生效的原文', 'sourceType' => 'user', 'sourceReportId' => null, 'validFrom' => '2026-09-30', 'reviewOn' => null, 'status' => 'active'],
            ['id' => 'review-due', 'description' => '保留原文，不推断医学规则', 'sourceType' => 'clinician', 'sourceReportId' => null, 'validFrom' => '2026-09-01', 'reviewOn' => '2026-09-20', 'status' => 'active'],
            ['id' => 'inactive', 'description' => '已明确停用', 'sourceType' => 'user', 'sourceReportId' => null, 'validFrom' => null, 'reviewOn' => null, 'status' => 'inactive'],
        ], 'lifestylePreferences' => [], 'notes' => ''], 0);
    }

    public function testEveryBriefingIncludesConstraintsForTheSessionsProfileLocalDate(): void
    {
        $session = [...TrainingInput::defaults('sessions'), 'title' => '未来计划', 'startAt' => '2026-09-29T16:30:00Z', 'durationMinutes' => 30, 'type' => 'easy'];
        $this->repository->save('sessions', 'future', $session, 0);
        $this->repository->save('check-ins', '2026-09-29', ['date' => '2026-09-29', 'sleepHours' => 8, 'fatigue' => 1, 'soreness' => 1, 'pain' => false], 0);
        foreach ([$this->briefing->generate('future'), $this->briefing->generateForSession($session)] as $result) {
            self::assertArrayHasKey('health', $result);
            self::assertSame(['tomorrow', 'review-due'], array_column($result['health']['constraints'], 'id'));
            self::assertSame(1, $result['health']['version']);
            self::assertNull($result['checkIn'], 'Today check-in must not be reused as future recovery state.');
            self::assertSame('review_constraints', $result['advice']['training']);
            self::assertStringContainsString('约束', $result['advice']['summary']);
            self::assertSame('unknown', $result['advice']['conditionsTraining']);
        }
    }

    public function testRestBriefingCannotClaimHealthClearanceWhileConstraintsNeedReview(): void
    {
        $result = $this->briefing->generateForSession([...TrainingInput::defaults('sessions'), 'title' => '休息', 'startAt' => '2026-09-29T06:30:00+08:00', 'durationMinutes' => 1, 'type' => 'rest']);
        self::assertSame('review_constraints', $result['advice']['training']);
        self::assertSame('keep', $result['advice']['conditionsTraining']);
        self::assertStringNotContainsString('按计划', $result['advice']['summary']);
        self::assertSame(['review-due'], array_column($result['health']['constraints'], 'id'));
    }

    public function testNoSessionStillReturnsTodaysConstraintsAndNoInventedWeather(): void
    {
        $result = $this->briefing->generate();
        self::assertNull($result['session']);
        self::assertArrayHasKey('health', $result);
        self::assertSame(['review-due'], array_column($result['health']['constraints'], 'id'));
        self::assertSame('review_constraints', $result['advice']['training']);
        self::assertSame('unavailable', $result['forecast']['status']);
    }

    public function testConstraintReviewKeepsAnExistingPainStopMessageVisible(): void
    {
        $this->repository->save('check-ins', '2026-09-29', ['date' => '2026-09-29', 'sleepHours' => null, 'fatigue' => null, 'soreness' => null, 'pain' => true], 0);
        $result = $this->briefing->generateForSession([...TrainingInput::defaults('sessions'), 'title' => '今天晨跑', 'startAt' => '2026-09-29T06:30:00+08:00', 'durationMinutes' => 30, 'type' => 'easy']);
        self::assertSame('review_constraints', $result['advice']['training']);
        self::assertStringContainsString('疼痛', $result['advice']['summary']);
        self::assertStringContainsString('暂停', $result['advice']['summary']);
        self::assertStringContainsString('约束', $result['advice']['summary']);
    }
}
