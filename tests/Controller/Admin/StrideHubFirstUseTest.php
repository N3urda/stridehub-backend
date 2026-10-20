<?php

declare(strict_types=1);

namespace App\Tests\Controller\Admin;

use App\Domain\Api\Token;
use App\Domain\Import\ImportMode;
use Doctrine\DBAL\Connection;
use League\Flysystem\FilesystemOperator;
use PHPUnit\Framework\Attributes\DataProvider;

final class StrideHubFirstUseTest extends AdminWebTestCase
{
    private string $apiToken;

    protected function prepareEnvironment(): void
    {
        parent::prepareEnvironment();
        $this->apiToken = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $this->apiToken;
    }

    protected function provideSettings(): void
    {
        // A first visit has no athlete name, birthday, or legacy dashboard settings.
    }

    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    #[DataProvider('modes')]
    public function testAllWorkspacePagesOpenWithoutLegacySetup(ImportMode $mode): void
    {
        $this->withImportMode($mode);
        $this->client->loginUser($this->adminUser());
        foreach (['/admin/insights', '/admin/health', '/admin/plans', '/admin/running', '/admin/training', '/admin/training/today', '/admin/upload'] as $path) {
            $crawler = $this->client->request('GET', $path);
            self::assertResponseIsSuccessful($path);
            self::assertCount(4, $crawler->filter('.hub-primary-nav a'), $path);
        }
        $this->assertNoInventedProfile();
    }

    #[DataProvider('modes')]
    public function testWorkspacePagesAndBrowserApisStillRequireLogin(ImportMode $mode): void
    {
        $this->withImportMode($mode);
        foreach (['/admin/upload', '/admin/importStatus', '/admin', '/admin/insights', '/admin/health', '/admin/plans', '/admin/running', '/admin/training/today', '/admin/health/api/weight', '/admin/health/api/plans', '/admin/running/api/overview', '/admin/training/api/profile'] as $path) {
            $this->client->request('GET', $path);
            self::assertResponseRedirects('/admin/login', message: $path);
        }
        $this->client->request('GET', '/');
        self::assertResponseRedirects('/admin/insights');
    }

    #[DataProvider('modes')]
    public function testEmptyBrowserAndExternalApisWorkWithoutProfile(ImportMode $mode): void
    {
        $this->withImportMode($mode);
        $this->client->loginUser($this->adminUser());
        foreach ($this->apiPaths() as [$browser, $external]) {
            $this->client->request('GET', $browser);
            self::assertResponseIsSuccessful($browser);
            self::assertStringStartsWith('application/json', (string) $this->client->getResponse()->headers->get('Content-Type'));
            $this->client->request('GET', $external, server: ['HTTP_AUTHORIZATION' => 'Bearer '.$this->apiToken]);
            self::assertResponseIsSuccessful($external);
        }
        $this->assertNoInventedProfile();
    }

    public function testExternalApisStillRequireBearerAuthentication(): void
    {
        foreach ($this->apiPaths() as [, $external]) {
            $this->client->request('GET', $external);
            self::assertResponseStatusCodeSame(401, $external);
        }
    }

    #[DataProvider('modes')]
    public function testImportStatusCanClearWithoutCreatingAnAthleteProfile(ImportMode $mode): void
    {
        $this->withImportMode($mode);
        $this->client->loginUser($this->adminUser());
        /** @var FilesystemOperator $watchStorage */
        $watchStorage = $this->getContainer()->get('default.storage');
        $watchStorage->write('watch/first-use.fit', 'raw-fit-bytes');
        try {
            $crawler = $this->client->request('GET', '/admin/upload');
            self::assertResponseIsSuccessful();
            self::assertSame('Import pending', $crawler->filter('#import-pending-badge')->text());
            $this->client->request('GET', '/admin/importStatus');
            self::assertResponseIsSuccessful();
            self::assertSame(['pending' => true], json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR));
        } finally {
            $watchStorage->delete('watch/first-use.fit');
        }
        $this->client->request('GET', '/admin/importStatus');
        self::assertResponseIsSuccessful();
        self::assertSame(['pending' => false], json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR));
        $this->assertNoInventedProfile();
    }

    public function testFirstWeightCanBeSavedWithoutCreatingAnAthleteProfile(): void
    {
        $this->client->request('GET', '/api/v1/health/weight', server: ['HTTP_AUTHORIZATION' => 'Bearer '.$this->apiToken]);
        self::assertResponseIsSuccessful();
        $empty = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame([], $empty['records']);
        $this->client->request('PUT', '/api/v1/health/weight/2026-10-19', server: ['HTTP_AUTHORIZATION' => 'Bearer '.$this->apiToken, 'CONTENT_TYPE' => 'application/json'], content: json_encode(['version' => $empty['version'], 'weightKg' => 72.4], JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();
        $saved = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(72.4, $saved['records'][0]['weightKg']);
        $this->assertNoInventedProfile();
    }

    /** @return iterable<string, array{ImportMode}> */
    public static function modes(): iterable
    {
        yield 'files' => [ImportMode::FILES];
        yield 'strava' => [ImportMode::STRAVA_API];
    }

    /** @return list<array{string, string}> */
    private function apiPaths(): array
    {
        $paths = [];
        foreach (['weight', 'plans', 'sleep', 'sleep/status', 'blood-pressure', 'zepp/status', 'zepp/days'] as $path) {
            $paths[] = ['/admin/health/api/'.$path, '/api/v1/health/'.$path];
        }
        foreach (['profile', 'sessions', 'today', 'activities'] as $path) {
            $paths[] = ['/admin/training/api/'.$path, '/api/v1/training/'.$path];
        }
        $paths[] = ['/admin/running/api/overview', '/api/v1/running/overview'];

        return $paths;
    }

    private function assertNoInventedProfile(): void
    {
        $db = self::getContainer()->get(Connection::class);
        self::assertSame(0, (int) $db->fetchOne("SELECT COUNT(*) FROM Setting WHERE settingsGroup = 'general' AND name IN ('birthday', 'firstName', 'lastName')"));
        self::assertSame(0, (int) $db->fetchOne('SELECT COUNT(*) FROM Activity'));
    }
}
