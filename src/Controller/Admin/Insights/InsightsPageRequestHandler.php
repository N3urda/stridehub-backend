<?php

declare(strict_types=1);

namespace App\Controller\Admin\Insights;

use App\Infrastructure\Http\HtmlResponse;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Twig\Environment;

#[AsController]
final readonly class InsightsPageRequestHandler
{
    public function __construct(private Environment $twig)
    {
    }

    #[Route('/admin/insights', name: 'admin_insights', methods: ['GET'], priority: 40)]
    public function overview(): HtmlResponse
    {
        return $this->page('insights');
    }

    #[Route('/admin/plans', name: 'admin_plans', methods: ['GET'], priority: 40)]
    public function plans(): HtmlResponse
    {
        return $this->page('plans');
    }

    private function page(string $name): HtmlResponse
    {
        $response = new HtmlResponse($this->twig->render('html/stridehub/'.$name.'.html.twig'));
        $response->headers->set('Cache-Control', 'no-store, private');

        return $response;
    }
}
