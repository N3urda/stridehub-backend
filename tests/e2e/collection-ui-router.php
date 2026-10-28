<?php

declare(strict_types=1);

if ('test' !== getenv('APP_ENV') || 'collection-ui-browser' !== getenv('TEST_TOKEN')) {
    throw new RuntimeException('Only the isolated collection UI browser database is allowed.');
}
foreach (['APP_ENV', 'APP_DEBUG', 'TEST_TOKEN', 'TZ', 'APP_URL', 'APP_SECRET', 'ADMIN_USERNAME', 'ADMIN_PASSWORD_HASH', 'DREEVE_API_KEY', 'IMPORT_MODE', 'TRUSTED_PROXIES', 'DEV_MODE_IS_ENABLED', 'ZEPP_CREDENTIALS_FILE'] as $key) {
    $_SERVER[$key] = $_ENV[$key] = getenv($key);
}

return require dirname(__DIR__, 2).'/docker/stridehub-dev/router.php';
