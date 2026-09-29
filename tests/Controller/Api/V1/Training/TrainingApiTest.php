<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Training;

use App\Domain\Api\Token;
use App\Tests\Controller\Admin\AdminWebTestCase;

final class TrainingApiTest extends AdminWebTestCase
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

    private function session(string $id = 'long-run'): array
    {
        return ['id' => $id, 'title' => '周日长距离', 'startAt' => '2026-10-04T06:30:00+08:00',
            'durationMinutes' => 120, 'distanceKm' => 20, 'type' => 'long',
            'steps' => [['kind' => 'easy', 'minutes' => 120, 'target' => '轻松体感']],
            'fuelPlan' => [['minute' => 40, 'item' => '能量胶', 'carbsGrams' => 25]]];
    }

    public function testSchedulePersistsAndStaleCodexEditsCannotOverwrite(): void
    {
        $created = $this->call('POST', '/sessions', $this->session());
        self::assertResponseStatusCodeSame(201);
        self::assertSame(1, $created['version']);
        $updated = $this->call('PUT', '/sessions/long-run', ['version' => 1, 'startAt' => '2026-10-05T06:30:00+08:00']);
        self::assertResponseIsSuccessful();
        self::assertSame(2, $updated['version']);
        self::assertSame('long', $updated['type']);
        self::assertSame('能量胶', $updated['fuelPlan'][0]['item']);
        $this->call('PUT', '/sessions/long-run', ['version' => 1, 'title' => 'stale']);
        self::assertResponseStatusCodeSame(409);
        $list = $this->call('GET', '/sessions?from=2026-10-05&to=2026-10-05');
        self::assertCount(1, $list['items']);
        self::assertSame('周日长距离', $list['items'][0]['title']);
        $this->call('DELETE', '/sessions/long-run?version=1');
        self::assertResponseStatusCodeSame(409);
        $this->call('DELETE', '/sessions/long-run?version=2');
        self::assertResponseStatusCodeSame(204);
        self::assertSame([], $this->call('GET', '/sessions')['items']);
    }

    public function testBatchIsAtomicAndCanCreateMultipleStructuredSessions(): void
    {
        $this->call('PUT', '/sessions/batch', ['sessions' => [
            [...$this->session('a'), 'version' => 0], [...$this->session('b'), 'version' => 0],
        ]]);
        self::assertResponseIsSuccessful();
        $this->call('PUT', '/sessions/batch', ['sessions' => [
            ['id' => 'a', 'version' => 1, 'title' => 'must roll back'],
            ['id' => 'b', 'version' => 99, 'title' => 'stale'],
        ]]);
        self::assertResponseStatusCodeSame(409);
        $record = $this->call('GET', '/sessions/a');
        self::assertSame(1, $record['version']);
        self::assertSame('周日长距离', $record['title']);
    }

    public function testValidationAndReferenceIntegrity(): void
    {
        $this->call('POST', '/sessions', [...$this->session(), 'startAt' => '2026-02-30T06:30:00+08:00']);
        self::assertResponseStatusCodeSame(422);
        $this->call('POST', '/sessions', [...$this->session(), 'raceId' => 'absent']);
        self::assertResponseStatusCodeSame(422);
        $race = $this->call('POST', '/races', ['id' => 'race', 'name' => '秋季马拉松', 'date' => '2026-11-08', 'distanceKm' => 42.195]);
        self::assertResponseStatusCodeSame(201);
        $this->call('POST', '/sessions', [...$this->session(), 'raceId' => 'race']);
        self::assertResponseStatusCodeSame(201);
        $this->call('DELETE', '/races/race?version='.$race['version']);
        self::assertResponseStatusCodeSame(409);
        $this->call('PUT', '/sessions/long-run', ['title' => 'unversioned']);
        self::assertResponseStatusCodeSame(428);
        $this->call('POST', '/sessions/long-run/link', ['version' => 1, 'activityId' => 'missing']);
        self::assertResponseStatusCodeSame(422);
        $this->call('GET', '/sessions?from=2026-02-30');
        self::assertResponseStatusCodeSame(422);
    }

    public function testWellbeingFuelAndProfileAreSavedWithoutInventingData(): void
    {
        $profile = $this->call('GET', '/profile');
        self::assertResponseIsSuccessful();
        self::assertNull($profile['location']);
        self::assertSame(0, $profile['version']);
        $this->call('PUT', '/profile', ['version' => 0, 'timezone' => 'Asia/Shanghai', 'location' => ['label' => '上海', 'latitude' => 31.23, 'longitude' => 121.47], 'thermalPreference' => 'cold']);
        self::assertResponseIsSuccessful();
        self::assertSame('cold', $this->call('GET', '/profile')['thermalPreference']);
        $this->call('POST', '/check-ins', ['date' => '2026-10-04', 'sleepHours' => 7.5, 'fatigue' => 2, 'soreness' => 2, 'pain' => false]);
        self::assertResponseStatusCodeSame(201);
        $this->call('POST', '/check-ins', ['date' => '2026-10-04', 'fatigue' => 1, 'soreness' => 1]);
        self::assertResponseStatusCodeSame(409);
        $this->call('POST', '/fuel-logs', ['date' => '2026-10-04', 'minute' => 40, 'item' => '能量胶', 'carbsGrams' => 25, 'fluidMl' => 150, 'giComfort' => 'good']);
        self::assertResponseStatusCodeSame(201);
        self::assertCount(1, $this->call('GET', '/fuel-logs')['items']);
        $this->call('PUT', '/profile', ['version' => 1, 'timezone' => 'invalid/timezone']);
        self::assertResponseStatusCodeSame(422);
    }

    public function testApiNeedsBearerAndAdminMutationsNeedCsrf(): void
    {
        $this->client->request('GET', '/api/v1/training/sessions');
        self::assertResponseStatusCodeSame(401);
        $this->client->loginUser($this->adminUser());
        $this->client->request('POST', '/admin/training/api/sessions', server: ['CONTENT_TYPE' => 'application/json'], content: json_encode($this->session(), JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(403);
    }

    public function testTrainingPageIsProtectedButWorksBeforeFirstImportedActivity(): void
    {
        $this->client->request('GET', '/admin/training');
        self::assertResponseRedirects('/admin/login');
        $this->client->loginUser($this->adminUser());
        $this->client->request('GET', '/admin/training');
        self::assertResponseIsSuccessful();
        self::assertSelectorExists('#training-app');
        self::assertStringNotContainsString($this->token, $this->client->getResponse()->getContent());
    }

    public function testComparisonDoesNotPretendThereIsAnActualActivity(): void
    {
        $this->call('POST', '/sessions', $this->session());
        $comparison = $this->call('GET', '/sessions/long-run/comparison');
        self::assertResponseIsSuccessful();
        self::assertNull($comparison['actual']);
        self::assertNull($comparison['delta']);
        self::assertSame([], $comparison['candidates']);
    }

    public function testBriefingIsExplicitAboutMissingLocationAndUnsavedHabit(): void
    {
        $briefing = $this->call('GET', '/briefing');
        self::assertResponseIsSuccessful();
        self::assertNull($briefing['session']);
        $this->call('PUT', '/profile', ['version' => 0, 'runningDays' => [1, 2, 3, 4, 5, 6, 7]]);
        $briefing = $this->call('GET', '/briefing');
        self::assertResponseIsSuccessful();
        self::assertTrue($briefing['session']['habitual']);
        self::assertSame('unavailable', $briefing['forecast']['status']);
        self::assertSame([], $this->call('GET', '/sessions')['items']);
    }

    public function testBrowserAndBearerClientsShareTheSameVersionedSchedule(): void
    {
        $this->client->loginUser($this->adminUser());
        $crawler = $this->client->request('GET', '/admin/training');
        $csrf = $crawler->filter('#training-app')->attr('data-csrf');
        $this->client->request('POST', '/admin/training/api/sessions', server: ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $csrf], content: json_encode($this->session(), JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(201);
        $record = $this->call('GET', '/sessions/long-run');
        self::assertSame('周日长距离', $record['title']);
        $this->call('PUT', '/sessions/long-run', ['version' => 1, 'notes' => 'Codex 编辑']);
        $this->client->request('PUT', '/admin/training/api/sessions/long-run', server: ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $csrf], content: '{"version":1,"notes":"stale browser"}');
        self::assertResponseStatusCodeSame(409);
        self::assertSame('Codex 编辑', $this->call('GET', '/sessions/long-run')['notes']);
    }

    public function testActualRunningActivityCanBeLinkedOnceAndCompared(): void
    {
        $activity = \App\Tests\Domain\Activity\ActivityBuilder::fromDefaults()
            ->withSportType(\App\Domain\Activity\SportType\SportType::RUN)
            ->withStartDateTime(\App\Infrastructure\ValueObject\Time\SerializableDateTime::fromString('2026-10-04 06:30:00'))
            ->withDistance(\App\Infrastructure\Measurement\Length\Kilometer::from(19))
            ->withMovingTimeInSeconds(6900)->build();
        $this->getContainer()->get(\App\Domain\Activity\ActivityRepository::class)->add(\App\Domain\Activity\ActivityWithRawData::fromState($activity, []));
        $this->call('POST', '/sessions', $this->session());
        $this->call('POST', '/sessions/long-run/link', ['version' => 1, 'activityId' => (string) $activity->getId()]);
        self::assertResponseIsSuccessful();
        $comparison = $this->call('GET', '/sessions/long-run/comparison');
        self::assertSame('completed', $comparison['planned']['status']);
        self::assertEquals(-1, $comparison['delta']['distanceKm']);
        self::assertEquals(-5, $comparison['delta']['durationMinutes']);
        self::assertNull($comparison['actual']['averageHeartRate']);
        $this->call('POST', '/sessions', $this->session('second'));
        $this->call('POST', '/sessions/second/link', ['version' => 1, 'activityId' => (string) $activity->getId()]);
        self::assertResponseStatusCodeSame(409);
    }

    public function testDateFiltersUseRunnerTimezoneAndOpenApiIsMachineReadable(): void
    {
        $this->call('POST', '/sessions', [...$this->session(), 'startAt' => '2026-10-03T23:30:00Z']);
        self::assertCount(1, $this->call('GET', '/sessions?from=2026-10-04&to=2026-10-04')['items']);
        self::assertCount(0, $this->call('GET', '/sessions?from=2026-10-03&to=2026-10-03')['items']);
        $spec = $this->call('GET', '/openapi.json');
        self::assertSame('3.1.0', $spec['openapi']);
        self::assertArrayHasKey('/sessions/batch', $spec['paths']);
        self::assertSame('bearer', $spec['components']['securitySchemes']['bearerAuth']['scheme']);
    }

    public function testMalformedAndUnboundedInputsAreRejected(): void
    {
        foreach (['[]', 'null', '{', '"hello"'] as $content) {
            $this->client->request('POST', '/api/v1/training/sessions', server: ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token, 'CONTENT_TYPE' => 'application/json'], content: $content);
            self::assertResponseStatusCodeSame(422);
        }
        $this->call('POST', '/sessions', [...$this->session(), 'durationMinutes' => 45.5]);
        self::assertResponseStatusCodeSame(422);
        $this->call('POST', '/sessions', [...$this->session(), 'unknown' => 'no']);
        self::assertResponseStatusCodeSame(422);
        $this->call('PUT', '/profile', ['version' => 0, 'location' => ['label' => 'bad', 'latitude' => 91, 'longitude' => 0]]);
        self::assertResponseStatusCodeSame(422);
        foreach ([['notes' => null], ['startAt' => '2026-10-04T06:30:00+99:99'], ['steps' => [['kind' => 'easy', 'minutes' => 45, 'distanceKm' => null]]]] as $invalid) {
            $this->call('POST', '/sessions', array_replace($this->session(), $invalid));
            self::assertResponseStatusCodeSame(422);
        }
        $this->client->request('POST', '/api/v1/training/sessions', server: ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token, 'CONTENT_TYPE' => 'application/jsonp'], content: json_encode($this->session(), JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(415);
    }
}
