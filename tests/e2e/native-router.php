<?php

declare(strict_types=1);

if ('1' !== getenv('STRIDEHUB_NATIVE_E2E') || '/var/www/var/native-e2e/db' !== getenv('DATABASE_DIRECTORY')) {
    throw new RuntimeException('Only the isolated native Android test server is allowed.');
}
foreach (['APP_ENV', 'APP_DEBUG', 'APP_URL', 'APP_SECRET', 'ADMIN_USERNAME', 'ADMIN_PASSWORD_HASH', 'DATABASE_DIRECTORY', 'IMPORT_MODE'] as $key) {
    $_SERVER[$key] = $_ENV[$key] = getenv($key);
}
return require dirname(__DIR__, 2).'/docker/stridehub-dev/router.php';
