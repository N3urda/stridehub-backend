<?php

// This listener only serves MCP. The web application can run in its own process.
if ('/mcp' !== parse_url($_SERVER['REQUEST_URI'] ?? '', PHP_URL_PATH)) {
    http_response_code(404);
    header('Cache-Control: no-store, private');
    exit;
}
require __DIR__.'/bootstrap.php';
$kernel = new App\Kernel($_SERVER['APP_ENV'], (bool) $_SERVER['APP_DEBUG']);
$request = Symfony\Component\HttpFoundation\Request::createFromGlobals();
$response = $kernel->handle($request);
$response->send();
$kernel->terminate($request, $response);
