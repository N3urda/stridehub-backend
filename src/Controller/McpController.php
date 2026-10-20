<?php

declare(strict_types=1);

namespace App\Controller;

use App\Infrastructure\Mcp\McpServerFactory;
use GuzzleHttp\Psr7\HttpFactory;
use GuzzleHttp\Psr7\ServerRequest;
use Mcp\Server\Transport\StreamableHttpTransport;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final readonly class McpController
{
    public function __construct(private McpServerFactory $factory)
    {
    }

    #[Route('/mcp', name: 'stridehub_mcp', methods: ['POST', 'GET', 'DELETE', 'OPTIONS'])]
    public function __invoke(Request $request): Response
    {
        $body = $request->getContent();
        if (strlen($body) > 1024 * 1024) {
            return new Response('', 413);
        }
        $psr = new ServerRequest($request->getMethod(), $request->getUri(), array_map(static fn (array $values): array => array_values(array_filter($values, static fn (?string $v): bool => null !== $v)), $request->headers->all()), $body);
        $httpFactory = new HttpFactory();
        // Keep SDK Origin and Host validation enabled (localhost/loopback only).
        $response = $this->factory->create(true)->run(new StreamableHttpTransport($psr, $httpFactory, $httpFactory, maxBodyBytes: 1024 * 1024));

        return new Response((string) $response->getBody(), $response->getStatusCode(), $response->getHeaders());
    }
}
