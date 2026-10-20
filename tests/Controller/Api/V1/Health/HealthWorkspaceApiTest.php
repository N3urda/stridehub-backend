<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Domain\Settings\DbalSettingsRepository;
use App\Domain\Settings\SettingsName;
use App\Domain\Training\TrainingRepository;
use App\Tests\Controller\Admin\AdminWebTestCase;
use Doctrine\DBAL\Connection;

final class HealthWorkspaceApiTest extends AdminWebTestCase
{
    private string $token;

    protected function prepareEnvironment(): void
    {
        parent::prepareEnvironment();
        $this->token = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $this->token;
    }

    public function testMealsAndPlansHaveIndependentFactsCoverageAndVersionedHistory(): void
    {
        $plan = ['version' => 0, 'date' => '2026-01-10', 'targets' => ['energyKcal' => 2000, 'proteinGrams' => 90], 'meals' => [['id' => 'breakfast', 'mealType' => 'breakfast', 'foods' => 'DEMO 燕麦', 'portion' => '50 g']]];
        $saved = $this->call('PUT', 'meal-plans/day', $plan);
        self::assertResponseIsSuccessful();
        self::assertSame($saved, $this->call('PUT', 'meal-plans/day', $plan));
        self::assertSame(1, $saved['version']);
        $input = ['version' => 0, 'date' => '2026-01-10', 'mealType' => 'breakfast', 'foods' => 'DEMO 早餐', 'portion' => '一份', 'nutrients' => ['energyKcal' => 400, 'proteinGrams' => 15], 'nutritionSource' => 'estimate', 'planId' => 'day'];
        $meal = $this->call('PUT', 'meals/breakfast', $input);
        self::assertResponseIsSuccessful();
        self::assertSame($meal, $this->call('PUT', 'meals/breakfast', $input));
        self::assertCount(1, $this->call('GET', 'meals/breakfast/history')['records']);
        $updated = $this->call('PUT', 'meals/breakfast', ['version' => 1, 'notes' => '并发备注']);
        $this->call('PUT', 'meals/breakfast', ['version' => 1, 'portion' => '两份']);
        self::assertResponseStatusCodeSame(409);
        self::assertSame($updated, $this->call('GET', 'meals/breakfast'));
        self::assertCount(2, $this->call('GET', 'meals/breakfast/history')['records']);
        $this->call('PUT', 'meals/lunch', ['version' => 0, 'date' => '2026-01-10', 'foods' => 'DEMO 未知营养午餐']);
        self::assertResponseIsSuccessful();
        $workspace = $this->call('GET', 'workspace?from=2026-01-10&to=2026-01-11');
        self::assertResponseIsSuccessful();
        self::assertNull($workspace['nutrition']['days'][0]['actualLogged']['energyKcal']);
        self::assertEquals(400, $workspace['nutrition']['days'][0]['knownSubtotal']['energyKcal']);
        self::assertSame(1, $workspace['nutrition']['days'][0]['coverage']['energyKcal']);
        self::assertNull($workspace['nutrition']['days'][0]['delta']['energyKcal']);
        self::assertSame(0, $workspace['nutrition']['days'][1]['mealCount']);
        self::assertNull($workspace['nutrition']['days'][1]['actualLogged']['energyKcal']);
        $this->call('PUT', 'meals/lunch', ['version' => 1, 'nutritionSource' => 'label', 'nutrients' => ['energyKcal' => 0]]);
        self::assertResponseIsSuccessful();
        $workspace = $this->call('GET', 'workspace?from=2026-01-10&to=2026-01-10');
        self::assertEquals(400, $workspace['nutrition']['days'][0]['actualLogged']['energyKcal']);
        self::assertEquals(-1600, $workspace['nutrition']['days'][0]['delta']['energyKcal']);
    }

    public function testBatchIsAtomicAndReferencesAndActiveDailyPlanAreProtected(): void
    {
        $this->call('PUT', 'meal-plans/day', ['version' => 0, 'date' => '2026-01-10']);
        self::assertResponseIsSuccessful();
        $this->call('PUT', 'meal-plans/duplicate', ['version' => 0, 'date' => '2026-01-10']);
        self::assertResponseStatusCodeSame(409);
        $this->call('PUT', 'meals/batch', ['records' => [
            ['id' => 'one', 'version' => 0, 'date' => '2026-01-10', 'foods' => 'DEMO'],
            ['id' => 'two', 'version' => 0, 'date' => '2026-02-30', 'foods' => 'DEMO'],
        ]]);
        self::assertResponseStatusCodeSame(422);
        self::assertSame([], $this->call('GET', 'meals')['records']);
        self::assertSame([], $this->call('GET', 'meals/one/history')['records']);
        $this->call('PUT', 'meals/one', ['version' => 0, 'date' => '2026-01-11', 'foods' => 'DEMO', 'planId' => 'day']);
        self::assertResponseStatusCodeSame(422);
        $this->call('PUT', 'meals/one', ['version' => 0, 'date' => '2026-01-10', 'foods' => 'DEMO', 'planId' => 'day']);
        self::assertResponseIsSuccessful();
        $this->call('DELETE', 'meal-plans/day?version=1');
        self::assertResponseStatusCodeSame(409);
        $this->call('PUT', 'meal-plans/day', ['version' => 1, 'date' => '2026-01-12']);
        self::assertResponseStatusCodeSame(409);
        $this->call('DELETE', 'meals/one?version=1');
        self::assertResponseStatusCodeSame(204);
        self::assertSame('delete', $this->call('GET', 'meals/one/history')['records'][0]['action']);
        $this->call('PUT', 'meals/one', ['version' => 0, 'date' => '2026-01-10', 'foods' => 'DEMO']);
        self::assertResponseStatusCodeSame(409);
    }

    public function testWeightTrendsUseCalendarDaysAndDoNotTurnTargetsIntoMeasurements(): void
    {
        self::getContainer()->set(\App\Infrastructure\Time\Clock\Clock::class, \App\Tests\Infrastructure\Time\Clock\PausedClock::fromString('2026-01-11 12:00:00'));
        $settings = self::getContainer()->get(DbalSettingsRepository::class);
        $settings->save(SettingsName::WEIGHT_HISTORY, [
            ['on' => '2026-01-01', 'weight' => 90], ['on' => '2026-01-08', 'weight' => 80],
            ['on' => '2026-01-10', 'weight' => 78], ['on' => '2099-01-01', 'weight' => 50],
        ]);
        $goal = ['version' => 0, 'title' => 'DEMO 目标', 'startDate' => '2026-01-08', 'endDate' => '2026-02-08', 'startWeightKg' => 80, 'targetWeightKg' => 75, 'milestones' => [['date' => '2026-01-20', 'weightKg' => 77]]];
        $saved = $this->call('PUT', 'weight-goals/goal', $goal);
        self::assertResponseIsSuccessful();
        self::assertEquals(75, $saved['targetWeightKg']);
        $this->call('PUT', 'measurements/waist', ['version' => 0, 'date' => '2026-01-10', 'waistCm' => 80.2]);
        self::assertResponseIsSuccessful();
        $data = $this->call('GET', 'workspace?from=2026-01-10&to=2026-01-10');
        self::assertResponseIsSuccessful();
        self::assertCount(1, $data['weight']['records']);
        self::assertEquals(79, $data['weight']['trend'][0]['average7DaysKg']);
        self::assertSame(2, $data['weight']['trend'][0]['sampleDays']);
        self::assertEquals(40, $data['weight']['goals'][0]['progress']['percent']);
        self::assertEquals(3, $data['weight']['goals'][0]['progress']['remainingKg']);
        self::assertNull($data['measurements'][0]['bodyFatPercent']);
        $this->call('PUT', 'weight-goals/goal', ['version' => 1, 'notes' => '仅修改备注']);
        self::assertSame($goal['milestones'], $this->call('GET', 'weight-goals/goal')['milestones']);
    }

    public function testInvalidValuesMissingVersionsAndBodiesAreRejected(): void
    {
        foreach ([
            ['meals', ['date' => '2026-01-01', 'foods' => 'DEMO', 'nutrients' => ['energyKcal' => 10]]],
            ['meals', ['date' => '2026-01-01', 'foods' => 'DEMO', 'nutrients' => ['energyKcal' => -1], 'nutritionSource' => 'estimate']],
            ['meals', ['date' => '2026-01-01', 'foods' => 'DEMO', 'source' => 'ai']],
            ['meal-plans', ['date' => '2026-01-01', 'targets' => ['energyKcal' => '1000']]],
            ['measurements', ['date' => '2026-01-01']],
            ['measurements', ['date' => '2026-01-01', 'bodyFatPercent' => 101]],
            ['measurements', ['date' => '2026-01-01', 'waistCm' => 70, 'source' => 'ai', 'rationale' => '推测']],
            ['weight-goals', ['title' => 'DEMO', 'startDate' => '2026-01-01', 'endDate' => '2026-02-01', 'startWeightKg' => 70, 'targetWeightKg' => 70]],
        ] as [$resource, $input]) {
            $this->call('PUT', $resource.'/invalid', ['version' => 0, ...$input]);
            self::assertResponseStatusCodeSame(422);
        }
        $this->call('PUT', 'meals/one', ['date' => '2026-01-01', 'foods' => 'DEMO']);
        self::assertResponseStatusCodeSame(428);
        $this->call('GET', 'workspace?from=2020-01-01&to=2026-01-01');
        self::assertResponseStatusCodeSame(422);
        $this->call('GET', 'workspace?from=2026-01-02&to=2026-01-01');
        self::assertResponseStatusCodeSame(422);
    }

    public function testCloudRunsCanCompleteSessionsAndConfirmedSourcesCountOnce(): void
    {
        $db = self::getContainer()->get(Connection::class);
        $db->executeStatement("UPDATE Activity SET sportType = 'Run', startDateTime = '2026-01-10 07:00:00', distance = 5000, movingTimeInSeconds = 1800, elapsedTimeInSeconds = 1850 WHERE activityId = 'activity-903645'");
        $repository = self::getContainer()->get(TrainingRepository::class);
        // Use the actual application's storage timezone in the synthetic source fixture.
        $zone = self::getContainer()->get(\App\Infrastructure\ValueObject\Time\SerializableTimezone::class);
        $start = new \DateTimeImmutable('2026-01-10 07:00:00', $zone)->format(DATE_ATOM);
        $repository->save('zepp-workout', 'zepp-demo', ['startedAt' => $start, 'sportType' => 1, 'sportName' => 'DEMO 跑步', 'distanceMeters' => 5000, 'durationSeconds' => 1800, 'elapsedSeconds' => 1850, 'averageHeartRateBpm' => 140], 0);
        $data = $this->call('GET', 'workouts?from=2026-01-10&to=2026-01-11');
        self::assertResponseIsSuccessful();
        self::assertSame(1, $data['pendingDuplicates']);
        self::assertNull($data['summary']['distanceKm']);
        $this->training('POST', 'sessions', ['id' => 'cloud-run', 'title' => 'DEMO', 'startAt' => $start, 'durationMinutes' => 30, 'type' => 'easy']);
        self::assertResponseStatusCodeSame(201);
        $linked = $this->training('POST', 'sessions/cloud-run/link', ['version' => 1, 'activityIds' => ['zepp-demo']]);
        self::assertResponseIsSuccessful();
        self::assertSame('completed', $linked['status']);
        $comparison = $this->training('GET', 'sessions/cloud-run/comparison');
        self::assertEquals(5, $comparison['actual']['distanceKm']);
        $this->call('PUT', 'workout-links/zepp-demo', ['version' => 0, 'activityId' => 'activity-903645']);
        self::assertResponseStatusCodeSame(409);
        $this->training('POST', 'sessions/cloud-run/link', ['version' => $linked['version'], 'activityIds' => []]);
        $mapping = $this->call('PUT', 'workout-links/zepp-demo', ['version' => 0, 'activityId' => 'activity-903645']);
        self::assertResponseIsSuccessful();
        self::assertSame($mapping, $this->call('GET', 'workout-links/zepp-demo'));
        $data = $this->call('GET', 'workouts?from=2026-01-10&to=2026-01-11');
        self::assertSame(0, $data['pendingDuplicates']);
        self::assertSame(1, $data['summary']['count']);
        self::assertEquals(5, $data['summary']['distanceKm']);
        self::assertCount(2, $data['records'][0]['sources']);
        $current = $this->training('GET', 'sessions/cloud-run');
        $linked = $this->training('POST', 'sessions/cloud-run/link', ['version' => $current['version'], 'activityIds' => ['zepp-demo']]);
        self::assertSame(['activity-903645'], $linked['activityIds']);
        $this->training('POST', 'sessions/cloud-run/link', ['version' => $linked['version'], 'activityIds' => ['zepp-demo', 'activity-903645']]);
        self::assertResponseStatusCodeSame(409);
        $this->call('DELETE', 'workout-links/zepp-demo?version=1');
        self::assertResponseStatusCodeSame(409);
        $this->training('PUT', 'sessions/batch', ['sessions' => [
            ['id' => 'cloud-run', 'version' => $linked['version'], 'activityIds' => ['zepp-demo']],
            ['id' => 'second-claim', 'version' => 0, 'title' => 'DEMO duplicate', 'startAt' => $start, 'durationMinutes' => 30, 'type' => 'easy', 'activityIds' => ['activity-903645']],
        ]]);
        self::assertResponseStatusCodeSame(409);
        self::assertSame($linked['version'], $this->training('GET', 'sessions/cloud-run')['version']);
        $this->training('GET', 'sessions/second-claim');
        self::assertResponseStatusCodeSame(404);
    }

    public function testMissingCloudMeasurementsNeverBecomeZeroInTrainingComparisons(): void
    {
        self::getContainer()->get(TrainingRepository::class)->save('zepp-workout', 'zepp-partial', [
            'startedAt' => '2026-01-10T07:00:00+08:00', 'sportType' => 1, 'sportName' => 'DEMO incomplete',
            'distanceMeters' => null, 'durationSeconds' => null, 'elapsedSeconds' => null, 'averageHeartRateBpm' => null,
        ], 0);
        $this->training('POST', 'sessions', ['id' => 'partial-run', 'title' => 'DEMO', 'startAt' => '2026-01-10T07:00:00+08:00', 'durationMinutes' => 30, 'distanceKm' => 5, 'type' => 'easy', 'activityIds' => ['zepp-partial']]);
        self::assertResponseStatusCodeSame(201);
        $comparison = $this->training('GET', 'sessions/partial-run/comparison');
        self::assertResponseIsSuccessful();
        foreach (['distanceKm', 'durationMinutes', 'movingSeconds', 'elapsedSeconds', 'averageHeartRate'] as $field) {
            self::assertNull($comparison['actual'][$field]);
        }
        self::assertSame(['distanceKm' => null, 'durationMinutes' => null], $comparison['delta']);
    }

    public function testNewResourcesRequireAuthenticationCsrfAndSharePageData(): void
    {
        foreach (['workspace', 'workouts', 'meals', 'meal-plans', 'weight-goals', 'measurements'] as $route) {
            $this->client->request('GET', '/api/v1/health/'.$route);
            self::assertResponseStatusCodeSame(401);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
        $this->client->loginUser($this->adminUser());
        foreach (['meals/demo', 'meal-plans/demo', 'weight-goals/demo', 'measurements/demo', 'workout-links/demo'] as $route) {
            $this->client->request('PUT', '/admin/health/api/'.$route, server: ['CONTENT_TYPE' => 'application/json'], content: '{}');
            self::assertResponseStatusCodeSame(403);
        }
        foreach (['nutrition', 'weight', 'plans', 'running'] as $page) {
            $this->client->request('GET', '/admin/'.$page);
            self::assertResponseIsSuccessful();
        }
        $page = $this->client->request('GET', '/admin/nutrition');
        $csrf = $page->filter('#data-workspace')->attr('data-csrf');
        $this->client->request('PUT', '/admin/health/api/meals/browser', server: ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $csrf], content: '{"version":0,"date":"2026-01-10","foods":"DEMO 网页记录"}');
        self::assertResponseIsSuccessful();
        self::assertSame('DEMO 网页记录', $this->call('GET', 'meals/browser')['foods']);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
    }

    /** @param array<string, mixed>|null $body
     * @return array<string, mixed>
     */
    private function call(string $method, string $path, ?array $body = null): array
    {
        return $this->send($method, '/api/v1/health/'.$path, $body);
    }

    /** @param array<string, mixed>|null $body
     * @return array<string, mixed>
     */
    private function training(string $method, string $path, ?array $body = null): array
    {
        return $this->send($method, '/api/v1/training/'.$path, $body);
    }

    /** @param array<string, mixed>|null $body
     * @return array<string, mixed>
     */
    private function send(string $method, string $path, ?array $body): array
    {
        $this->client->request($method, $path, server: ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token, 'CONTENT_TYPE' => 'application/json'], content: null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode($this->client->getResponse()->getContent() ?: '{}', true, 512, JSON_THROW_ON_ERROR);
    }
}
