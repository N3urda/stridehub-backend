<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Tests\Controller\Admin\AdminWebTestCase;

final class WeightCollectionApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    private function call(string $method, string $url, ?array $body = null, string $token = '', string $csrf = ''): array
    {
        $this->client->request($method, $url, server: ['CONTENT_TYPE' => 'application/json', 'HTTP_AUTHORIZATION' => 'Bearer '.$token, 'HTTP_X_CSRF_TOKEN' => $csrf], content: null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode($this->client->getResponse()->getContent(), true) ?? [];
    }

    private function pair(string $name = 'Demo phone'): array
    {
        $this->client->loginUser($this->adminUser());
        $csrf = $this->call('GET', '/admin/health/api/collection/csrf')['csrfToken'];
        $d = $this->call('POST', '/admin/health/api/collection/devices', ['name' => $name], csrf: $csrf);
        self::assertResponseStatusCodeSame(201);

        return $d + ['csrf' => $csrf];
    }

    private function capture(string $id = 'a0000000-0000-4000-8000-000000000001', string $at = '2026-10-21T08:00:00+08:00', float $kg = 75.2): array
    {
        return ['measurementId' => $id, 'measuredAt' => $at, 'localDate' => '2026-10-21', 'weightKg' => $kg, 'profile' => ['heightCm' => 175], 'derived' => ['algorithmVersion' => 'v1', 'values' => ['bmi' => 24.55]]];
    }

    public function testClientCandidateMakesUncertainPairRetrySafe(): void
    {
        $d = $this->pair();
        $body = ['name' => 'Stable candidate', 'deviceId' => str_repeat('a', 32)];
        $first = $this->call('POST', '/admin/health/api/collection/devices', $body, csrf: $d['csrf']);
        $second = $this->call('POST', '/admin/health/api/collection/devices', $body, csrf: $d['csrf']);
        self::assertSame($first['deviceId'], $second['deviceId']);
        self::assertCount(2, $this->call('GET', '/admin/health/api/collection/devices')['devices']);
        $this->call('GET', '/device/v1/weight', token: $first['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('GET', '/device/v1/weight', token: $second['token']);
        self::assertResponseIsSuccessful();
        $this->call('POST', '/admin/health/api/collection/devices', [...$body, 'deviceId' => '../invalid'], csrf: $d['csrf']);
        self::assertResponseStatusCodeSame(422);
    }

    public function testSameDeviceCredentialRotationRetainsReplayAndTombstones(): void
    {
        $d = $this->pair();
        $p = $this->capture();
        $url = '/device/v1/weight/'.$p['measurementId'];
        $this->call('PUT', $url, $p, $d['token']);
        $this->call('DELETE', '/admin/health/api/collection/devices/'.$d['deviceId'], csrf: $d['csrf']);
        $renewed = $this->call('POST', '/admin/health/api/collection/devices', ['name' => 'Recovered phone', 'deviceId' => $d['deviceId']], csrf: $d['csrf']);
        self::assertSame($d['deviceId'], $renewed['deviceId']);
        self::assertNotSame($d['token'], $renewed['token']);
        $this->call('GET', $url, token: $d['token']);
        self::assertResponseStatusCodeSame(401);
        self::assertTrue($this->call('PUT', $url, $p, $renewed['token'])['replayed']);
        $this->call('DELETE', '/admin/health/api/collection/weight/'.$p['measurementId'].'?deviceId='.$d['deviceId'], csrf: $d['csrf']);
        $again = $this->call('POST', '/admin/health/api/collection/devices', ['name' => 'Recovered phone', 'deviceId' => $d['deviceId']], csrf: $d['csrf']);
        $this->call('PUT', $url, $p, $again['token']);
        self::assertResponseStatusCodeSame(409);
    }

    public function testCaptureReplayIsolationOrderingDeletionAndDailyProjection(): void
    {
        $d = $this->pair();
        $p = $this->capture();
        $url = '/device/v1/weight/'.$p['measurementId'];
        $r = $this->call('PUT', $url, $p, $d['token']);
        self::assertResponseIsSuccessful();
        self::assertFalse($r['replayed']);
        self::assertNull($r['measurement']['capture']['fatPercent']);
        self::assertSame('projected', $r['projection']['status']);
        self::assertTrue($this->call('PUT', $url, $p, $d['token'])['replayed']);
        $this->call('PUT', $url, [...$p, 'weightKg' => 76], $d['token']);
        self::assertResponseStatusCodeSame(409);
        $other = $this->pair('Other');
        $this->call('GET', $url, token: $other['token']);
        self::assertResponseStatusCodeSame(404);
        $later = $this->capture('b0000000-0000-4000-8000-000000000002', '2026-10-21T01:00:00Z', 74);
        $this->call('PUT', '/device/v1/weight/'.$later['measurementId'], $later, $d['token']);
        self::assertResponseIsSuccessful();
        $list = $this->call('GET', '/device/v1/weight?limit=1', token: $d['token']);
        self::assertSame(2, $list['total']);
        self::assertSame($later['measurementId'], $list['records'][0]['measurementId']);
        $daily = $this->call('GET', '/admin/health/api/weight');
        self::assertEquals(74, $daily['records'][0]['weightKg']);
        $this->call('DELETE', '/admin/health/api/collection/weight/'.$later['measurementId'].'?deviceId='.$d['deviceId'], csrf: $d['csrf']);
        self::assertResponseIsSuccessful();
        $daily = $this->call('GET', '/admin/health/api/weight');
        self::assertEquals(75.2, $daily['records'][0]['weightKg']);
        $this->call('PUT', '/device/v1/weight/'.$later['measurementId'], $later, $d['token']);
        self::assertResponseStatusCodeSame(409);
        $this->call('PUT', '/admin/health/api/weight/2026-10-21', ['version' => $daily['version'], 'weightKg' => 80, 'source' => 'manual'], csrf: $d['csrf']);
        self::assertResponseIsSuccessful();
        $new = $this->capture('c0000000-0000-4000-8000-000000000003', '2026-10-21T10:00:00+08:00');
        $r = $this->call('PUT', '/device/v1/weight/'.$new['measurementId'], $new, $d['token']);
        self::assertSame('manual_conflict', $r['projection']['status']);
    }

    public function testHistoricalCapturedDayIsIndependentFromUploadOffset(): void
    {
        $d = $this->pair();
        $p = $this->capture(at: '2026-10-20T23:00:00Z');
        $r = $this->call('PUT', '/device/v1/weight/'.$p['measurementId'], $p, $d['token']);
        self::assertResponseIsSuccessful();
        self::assertSame('2026-10-21', $r['measurement']['capture']['localDate']);
        self::assertSame('2026-10-20T23:00:00.000000Z', $r['measurement']['capture']['measuredAt']);
    }

    public function testBodyLimitsAndStableUtcTieBreak(): void
    {
        $d = $this->pair();
        $p = $this->capture();
        $url = '/device/v1/weight/'.$p['measurementId'];
        $this->client->request('PUT', $url, server: ['CONTENT_TYPE' => 'text/plain', 'HTTP_AUTHORIZATION' => 'Bearer '.$d['token']], content: '{}');
        self::assertResponseStatusCodeSame(415);
        $this->client->request('PUT', $url, server: ['CONTENT_TYPE' => 'application/json', 'HTTP_AUTHORIZATION' => 'Bearer '.$d['token']], content: str_repeat(' ', 32769));
        self::assertResponseStatusCodeSame(413);
        $this->call('PUT', $url, $p, $d['token']);
        self::assertResponseIsSuccessful();
        $tie = $this->capture('b0000000-0000-4000-8000-000000000002', '2026-10-21T00:00:00Z', 74);
        $this->call('PUT', '/device/v1/weight/'.$tie['measurementId'], $tie, $d['token']);
        self::assertResponseIsSuccessful();
        $list = $this->call('GET', '/device/v1/weight', token: $d['token']);
        self::assertSame($tie['measurementId'], $list['records'][0]['measurementId']);
        $changed = $p;
        $changed['derived']['values']['bmi'] = '24.55';
        $this->call('PUT', $url, $changed, $d['token']);
        self::assertResponseStatusCodeSame(409);
    }

    public function testSnapshotJsonIdentitySurvivesStorageAndReplay(): void
    {
        $d = $this->pair();
        $p = $this->capture();
        $p['profile'] = (object) ['empty' => (object) [], 'list' => [], 'numericKeys' => (object) ['0' => 'a']];
        $p['derived']['values'] = (object) [];
        $url = '/device/v1/weight/'.$p['measurementId'];
        $this->call('PUT', $url, $p, $d['token']);
        self::assertResponseIsSuccessful();
        $this->call('GET', $url, token: $d['token']);
        $json = json_decode($this->client->getResponse()->getContent());
        self::assertInstanceOf(\stdClass::class, $json->measurement->capture->profile->empty);
        self::assertSame([], $json->measurement->capture->profile->list);
        self::assertInstanceOf(\stdClass::class, $json->measurement->capture->profile->numericKeys);
        self::assertInstanceOf(\stdClass::class, $json->measurement->capture->derived->values);
        self::assertTrue($this->call('PUT', $url, $p, $d['token'])['replayed']);
        $p['profile']->empty = [];
        $this->call('PUT', $url, $p, $d['token']);
        self::assertResponseStatusCodeSame(409);
    }

    public function testManualDailyDeletionIsProtectedButCollectionDeletionReleasesOwnership(): void
    {
        $d = $this->pair();
        $p = $this->capture();
        $url = '/device/v1/weight/'.$p['measurementId'];
        $this->call('PUT', $url, $p, $d['token']);
        $daily = $this->call('GET', '/admin/health/api/weight');
        $this->call('DELETE', '/admin/health/api/weight/2026-10-21?version='.$daily['version'], csrf: $d['csrf']);
        self::assertResponseStatusCodeSame(204);
        $r = $this->call('PUT', $url, $p, $d['token']);
        self::assertSame('manual_conflict', $r['projection']['status']);
        $next = $this->capture('b0000000-0000-4000-8000-000000000002');
        $r = $this->call('PUT', '/device/v1/weight/'.$next['measurementId'], $next, $d['token']);
        self::assertSame('manual_conflict', $r['projection']['status']);
        self::assertSame([], $this->call('GET', '/admin/health/api/weight?from=2026-10-21&to=2026-10-21')['records']);

        $p['localDate'] = '2026-10-22';
        $p['measurementId'] = 'c0000000-0000-4000-8000-000000000003';
        $this->call('PUT', '/device/v1/weight/'.$p['measurementId'], $p, $d['token']);
        $this->call('DELETE', '/admin/health/api/collection/weight/'.$p['measurementId'].'?deviceId='.$d['deviceId'], csrf: $d['csrf']);
        $p['measurementId'] = 'd0000000-0000-4000-8000-000000000004';
        $r = $this->call('PUT', '/device/v1/weight/'.$p['measurementId'], $p, $d['token']);
        self::assertSame('projected', $r['projection']['status']);
    }

    public function testAuthorizationCsrfValidationAndRevocation(): void
    {
        $d = $this->pair();
        $p = $this->capture();
        $url = '/device/v1/weight/'.$p['measurementId'];
        $this->call('POST', '/admin/health/api/collection/devices', ['name' => 'bad']);
        self::assertResponseStatusCodeSame(403);
        $this->call('PUT', $url, $p, 'wrong');
        self::assertResponseStatusCodeSame(401);
        $this->call('PUT', $url, [...$p, 'weightKg' => 301], $d['token']);
        self::assertResponseStatusCodeSame(422);
        $this->call('PUT', $url, [...$p, 'measuredAt' => '2026-02-30T08:00:00Z'], $d['token']);
        self::assertResponseStatusCodeSame(422);
        $this->call('GET', '/device/v1/weight?limit=101', token: $d['token']);
        self::assertResponseStatusCodeSame(422);
        $this->call('GET', '/api/v1/health/context', token: $d['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('POST', '/mcp', [], $d['token']);
        self::assertResponseStatusCodeSame(401);
        $listed = $this->call('GET', '/admin/health/api/collection/devices');
        self::assertStringNotContainsString('token', json_encode($listed));
        $this->call('DELETE', '/admin/health/api/collection/devices/'.$d['deviceId'], csrf: $d['csrf']);
        self::assertResponseStatusCodeSame(204);
        $this->call('GET', '/device/v1/weight', token: $d['token']);
        self::assertResponseStatusCodeSame(401);
        $this->client->getCookieJar()->clear();
        $this->call('GET', '/admin/health/api/collection/devices', token: $d['token']);
        self::assertResponseRedirects('/admin/login');
    }
}
