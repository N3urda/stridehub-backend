<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Tests\Controller\Admin\AdminWebTestCase;

final class ZeppDataApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testDataAndRawRoutesRequireAuthenticationAndNeverCache(): void
    {
        $token = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $token;
        foreach (['status', 'sources', 'days', 'days/2026-10-19', 'raw/'.str_repeat('a', 64), 'sync'] as $path) {
            $this->client->request('GET', '/api/v1/health/zepp/'.$path);
            self::assertResponseStatusCodeSame(401);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
        $server = ['HTTP_AUTHORIZATION' => 'Bearer '.$token];
        $this->client->request('GET', '/api/v1/health/zepp/days', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame(['records' => []], json_decode($this->client->getResponse()->getContent(), true));
        $this->client->request('GET', '/api/v1/health/zepp/sources', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame(['records' => [], 'next' => null], json_decode($this->client->getResponse()->getContent(), true));
        $this->client->request('GET', '/api/v1/health/zepp/sources?limit=201', server: $server);
        self::assertResponseStatusCodeSame(422);
        $this->client->request('GET', '/api/v1/health/zepp/days?from=2026-02-30', server: $server);
        self::assertResponseStatusCodeSame(422);
        $this->client->request('GET', '/api/v1/health/zepp/days/2026-10-19', server: $server);
        self::assertResponseStatusCodeSame(404);
        $this->client->request('GET', '/api/v1/health/zepp/raw/'.str_repeat('a', 64), server: $server);
        self::assertResponseStatusCodeSame(404);
    }

    public function testSyncRejectsCredentialsAndRequiresAdminCsrf(): void
    {
        $this->client->loginUser($this->adminUser(), 'main');
        $this->client->request('POST', '/admin/health/api/zepp/sync', server: ['CONTENT_TYPE' => 'application/json'], content: '{}');
        self::assertResponseStatusCodeSame(403);
        $token = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $token;
        foreach (['{"appToken":"secret"}', '{"days":0}', '{"days":91}', '{"days":null}', '[]'] as $body) {
            $this->client->request('POST', '/api/v1/health/zepp/sync', server: ['HTTP_AUTHORIZATION' => 'Bearer '.$token, 'CONTENT_TYPE' => 'application/json'], content: $body);
            self::assertResponseStatusCodeSame(422);
        }
    }
}
