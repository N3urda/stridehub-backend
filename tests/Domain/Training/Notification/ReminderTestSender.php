<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training\Notification;

use App\Domain\Integration\Notification\Shoutrrr\Shoutrrr;
use App\Domain\Integration\Notification\Shoutrrr\ShoutrrrUrl;

final class ReminderTestSender implements Shoutrrr
{
    public array $calls = [];
    public array $failures = [];
    public ?\Closure $duringSend = null;

    public function send(ShoutrrrUrl $shoutrrrUrl, string $message, string $title): void
    {
        $url = (string) $shoutrrrUrl;
        $this->calls[] = $url;
        if (null !== $this->duringSend) {
            $callback = $this->duringSend;
            $this->duringSend = null;
            $callback();
        }
        if (($this->failures[$url] ?? 0) > 0) {
            --$this->failures[$url];
            throw new \RuntimeException('Transport failed for '.$url);
        }
    }
}
