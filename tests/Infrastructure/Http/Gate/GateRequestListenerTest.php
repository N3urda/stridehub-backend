<?php

namespace App\Tests\Infrastructure\Http\Gate;

use App\Infrastructure\Config\AdminAllowedIpAddresses;
use App\Infrastructure\Http\ClientIpResolver;
use App\Infrastructure\Http\Gate\AdminAllowedIpGate;
use App\Infrastructure\Http\Gate\ConditionalRedirectGate;
use App\Infrastructure\Http\Gate\Gate;
use App\Infrastructure\Http\Gate\GateDecision;
use App\Infrastructure\Http\Gate\GateRequestListener;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\HttpFoundation\RedirectResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\HttpKernel\HttpKernelInterface;
use Symfony\Component\Routing\Generator\UrlGeneratorInterface;

class GateRequestListenerTest extends TestCase
{
    public function testItSetsTheResponseOfTheFirstInterceptingGate(): void
    {
        $redirect = new RedirectResponse('/gated');
        $listener = new GateRequestListener([
            $this->gate(GateDecision::defer()),
            $this->gate(GateDecision::respond($redirect)),
            $this->gate(GateDecision::respond(new RedirectResponse('/never-reached'))),
        ]);

        $event = new RequestEvent(
            kernel: $this->createStub(HttpKernelInterface::class),
            request: Request::create('/dashboard'),
            requestType: HttpKernelInterface::MAIN_REQUEST,
        );
        $listener->onKernelRequest($event);

        $this->assertSame($redirect, $event->getResponse());
    }

    public function testItInvokesGatesInOrderAndStopsAtTheFirstThatIntercepts(): void
    {
        /** @var \ArrayObject<int, string> $calls */
        $calls = new \ArrayObject();
        $listener = new GateRequestListener([
            $this->recordingGate($calls, 'first', GateDecision::defer()),
            $this->recordingGate($calls, 'second', GateDecision::respond(new RedirectResponse('/gated'))),
            $this->recordingGate($calls, 'third', GateDecision::respond(new RedirectResponse('/never-reached'))),
        ]);

        $event = new RequestEvent(
            kernel: $this->createStub(HttpKernelInterface::class),
            request: Request::create('/dashboard'),
            requestType: HttpKernelInterface::MAIN_REQUEST,
        );
        $listener->onKernelRequest($event);

        // The third gate is never reached once the second one intercepts.
        $this->assertSame(['first', 'second'], $calls->getArrayCopy());
        $this->assertSame('/gated', $event->getResponse()?->getTargetUrl());
    }

    public function testItStopsAtAGateThatAllowsTheRequest(): void
    {
        /** @var \ArrayObject<int, string> $calls */
        $calls = new \ArrayObject();
        $listener = new GateRequestListener([
            $this->recordingGate($calls, 'first', GateDecision::allow()),
            $this->recordingGate($calls, 'second', GateDecision::respond(new RedirectResponse('/never-reached'))),
        ]);

        $event = new RequestEvent(
            kernel: $this->createStub(HttpKernelInterface::class),
            request: Request::create('/admin/settings/athlete'),
            requestType: HttpKernelInterface::MAIN_REQUEST,
        );
        $listener->onKernelRequest($event);

        // The first gate is guarding and keeps this path open. The gates behind it must not
        // redirect the user away from it.
        $this->assertSame(['first'], $calls->getArrayCopy());
        $this->assertNull($event->getResponse());
    }

    public function testItLetsTheRequestThroughWhenNoGateIntercepts(): void
    {
        $listener = new GateRequestListener([$this->gate(GateDecision::defer()), $this->gate(GateDecision::defer())]);

        $event = new RequestEvent(
            kernel: $this->createStub(HttpKernelInterface::class),
            request: Request::create('/dashboard'),
            requestType: HttpKernelInterface::MAIN_REQUEST,
        );
        $listener->onKernelRequest($event);

        $this->assertNull($event->getResponse());
    }

    #[DataProvider('provideAlwaysOpenPaths')]
    public function testItNeverGatesAlwaysOpenPaths(string $path): void
    {
        $listener = new GateRequestListener([$this->gate(GateDecision::respond(new RedirectResponse('/gated')))]);

        $event = new RequestEvent(
            kernel: $this->createStub(HttpKernelInterface::class),
            request: Request::create($path),
            requestType: HttpKernelInterface::MAIN_REQUEST,
        );
        $listener->onKernelRequest($event);

        $this->assertNull($event->getResponse());
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function provideAlwaysOpenPaths(): iterable
    {
        yield 'strava webhook' => ['/strava/webhook'];
        yield 'profiler' => ['/_profiler/abc123'];
        yield 'web debug toolbar' => ['/_wdt/abc123'];
        yield 'css asset' => ['/css/app.css'];
        yield 'stored image' => ['/files/gear/bike.png'];
    }

    public function testItDoesNothingForSubRequests(): void
    {
        $listener = new GateRequestListener([$this->gate(GateDecision::respond(new RedirectResponse('/gated')))]);

        $event = new RequestEvent(
            kernel: $this->createStub(HttpKernelInterface::class),
            request: Request::create('/dashboard'),
            requestType: HttpKernelInterface::SUB_REQUEST,
        );
        $listener->onKernelRequest($event);

        $this->assertNull($event->getResponse());
    }

    #[DataProvider('provideDataWorkspacePaths')]
    public function testDataWorkspaceSkipsOnlySetupRedirects(string $path): void
    {
        $setupGate = new class($this->createStub(UrlGeneratorInterface::class)) extends ConditionalRedirectGate {
            protected function shouldGuard(): bool
            {
                throw new \LogicException('A workspace request must not evaluate legacy setup.');
            }

            protected function allowedPaths(): array
            {
                return [];
            }

            protected function redirectToRouteName(): string
            {
                return 'admin_settings_athlete';
            }
        };
        $redirect = new RedirectResponse('/policy-denied');
        $listener = new GateRequestListener([$setupGate, $this->gate(GateDecision::respond($redirect))]);
        $event = new RequestEvent($this->createStub(HttpKernelInterface::class), Request::create($path), HttpKernelInterface::MAIN_REQUEST);
        $listener->onKernelRequest($event);
        self::assertSame($redirect, $event->getResponse(), 'Non-setup policies must still run.');
    }

    #[DataProvider('provideProtectedAdminWorkspacePaths')]
    public function testNewAndExistingWorkspaceRoutesStillEnforceAllowedIps(string $path): void
    {
        $listener = new GateRequestListener([new AdminAllowedIpGate(AdminAllowedIpAddresses::fromString('192.168.1.1'), new ClientIpResolver())]);
        $event = new RequestEvent($this->createStub(HttpKernelInterface::class), Request::create($path, server: ['REMOTE_ADDR' => '10.0.0.1']), HttpKernelInterface::MAIN_REQUEST);
        self::expectException(NotFoundHttpException::class);
        $listener->onKernelRequest($event);
    }

    /** @return iterable<string, array{string}> */
    public static function provideDataWorkspacePaths(): iterable
    {
        foreach (['/', '/admin', '/admin/login', '/admin/logout', '/admin/upload', '/admin/importStatus', '/admin/insights', '/admin/plans', '/admin/health', '/admin/nutrition', '/admin/weight', '/admin/health/api/weight', '/admin/running', '/admin/running/api/overview', '/admin/training', '/admin/training/api/today', '/mobile/v1/sessions', '/mobile/v1/session', '/mobile/v1/collection/devices', '/api/v1/health/weight', '/api/v1/training/profile', '/api/v1/running/overview'] as $path) {
            yield $path => [$path];
        }
    }

    /** @return iterable<string, array{string}> */
    public static function provideProtectedAdminWorkspacePaths(): iterable
    {
        foreach (self::provideDataWorkspacePaths() as $name => [$path]) {
            if (str_starts_with($path, '/admin')) {
                yield $name => [$path];
            }
        }
    }

    private function gate(GateDecision $decision): Gate
    {
        return new readonly class($decision) implements Gate {
            public function __construct(private GateDecision $decision)
            {
            }

            public function handle(Request $request): GateDecision
            {
                return $this->decision;
            }
        };
    }

    /**
     * @param \ArrayObject<int, string> $calls
     */
    private function recordingGate(\ArrayObject $calls, string $name, GateDecision $decision): Gate
    {
        return new readonly class($calls, $name, $decision) implements Gate {
            /**
             * @param \ArrayObject<int, string> $calls
             */
            public function __construct(
                private \ArrayObject $calls,
                private string $name,
                private GateDecision $decision,
            ) {
            }

            public function handle(Request $request): GateDecision
            {
                $this->calls[] = $this->name;

                return $this->decision;
            }
        };
    }
}
