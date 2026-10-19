<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training\Notification;

use App\Infrastructure\Time\Clock\Clock;
use App\Infrastructure\ValueObject\Time\SerializableDateTime;

final class ReminderTestClock implements Clock
{
    public function __construct(public string $now)
    {
    }

    public function getCurrentDateTimeImmutable(): SerializableDateTime
    {
        return SerializableDateTime::fromString($this->now);
    }
}
