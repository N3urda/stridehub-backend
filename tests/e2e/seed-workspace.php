<?php

declare(strict_types=1);

// This bootstrap drops only tests/dreeveworkspace-browser.db, never the user's database.
if ('test' !== getenv('APP_ENV') || 'workspace-browser' !== getenv('TEST_TOKEN')) {
    throw new RuntimeException('Only the dedicated workspace-browser test database is allowed.');
}
require dirname(__DIR__).'/bootstrap.php';
$kernel = new App\Kernel('test', true);
$kernel->boot();
$container = $kernel->getContainer()->get('test.service_container');
$settings = $container->get(App\Domain\Settings\DbalSettingsRepository::class);
$settings->saveGroup(App\Domain\Settings\SettingsGroup::GENERAL, ['firstName' => 'DEMO', 'lastName' => 'Workspace', 'birthday' => '1990-01-01', 'weightHistory' => []]);
$settings->saveGroup(App\Domain\Settings\SettingsGroup::APPEARANCE, ['locale' => 'en_US', 'unitSystem' => 'metric']);
$kernel->shutdown();
echo "Isolated workspace browser database ready.\n";
