<?php

declare(strict_types=1);

namespace App\Domain\Training;

use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity]
#[ORM\Index(name: 'TrainingActivityLink_sessionId', columns: ['sessionId'])]
final readonly class TrainingActivityLink
{
    private function __construct(
        #[ORM\Id, ORM\Column(type: 'string', length: 255)] public string $activityId,
        #[ORM\Column(type: 'string', length: 80)] public string $sessionId,
    ) {
    }
}
