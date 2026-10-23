<?php

declare(strict_types=1);

namespace App\Tests\Infrastructure\Http\Session;

use App\Infrastructure\Http\Session\RequestAwareNativeSessionStorageFactory;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\Attributes\PreserveGlobalState;
use PHPUnit\Framework\Attributes\RunTestsInSeparateProcesses;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Filesystem\Filesystem;
use Symfony\Component\HttpFoundation\Cookie;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpFoundation\Session\Session;
use Symfony\Component\HttpFoundation\Session\Storage\NativeSessionStorageFactory;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Symfony\Component\HttpKernel\EventListener\SessionListener;
use Symfony\Component\HttpKernel\HttpKernelInterface;

// Each case is isolated from PHPUnit's output and PHP's global session state.
// Requests within a case share one process, one factory and one session listener.
#[RunTestsInSeparateProcesses]
#[PreserveGlobalState(false)]
final class RequestAwareNativeSessionStorageFactoryTest extends TestCase
{
    private string $sessionDirectory;

    #[\Override]
    protected function setUp(): void
    {
        parent::setUp();
        $this->sessionDirectory = sys_get_temp_dir().'/stridehub-session-test-'.bin2hex(random_bytes(8));
        new Filesystem()->mkdir($this->sessionDirectory);
        ini_set('session.save_path', $this->sessionDirectory);
        ini_set('session.cookie_secure', '0');
    }

    #[\Override]
    protected function tearDown(): void
    {
        if (PHP_SESSION_ACTIVE === session_status()) {
            session_abort();
        }
        new Filesystem()->remove($this->sessionDirectory);
        parent::tearDown();
    }

    /** @param list<string> $schemes */
    #[DataProvider('requestSchemes')]
    public function testResponseCookiesFollowEveryRequestInTheSameWorker(array $schemes): void
    {
        $options = ['cookie_secure' => 'auto', 'cookie_httponly' => true, 'cookie_samesite' => 'lax', 'cookie_path' => '/'];
        $factory = new RequestAwareNativeSessionStorageFactory(new NativeSessionStorageFactory($options, secure: true), $options);
        $listener = new SessionListener(sessionOptions: $options);

        foreach ($schemes as $scheme) {
            $request = Request::create($scheme.'://stridehub.test/admin/insights');
            $cookie = $this->responseCookie($factory, $listener, $request);

            self::assertSame('https' === $scheme, $cookie->isSecure(), $scheme.' response after previous requests');
            self::assertTrue($cookie->isHttpOnly());
            self::assertSame('lax', $cookie->getSameSite());
            self::assertSame('/', $cookie->getPath());
            $listener->reset();
        }
    }

    /** @return iterable<string, array{list<string>}> */
    public static function requestSchemes(): iterable
    {
        yield 'HTTPS then LAN HTTP' => [['https', 'http', 'https']];
        yield 'LAN HTTP then HTTPS' => [['http', 'https', 'http']];
    }

    public function testTrustedProxyHttpsDoesNotMakeTheFollowingLanOrSpoofedRequestSecure(): void
    {
        Request::setTrustedProxies(['100.93.49.29'], Request::HEADER_X_FORWARDED_FOR | Request::HEADER_X_FORWARDED_PROTO | Request::HEADER_X_FORWARDED_PORT);
        $options = ['cookie_secure' => 'auto'];
        $factory = new RequestAwareNativeSessionStorageFactory(new NativeSessionStorageFactory($options, secure: true), $options);
        $listener = new SessionListener(sessionOptions: $options);

        foreach ([['100.93.49.29', true], ['192.168.31.80', false]] as [$remoteAddress, $secure]) {
            $request = Request::create('http://stridehub.test/admin/insights', server: [
                'REMOTE_ADDR' => $remoteAddress,
                'HTTP_X_FORWARDED_PROTO' => 'https',
                'HTTP_X_FORWARDED_PORT' => '9464',
            ]);
            self::assertSame($secure, $this->responseCookie($factory, $listener, $request)->isSecure());
            $listener->reset();
        }
    }

    #[DataProvider('explicitCookieSecurity')]
    public function testExplicitCookieSecurityIsPreserved(bool $secure): void
    {
        $options = ['cookie_secure' => $secure];
        $factory = new RequestAwareNativeSessionStorageFactory(new NativeSessionStorageFactory($options), $options);
        $listener = new SessionListener(sessionOptions: $options);

        foreach (['https', 'http'] as $scheme) {
            self::assertSame($secure, $this->responseCookie($factory, $listener, Request::create($scheme.'://stridehub.test/admin/insights'))->isSecure());
            $listener->reset();
        }
    }

    /** @return iterable<string, array{bool}> */
    public static function explicitCookieSecurity(): iterable
    {
        yield 'always secure' => [true];
        yield 'explicitly disabled' => [false];
    }

    public function testNoRequestDoesNotInheritASecureFlagFromAnEarlierRequest(): void
    {
        $options = ['cookie_secure' => 'auto'];
        $factory = new RequestAwareNativeSessionStorageFactory(new NativeSessionStorageFactory($options, secure: true), $options);
        $factory->createStorage(Request::create('https://stridehub.test/'));
        self::assertTrue(session_get_cookie_params()['secure']);

        $factory->createStorage(null);

        self::assertFalse(session_get_cookie_params()['secure']);
    }

    private function responseCookie(RequestAwareNativeSessionStorageFactory $factory, SessionListener $listener, Request $request): Cookie
    {
        $session = new Session($factory->createStorage($request));
        $request->setSession($session);
        $session->set('DEMO-request', $request->getUri());
        $response = new Response();
        $listener->onKernelResponse(new ResponseEvent($this->createStub(HttpKernelInterface::class), $request, HttpKernelInterface::MAIN_REQUEST, $response));
        $cookies = $response->headers->getCookies();
        self::assertCount(1, $cookies);

        return $cookies[0];
    }
}
