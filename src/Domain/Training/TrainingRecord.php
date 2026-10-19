<?php

declare(strict_types=1);

namespace App\Domain\Training;

use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity]
#[ORM\UniqueConstraint(name: 'TrainingRecord_naturalKey', columns: ['kind', 'naturalKey'])]
final readonly class TrainingRecord
{
    private function __construct(
        #[ORM\Id, ORM\Column(type: 'string', length: 32)] public string $kind,
        #[ORM\Id, ORM\Column(type: 'string', length: 80)] public string $id,
        #[ORM\Column(type: 'text')] public string $payload,
        #[ORM\Column(type: 'integer')] public int $version,
        #[ORM\Column(type: 'string', length: 40)] public string $updatedAt,
        #[ORM\Column(type: 'string', length: 255, nullable: true)] public ?string $naturalKey,
    ) {
    }
}
