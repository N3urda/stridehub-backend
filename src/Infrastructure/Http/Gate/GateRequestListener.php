<?php

declare(strict_types=1);

namespace App\Infrastructure\Http\Gate;

use Symfony\Component\DependencyInjection\Attribute\AutowireIterator;
use Symfony\Component\EventDispatcher\EventSubscriberInterface;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\KernelEvents;

final readonly class GateRequestListener implements EventSubscriberInterface
{
    /**
     * @param iterable<Gate> $gates
     */
    public function __construct(
        #[AutowireIterator('app.http.gate')]
        private iterable $gates,
    ) {
    }

    public function onKernelRequest(RequestEvent $event): void
    {
        if (!$event->isMainRequest()) {
            return;
        }

        $request = $event->getRequest();
        if ($this->isAlwaysOpen($request->getPathInfo())) {
            return;
        }

        foreach ($this->gates as $gate) {
            // The data workspace is usable before an athlete profile, Strava connection,
            // or first imported activity exists. Only setup redirects are skipped:
            // IP restrictions and the firewall still protect these requests.
            if ($gate instanceof ConditionalRedirectGate && $this->isSetupIndependent($request->getPathInfo())) {
                continue;
            }

            $decision = $gate->handle($request);
            if (!$decision->hasBeenApplied()) {
                continue;
            }

            if ($response = $decision->getResponse()) {
                $event->setResponse($response);
            }

            return;
        }
    }

    private function isAlwaysOpen(string $path): bool
    {
        return 1 === preg_match('#^/(_(profiler|wdt)|css|images|js|files)/#', $path)
            || '/strava/webhook' === $path;
    }

    private function isSetupIndependent(string $path): bool
    {
        if (in_array($path, ['/', '/admin', '/admin/login', '/admin/logout', '/admin/upload', '/admin/importStatus'], true)) {
            return true;
        }

        return array_any(['/api/v1', '/admin/insights', '/admin/plans', '/admin/training', '/admin/running', '/admin/health', '/admin/nutrition', '/admin/weight'], fn (string $prefix): bool => $path === $prefix || str_starts_with($path, $prefix.'/'));
    }

    /**
     * @codeCoverageIgnore
     */
    public static function getSubscribedEvents(): array
    {
        // Priority 12: after the RouterListener, before the firewall (priority 8)
        // so a gate can preempt the login redirect.
        return [KernelEvents::REQUEST => [['onKernelRequest', 12]]];
    }
}
