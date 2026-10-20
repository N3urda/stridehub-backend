<?php

declare(strict_types=1);

namespace App\Tests\Infrastructure\Mcp;

use App\Tests\Controller\Admin\AdminWebTestCase;

final class McpProtocolTest extends AdminWebTestCase
{
    private const string TOKEN = 'test-only-mcp-token-with-at-least-32-chars';
    private ?string $oldToken;
    private ?string $session = null;

    protected function prepareEnvironment(): void
    {
        parent::prepareEnvironment();
        $this->oldToken = $_ENV['STRIDEHUB_MCP_TOKEN'] ?? null;
        $_SERVER['STRIDEHUB_MCP_TOKEN'] = $_ENV['STRIDEHUB_MCP_TOKEN'] = self::TOKEN;
    }

    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    protected function tearDown(): void
    {
        if (null === $this->oldToken) {
            unset($_SERVER['STRIDEHUB_MCP_TOKEN'], $_ENV['STRIDEHUB_MCP_TOKEN']);
        } else {
            $_SERVER['STRIDEHUB_MCP_TOKEN'] = $_ENV['STRIDEHUB_MCP_TOKEN'] = $this->oldToken;
        }
        parent::tearDown();
    }

    public function testAuthenticationIsRequiredWithoutSetupRedirect(): void
    {
        foreach ([null, 'wrong', ''] as $token) {
            $this->client->request('POST', '/mcp', server: ['CONTENT_TYPE' => 'application/json', 'HTTP_AUTHORIZATION' => null === $token ? '' : 'Bearer '.$token], content: '{}');
            self::assertResponseStatusCodeSame(401);
            self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
        }
    }

    public function testAdminCookieDoesNotAuthorizeMcp(): void
    {
        $this->client->enableReboot();
        $this->client->request('GET', '/admin/login');
        $this->client->loginUser($this->adminUser());
        $this->client->request('POST', '/mcp', server: ['CONTENT_TYPE' => 'application/json'], content: '{}');
        self::assertResponseStatusCodeSame(401);
    }

    public function testDiscoveryResourcePromptAndToolCallsUseStandardMcp(): void
    {
        $init = $this->initialize();
        self::assertSame('stridehub', $init['result']['serverInfo']['name']);
        self::assertStringContainsString('get_overview', $init['result']['instructions']);
        $tools = $this->rpc('tools/list')['result']['tools'];
        self::assertCount(9, $tools);
        self::assertSame(['get_overview', 'get_history', 'get_workout_details', 'save_plan', 'record_progress', 'update_health_profile', 'remove_record', 'resolve_workout_sources', 'get_operation'], array_column($tools, 'name'));
        self::assertTrue($tools[0]['annotations']['readOnlyHint']);
        self::assertFalse($tools[3]['annotations']['readOnlyHint']);
        $resources = $this->rpc('resources/list')['result']['resources'];
        self::assertSame('stridehub://guide', $resources[0]['uri']);
        self::assertStringContainsString('requestId', $this->rpc('resources/read', ['uri' => 'stridehub://guide'])['result']['contents'][0]['text']);
        self::assertCount(1, $this->rpc('prompts/list')['result']['prompts']);
        $prompt = $this->rpc('prompts/get', ['name' => 'review-and-plan']);
        self::assertNotEmpty($prompt['result']['messages'] ?? null, json_encode($prompt));
        $overview = $this->rpc('tools/call', ['name' => 'get_overview', 'arguments' => (object) []]);
        self::assertFalse($overview['result']['isError'] ?? false, json_encode($overview));
        self::assertTrue($overview['result']['structuredContent']['ok']);
        self::assertResponseHeaderSame('Cache-Control', 'no-store, private');
    }

    public function testSchemaRejectsUnsafeAndMalformedInputAndAcceptsTaskWrite(): void
    {
        $this->initialize();
        foreach ([['name' => 'get_overview', 'arguments' => ['days' => 999]],
            ['name' => 'save_plan', 'arguments' => ['requestId' => 'bad', 'reason' => 'test', 'sql' => 'DROP TABLE Activity']],
            ['name' => 'update_health_profile', 'arguments' => ['requestId' => 'bad', 'reason' => 'test', 'profile' => ['version' => 0, 'notificationsEnabled' => true]]],
        ] as $args) {
            $response = $this->rpc('tools/call', $args);
            self::assertTrue(isset($response['error']) || ($response['result']['isError'] ?? false), json_encode($response));
        }
        $args = ['name' => 'save_plan', 'arguments' => ['requestId' => 'protocol-plan', 'reason' => 'DEMO 用户要求',
            'nutrition' => [['date' => '2026-01-10', 'targets' => ['energyKcal' => 2000]]]]];
        $saved = $this->rpc('tools/call', $args);
        self::assertTrue($saved['result']['structuredContent']['ok'] ?? false, json_encode($saved));
        self::assertSame(1, $saved['result']['structuredContent']['data']['result']['nutrition'][0]['version']);
        self::assertTrue($this->rpc('tools/call', $args)['result']['structuredContent']['data']['replayed']);
    }

    public function testHostOriginAndBodyBoundsAreEnforced(): void
    {
        foreach ([['HTTP_ORIGIN' => 'https://evil.example'], ['HTTP_HOST' => 'evil.example']] as $extra) {
            $this->client->request('POST', '/mcp', server: [...$this->headers(), ...$extra], content: json_encode(['jsonrpc' => '2.0', 'id' => 1, 'method' => 'initialize', 'params' => ['protocolVersion' => '2025-11-25', 'capabilities' => (object) [], 'clientInfo' => ['name' => 'test', 'version' => '1']]]));
            self::assertResponseStatusCodeSame(403);
        }
        $this->client->request('POST', '/mcp', server: $this->headers(), content: str_repeat('x', 1024 * 1024 + 1));
        self::assertResponseStatusCodeSame(413);
    }

    /** @return array<string, mixed> */
    private function initialize(): array
    {
        $result = $this->rpc('initialize', ['protocolVersion' => '2025-11-25', 'capabilities' => (object) [], 'clientInfo' => ['name' => 'StrideHub test', 'version' => '1.0']]);
        self::assertResponseIsSuccessful();
        $this->session = $this->client->getResponse()->headers->get('Mcp-Session-Id');
        self::assertNotEmpty($this->session);
        $this->client->request('POST', '/mcp', server: $this->headers(), content: json_encode(['jsonrpc' => '2.0', 'method' => 'notifications/initialized']));
        self::assertResponseIsSuccessful();

        return $result;
    }

    /** @return array<string, string> */
    private function headers(): array
    {
        $headers = ['HTTP_HOST' => 'localhost', 'HTTP_AUTHORIZATION' => 'Bearer '.self::TOKEN,
            'HTTP_ACCEPT' => 'application/json, text/event-stream', 'CONTENT_TYPE' => 'application/json'];
        if (null !== $this->session) {
            $headers['HTTP_MCP_SESSION_ID'] = $this->session;
            $headers['HTTP_MCP_PROTOCOL_VERSION'] = '2025-11-25';
        }

        return $headers;
    }

    /** @param array<string, mixed> $params
     * @return array<string, mixed>
     */
    private function rpc(string $method, array $params = []): array
    {
        $this->client->request('POST', '/mcp', server: $this->headers(), content: json_encode(['jsonrpc' => '2.0', 'id' => 1, 'method' => $method, 'params' => (object) $params]));
        $raw = $this->client->getResponse()->getContent();
        self::assertJson($raw, substr($raw, 0, 500));

        return json_decode($raw, true, 512, JSON_THROW_ON_ERROR);
    }
}
