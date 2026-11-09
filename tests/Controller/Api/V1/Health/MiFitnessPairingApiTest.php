<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Domain\Health\MiFitnessPairing;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use App\Tests\Controller\Admin\AdminWebTestCase;
use GuzzleHttp\Client;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Psr7\Response;

final class MiFitnessPairingApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testAdminOnlyCsrfAndBodyValidation(): void
    {
        $_ENV['MI_FITNESS_PAIRING_FILE'] = '/not-configured-mi-pair-fixture.json';
        try {
            $token = (string) Token::generate();
            $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $token;
            foreach (['status', 'qr', 'start', 'cancel'] as $action) {
                $this->client->request(in_array($action, ['start', 'cancel'], true) ? 'POST' : 'GET', '/admin/health/api/mi-fitness/pair/'.$action, server: ['HTTP_AUTHORIZATION' => 'Bearer '.$token]);
                self::assertResponseRedirects('/admin/login');
                self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
            }
            $this->client->loginUser($this->adminUser(), 'main');
            $this->client->request('GET', '/admin/health/api/mi-fitness/pair/status');
            self::assertResponseIsSuccessful();
            self::assertSame('unavailable', json_decode($this->client->getResponse()->getContent(), true)['state']);
            $this->client->request('GET', '/admin/health/api/mi-fitness/pair/start');
            self::assertResponseStatusCodeSame(405);
            foreach (['start', 'cancel'] as $action) {
                $this->client->request('POST', '/admin/health/api/mi-fitness/pair/'.$action, server: ['CONTENT_TYPE' => 'application/json'], content: '{}');
                self::assertResponseStatusCodeSame(403);
            }
            $crawler = $this->client->request('GET', '/admin/health');
            self::assertSame(1, $crawler->filter('#mi-fitness-connect')->count());
            $headers = ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $crawler->filter('#health-app')->attr('data-csrf')];
            foreach (['[]', '{"baseUrl":"https://untrusted.example"}', '{"apiKey":"secret"}', '{'] as $body) {
                $this->client->request('POST', '/admin/health/api/mi-fitness/pair/start', server: $headers, content: $body);
                self::assertResponseStatusCodeSame(422);
            }
            $this->client->request('POST', '/admin/health/api/mi-fitness/pair/cancel', server: $headers, content: '{"attemptId":"bad"}');
            self::assertResponseStatusCodeSame(422);
            $this->client->request('POST', '/admin/health/api/mi-fitness/pair/start', server: $headers, content: str_repeat('x', 4097));
            self::assertResponseStatusCodeSame(413);
            $this->client->request('POST', '/admin/health/api/mi-fitness/pair/start', server: $headers, content: '{}');
            self::assertResponseStatusCodeSame(503);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        } finally {
            unset($_ENV['MI_FITNESS_PAIRING_FILE']);
        }
    }

    public function testQrImageAndBindingStateStayPrivate(): void
    {
        $directory = sys_get_temp_dir().'/mi-pair-api-test-'.bin2hex(random_bytes(6));
        mkdir($directory, 0700);
        $_ENV['MI_FITNESS_PAIRING_FILE'] = $directory.'/control.json';
        file_put_contents($directory.'/control.json', '{"baseUrl":"http://127.0.0.1:8321","apiKey":"control-secret"}');
        chmod($directory.'/control.json', 0600);
        try {
            $id = str_repeat('a', 32);
            $png = "\x89PNG\r\n\x1a\nfixture";
            $pairing = new MiFitnessPairing(new Client(['handler' => HandlerStack::create(new MockHandler([
                new Response(200, [], json_encode(['state' => 'pending', 'attemptId' => $id, 'expiresAt' => '2026-11-09T12:05:00Z', 'apiKey' => 'secret'])),
                new Response(200, ['Content-Type' => 'image/png'], $png),
                new Response(200, [], '{"state":"success","apiKey":"secret"}'),
            ]))]), KernelProjectDir::fromString($directory));
            $this->getContainer()->set(MiFitnessPairing::class, $pairing);
            $this->client->loginUser($this->adminUser(), 'main');
            $crawler = $this->client->request('GET', '/admin/health');
            $this->client->request('POST', '/admin/health/api/mi-fitness/pair/start', server: ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $crawler->filter('#health-app')->attr('data-csrf')], content: '{}');
            self::assertResponseIsSuccessful();
            self::assertStringNotContainsString('secret', $this->client->getResponse()->getContent());
            $this->client->request('GET', '/admin/health/api/mi-fitness/pair/qr?attemptId='.$id);
            self::assertResponseIsSuccessful();
            self::assertSame($png, $this->client->getResponse()->getContent());
            self::assertResponseHeaderSame('Content-Type', 'image/png');
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
            self::assertResponseHeaderSame('Cross-Origin-Resource-Policy', 'same-origin');
            $this->client->request('GET', '/admin/health/api/mi-fitness/pair/status');
            self::assertSame('success', json_decode($this->client->getResponse()->getContent(), true)['state']);
        } finally {
            unset($_ENV['MI_FITNESS_PAIRING_FILE']);
            unlink($directory.'/control.json');
            rmdir($directory);
        }
    }
}
