<?php

declare(strict_types=1);

namespace App\Infrastructure\Mcp;

use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpKernel\Event\ExceptionEvent;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;
use Symfony\Component\HttpKernel\KernelEvents;
use Symfony\Component\Security\Core\Exception\AccessDeniedException;

/** Keep transport failures machine-readable and private, including before tool dispatch. */
#[AsEventListener(event: KernelEvents::EXCEPTION, priority: -16)]
final readonly class McpExceptionListener
{
    public function __invoke(ExceptionEvent $event): void
    {
        if ('/mcp' !== $event->getRequest()->getPathInfo()) {
            return;
        }
        $error = $event->getThrowable();
        $status = $error instanceof HttpExceptionInterface ? $error->getStatusCode() : ($error instanceof AccessDeniedException ? 403 : 500);
        $event->allowCustomResponseCode();
        $event->setResponse(new JsonResponse(['error' => ['code' => $status >= 500 ? 'internal_error' : 'request_rejected',
            'message' => 'MCP request could not be processed.']], $status));
    }
}
