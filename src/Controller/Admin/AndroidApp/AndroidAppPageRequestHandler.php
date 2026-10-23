<?php

declare(strict_types=1);

namespace App\Controller\Admin\AndroidApp;

use App\Application\AndroidAppRelease;
use App\Application\AppUrl;
use App\Infrastructure\Http\HtmlResponse;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Twig\Environment;

#[AsController]
final readonly class AndroidAppPageRequestHandler
{
    public function __construct(
        private Environment $twig,
        private AndroidAppRelease $release,
        private AppUrl $appUrl,
    ) {
    }

    #[Route('/admin/app', name: 'admin_android_app', methods: ['GET'], priority: 40)]
    public function __invoke(): HtmlResponse
    {
        $response = new HtmlResponse($this->twig->render('html/stridehub/android-app.html.twig', [
            'androidRelease' => $this->release->current(),
            'serverAddress' => rtrim((string) $this->appUrl, '/'),
        ]));
        $response->headers->set('Cache-Control', 'no-store, private');

        return $response;
    }
}
