<?php

declare(strict_types=1);

namespace App\Controller\Mobile;

use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Symfony\Component\HttpKernel\KernelEvents;
use Symfony\Component\Security\Http\AccessToken\HeaderAccessTokenExtractor;

#[AsEventListener(event: KernelEvents::RESPONSE, priority: -1100)]
final readonly class MobilePrivacyResponseListener
{
    public function __invoke(ResponseEvent $event): void
    {
        $request = $event->getRequest();
        if (preg_match('#^/mobile/v1(?:/|$)#', $request->getPathInfo()) || str_starts_with(new HeaderAccessTokenExtractor()->extractAccessToken($request) ?? '', 'shmobile_')) {
            $event->getResponse()->headers->set('Cache-Control', 'no-store, private');
        }
    }
}
