<?php

// Shared environment for the dedicated local process and the stdio launcher.
require_once dirname(__DIR__, 2).'/vendor/autoload.php';

new Symfony\Component\Dotenv\Dotenv()->bootEnv(dirname(__DIR__, 2).'/.env');
$runtime = dirname(__DIR__, 2).'/var/runtime/mcp/credentials.json';
$credentials = is_file($runtime) ? json_decode(file_get_contents($runtime), true, 512, JSON_THROW_ON_ERROR) : [];
foreach (['APP_SECRET' => $credentials['appSecret'] ?? '', 'STRIDEHUB_MCP_TOKEN' => $credentials['token'] ?? '', 'APP_URL' => 'http://127.0.0.1:8081', 'TZ' => 'Asia/Shanghai'] as $name => $fallback) {
    if ('' === ($_SERVER[$name] ?? $_ENV[$name] ?? '')) {
        $_SERVER[$name] = $_ENV[$name] = $fallback;
    }
}
