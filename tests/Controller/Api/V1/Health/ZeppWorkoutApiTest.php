<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Tests\Controller\Admin\AdminWebTestCase;

final class ZeppWorkoutApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testWorkoutRoutesRequireAuthenticationAndArePrivate(): void
    {
        foreach (['', '/status', '/sync', '/zepp-'.str_repeat('a', 16).'-123'] as $path) {
            $this->client->request('GET', '/api/v1/health/zepp/workouts'.$path);
            self::assertResponseStatusCodeSame(401);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
        $token = (string) Token::generate();
        $this->withApiKey($token);
        $server = ['HTTP_AUTHORIZATION' => 'Bearer '.$token];
        $this->client->request('GET', '/api/v1/health/zepp/workouts', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame(['records' => []], json_decode($this->client->getResponse()->getContent(), true));
        foreach (['from=2026-02-30', 'from=2026-09-30&to=2026-09-20'] as $query) {
            $this->client->request('GET', '/api/v1/health/zepp/workouts?'.$query, server: $server);
            self::assertResponseStatusCodeSame(422);
        }
        $this->client->request('GET', '/api/v1/health/zepp/workouts/zepp-'.str_repeat('a', 16).'-123', server: $server);
        self::assertResponseStatusCodeSame(404);
        $this->client->request('GET', '/api/v1/health/zepp/workouts/sync', server: $server);
        self::assertResponseStatusCodeSame(405);
    }

    public function testSyncRejectsCredentialsInvalidRangeAndMissingCsrf(): void
    {
        $this->client->loginUser($this->adminUser(), 'main');
        $this->client->request('POST', '/admin/health/api/zepp/workouts/sync', server: ['CONTENT_TYPE' => 'application/json'], content: '{}');
        self::assertResponseStatusCodeSame(403);
        $token = (string) Token::generate();
        $this->withApiKey($token);
        foreach (['{"appToken":"secret"}', '{"days":0}', '{"days":91}', '{"days":null}', '[]'] as $body) {
            $this->client->request('POST', '/api/v1/health/zepp/workouts/sync', server: ['HTTP_AUTHORIZATION' => 'Bearer '.$token, 'CONTENT_TYPE' => 'application/json'], content: $body);
            self::assertResponseStatusCodeSame(422);
        }
    }
}
