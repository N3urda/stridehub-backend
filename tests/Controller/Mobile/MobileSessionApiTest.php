<?php

declare(strict_types=1);

namespace App\Tests\Controller\Mobile;

use App\Tests\Controller\Admin\AdminWebTestCase;
use Doctrine\DBAL\Connection;

final class MobileSessionApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    private function call(string $method, string $path, ?array $body = null, string $token = '', array $server = []): array
    {
        $this->client->request($method, $path, server: [...$server, 'CONTENT_TYPE' => 'application/json', 'HTTP_AUTHORIZATION' => 'Bearer '.$token], content: null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode($this->client->getResponse()->getContent(), true) ?? [];
    }

    private function login(): array
    {
        $result = $this->call('POST', '/mobile/v1/sessions', ['username' => self::ADMIN_USERNAME, 'password' => self::ADMIN_PASSWORD, 'deviceName' => 'Pixel test']);
        self::assertResponseStatusCodeSame(201);

        return $result;
    }

    public function testLoginCreatesPrivateRevocableSessionAndNeverStoresRawCredentials(): void
    {
        $session = $this->login();
        self::assertSame('admin', $session['username']);
        self::assertSame(1, $session['apiVersion']);
        self::assertContains('mobile-workflows', $session['capabilities']);
        self::assertMatchesRegularExpression('/^shmobile_[a-f0-9]{64}$/D', $session['token']);
        self::assertSame('2023-10-24T16:15:04+00:00', $session['expiresAt']);
        self::assertStringContainsString('no-store', $this->client->getResponse()->headers->get('Cache-Control'));
        self::assertCount(0, $this->client->getCookieJar()->all());
        $stored = json_encode(self::getContainer()->get(Connection::class)->fetchAllAssociative('SELECT * FROM TrainingRecord'));
        self::assertStringNotContainsString($session['token'], $stored);
        self::assertStringNotContainsString(self::ADMIN_PASSWORD, $stored);
        $metadata = $this->call('GET', '/mobile/v1/session', token: $session['token']);
        self::assertResponseIsSuccessful();
        self::assertSame('admin', $metadata['username']);
        self::assertArrayNotHasKey('token', $metadata);
        $this->call('DELETE', '/mobile/v1/session', token: $session['token']);
        self::assertResponseStatusCodeSame(204);
        $this->call('GET', '/mobile/v1/session', token: $session['token']);
        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidCredentialsUseUniformErrorsAndAreThrottled(): void
    {
        $first = $this->call('POST', '/mobile/v1/sessions', ['username' => 'missing', 'password' => 'sensitive-invalid', 'deviceName' => 'Phone']);
        self::assertResponseStatusCodeSame(401);
        for ($attempt = 1; $attempt < 5; ++$attempt) {
            self::assertSame($first, $this->call('POST', '/mobile/v1/sessions', ['username' => 'admin', 'password' => 'other-invalid', 'deviceName' => 'Phone']));
            self::assertResponseStatusCodeSame(401);
        }
        $this->call('POST', '/mobile/v1/sessions', ['username' => 'admin', 'password' => self::ADMIN_PASSWORD, 'deviceName' => 'Phone']);
        self::assertResponseStatusCodeSame(429);
        self::assertNotNull($this->client->getResponse()->headers->get('Retry-After'));
        self::assertStringNotContainsString('sensitive-invalid', $this->client->getResponse()->getContent());
    }

    public function testNativeTokenAllowsNeededApisButCannotBecomeGlobalOrAdminCredential(): void
    {
        $session = $this->login();
        foreach (['/api/v1/health/context', '/api/v1/training/profile', '/api/v1/running/overview', '/api/v1/activities', '/api/v1/athlete/weights'] as $path) {
            $this->call('GET', $path, token: $session['token']);
            self::assertResponseIsSuccessful();
        }
        $this->call('GET', '/api/v1/status', token: $session['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('POST', '/mcp', [], $session['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('GET', '/admin/health', token: $session['token']);
        self::assertResponseRedirects('/admin/login');
    }

    public function testNativePairingRetainsDeviceIdentityAndRotatesOnlyCollectionToken(): void
    {
        $session = $this->login();
        $payload = ['deviceId' => str_repeat('a', 32), 'name' => 'Native phone'];
        $first = $this->call('POST', '/mobile/v1/collection/devices', $payload, $session['token']);
        self::assertResponseStatusCodeSame(201);
        $second = $this->call('POST', '/mobile/v1/collection/devices', $payload, $session['token']);
        self::assertResponseStatusCodeSame(201);
        self::assertSame($first['deviceId'], $second['deviceId']);
        self::assertNotSame($first['token'], $second['token']);
        $this->call('GET', '/device/v1/weight', token: $first['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('GET', '/device/v1/weight', token: $second['token']);
        self::assertResponseIsSuccessful();
        $this->call('GET', '/mobile/v1/session', token: $second['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('GET', '/mobile/v1/session', token: $session['token']);
        self::assertResponseIsSuccessful();
    }

    public function testSubdirectoryRequestUsesPathInfoAndDoesNotIssueCookies(): void
    {
        $server = ['SCRIPT_NAME' => '/stride/index.php', 'SCRIPT_FILENAME' => '/var/www/public/index.php', 'PHP_SELF' => '/stride/index.php'];
        $session = $this->call('POST', '/stride/mobile/v1/sessions', ['username' => 'admin', 'password' => self::ADMIN_PASSWORD, 'deviceName' => 'Phone'], server: $server);
        self::assertResponseStatusCodeSame(201);
        $this->call('GET', '/stride/api/v1/health/context', token: $session['token'], server: $server);
        self::assertResponseIsSuccessful();
        self::assertCount(0, $this->client->getCookieJar()->all());
    }
}
