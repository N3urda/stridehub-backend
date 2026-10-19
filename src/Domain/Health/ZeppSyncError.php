<?php

declare(strict_types=1);

namespace App\Domain\Health;

final class ZeppSyncError extends \RuntimeException
{
    public function __construct(public readonly string $state, string $message, public readonly string $nextAction)
    {
        parent::__construct($message);
    }
}
