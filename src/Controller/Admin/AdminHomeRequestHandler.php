<?php

declare(strict_types=1);

namespace App\Controller\Admin;

use Symfony\Component\HttpFoundation\RedirectResponse;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Routing\Generator\UrlGeneratorInterface;

#[AsController]
final readonly class AdminHomeRequestHandler
{
    public function __construct(private UrlGeneratorInterface $urlGenerator)
    {
    }

    #[Route(path: '/', name: 'stridehub_home', methods: ['GET'], priority: 30)]
    #[Route(path: '/admin', name: 'admin', methods: ['GET'], priority: 30)]
    public function index(): RedirectResponse
    {
        return new RedirectResponse($this->urlGenerator->generate('admin_insights'));
    }
}
