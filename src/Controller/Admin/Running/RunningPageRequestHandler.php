<?php

declare(strict_types=1);

namespace App\Controller\Admin\Running;

use App\Infrastructure\Http\HtmlResponse;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Twig\Environment;

#[AsController]
final readonly class RunningPageRequestHandler
{
    public function __construct(private Environment $twig)
    {
    }

    #[Route('/admin/running', name: 'admin_running', methods: ['GET'], priority: 20)]
    public function __invoke(): HtmlResponse
    {
        $response = new HtmlResponse($this->twig->render('html/admin/page/running.html.twig'));
        $response->headers->set('Cache-Control', 'no-store');

        return $response;
    }
}
