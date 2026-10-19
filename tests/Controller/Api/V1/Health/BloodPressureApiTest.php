<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Tests\Controller\Admin\AdminWebTestCase;
use PHPUnit\Framework\Attributes\DataProvider;

final class BloodPressureApiTest extends AdminWebTestCase
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

    public function testCreateReplayReadUpdateAndDeleteShareOneVersionedRecord(): void
    {
        $saved = $this->call('PUT', '/reading-1', self::measurement());
        self::assertResponseStatusCodeSame(200);
        self::assertSame(1, $saved['version']);
        self::assertSame('reading-1', $saved['id']);
        self::assertSame('2026-10-19T07:30:00+08:00', $saved['measuredAt']);
        self::assertNull($saved['pulse']);
        self::assertSame('unknown', $saved['arm']);
        self::assertSame('unknown', $saved['posture']);
        self::assertNotNull($saved['updatedAt']);
        self::assertSame($saved, $this->call('PUT', '/reading-1', self::measurement()));
        self::assertSame($saved, $this->call('GET', '/reading-1'));
        self::assertSame([$saved], $this->call('GET')['records']);

        $updated = $this->call('PUT', '/reading-1', ['version' => 1, 'pulse' => 62, 'arm' => 'left', 'posture' => 'seated', 'notes' => '晨起测量']);
        self::assertSame(2, $updated['version']);
        self::assertSame(120, $updated['systolic']);
        self::assertSame(80, $updated['diastolic']);
        self::assertSame(62, $updated['pulse']);
        self::assertSame('晨起测量', $updated['notes']);
        $cleared = $this->call('PUT', '/reading-1', ['version' => 2, 'pulse' => null]);
        self::assertNull($cleared['pulse']);
        self::assertSame('晨起测量', $cleared['notes']);
        $this->call('DELETE', '/reading-1?version=3');
        self::assertResponseStatusCodeSame(204);
        self::assertSame([], $this->call('GET')['records']);
        $this->call('GET', '/reading-1');
        self::assertResponseStatusCodeSame(404);
    }

    public function testConflictingCreateAndStaleUpdateOrDeleteDoNotOverwrite(): void
    {
        $this->call('PUT', '/reading-1', self::measurement());
        $this->call('PUT', '/reading-1', [...self::measurement(), 'systolic' => 121]);
        self::assertResponseStatusCodeSame(409);
        $this->call('PUT', '/reading-1', ['version' => 1, 'notes' => 'new']);
        $this->call('PUT', '/reading-1', self::measurement());
        self::assertResponseStatusCodeSame(409);
        $this->call('PUT', '/reading-1', ['version' => 1, 'notes' => 'stale']);
        self::assertResponseStatusCodeSame(409);
        $this->call('DELETE', '/reading-1?version=1');
        self::assertResponseStatusCodeSame(409);
        $record = $this->call('GET', '/reading-1');
        self::assertSame(120, $record['systolic']);
        self::assertSame('new', $record['notes']);
        self::assertSame(2, $record['version']);
    }

    public function testMultipleSameDayMeasurementsSortByInstantAndFilterInProfileTimezone(): void
    {
        $this->call('PUT', '/night', [...self::measurement(), 'measuredAt' => '2026-10-18T16:15:00Z']);
        $this->call('PUT', '/morning', self::measurement());
        $this->call('PUT', '/evening', [...self::measurement(), 'measuredAt' => '2026-10-19T20:30:00+08:00']);
        $this->call('PUT', '/prior', [...self::measurement(), 'measuredAt' => '2026-10-18T15:59:59+00:00']);
        $this->call('PUT', '/next', [...self::measurement(), 'measuredAt' => '2026-10-19T16:00:00Z']);
        self::assertSame(['evening', 'morning', 'night'], array_column($this->call('GET', '?from=2026-10-19&to=2026-10-19')['records'], 'id'));
        self::assertSame(['next', 'evening', 'morning', 'night'], array_column($this->call('GET', '?from=2026-10-19')['records'], 'id'));
        self::assertSame(['prior'], array_column($this->call('GET', '?to=2026-10-18')['records'], 'id'));
        $this->client->request('PUT', '/api/v1/training/profile', server: $this->server(), content: '{"version":0,"timezone":"America/New_York"}');
        self::assertResponseIsSuccessful();
        self::assertSame(['morning', 'night', 'prior'], array_column($this->call('GET', '?from=2026-10-18&to=2026-10-18')['records'], 'id'));
        self::assertSame(['next', 'evening', 'morning', 'night', 'prior'], array_column($this->call('GET')['records'], 'id'));
    }

    public function testApiReadsWritesAndDeletesRequireBearerAndRemainPrivate(): void
    {
        foreach ([['GET', ''], ['GET', '/reading-1'], ['PUT', '/reading-1'], ['DELETE', '/reading-1?version=1']] as [$method, $path]) {
            foreach ([[], ['HTTP_AUTHORIZATION' => 'Bearer invalid']] as $server) {
                $this->client->request($method, '/api/v1/health/blood-pressure'.$path, server: $server);
                self::assertResponseStatusCodeSame(401);
                self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
            }
        }
        $this->call('GET');
        self::assertResponseIsSuccessful();
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
    }

    public function testAdminRequiresSessionAndBothWritesRequireHealthCsrf(): void
    {
        foreach (['GET', 'PUT', 'DELETE'] as $method) {
            $this->client->request($method, '/admin/health/api/blood-pressure/reading-1');
            self::assertResponseRedirects('/admin/login');
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
        $this->client->loginUser($this->adminUser());
        $this->client->request('GET', '/admin/health/api/blood-pressure');
        self::assertResponseIsSuccessful();
        foreach (['PUT', 'DELETE'] as $method) {
            foreach ([[], ['HTTP_X_CSRF_TOKEN' => 'tampered']] as $server) {
                $this->client->request($method, '/admin/health/api/blood-pressure/reading-1?version=1', server: ['CONTENT_TYPE' => 'application/json', ...$server], content: json_encode(self::measurement(), JSON_THROW_ON_ERROR));
                self::assertResponseStatusCodeSame(403);
                self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
            }
        }
        self::assertSame([], $this->call('GET')['records']);
    }

    public function testBrowserAndApiShareDataAndVersionConflicts(): void
    {
        $this->client->loginUser($this->adminUser());
        $page = $this->client->request('GET', '/admin/health');
        self::assertResponseIsSuccessful();
        $csrf = $page->filter('#health-app')->attr('data-csrf');
        $server = ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $csrf];
        $this->client->request('PUT', '/admin/health/api/blood-pressure/mobile', server: $server, content: json_encode(self::measurement(), JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();
        self::assertSame(120, $this->call('GET', '/mobile')['systolic']);
        $this->call('PUT', '/mobile', ['version' => 1, 'notes' => 'updated via API']);
        $this->client->request('PUT', '/admin/health/api/blood-pressure/mobile', server: $server, content: '{"version":1,"notes":"stale mobile"}');
        self::assertResponseStatusCodeSame(409);
        self::assertSame('updated via API', $this->call('GET', '/mobile')['notes']);
        $this->client->request('DELETE', '/admin/health/api/blood-pressure/mobile?version=2', server: $server);
        self::assertResponseStatusCodeSame(204);
        self::assertSame([], $this->call('GET')['records']);
    }

    /** @param array<string, mixed> $patch */
    #[DataProvider('invalidPayloads')]
    public function testInvalidMeasurementDoesNotPersist(array $patch): void
    {
        $this->call('PUT', '/bad', [...self::measurement(), ...$patch]);
        self::assertResponseStatusCodeSame(422);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        self::assertSame([], $this->call('GET')['records']);
    }

    public static function invalidPayloads(): iterable
    {
        yield 'unknown field' => [['diagnosis' => 'invented']];
        yield 'read-only id' => [['id' => 'other']];
        yield 'read-only timestamp' => [['updatedAt' => '2026-10-19']];
        yield 'string version' => [['version' => '0']];
        yield 'missing measuredAt' => [['measuredAt' => null]];
        yield 'invalid calendar date' => [['measuredAt' => '2026-02-30T07:30:00+08:00']];
        yield 'missing timezone' => [['measuredAt' => '2026-10-19T07:30:00']];
        yield 'invalid hour' => [['measuredAt' => '2026-10-19T24:30:00+08:00']];
        yield 'invalid offset' => [['measuredAt' => '2026-10-19T07:30:00+14:01']];
        yield 'fractional seconds unsupported' => [['measuredAt' => '2026-10-19T07:30:00.500+08:00']];
        yield 'systolic null' => [['systolic' => null]];
        yield 'string systolic' => [['systolic' => '120']];
        yield 'fractional systolic' => [['systolic' => 120.5]];
        yield 'boolean systolic' => [['systolic' => true]];
        yield 'systolic outside entry bounds' => [['systolic' => 401]];
        yield 'negative diastolic' => [['diastolic' => -1]];
        yield 'reversed pressure' => [['diastolic' => 130]];
        yield 'equal pressure' => [['diastolic' => 120]];
        yield 'zero pulse' => [['pulse' => 0]];
        yield 'fractional pulse' => [['pulse' => 60.5]];
        yield 'unknown arm' => [['arm' => 'both']];
        yield 'unknown posture' => [['posture' => 'running']];
        yield 'notes must be text' => [['notes' => ['oops']]];
        yield 'notes too long' => [['notes' => str_repeat('字', 2001)]];
    }

    public function testMissingVersionInvalidFiltersAndTransportDoNotChangeSavedData(): void
    {
        $this->call('PUT', '/reading-1', ['systolic' => 120]);
        self::assertResponseStatusCodeSame(428);
        $saved = $this->call('PUT', '/reading-1', self::measurement());
        foreach (['', '?version=-1', '?version=0', '?version=1.5'] as $query) {
            $this->call('DELETE', '/reading-1'.$query);
            self::assertResponseStatusCodeSame('' === $query ? 428 : 422);
        }
        foreach (['?from=2026-02-30', '?to=bad', '?from=2026-10-20&to=2026-10-19'] as $query) {
            $this->call('GET', $query);
            self::assertResponseStatusCodeSame(422);
        }
        $this->call('PUT', '/missing', ['version' => 1, 'notes' => 'cannot create using stale version']);
        self::assertResponseStatusCodeSame(404);
        $this->client->request('PUT', '/api/v1/health/blood-pressure/reading-1', server: ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token], content: '{}');
        self::assertResponseStatusCodeSame(415);
        foreach (['[]', 'null', '{bad'] as $body) {
            $this->client->request('PUT', '/api/v1/health/blood-pressure/reading-1', server: $this->server(), content: $body);
            self::assertResponseStatusCodeSame(422);
        }
        $this->client->request('PUT', '/api/v1/health/blood-pressure/reading-1', server: $this->server(), content: str_repeat(' ', 16385));
        self::assertResponseStatusCodeSame(413);
        self::assertSame($saved, $this->call('GET', '/reading-1'));
    }

    public function testUnsupportedMethodsAndInvalidIdsRemainPrivate(): void
    {
        foreach (['POST', 'PATCH'] as $method) {
            $this->call($method, '/reading-1', self::measurement());
            self::assertResponseStatusCodeSame(405);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
        $this->call('PUT', '', self::measurement());
        self::assertResponseStatusCodeSame(405);
        $this->call('PUT', '/'.str_repeat('a', 81), self::measurement());
        self::assertResponseStatusCodeSame(422);
        self::assertSame([], $this->call('GET')['records']);
    }

    /** @return array<string, mixed> */
    private static function measurement(): array
    {
        return ['version' => 0, 'measuredAt' => '2026-10-19T07:30:00+08:00', 'systolic' => 120, 'diastolic' => 80];
    }

    /** @return array<string, string> */
    private function server(): array
    {
        return ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token, 'CONTENT_TYPE' => 'application/json'];
    }

    /** @param array<string, mixed>|null $body
     * @return array<string, mixed>
     */
    private function call(string $method, string $path = '', ?array $body = null): array
    {
        $this->client->request($method, '/api/v1/health/blood-pressure'.$path, server: $this->server(), content: null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode($this->client->getResponse()->getContent() ?: '{}', true, 512, JSON_THROW_ON_ERROR);
    }
}
