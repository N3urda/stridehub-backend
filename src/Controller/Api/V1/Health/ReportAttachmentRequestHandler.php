<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\ReportAttachmentStore;
use App\Domain\Training\TrainingError;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use Symfony\Component\HttpFoundation\BinaryFileResponse;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpFoundation\ResponseHeaderBag;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Security\Csrf\CsrfToken;
use Symfony\Component\Security\Csrf\CsrfTokenManagerInterface;

#[AsController]
final readonly class ReportAttachmentRequestHandler
{
    public function __construct(private ReportAttachmentStore $attachments, private CsrfTokenManagerInterface $csrf)
    {
    }

    #[Route('/api/v1/health/reports/{id}/attachment/{action}', name: 'api_v1_health_report_attachment', requirements: ['id' => '[a-zA-Z0-9_-]+', 'action' => 'file'], defaults: ['action' => null], priority: 40)]
    #[Route('/admin/health/api/reports/{id}/attachment/{action}', name: 'admin_health_report_attachment', requirements: ['id' => '[a-zA-Z0-9_-]+', 'action' => 'file'], defaults: ['action' => null], priority: 40)]
    public function handle(Request $request, string $id, ?string $action): Response
    {
        try {
            if (!in_array($request->getMethod(), null === $action ? ['GET', 'PUT'] : ['GET', 'HEAD'], true)) {
                throw new TrainingError('此接口不支持该方法。', 405);
            }
            if ($request->isMethod('PUT')) {
                if (str_starts_with($request->getPathInfo(), '/admin/') && !$this->csrf->isTokenValid(new CsrfToken('health', $request->headers->get('X-CSRF-Token')))) {
                    throw new TrainingError('页面校验已过期，请刷新后重试。', 403);
                }
                if ('application/pdf' !== strtolower(trim(explode(';', $request->headers->get('Content-Type', ''))[0]))) {
                    throw new TrainingError('请使用 application/pdf 上传原件。', 415);
                }
                if ((int) $request->headers->get('Content-Length', '0') > ReportAttachmentStore::MAX_BYTES) {
                    throw new TrainingError('PDF 不能超过 20 MiB。', 413);
                }

                return new JsonResponse($this->attachments->save($id, $request->getContent()));
            }
            if (null === $action) {
                return new JsonResponse($this->attachments->metadata($id));
            }
            $path = $this->attachments->path($id);
            if (!is_file($path)) {
                throw new TrainingError('尚未保存这份报告的原件。', 404);
            }
            $response = new BinaryFileResponse($path, public: false);
            $response->setContentDisposition($request->query->getBoolean('download') ? ResponseHeaderBag::DISPOSITION_ATTACHMENT : ResponseHeaderBag::DISPOSITION_INLINE, $id.'.pdf');
            $response->headers->set('Content-Type', 'application/pdf');
            $response->headers->set('X-Content-Type-Options', 'nosniff');
            $response->headers->set('Content-Security-Policy', "sandbox; default-src 'none'; frame-ancestors 'self'");
            $response->headers->set('Cache-Control', 'no-store, private');

            return $response;
        } catch (TrainingError $error) {
            return new ApiErrorResponse($error->status, match ($error->status) {
                404 => 'not_found', 409 => 'conflict', 403 => 'forbidden', 405 => 'method_not_allowed', 413 => 'payload_too_large', 415 => 'unsupported_media_type', default => 'validation_error',
            }, $error->getMessage(), 405 === $error->status ? ['Allow' => null === $action ? 'GET, PUT' : 'GET, HEAD'] : []);
        }
    }
}
