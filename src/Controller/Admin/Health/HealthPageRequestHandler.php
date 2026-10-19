<?php

declare(strict_types=1);

namespace App\Controller\Admin\Health;

use App\Infrastructure\Http\HtmlResponse;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Twig\Environment;

#[AsController]
final readonly class HealthPageRequestHandler
{
    public function __construct(private Environment $twig)
    {
    }

    #[Route('/admin/health', name: 'admin_health', methods: ['GET'], priority: 30)]
    public function handle(): HtmlResponse
    {
        $response = new HtmlResponse($this->twig->render('html/admin/page/health.html.twig'));
        $response->headers->set('Cache-Control', 'no-store, private');

        return $response;
    }
}
