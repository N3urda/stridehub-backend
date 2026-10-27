<?php

declare(strict_types=1);

namespace App\Tests\Controller\Mobile;

use App\Tests\Controller\Admin\AdminWebTestCase;
use Doctrine\DBAL\Connection;
use PHPUnit\Framework\Attributes\DataProvider;

final class MobileSessionApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    private function call(string $method, string $path, ?array $body = null, string $token = '', array $server = []): array
    {
        $this->client->request($method, $path, server: [...$server, 'CONTENT_TYPE' => 'application/json', 'HTTP_AUTHORIZATION' => 'Bearer '.$token], content: null === $body ? null : json_encode((object) $body, JSON_THROW_ON_ERROR));

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

    #[DataProvider('bearerSeparators')]
    public function testSessionAndSharedApiRespectBearerHeaderWhitespace(string $separator): void
    {
        $token = $this->login()['token'];
        $server = ['HTTP_AUTHORIZATION' => 'Bearer'.$separator.$token];
        $this->client->request('GET', '/mobile/v1/session', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame('admin', json_decode($this->client->getResponse()->getContent(), true)['username']);
        $this->client->request('GET', '/api/v1/activities', server: $server);
        self::assertResponseIsSuccessful();
        self::assertTrue($this->client->getResponse()->headers->hasCacheControlDirective('no-store'));
        $this->client->request('DELETE', '/mobile/v1/session', server: $server);
        self::assertResponseStatusCodeSame(204);
        $this->client->request('GET', '/mobile/v1/session', server: $server);
        self::assertResponseStatusCodeSame(401);
    }

    /** @return iterable<string, array{string}> */
    public static function bearerSeparators(): iterable
    {
        yield 'one space' => [' '];
        yield 'two spaces' => ['  '];
        yield 'tab' => ["\t"];
        yield 'mixed whitespace' => [" \t "];
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

    public function testPersistentLoginAndSessionUpgradeUseStrictExplicitBooleanContract(): void
    {
        $session = $this->call('POST', '/mobile/v1/sessions', ['username' => self::ADMIN_USERNAME, 'password' => self::ADMIN_PASSWORD, 'deviceName' => 'Persistent phone', 'persistent' => true]);
        self::assertResponseStatusCodeSame(201);
        self::assertTrue($session['persistent']);
        self::assertNull($session['expiresAt']);
        self::assertContains('persistent-mobile-sessions', $session['capabilities']);
        $metadata = $this->call('GET', '/mobile/v1/session', token: $session['token']);
        self::assertResponseStatusCodeSame(200);
        self::assertTrue($metadata['persistent']);
        self::assertNull($metadata['expiresAt']);
        $legacy = $this->login();
        self::assertFalse($legacy['persistent']);
        self::assertNotNull($legacy['expiresAt']);
        $before = self::getContainer()->get(Connection::class)->fetchAllAssociative("SELECT * FROM TrainingRecord WHERE kind != 'mobile-sessions' ORDER BY kind,id");
        foreach ([1, 2] as $attempt) {
            $promoted = $this->call('PATCH', '/mobile/v1/session', ['persistent' => true], $legacy['token']);
            self::assertResponseStatusCodeSame(200);
            self::assertTrue($promoted['persistent']);
            self::assertNull($promoted['expiresAt']);
            self::assertArrayNotHasKey('token', $promoted);
            self::assertTrue($this->client->getResponse()->headers->hasCacheControlDirective('no-store'));
        }
        self::assertSame($before, self::getContainer()->get(Connection::class)->fetchAllAssociative("SELECT * FROM TrainingRecord WHERE kind != 'mobile-sessions' ORDER BY kind,id"));
        $this->call('DELETE', '/mobile/v1/session', token: $legacy['token']);
        self::assertResponseStatusCodeSame(204);
        $this->call('PATCH', '/mobile/v1/session', ['persistent' => true], $legacy['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('GET', '/mobile/v1/session', token: $session['token']);
        self::assertResponseStatusCodeSame(200);
    }

    #[DataProvider('invalidPersistentLoginValues')]
    public function testLoginRejectsNonBooleanPersistentFlag(mixed $persistent): void
    {
        $this->call('POST', '/mobile/v1/sessions', ['username' => self::ADMIN_USERNAME, 'password' => self::ADMIN_PASSWORD, 'deviceName' => 'Phone', 'persistent' => $persistent]);
        self::assertResponseStatusCodeSame(422);
    }

    public static function invalidPersistentLoginValues(): iterable
    {
        foreach ([null, 'true', 'false', 1, 0, [], ['yes' => true]] as $index => $value) {
            yield (string) $index => [$value];
        }
    }

    #[DataProvider('invalidPromotionBodies')]
    public function testUpgradeRejectsMissingFalseOrNonBooleanFlag(array $body): void
    {
        $session = $this->login();
        $this->call('PATCH', '/mobile/v1/session', $body, $session['token']);
        self::assertResponseStatusCodeSame(422);
        $metadata = $this->call('GET', '/mobile/v1/session', token: $session['token']);
        self::assertFalse($metadata['persistent']);
        self::assertSame($session['expiresAt'], $metadata['expiresAt']);
    }

    public static function invalidPromotionBodies(): iterable
    {
        yield 'missing' => [[]];
        yield 'false' => [['persistent' => false]];
        yield 'unexpected field' => [['persistent' => true, 'expiresAt' => null]];
        foreach ([null, 'true', 1, []] as $index => $value) {
            yield 'non-bool '.$index => [['persistent' => $value]];
        }
    }

    public function testExpiredLegacySessionCannotUpgradeAndPersistentTokenKeepsExistingScopes(): void
    {
        $legacy = $this->login();
        $connection = self::getContainer()->get(Connection::class);
        $id = hash('sha256', $legacy['token']);
        $payload = json_decode($connection->fetchOne("SELECT payload FROM TrainingRecord WHERE kind='mobile-sessions' AND id=?", [$id]), true);
        unset($payload['persistent']);
        $payload['expiresAt'] = '2000-01-01T00:00:00+00:00';
        $connection->update('TrainingRecord', ['payload' => json_encode($payload)], ['kind' => 'mobile-sessions', 'id' => $id]);
        $this->call('PATCH', '/mobile/v1/session', ['persistent' => true], $legacy['token']);
        self::assertResponseStatusCodeSame(401);
        $session = $this->call('POST', '/mobile/v1/sessions', ['username' => self::ADMIN_USERNAME, 'password' => self::ADMIN_PASSWORD, 'deviceName' => 'Persistent phone', 'persistent' => true]);
        self::assertResponseStatusCodeSame(201);
        $this->call('POST', '/mcp', [], $session['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('GET', '/api/v1/status', token: $session['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('PATCH', '/mobile/v1/session', ['persistent' => true]);
        self::assertResponseStatusCodeSame(401);
    }

    public function testPersistentSessionOutlivesLegacyExpiryAndRemainsRevocableYearsLater(): void
    {
        $clock = new \App\Tests\Domain\Training\Notification\ReminderTestClock('2026-10-27T00:00:00Z');
        self::getContainer()->set(\App\Infrastructure\Time\Clock\Clock::class, $clock);
        $legacy = $this->call('POST', '/mobile/v1/sessions', ['username' => self::ADMIN_USERNAME, 'password' => self::ADMIN_PASSWORD, 'deviceName' => 'Finite phone', 'persistent' => false]);
        self::assertResponseStatusCodeSame(201);
        self::assertFalse($legacy['persistent']);
        $connection = self::getContainer()->get(Connection::class);
        $id = hash('sha256', $legacy['token']);
        $payload = json_decode($connection->fetchOne("SELECT payload FROM TrainingRecord WHERE kind='mobile-sessions' AND id=?", [$id]), true);
        unset($payload['persistent']);
        $connection->update('TrainingRecord', ['payload' => json_encode($payload)], ['kind' => 'mobile-sessions', 'id' => $id]);
        $session = $this->call('POST', '/mobile/v1/sessions', ['username' => self::ADMIN_USERNAME, 'password' => self::ADMIN_PASSWORD, 'deviceName' => 'Persistent phone', 'persistent' => true]);
        self::assertResponseStatusCodeSame(201);
        $clock->now = '2026-11-04T00:00:00Z';
        $this->call('GET', '/mobile/v1/session', token: $legacy['token']);
        self::assertResponseStatusCodeSame(401);
        $this->call('PATCH', '/mobile/v1/session', ['persistent' => true], $legacy['token']);
        self::assertResponseStatusCodeSame(401);
        foreach (['2026-11-04T00:00:00Z', '2046-10-27T00:00:00Z'] as $now) {
            $clock->now = $now;
            $metadata = $this->call('GET', '/mobile/v1/session', token: $session['token']);
            self::assertResponseStatusCodeSame(200);
            self::assertTrue($metadata['persistent']);
            self::assertNull($metadata['expiresAt']);
        }
        $this->call('DELETE', '/mobile/v1/session', token: $session['token']);
        self::assertResponseStatusCodeSame(204);
        $this->call('GET', '/mobile/v1/session', token: $session['token']);
        self::assertResponseStatusCodeSame(401);
    }
}
