<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Tests\Controller\Admin\AdminWebTestCase;

final class MiFitnessApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testAuthenticationCsrfValidationAndUnconfiguredSync(): void
    {
        $_ENV['MI_FITNESS_CREDENTIALS_FILE'] = '/not-configured-mi-fixture.json';
        try {
            $token = (string) Token::generate();
            $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $token;
            foreach (['mi-fitness/status', 'mi-fitness/sync', 'mi-fitness/data', 'mi-fitness/records', 'sleep/sources'] as $path) {
                $this->client->request('GET', '/api/v1/health/'.$path);
                self::assertResponseStatusCodeSame(401);
                self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
            }
            $server = ['HTTP_AUTHORIZATION' => 'Bearer '.$token, 'CONTENT_TYPE' => 'application/json'];
            foreach (['{"days":null}', '{"days":0}', '{"days":91}', '{"apiKey":"not-accepted"}', '[]'] as $body) {
                $this->client->request('POST', '/api/v1/health/mi-fitness/sync', server: $server, content: $body);
                self::assertResponseStatusCodeSame(422);
            }
            $this->client->request('GET', '/api/v1/health/mi-fitness/status', server: $server);
            self::assertResponseIsSuccessful();
            self::assertSame('not_configured', json_decode($this->client->getResponse()->getContent(), true)['state']);
            foreach (['data', 'records'] as $path) {
                $this->client->request('GET', '/api/v1/health/mi-fitness/'.$path, server: $server);
                self::assertResponseIsSuccessful();
                self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
            }
            foreach (['type=invalid', 'limit=201', 'offset=-1', 'from=invalid'] as $query) {
                $this->client->request('GET', '/api/v1/health/mi-fitness/records?'.$query, server: $server);
                self::assertResponseStatusCodeSame(422);
            }
            $this->client->request('GET', '/api/v1/health/sleep/sources', server: $server);
            self::assertResponseIsSuccessful();
            $this->client->request('POST', '/api/v1/health/mi-fitness/sync', server: $server, content: '{}');
            self::assertResponseStatusCodeSame(503);
            $this->client->loginUser($this->adminUser(), 'main');
            $this->client->request('POST', '/admin/health/api/mi-fitness/sync', server: ['CONTENT_TYPE' => 'application/json'], content: '{}');
            self::assertResponseStatusCodeSame(403);
            $crawler = $this->client->request('GET', '/admin/health');
            self::assertResponseIsSuccessful();
            self::assertSame(1, $crawler->filter('#mi-fitness-sync')->count());
            self::assertSame(1, $crawler->filter('#sleep-section')->count());
            $this->client->request('POST', '/admin/health/api/mi-fitness/sync', server: ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $crawler->filter('#health-app')->attr('data-csrf')], content: '{}');
            self::assertResponseStatusCodeSame(503);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        } finally {
            unset($_ENV['MI_FITNESS_CREDENTIALS_FILE']);
        }
    }
}
