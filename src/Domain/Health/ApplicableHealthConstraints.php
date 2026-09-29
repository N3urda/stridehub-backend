<?php

declare(strict_types=1);

namespace App\Domain\Health;

/** Presents declared constraints without interpreting their medical meaning. */
final readonly class ApplicableHealthConstraints
{
    public function __construct(private HealthContextService $context)
    {
    }

    /** @return array{version: int, constraints: list<array<string, mixed>>} */
    public function forDate(string $localDate): array
    {
        $context = $this->context->get();
        $constraints = array_values(array_filter($context['constraints'], static fn (array $constraint): bool => 'active' === $constraint['status'] && (null === $constraint['validFrom'] || $constraint['validFrom'] <= $localDate)));

        // reviewOn requests reassessment; it is never an automatic expiration.
        return ['version' => $context['version'], 'constraints' => $constraints];
    }
}
