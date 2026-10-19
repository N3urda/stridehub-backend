<?php

declare(strict_types=1);

namespace App\Controller\Admin\Training;

use App\Infrastructure\Http\HtmlResponse;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Twig\Environment;

#[AsController]
final readonly class TrainingPageRequestHandler
{
    public function __construct(private Environment $twig)
    {
    }

    #[Route(path: '/admin/training', name: 'admin_training', methods: ['GET'], priority: 20)]
    public function handle(): HtmlResponse
    {
        return new HtmlResponse($this->twig->render('html/admin/page/training.html.twig'));
    }
}
