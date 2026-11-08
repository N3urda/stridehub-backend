<?php

declare(strict_types=1);

namespace App\Domain\Health;

final class MiFitnessSyncError extends \RuntimeException
{
    public function __construct(public readonly string $state, string $message)
    {
        parent::__construct($message);
    }
}
