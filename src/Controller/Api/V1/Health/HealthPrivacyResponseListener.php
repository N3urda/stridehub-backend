<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Symfony\Component\HttpKernel\KernelEvents;

// Apply after Symfony's session listener (-1000), including authentication redirects.
#[AsEventListener(event: KernelEvents::RESPONSE, priority: -1100)]
final readonly class HealthPrivacyResponseListener
{
    public function __invoke(ResponseEvent $event): void
    {
        if (preg_match('#^/(?:mcp|api/v1/health|admin/health)(?:/|$)#', $event->getRequest()->getPathInfo())) {
            $event->getResponse()->headers->set('Cache-Control', 'no-store, private');
        }
    }
}
