<?php

// Only for the PHP development server. Production continues to use FrankenPHP/Caddy.
declare(strict_types=1);

$public = dirname(__DIR__, 2).'/public';
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$file = realpath($public.urldecode(is_string($path) ? $path : '/'));
if (false !== $file && str_starts_with($file, $public.'/') && is_file($file)) {
    return false;
}

require $public.'/index.php';
