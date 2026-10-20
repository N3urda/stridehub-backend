<?php

// Read-only end-to-end check using the official MCP client, never prints health data or tokens.
require __DIR__.'/bootstrap.php';

use Mcp\Client;
use Mcp\Client\Transport\HttpTransport;
use Mcp\Client\Transport\StdioTransport;
use Mcp\Schema\Enum\ProtocolVersion;

$root = dirname(__DIR__, 2);
$state = json_decode(file_get_contents($root.'/var/runtime/mcp/server.json'), true, 512, JSON_THROW_ON_ERROR);
foreach (['stdio', 'http'] as $transportName) {
    foreach (['2025-11-25', '2026-07-28'] as $revision) {
        $client = Client::builder()->setClientInfo('stridehub-smoke', '1.0')->setProtocolVersion(ProtocolVersion::from($revision))->setRequestTimeout(15)->setMaxRetries(0)->build();
        $transport = 'stdio' === $transportName
            ? new StdioTransport($root.'/bin/stridehub-mcp', ['stdio'], cwd: $root)
            : new HttpTransport('http://127.0.0.1:'.$state['port'].'/mcp', ['Authorization' => 'Bearer '.$_SERVER['STRIDEHUB_MCP_TOKEN']], httpClient: new GuzzleHttp\Client(['timeout' => 15, 'allow_redirects' => false]));
        try {
            $client->connect($transport);
            $tools = $client->listTools();
            if (9 !== count($tools->tools)) {
                throw new RuntimeException('Expected nine task-oriented tools.');
            }
            $result = $client->callTool('get_overview');
            $payload = $result->structuredContent;
            if (!$payload['ok']) {
                throw new RuntimeException('Overview failed: '.$payload['error']['code']);
            }
            $resources = $client->listResources();
            $client->readResource('stridehub://guide');
            $client->getPrompt('review-and-plan');
            printf("%s %s: discovery(9), overview, resource, prompt OK; measurement present=%s\n", $transportName, $revision, null !== $payload['data']['body']['weight'] ? 'yes' : 'no');
        } finally {
            $client->disconnect();
        }
    }
}
