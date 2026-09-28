<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Training;

use App\Domain\Activity\ActivityRepository;
use App\Domain\Activity\ActivityWithRawData;
use App\Domain\Activity\SportType\SportType;
use App\Domain\Api\Token;
use App\Tests\Controller\Admin\AdminWebTestCase;
use App\Tests\Domain\Activity\ActivityBuilder;

final class TrainingDailyApiTest extends AdminWebTestCase
{
    private string $token;

    protected function prepareEnvironment(): void
    {
        parent::prepareEnvironment();
        $this->token = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $this->token;
    }

    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    private function call(string $method, string $path, ?array $body = null): array
    {
        $this->client->request($method, '/api/v1/training'.$path,
            server: ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token, 'CONTENT_TYPE' => 'application/json'],
            content: null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode($this->client->getResponse()->getContent() ?: '{}', true, 512, JSON_THROW_ON_ERROR);
    }

    private function createSession(): array
    {
        return $this->call('POST', '/sessions', ['id' => 'daily-run', 'title' => 'DEMO easy run', 'type' => 'easy', 'startAt' => '2026-09-28T06:30:00+08:00', 'durationMinutes' => 45, 'notes' => 'Preserve my route', 'feedback' => ['notes' => 'Existing comment', 'thermalFeeling' => 'comfortable']]);
    }

    public function testSleepCanBeRecordedWithoutInventingFatigueOrAbsenceOfPain(): void
    {
        $record = $this->call('POST', '/check-ins', ['date' => '2026-09-28', 'sleepHours' => 6]);
        self::assertResponseStatusCodeSame(201);
        self::assertNull($record['pain']);
        self::assertNull($record['fatigue']);
        self::assertNull($record['soreness']);
        $updated = $this->call('PUT', '/check-ins/2026-09-28', ['version' => 1, 'pain' => false]);
        self::assertFalse($updated['pain']);
        $updated = $this->call('PUT', '/check-ins/2026-09-28', ['version' => 2, 'notes' => 'Only notes changed']);
        self::assertFalse($updated['pain']);
        self::assertSame(6, $updated['sleepHours']);
    }

    public function testQuickFeedbackMergesWithoutChangingPlanOrMarkingItCompleted(): void
    {
        $this->createSession();
        $saved = $this->call('PUT', '/sessions/daily-run/feedback', ['version' => 1, 'feedback' => ['rpe' => 4, 'pain' => null, 'fuel' => ['fluidMl' => 250]]]);
        self::assertResponseIsSuccessful();
        self::assertSame('planned', $saved['status']);
        self::assertSame('Preserve my route', $saved['notes']);
        self::assertSame('Existing comment', $saved['feedback']['notes']);
        self::assertSame('comfortable', $saved['feedback']['thermalFeeling']);
        self::assertSame(4, $saved['feedback']['rpe']);
        self::assertNull($saved['feedback']['pain']);
        self::assertNull($saved['feedback']['fuel']['carbsGrams']);
        self::assertSame(250, $saved['feedback']['fuel']['fluidMl']);
        self::assertSame('unknown', $saved['feedback']['fuel']['giComfort']);
        $saved = $this->call('PUT', '/sessions/daily-run/feedback', ['version' => 2, 'feedback' => ['pain' => false, 'fuel' => ['notes' => 'Water only']]]);
        self::assertResponseIsSuccessful();
        self::assertSame(250, $saved['feedback']['fuel']['fluidMl']);
        self::assertSame('Water only', $saved['feedback']['fuel']['notes']);
        self::assertFalse($saved['feedback']['pain']);
        self::assertSame(4, $saved['feedback']['rpe']);
        self::assertSame($saved, $this->call('GET', '/sessions/daily-run'));
    }

    public function testFeedbackConflictAndInvalidPayloadDoNotChangeExistingData(): void
    {
        $this->createSession();
        $this->call('PUT', '/sessions/daily-run/feedback', ['version' => 1, 'feedback' => ['pain' => true]]);
        self::assertResponseIsSuccessful();
        $this->call('PUT', '/sessions/daily-run/feedback', ['version' => 1, 'feedback' => ['pain' => false]]);
        self::assertResponseStatusCodeSame(409);
        foreach ([['rpe' => 0], ['pain' => 'no'], ['fuel' => ['fluidMl' => -1]], ['fuel' => ['invented' => 1]], ['invented' => 1]] as $feedback) {
            $this->call('PUT', '/sessions/daily-run/feedback', ['version' => 2, 'feedback' => $feedback]);
            self::assertResponseStatusCodeSame(422);
        }
        $this->call('PUT', '/sessions/daily-run/feedback', ['version' => 2, 'feedback' => ['rpe' => 3], 'status' => 'completed']);
        self::assertResponseStatusCodeSame(422);
        $saved = $this->call('GET', '/sessions/daily-run');
        self::assertTrue($saved['feedback']['pain']);
        self::assertSame(2, $saved['version']);
    }

    public function testExplicitUnknownFeedbackAndMissingVersion(): void
    {
        $this->createSession();
        $this->call('PUT', '/sessions/daily-run/feedback', ['feedback' => ['rpe' => 3]]);
        self::assertResponseStatusCodeSame(428);
        $saved = $this->call('PUT', '/sessions/daily-run/feedback', ['version' => 1, 'feedback' => ['rpe' => null, 'thermalFeeling' => null, 'pain' => null, 'fuel' => null]]);
        self::assertResponseIsSuccessful();
        self::assertNull($saved['feedback']['rpe']);
        self::assertNull($saved['feedback']['fuel']);
        self::assertSame('Existing comment', $saved['feedback']['notes']);
    }

    public function testEmptyFuelObjectDoesNotEraseSavedQuantities(): void
    {
        $this->createSession();
        $this->call('PUT', '/sessions/daily-run/feedback', ['version' => 1, 'feedback' => ['fuel' => ['fluidMl' => 250, 'carbsGrams' => 40, 'giComfort' => 'good', 'notes' => 'Preserve this']]]);
        $saved = $this->call('PUT', '/sessions/daily-run/feedback', ['version' => 2, 'feedback' => ['rpe' => 4, 'fuel' => new \stdClass()]]);
        self::assertResponseIsSuccessful();
        self::assertSame(250, $saved['feedback']['fuel']['fluidMl']);
        self::assertSame(40, $saved['feedback']['fuel']['carbsGrams']);
        self::assertSame('good', $saved['feedback']['fuel']['giComfort']);
        self::assertSame('Preserve this', $saved['feedback']['fuel']['notes']);
    }

    public function testTodayAndReconciliationRequireBearerAndUseNoStore(): void
    {
        foreach (['/today', '/reconciliation'] as $path) {
            $this->client->request('GET', '/api/v1/training'.$path);
            self::assertResponseStatusCodeSame(401);
            $data = $this->call('GET', $path);
            self::assertResponseIsSuccessful();
            self::assertStringContainsString('no-store', $this->client->getResponse()->headers->get('Cache-Control'));
            self::assertIsArray($data);
        }
    }

    public function testMultipleLinksPassThroughTheApiAndRejectReusingAnActivity(): void
    {
        $this->createSession();
        $repository = self::getContainer()->get(ActivityRepository::class);
        $first = ActivityBuilder::fromDefaults()->withSportType(SportType::RUN)->build();
        $repository->add(ActivityWithRawData::fromState($first, []));
        $db = self::getContainer()->get(\Doctrine\DBAL\Connection::class);
        $copy = $db->fetchAssociative('SELECT * FROM Activity WHERE activityId = ?', [(string) $first->getId()]);
        $db->insert('Activity', [...$copy, 'activityId' => 'activity-daily-second']);
        $ids = [(string) $first->getId(), 'activity-daily-second'];
        $saved = $this->call('POST', '/sessions/daily-run/link', ['version' => 1, 'activityIds' => $ids]);
        self::assertResponseIsSuccessful();
        self::assertSame($ids, $saved['activityIds']);
        self::assertSame($ids[0], $saved['activityId']);
        self::assertSame('completed', $saved['status']);
        $comparison = $this->call('GET', '/sessions/daily-run/comparison');
        self::assertCount(2, $comparison['actualActivities']);
        $this->call('POST', '/sessions', ['id' => 'other-run', 'title' => 'Another run', 'type' => 'easy', 'startAt' => '2026-09-28T08:00:00+08:00', 'durationMinutes' => 30]);
        $this->call('POST', '/sessions/other-run/link', ['version' => 1, 'activityIds' => [$ids[1]]]);
        self::assertResponseStatusCodeSame(409);
        $this->call('POST', '/sessions/daily-run/link', ['version' => 2, 'activityIds' => []]);
        self::assertResponseIsSuccessful();
        $saved = $this->call('POST', '/sessions/other-run/link', ['version' => 1, 'activityId' => $ids[1]]);
        self::assertResponseIsSuccessful();
        self::assertSame([$ids[1]], $saved['activityIds']);
    }

    public function testTodayPageIsPrivateAndQuickFeedbackRequiresCsrf(): void
    {
        $this->client->request('GET', '/admin/training/today');
        self::assertResponseRedirects('/admin/login');
        $this->client->loginUser($this->adminUser());
        $this->client->request('GET', '/admin/training/today');
        self::assertResponseIsSuccessful();
        self::assertSelectorExists('#today-app');
        $this->client->request('PUT', '/admin/training/api/sessions/daily-run/feedback', server: ['CONTENT_TYPE' => 'application/json'], content: '{"version":1,"feedback":{"rpe":4}}');
        self::assertResponseStatusCodeSame(403);
    }
}
