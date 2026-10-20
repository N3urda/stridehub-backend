<?php

declare(strict_types=1);

if ('test' !== getenv('APP_ENV') || 'workspace-browser' !== getenv('TEST_TOKEN')) {
    throw new RuntimeException('Only the isolated workspace browser server is allowed.');
}
// Some local PHP builds omit process environment from HTTP $_SERVER.
foreach (['APP_ENV', 'APP_DEBUG', 'TEST_TOKEN', 'TZ', 'APP_URL', 'APP_SECRET', 'ADMIN_USERNAME', 'ADMIN_PASSWORD_HASH', 'DREEVE_API_KEY', 'IMPORT_MODE', 'TRUSTED_PROXIES', 'DEV_MODE_IS_ENABLED'] as $key) {
    $_SERVER[$key] = $_ENV[$key] = getenv($key);
}

return require dirname(__DIR__, 2).'/docker/stridehub-dev/router.php';
