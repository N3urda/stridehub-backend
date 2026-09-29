<?php

declare(strict_types=1);

namespace App\Controller\Admin\Training;

use App\Infrastructure\Http\HtmlResponse;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Twig\Environment;

#[AsController]
final readonly class TrainingTodayPageRequestHandler
{
    public function __construct(private Environment $twig)
    {
    }

    #[Route(path: '/admin/training/today', name: 'admin_training_today', methods: ['GET'], priority: 30)]
    public function handle(): HtmlResponse
    {
        return new HtmlResponse($this->twig->render('html/admin/page/training-today.html.twig'));
    }
}
