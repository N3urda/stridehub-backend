<?php

declare(strict_types=1);

if ('1' !== getenv('STRIDEHUB_NATIVE_E2E') || '/var/www/var/native-e2e/db' !== getenv('DATABASE_DIRECTORY')) {
    throw new RuntimeException('Only the isolated native Android fixture database is allowed.');
}
require dirname(__DIR__, 2).'/vendor/autoload.php';
new Symfony\Component\Dotenv\Dotenv()->bootEnv(dirname(__DIR__, 2).'/.env');
$kernel = new App\Kernel('dev', true);
$kernel->boot();
$connection = $kernel->getContainer()->get('doctrine')->getConnection();
$settings = new App\Domain\Settings\DbalSettingsRepository($connection, new App\Tests\Infrastructure\Eventing\SpyEventBus());
$settings->saveGroup(App\Domain\Settings\SettingsGroup::GENERAL, ['firstName' => 'DEMO', 'lastName' => 'Native', 'birthday' => '1990-01-01', 'weightHistory' => []]);
$settings->saveGroup(App\Domain\Settings\SettingsGroup::APPEARANCE, ['locale' => 'en_US', 'unitSystem' => 'metric']);
$kernel->shutdown();
echo "Initialized isolated native fixture profile.\n";
