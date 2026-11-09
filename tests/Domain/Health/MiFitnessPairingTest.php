<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\MiFitnessPairing;
use App\Domain\Training\TrainingError;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use GuzzleHttp\Client;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use PHPUnit\Framework\TestCase;

final class MiFitnessPairingTest extends TestCase
{
    private string $directory;

    protected function setUp(): void
    {
        $this->directory = sys_get_temp_dir().'/stridehub-pair-test-'.bin2hex(random_bytes(6));
        mkdir($this->directory, 0700);
        $_ENV['MI_FITNESS_PAIRING_FILE'] = $this->directory.'/pairing-control.json';
    }

    protected function tearDown(): void
    {
        @unlink($this->directory.'/pairing-control.json');
        rmdir($this->directory);
        unset($_ENV['MI_FITNESS_PAIRING_FILE']);
    }

    private function config(string $baseUrl = 'http://127.0.0.1:8321'): void
    {
        $file = $this->directory.'/pairing-control.json';
        file_put_contents($file, json_encode(['baseUrl' => $baseUrl, 'apiKey' => 'private-control-secret']));
        chmod($file, 0600);
        clearstatcache();
    }

    private function service(array $responses, ?array &$history = null): MiFitnessPairing
    {
        $stack = HandlerStack::create(new MockHandler($responses));
        $history = [];
        $stack->push(Middleware::history($history));

        return new MiFitnessPairing(new Client(['handler' => $stack]), KernelProjectDir::fromString($this->directory));
    }

    public function testUnavailableDoesNotCallCloud(): void
    {
        $service = $this->service([]);
        self::assertSame('unavailable', $service->status()['state']);
        $this->expectException(TrainingError::class);
        $service->start();
    }

    public function testProxyProtectsSecretsAndAttemptIdentity(): void
    {
        $this->config();
        $id = str_repeat('a', 32);
        $pending = ['state' => 'pending', 'attemptId' => $id, 'expiresAt' => '2026-11-09T12:05:00Z', 'apiKey' => 'must-not-leak', 'message' => 'must-not-leak', 'userId' => 'private-user'];
        $png = "\x89PNG\r\n\x1a\nfixture";
        $service = $this->service([
            new Response(200, [], json_encode($pending)),
            new Response(200, ['Content-Type' => 'image/png'], $png),
            new Response(200, [], json_encode(['state' => 'cancelled', 'attemptId' => $id])),
            new Response(200, [], json_encode(['state' => 'success'])),
        ], $history);
        $state = $service->start();
        self::assertSame('pending', $state['state']);
        self::assertSame($id, $state['attemptId']);
        self::assertStringNotContainsString('must-not-leak', json_encode($state));
        self::assertArrayNotHasKey('apiKey', $state);
        self::assertSame($png, $service->qr($id));
        self::assertSame('cancelled', $service->cancel($id)['state']);
        self::assertSame('success', $service->status()['state']);
        self::assertSame('POST', $history[0]['request']->getMethod());
        self::assertSame('{}', (string) $history[0]['request']->getBody());
        self::assertSame('private-control-secret', $history[0]['request']->getHeaderLine('X-StrideHub-Control-Key'));
        self::assertSame('attemptId='.$id, $history[1]['request']->getUri()->getQuery());
        self::assertSame(['attemptId' => $id], json_decode((string) $history[2]['request']->getBody(), true));
        self::assertFalse($history[0]['options']['allow_redirects']);
    }

    public function testDifferentAccountGetsActionableMessageWithoutIdentity(): void
    {
        $this->config();
        $service = $this->service([new Response(200, [], '{"state":"error","code":"account_mismatch","userId":"private"}')]);
        $state = $service->status();
        self::assertStringContainsString('原绑定账号', $state['message']);
        self::assertArrayNotHasKey('userId', $state);
    }

    public function testUnsafeControlAddressNeverReceivesKey(): void
    {
        $this->config('http://public.example');
        $service = $this->service([]);
        $this->expectException(TrainingError::class);
        $service->start();
    }

    public function testReadableByOthersConfigurationIsRejected(): void
    {
        $this->config();
        chmod($this->directory.'/pairing-control.json', 0644);
        $service = $this->service([]);
        $this->expectException(TrainingError::class);
        $service->start();
    }

    public function testInvalidAttemptIsRejectedBeforeHttp(): void
    {
        $this->config();
        $service = $this->service([]);
        $this->expectException(TrainingError::class);
        $service->qr('another/session');
    }

    public function testMalformedUnboundedAndFailedResponsesAreRedacted(): void
    {
        $this->config();
        foreach ([
            new Response(200, [], '{"state":"pending"}'),
            new Response(200, [], '{"state":"pending","attemptId":"token-secret","expiresAt":"tomorrow"}'),
            new Response(200, [], str_repeat('sensitive', 4000)),
            new Response(200, [], '{"state":"unknown"}'),
            new Response(302, ['Location' => 'https://untrusted.example/'], 'secret'),
            new Response(403, [], 'secret'),
        ] as $response) {
            try {
                $this->service([$response])->status();
                self::fail('Invalid bridge response accepted');
            } catch (TrainingError $error) {
                self::assertSame(503, $error->status);
                self::assertStringNotContainsString('secret', $error->getMessage());
            }
        }
    }

    public function testStaleQrCannotBecomeSuccessfulImage(): void
    {
        $this->config();
        foreach ([new Response(410), new Response(409), new Response(200, ['Content-Type' => 'image/svg+xml'], '<svg/>'), new Response(200, ['Content-Type' => 'image/png'], '<script>')] as $response) {
            try {
                $this->service([$response])->qr(str_repeat('b', 32));
                self::fail('Invalid image response accepted');
            } catch (TrainingError $error) {
                self::assertContains($error->status, [410, 409, 503]);
            }
        }
    }
}
