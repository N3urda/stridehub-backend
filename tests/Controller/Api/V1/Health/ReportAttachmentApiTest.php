<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Api\Token;
use App\Domain\Health\ReportAttachmentStore;
use App\Tests\Controller\Admin\AdminWebTestCase;
use Symfony\Component\HttpFoundation\BinaryFileResponse;

final class ReportAttachmentApiTest extends AdminWebTestCase
{
    private string $reportId;

    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    protected function tearDown(): void
    {
        if (isset($this->reportId)) {
            $path = dirname(__DIR__, 5).'/storage/files/health-reports/'.$this->reportId.'.pdf';
            if (is_file($path)) {
                unlink($path);
            }
        }
        parent::tearDown();
    }

    public function testOriginalIsPrivateReadableAndImmutableWithIdempotentRetry(): void
    {
        $server = $this->prepareReport();
        $path = '/api/v1/health/reports/'.$this->reportId.'/attachment';
        $this->client->request('GET', $path, server: $server);
        self::assertSame(['available' => false], $this->data());
        $pdf = self::pdf();
        $this->client->request('PUT', $path, server: [...$server, 'CONTENT_TYPE' => 'application/pdf'], content: $pdf);
        self::assertResponseIsSuccessful();
        self::assertSame(hash('sha256', $pdf), $this->data()['sha256']);
        self::assertSame(strlen($pdf), $this->data()['sizeBytes']);
        $this->client->request('PUT', $path, server: [...$server, 'CONTENT_TYPE' => 'application/pdf'], content: $pdf);
        self::assertResponseIsSuccessful();
        $this->client->request('PUT', $path, server: [...$server, 'CONTENT_TYPE' => 'application/pdf'], content: self::pdf('changed'));
        self::assertResponseStatusCodeSame(409);
        $this->client->request('GET', $path.'/file', server: $server);
        self::assertResponseIsSuccessful();
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        self::assertResponseHeaderSame('Content-Type', 'application/pdf');
        $response = $this->client->getResponse();
        self::assertInstanceOf(BinaryFileResponse::class, $response);
        self::assertSame($pdf, file_get_contents($response->getFile()->getPathname()));
        self::assertStringStartsWith('inline;', $response->headers->get('Content-Disposition'));
        $this->client->request('GET', $path.'/file?download=1', server: $server);
        self::assertStringStartsWith('attachment;', $this->client->getResponse()->headers->get('Content-Disposition'));
        foreach ([$path, $path.'/file'] as $url) {
            $this->client->request('GET', $url);
            self::assertResponseStatusCodeSame(401);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
    }

    public function testInvalidMissingAndOversizedUploadsNeverCreateFiles(): void
    {
        $server = $this->prepareReport();
        $path = '/api/v1/health/reports/'.$this->reportId.'/attachment';
        foreach ([['text/html', self::pdf(), 415], ['application/pdf', '<html>not a PDF</html>', 422], ['application/pdf', '%PDF-1.4 truncated', 422], ['application/pdf', str_repeat('x', ReportAttachmentStore::MAX_BYTES + 1), 413]] as [$type, $bytes, $expected]) {
            $this->client->request('PUT', $path, server: [...$server, 'CONTENT_TYPE' => $type], content: $bytes);
            self::assertResponseStatusCodeSame($expected);
        }
        $this->client->request('GET', $path, server: $server);
        self::assertSame(['available' => false], $this->data());
        $this->client->request('GET', $path.'/file', server: $server);
        self::assertResponseStatusCodeSame(404);
        $this->client->request('PUT', '/api/v1/health/reports/no-such-report/attachment', server: [...$server, 'CONTENT_TYPE' => 'application/pdf'], content: self::pdf());
        self::assertResponseStatusCodeSame(404);
        $this->client->request('DELETE', $path, server: $server);
        self::assertResponseStatusCodeSame(405);
        self::assertResponseHeaderSame('Allow', 'GET, PUT');
    }

    public function testBrowserUploadRequiresSessionAndCsrf(): void
    {
        $this->prepareReport();
        $path = '/admin/health/api/reports/'.$this->reportId.'/attachment';
        $this->client->request('GET', $path);
        self::assertResponseRedirects('/admin/login');
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        $this->client->loginUser($this->adminUser());
        $this->client->request('PUT', $path, server: ['CONTENT_TYPE' => 'application/pdf'], content: self::pdf());
        self::assertResponseStatusCodeSame(403);
        $this->client->request('GET', $path);
        self::assertResponseIsSuccessful();
        self::assertSame(['available' => false], $this->data());
        $page = $this->client->request('GET', '/admin/health/reports');
        self::assertResponseIsSuccessful();
        $csrf = $page->filter('#reports-app')->attr('data-csrf');
        $this->client->request('PUT', $path, server: ['CONTENT_TYPE' => 'application/pdf', 'HTTP_X_CSRF_TOKEN' => $csrf], content: self::pdf());
        self::assertResponseIsSuccessful();
        self::assertSame(hash('sha256', self::pdf()), $this->data()['sha256']);
        $this->client->request('GET', $path.'/file');
        self::assertResponseIsSuccessful();
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
    }

    /** @return array<string, string> */
    private function prepareReport(): array
    {
        $this->reportId = 'attachment-test-'.bin2hex(random_bytes(8));
        $token = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $token;
        $server = ['HTTP_AUTHORIZATION' => 'Bearer '.$token];
        $this->client->request('PUT', '/api/v1/health/context', server: [...$server, 'CONTENT_TYPE' => 'application/json'], content: json_encode(['version' => 0, 'reports' => [['id' => $this->reportId, 'title' => 'DEMO exam', 'reportDate' => '2026-01-01', 'sourceLabel' => 'Synthetic test fixture']]], JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();

        return $server;
    }

    private static function pdf(string $marker = 'original'): string
    {
        return "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n% ".$marker."\n%%EOF\n";
    }

    /** @return array<string, mixed> */
    private function data(): array
    {
        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }
}
