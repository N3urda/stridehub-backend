<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Tests\Controller\Admin\AdminWebTestCase;
use PHPUnit\Framework\Attributes\DataProvider;

final class HealthApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testHealthAndSchemaRequireBearerAndNeverCache(): void
    {
        $this->authorize();
        foreach (['context', 'openapi.json'] as $path) {
            $this->client->request('GET', '/api/v1/health/'.$path);
            self::assertResponseStatusCodeSame(401);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
            $this->client->request('GET', '/api/v1/health/'.$path, server: ['HTTP_AUTHORIZATION' => 'Bearer invalid']);
            self::assertResponseStatusCodeSame(401);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
    }

    public function testContextPersistsDefaultsPartialUpdatesAndExplicitArrayReplacement(): void
    {
        $server = $this->authorize();
        $this->client->request('GET', '/api/v1/health/context', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame(['reports' => [], 'constraints' => [], 'lifestylePreferences' => [], 'notes' => '', 'id' => 'default', 'version' => 0, 'updatedAt' => null], $this->data());
        $this->put(['version' => 0, 'reports' => [self::report()], 'constraints' => [['id' => 'limit-1', 'description' => '原文记录：复诊前避免高强度训练', 'sourceType' => 'clinician', 'sourceReportId' => 'exam-1']], 'lifestylePreferences' => ['周三休息']], $server);
        self::assertResponseIsSuccessful();
        $saved = $this->data();
        self::assertSame(1, $saved['version']);
        self::assertNotNull($saved['updatedAt']);
        self::assertSame('unknown', $saved['reports'][0]['findings'][0]['flag']);
        self::assertSame('', $saved['reports'][0]['findings'][0]['referenceRangeText']);
        self::assertTrue($saved['reports'][0]['needsReview']);
        self::assertSame('待结合原报告核对', $saved['reports'][0]['aiInterpretation']);
        self::assertSame(['医生原话'], $saved['reports'][0]['clinicianAdvice']);
        self::assertSame('active', $saved['constraints'][0]['status']);
        self::assertNull($saved['constraints'][0]['reviewOn']);
        $this->put(['version' => 1, 'notes' => '仅修改备注'], $server);
        self::assertResponseIsSuccessful();
        self::assertSame($saved['reports'], $this->data()['reports']);
        self::assertSame($saved['constraints'], $this->data()['constraints']);
        self::assertSame(['周三休息'], $this->data()['lifestylePreferences']);
        $this->client->request('GET', '/api/v1/health/context', server: $server);
        self::assertSame('仅修改备注', $this->data()['notes']);
        self::assertSame(2, $this->data()['version']);
        $this->put(['version' => 2, 'reports' => [], 'constraints' => [], 'lifestylePreferences' => []], $server);
        self::assertResponseIsSuccessful();
        self::assertSame([], $this->data()['reports']);
        self::assertSame([], $this->data()['constraints']);
        self::assertSame('仅修改备注', $this->data()['notes']);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
    }

    public function testMissingAndStaleVersionsCannotOverwriteTheContext(): void
    {
        $server = $this->authorize();
        $this->put(['notes' => 'missing version'], $server);
        self::assertResponseStatusCodeSame(428);
        $this->put(['version' => 0, 'notes' => 'first'], $server);
        self::assertResponseIsSuccessful();
        $this->put(['version' => 0, 'notes' => 'stale'], $server);
        self::assertResponseStatusCodeSame(409);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        $this->client->request('GET', '/api/v1/health/context', server: $server);
        self::assertSame('first', $this->data()['notes']);
    }

    public function testRemovingAReferencedReportRequiresUpdatingConstraintsInTheSameWrite(): void
    {
        $server = $this->authorize();
        $this->put(['version' => 0, 'reports' => [self::report()], 'constraints' => [['id' => 'limit', 'description' => '医嘱转录', 'sourceType' => 'clinician', 'sourceReportId' => 'exam-1']]], $server);
        self::assertResponseIsSuccessful();
        $this->put(['version' => 1, 'reports' => []], $server);
        self::assertResponseStatusCodeSame(422);
        $this->client->request('GET', '/api/v1/health/context', server: $server);
        self::assertSame(1, $this->data()['version']);
        self::assertCount(1, $this->data()['reports']);
        $this->put(['version' => 1, 'reports' => [], 'constraints' => []], $server);
        self::assertResponseIsSuccessful();
    }

    /** @param array<string, mixed> $patch */
    #[DataProvider('invalidPayloads')]
    public function testInvalidFieldsAreRejectedWithoutSaving(array $patch): void
    {
        $server = $this->authorize();
        $this->put(['version' => 0, ...$patch], $server);
        self::assertResponseStatusCodeSame(422);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        $this->client->request('GET', '/api/v1/health/context', server: $server);
        self::assertSame(0, $this->data()['version']);
    }

    public static function invalidPayloads(): iterable
    {
        yield 'unknown top-level field' => [['diagnosis' => 'invented']];
        yield 'read-only field' => [['updatedAt' => '2026-01-01']];
        yield 'version is not integer' => [['version' => '0']];
        yield 'reports must be a list' => [['reports' => new \stdClass()]];
        yield 'report count is bounded' => [['reports' => array_map(static fn (int $i): array => [...self::report(), 'id' => 'exam-'.$i], range(1, 31))]];
        yield 'report requires source' => [['reports' => [['id' => 'a', 'title' => 'exam', 'reportDate' => '2026-01-01']]]];
        yield 'unknown report field' => [['reports' => [[...self::report(), 'verifiedDiagnosis' => true]]]];
        yield 'duplicate report id' => [['reports' => [self::report(), self::report()]]];
        yield 'invalid report date' => [['reports' => [[...self::report(), 'reportDate' => '2026-02-30']]]];
        yield 'invalid id' => [['reports' => [[...self::report(), 'id' => '../secret']]]];
        yield 'long title' => [['reports' => [[...self::report(), 'title' => str_repeat('体', 201)]]]];
        yield 'review must be boolean' => [['reports' => [[...self::report(), 'needsReview' => 'false']]]];
        yield 'AI interpretation must be text' => [['reports' => [[...self::report(), 'aiInterpretation' => ['diagnosis']]]]];
        yield 'findings must be a list' => [['reports' => [[...self::report(), 'findings' => new \stdClass()]]]];
        yield 'per-report finding count is bounded' => [['reports' => [[...self::report(), 'findings' => array_fill(0, 201, ['name' => 'A', 'valueText' => '1'])]]]];
        yield 'total finding count is bounded' => [['reports' => array_map(static fn (int $i): array => [...self::report(), 'id' => 'exam-'.$i, 'findings' => array_fill(0, 200, ['name' => 'A', 'valueText' => '1'])], range(1, 6))]];
        yield 'unknown finding key' => [['reports' => [[...self::report(), 'findings' => [['name' => 'A', 'valueText' => '1', 'normalRange' => 'invented']]]]]];
        yield 'finding value is text' => [['reports' => [[...self::report(), 'findings' => [['name' => 'A', 'valueText' => 1]]]]]];
        yield 'invalid flag' => [['reports' => [[...self::report(), 'findings' => [['name' => 'A', 'valueText' => '1', 'flag' => 'healthy']]]]]];
        yield 'invalid page' => [['reports' => [[...self::report(), 'findings' => [['name' => 'A', 'valueText' => '1', 'page' => 0]]]]]];
        yield 'clinician advice is text list' => [['reports' => [[...self::report(), 'clinicianAdvice' => [false]]]]];
        yield 'unknown constraint key' => [['constraints' => [['id' => 'c', 'description' => 'x', 'sourceType' => 'user', 'severity' => 'high']]]];
        yield 'invalid constraint source' => [['constraints' => [['id' => 'c', 'description' => 'x', 'sourceType' => 'AI']]]];
        yield 'dangling report reference' => [['constraints' => [['id' => 'c', 'description' => 'x', 'sourceType' => 'clinician', 'sourceReportId' => 'missing']]]];
        yield 'duplicate constraint id' => [['constraints' => array_fill(0, 2, ['id' => 'c', 'description' => 'x', 'sourceType' => 'user'])]];
        yield 'constraint count is bounded' => [['constraints' => array_map(static fn (int $i): array => ['id' => 'c-'.$i, 'description' => 'x', 'sourceType' => 'user'], range(1, 51))]];
        yield 'invalid constraint date' => [['constraints' => [['id' => 'c', 'description' => 'x', 'sourceType' => 'user', 'reviewOn' => 'not-date']]]];
        yield 'review precedes start' => [['constraints' => [['id' => 'c', 'description' => 'x', 'sourceType' => 'user', 'validFrom' => '2026-10-10', 'reviewOn' => '2026-10-09']]]];
        yield 'invalid status' => [['constraints' => [['id' => 'c', 'description' => 'x', 'sourceType' => 'user', 'status' => 'expired']]]];
        yield 'too many preferences' => [['lifestylePreferences' => array_fill(0, 31, 'x')]];
        yield 'preference is not text' => [['lifestylePreferences' => [null]]];
        yield 'notes too long' => [['notes' => str_repeat('x', 10001)]];
    }

    public function testTransportMethodAndSchemaErrorsArePrivate(): void
    {
        $server = $this->authorize();
        $this->client->request('PUT', '/api/v1/health/context', server: $server, content: '{}');
        self::assertResponseStatusCodeSame(415);
        foreach (['[]', '{bad', 'null'] as $body) {
            $this->client->request('PUT', '/api/v1/health/context', server: [...$server, 'CONTENT_TYPE' => 'application/json'], content: $body);
            self::assertResponseStatusCodeSame(422);
        }
        $this->client->request('PUT', '/api/v1/health/context', server: [...$server, 'CONTENT_TYPE' => 'application/json'], content: str_repeat(' ', 1048577));
        self::assertResponseStatusCodeSame(413);
        foreach (['POST', 'PATCH', 'DELETE'] as $method) {
            $this->client->request($method, '/api/v1/health/context', server: $server);
            self::assertResponseStatusCodeSame(405);
            self::assertResponseHeaderSame('Allow', 'GET, PUT');
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
        $this->client->request('PUT', '/api/v1/health/openapi.json', server: $server);
        self::assertResponseStatusCodeSame(405);
        $this->client->request('GET', '/api/v1/health/unknown', server: $server);
        self::assertResponseStatusCodeSame(404);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        $this->client->request('GET', '/api/v1/health/openapi.json', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame('3.1.0', $this->data()['openapi']);
        self::assertArrayHasKey('/api/v1/health/context', $this->data()['paths']);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
    }

    /** @return array<string, mixed> */
    private static function report(): array
    {
        return ['id' => 'exam-1', 'title' => '年度体检', 'reportDate' => '2026-09-28', 'sourceLabel' => '用户上传报告第 2 页', 'findings' => [['name' => '报告项目', 'valueText' => '原始数值']], 'clinicianAdvice' => ['医生原话'], 'aiInterpretation' => '待结合原报告核对'];
    }

    /** @return array<string, string> */
    private function authorize(): array
    {
        $token = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $token;

        return ['HTTP_AUTHORIZATION' => 'Bearer '.$token];
    }

    /** @param array<string, mixed> $body
     * @param array<string, string> $server
     */
    private function put(array $body, array $server): void
    {
        $this->client->request('PUT', '/api/v1/health/context', server: [...$server, 'CONTENT_TYPE' => 'application/json'], content: json_encode($body, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function data(): array
    {
        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }
}
