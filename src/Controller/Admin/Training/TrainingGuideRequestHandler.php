<?php

declare(strict_types=1);

namespace App\Controller\Admin\Training;

use App\Infrastructure\ValueObject\String\KernelProjectDir;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;

#[AsController]
final readonly class TrainingGuideRequestHandler
{
    public function __construct(private KernelProjectDir $projectDir)
    {
    }

    #[Route('/admin/training/guide', name: 'admin_training_guide', methods: ['GET'], priority: 20)]
    public function __invoke(): Response
    {
        $text = htmlspecialchars((string) file_get_contents($this->projectDir.'/docs/stridehub/README.md'), ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');

        return new Response('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>StrideHub 使用与 API 文档</title><body style="max-width:960px;margin:32px auto;padding:20px;font:16px/1.7 system-ui"><a href="/admin/training">返回训练助手</a><pre style="white-space:pre-wrap;overflow-wrap:anywhere">'.$text.'</pre></body></html>', headers: ['Cache-Control' => 'no-store']);
    }
}
