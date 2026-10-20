<?php

require_once dirname(__DIR__, 2).'/vendor/autoload.php';
$input = new Symfony\Component\Console\Input\ArgvInput();
if ($input->hasParameterOption(['--env', '-e'])) {
    $_SERVER['APP_ENV'] = $_ENV['APP_ENV'] = $input->getParameterOption(['--env', '-e']);
}
if ($input->hasParameterOption('--no-debug')) {
    $_SERVER['APP_DEBUG'] = $_ENV['APP_DEBUG'] = '0';
}
require __DIR__.'/bootstrap.php';

$kernel = new App\Kernel($_SERVER['APP_ENV'], (bool) $_SERVER['APP_DEBUG']);
$application = new Symfony\Bundle\FrameworkBundle\Console\Application($kernel);
App\Infrastructure\Console\ConsoleApplication::setApplication($application);
exit($application->run($input));
