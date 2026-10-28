<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Domain\Settings\DbalSettingsRepository;
use App\Domain\Settings\SettingsName;
use App\Tests\Controller\Admin\AdminWebTestCase;

final class HealthRecordsApiTest extends AdminWebTestCase
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

    public function testPlansPersistReplayPatchAndRejectStaleChanges(): void
    {
        self::assertSame([], $this->call('GET', 'plans')['plans']);
        $input = self::plan();
        $saved = $this->call('PUT', 'plans/sleep-routine', $input);
        self::assertResponseIsSuccessful();
        self::assertSame(1, $saved['version']);
        self::assertSame('ai', $saved['source']);
        self::assertSame('pending', $saved['items'][0]['status']);
        self::assertSame($saved, $this->call('PUT', 'plans/sleep-routine', $input));
        self::assertSame($saved, $this->call('GET', 'plans/sleep-routine'));
        self::assertSame([$saved], $this->call('GET', 'plans')['plans']);

        $updated = $this->call('PUT', 'plans/sleep-routine', ['version' => 1, 'status' => 'active']);
        self::assertSame(2, $updated['version']);
        self::assertSame($saved['items'], $updated['items']);
        self::assertSame($saved['rationale'], $updated['rationale']);
        $this->call('PUT', 'plans/sleep-routine', ['version' => 1, 'status' => 'cancelled']);
        self::assertResponseStatusCodeSame(409);
        $this->call('DELETE', 'plans/sleep-routine?version=1');
        self::assertResponseStatusCodeSame(409);
        self::assertSame($updated, $this->call('GET', 'plans/sleep-routine'));
        $this->call('DELETE', 'plans/sleep-routine?version=2');
        self::assertResponseStatusCodeSame(204);
        self::assertSame([], $this->call('GET', 'plans')['plans']);
    }

    public function testPlansRejectInvalidDatesItemsSourcesAndMissingVersions(): void
    {
        foreach ([['startDate' => '2026-02-30'], ['endDate' => '2026-10-18'], ['rationale' => ''], ['source' => 'device'], ['diagnosis' => 'unsupported'], ['items' => [['id' => 'item', 'title' => '记录', 'date' => '2026-10-18']]], ['items' => [['id' => 'item', 'title' => '记录', 'time' => '24:00']]], ['items' => [['id' => 'same', 'title' => '一'], ['id' => 'same', 'title' => '二']]]] as $patch) {
            $this->call('PUT', 'plans/invalid', [...self::plan(), ...$patch]);
            self::assertResponseStatusCodeSame(422);
        }
        $this->call('PUT', 'plans/invalid', ['title' => '未带版本']);
        self::assertResponseStatusCodeSame(428);
        $this->call('DELETE', 'plans/invalid?version=0');
        self::assertResponseStatusCodeSame(422);
        self::assertSame([], $this->call('GET', 'plans')['plans']);
    }

    public function testWeightUsesExistingHistoryAndChangesAreVisibleToLegacyApi(): void
    {
        $before = $this->call('GET', 'weight');
        self::assertSame('kg', $before['unit']);
        self::assertCount(4, $before['records']);
        self::assertSame('unknown', $before['records'][0]['source']);
        $saved = $this->call('PUT', 'weight/2026-10-19', ['version' => $before['version'], 'weightKg' => 72.4, 'notes' => '晨起实测']);
        self::assertResponseIsSuccessful();
        self::assertCount(5, $saved['records']);
        self::assertSame(72.4, $saved['records'][0]['weightKg']);
        self::assertSame('manual', $saved['records'][0]['source']);
        self::assertNotSame($before['version'], $saved['version']);
        $this->client->request('GET', '/api/v1/athlete/weights', server: $this->server());
        self::assertResponseIsSuccessful();
        $legacy = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(['on' => '2026-10-19', 'weight' => 72.4], $legacy['weights'][0]);
        $updated = $this->call('PUT', 'weight/2026-10-19', ['version' => $saved['version'], 'weightKg' => 72.2]);
        self::assertSame('晨起实测', $updated['records'][0]['notes']);
        self::assertCount(5, $updated['records']);
        $this->call('PUT', 'weight/2026-10-20', ['version' => $saved['version'], 'weightKg' => 72.1]);
        self::assertResponseStatusCodeSame(409);
        $this->call('DELETE', 'weight/2026-10-19?version='.$saved['version']);
        self::assertResponseStatusCodeSame(409);
        self::assertSame($updated, $this->call('GET', 'weight'));
        self::assertCount(1, $this->call('GET', 'weight?from=2026-10-19&to=2026-10-19')['records']);
        $this->call('DELETE', 'weight/2026-10-19?version='.$updated['version']);
        self::assertResponseStatusCodeSame(204);
        self::assertSame($before['records'], $this->call('GET', 'weight')['records']);
    }

    public function testLegacyWeightWritesAndUnitChangesInvalidateReadVersion(): void
    {
        $before = $this->call('GET', 'weight');
        $this->client->request('POST', '/api/v1/athlete/weights', server: $this->server(), content: '{"on":"2026-10-18","weight":73.2}');
        self::assertResponseIsSuccessful();
        $this->call('PUT', 'weight/2026-10-19', ['version' => $before['version'], 'weightKg' => 72.1]);
        self::assertResponseStatusCodeSame(409);
        $before = $this->call('GET', 'weight');
        $settings = self::getContainer()->get(DbalSettingsRepository::class);
        assert($settings instanceof DbalSettingsRepository);
        $settings->save(SettingsName::UNIT_SYSTEM, 'imperial');
        $this->call('PUT', 'weight/2026-10-19', ['version' => $before['version'], 'weightKg' => 72.1]);
        self::assertResponseStatusCodeSame(409);
        $before = $this->call('GET', 'weight');
        $saved = $this->call('PUT', 'weight/2026-10-19', ['version' => $before['version'], 'weightKg' => 72.1]);
        self::assertSame(72.1, $saved['records'][0]['weightKg']);
        $stored = $settings->find(SettingsName::WEIGHT_HISTORY);
        self::assertEqualsWithDelta(72.1 / 0.45359237, $stored[array_key_last($stored)]['weight'], 0.00001);
    }

    public function testEmptyWeightHistoryIsUnknownAndInvalidInputsDoNotPersist(): void
    {
        $settings = self::getContainer()->get(DbalSettingsRepository::class);
        assert($settings instanceof DbalSettingsRepository);
        $settings->save(SettingsName::WEIGHT_HISTORY, []);
        $empty = $this->call('GET', 'weight');
        self::assertSame([], $empty['records']);
        foreach ([['weightKg' => null], ['weightKg' => '72'], ['weightKg' => -1], ['weightKg' => 1001], ['source' => 'ai'], ['notes' => []], ['unexpected' => 'field']] as $patch) {
            $this->call('PUT', 'weight/2026-10-19', ['version' => $empty['version'], 'weightKg' => 72.1, ...$patch]);
            self::assertResponseStatusCodeSame(422);
        }
        $this->call('PUT', 'weight/2026-10-19', ['weightKg' => 72.1]);
        self::assertResponseStatusCodeSame(428);
        $this->call('PUT', 'weight/2026-02-30', ['version' => $empty['version'], 'weightKg' => 72.1]);
        self::assertResponseStatusCodeSame(422);
        $this->call('GET', 'weight?from=2026-10-20&to=2026-10-19');
        self::assertResponseStatusCodeSame(422);
        self::assertSame($empty, $this->call('GET', 'weight'));
    }

    public function testHealthApisRequireAuthenticationAndAdminCsrfAndShareRecords(): void
    {
        foreach (['plans', 'weight'] as $path) {
            $this->client->request('GET', '/api/v1/health/'.$path);
            self::assertResponseStatusCodeSame(401);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
            $this->client->request('GET', '/admin/health/api/'.$path);
            self::assertResponseRedirects('/admin/login');
        }
        $this->client->loginUser($this->adminUser());
        foreach (['plans/one', 'weight/2026-10-19'] as $path) {
            foreach (['PUT', 'DELETE'] as $method) {
                $this->client->request($method, '/admin/health/api/'.$path, server: ['CONTENT_TYPE' => 'application/json'], content: '{}');
                self::assertResponseStatusCodeSame(403);
                self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
            }
        }
        $page = $this->client->request('GET', '/admin/health');
        self::assertResponseIsSuccessful();
        $csrf = $page->filter('#health-app')->attr('data-csrf');
        $this->client->request('PUT', '/admin/health/api/plans/browser', server: ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $csrf], content: json_encode(self::plan(), JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();
        self::assertSame('browser', $this->call('GET', 'plans/browser')['id']);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        $before = $this->call('GET', 'weight');
        $this->client->request('PUT', '/admin/health/api/weight/2026-10-19', server: ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $csrf], content: json_encode(['version' => $before['version'], 'weightKg' => 72.1], JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();
        self::assertSame(72.1, $this->call('GET', 'weight')['records'][0]['weightKg']);
    }

    /** @return array<string, mixed> */
    private static function plan(): array
    {
        return ['version' => 0, 'title' => '作息记录计划', 'category' => 'sleep', 'goal' => '观察这一周的作息规律', 'startDate' => '2026-10-19', 'endDate' => '2026-10-25', 'source' => 'ai', 'sourceLabel' => '用户授权的 AI 助手', 'rationale' => '根据用户要求记录睡眠与晨起感受，不推断缺失数据。', 'items' => [['id' => 'record-sleep', 'title' => '记录晨起感受', 'date' => '2026-10-19', 'time' => '07:00']]];
    }

    /** @return array<string, string> */
    private function server(): array
    {
        return ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token, 'CONTENT_TYPE' => 'application/json'];
    }

    /** @param array<string, mixed>|null $body
     * @return array<string, mixed>
     */
    private function call(string $method, string $path, ?array $body = null): array
    {
        $this->client->request($method, '/api/v1/health/'.$path, server: $this->server(), content: null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode($this->client->getResponse()->getContent() ?: '{}', true, 512, JSON_THROW_ON_ERROR);
    }
}
