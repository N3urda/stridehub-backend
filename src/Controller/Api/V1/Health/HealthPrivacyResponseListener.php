<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Symfony\Component\HttpKernel\KernelEvents;

#[AsEventListener(event: KernelEvents::RESPONSE, priority: -100)]
final readonly class HealthPrivacyResponseListener
{
    public function __invoke(ResponseEvent $event): void
    {
        if (preg_match('#^/api/v1/health(?:/|$)#', $event->getRequest()->getPathInfo())) {
            $event->getResponse()->headers->set('Cache-Control', 'no-store, private');
        }
    }
}
