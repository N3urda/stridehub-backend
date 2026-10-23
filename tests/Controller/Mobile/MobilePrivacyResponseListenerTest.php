<?php

declare(strict_types=1);

namespace App\Tests\Controller\Mobile;

use App\Controller\Mobile\MobilePrivacyResponseListener;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Symfony\Component\HttpKernel\HttpKernelInterface;

final class MobilePrivacyResponseListenerTest extends TestCase
{
    #[DataProvider('privateRequests')]
    public function testNativeCredentialsAlwaysDisableResponseStorage(string $path, ?string $header): void
    {
        $request = Request::create($path);
        if (null !== $header) {
            $request->headers->set('Authorization', $header);
        }
        $response = new Response();
        new MobilePrivacyResponseListener()(new ResponseEvent($this->createStub(HttpKernelInterface::class), $request, HttpKernelInterface::MAIN_REQUEST, $response));
        self::assertTrue($response->headers->hasCacheControlDirective('no-store'));
        self::assertTrue($response->headers->hasCacheControlDirective('private'));
    }

    /** @return iterable<string, array{string, ?string}> */
    public static function privateRequests(): iterable
    {
        yield 'native route without token' => ['/mobile/v1/sessions', null];
        foreach (['space' => ' ', 'two spaces' => '  ', 'tab' => "\t", 'mixed' => " \t "] as $label => $separator) {
            yield $label => ['/api/v1/activities', 'Bearer'.$separator.'shmobile_'.str_repeat('a', 64)];
        }
    }

    public function testUnrelatedPublicResponseIsNotMadePrivate(): void
    {
        $response = new Response(headers: ['Cache-Control' => 'public, max-age=60']);
        new MobilePrivacyResponseListener()(new ResponseEvent($this->createStub(HttpKernelInterface::class), Request::create('/manifest.json'), HttpKernelInterface::MAIN_REQUEST, $response));
        self::assertFalse($response->headers->hasCacheControlDirective('no-store'));
        self::assertTrue($response->headers->hasCacheControlDirective('public'));
    }
}
