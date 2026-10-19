<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Tests\Controller\Admin\AdminWebTestCase;

final class SleepApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testSleepRoutesNeedAuthenticationAndNeverCache(): void
    {
        $_ENV['ZEPP_CREDENTIALS_FILE'] = $_SERVER['ZEPP_CREDENTIALS_FILE'] = '/not-configured-fixture.json';
        $server = $this->authorize();
        foreach (['sleep', 'sleep/status', 'sleep/sync'] as $path) {
            $this->client->request('GET', '/api/v1/health/'.$path);
            self::assertResponseStatusCodeSame(401);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
        $this->client->request('GET', '/api/v1/health/sleep', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame(['records' => []], json_decode($this->client->getResponse()->getContent(), true));
        $this->client->request('GET', '/api/v1/health/sleep/status', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame('not_configured', json_decode($this->client->getResponse()->getContent(), true)['state']);
        unset($_ENV['ZEPP_CREDENTIALS_FILE'], $_SERVER['ZEPP_CREDENTIALS_FILE']);
    }

    public function testSyncValidatesPayloadAndReportsMissingConnectionWithoutLosingHistory(): void
    {
        $_ENV['ZEPP_CREDENTIALS_FILE'] = $_SERVER['ZEPP_CREDENTIALS_FILE'] = '/not-configured-fixture.json';
        $server = [...$this->authorize(), 'CONTENT_TYPE' => 'application/json'];
        foreach (['{"days":null}', '{"days":"30"}', '{"days":0}', '{"days":91}', '{"appToken":"forbidden"}', '[]'] as $body) {
            $this->client->request('POST', '/api/v1/health/sleep/sync', server: $server, content: $body);
            self::assertResponseStatusCodeSame(422);
        }
        $this->client->request('POST', '/api/v1/health/sleep/sync', server: $server, content: '{}');
        self::assertResponseStatusCodeSame(503);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        self::assertSame('not_configured', json_decode($this->client->getResponse()->getContent(), true)['state']);
        $this->client->request('GET', '/api/v1/health/sleep?from=2026-02-30', server: $server);
        self::assertResponseStatusCodeSame(422);
        $this->client->request('DELETE', '/api/v1/health/sleep', server: $server);
        self::assertResponseStatusCodeSame(405);
        unset($_ENV['ZEPP_CREDENTIALS_FILE'], $_SERVER['ZEPP_CREDENTIALS_FILE']);
    }

    public function testAdminAliasReadsSessionAndRequiresHealthPageCsrfForSync(): void
    {
        $_ENV['ZEPP_CREDENTIALS_FILE'] = $_SERVER['ZEPP_CREDENTIALS_FILE'] = '/not-configured-fixture.json';
        $this->client->loginUser($this->adminUser(), 'main');
        $this->client->request('GET', '/admin/health/api/sleep');
        self::assertResponseIsSuccessful();
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        $this->client->request('GET', '/admin/health/api/sleep/status');
        self::assertResponseIsSuccessful();
        $this->client->request('POST', '/admin/health/api/sleep/sync', server: ['CONTENT_TYPE' => 'application/json'], content: '{}');
        self::assertResponseStatusCodeSame(403);
        $crawler = $this->client->request('GET', '/admin/health');
        self::assertResponseIsSuccessful();
        $csrf = $crawler->filter('#health-app')->attr('data-csrf');
        $this->client->request('POST', '/admin/health/api/sleep/sync', server: ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $csrf], content: '{}');
        self::assertResponseStatusCodeSame(503);
        self::assertSame('not_configured', json_decode($this->client->getResponse()->getContent(), true)['state']);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        unset($_ENV['ZEPP_CREDENTIALS_FILE'], $_SERVER['ZEPP_CREDENTIALS_FILE']);
    }

    /** @return array<string, string> */
    private function authorize(): array
    {
        $token = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $token;

        return ['HTTP_AUTHORIZATION' => 'Bearer '.$token];
    }
}
