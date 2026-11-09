<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\MiFitnessPairing;
use App\Domain\Training\TrainingError;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Security\Csrf\CsrfToken;
use Symfony\Component\Security\Csrf\CsrfTokenManagerInterface;

#[AsController]
final readonly class MiFitnessPairingRequestHandler
{
    public function __construct(private MiFitnessPairing $pairing, private CsrfTokenManagerInterface $csrf)
    {
    }

    // Pairing grants access to a health account: only the administrator session may manage it.
    #[Route('/admin/health/api/mi-fitness/pair/{action}', name: 'admin_health_mi_fitness_pair', requirements: ['action' => 'status|start|cancel|qr'], priority: 50)]
    public function handle(Request $request, string $action): Response
    {
        try {
            $method = in_array($action, ['start', 'cancel'], true) ? 'POST' : 'GET';
            if (!$request->isMethod($method)) {
                return new ApiErrorResponse(405, 'method_not_allowed', '此接口不支持该方法。', ['Allow' => $method]);
            }
            if ('GET' === $method) {
                if ('status' === $action) {
                    return new JsonResponse($this->pairing->status());
                }

                return new Response($this->pairing->qr($request->query->getString('attemptId')), 200, [
                    'Content-Type' => 'image/png', 'X-Content-Type-Options' => 'nosniff', 'Cross-Origin-Resource-Policy' => 'same-origin',
                ]);
            }
            if (!$this->csrf->isTokenValid(new CsrfToken('health', $request->headers->get('X-CSRF-Token')))) {
                throw new TrainingError('页面校验已过期，请刷新后重试。', 403);
            }
            if ('application/json' !== strtolower(trim(explode(';', $request->headers->get('Content-Type') ?? '')[0]))) {
                throw new TrainingError('请求必须使用 application/json。', 415);
            }
            if (strlen($request->getContent()) > 4096) {
                throw new TrainingError('请求超过 4 KiB。', 413);
            }
            try {
                $body = json_decode($request->getContent(), false, 4, JSON_THROW_ON_ERROR);
                if (!$body instanceof \stdClass || array_diff(array_keys(get_object_vars($body)), 'cancel' === $action ? ['attemptId'] : [])) {
                    throw new \JsonException();
                }
            } catch (\JsonException) {
                throw new TrainingError('请求参数无效。');
            }
            if ('start' === $action) {
                return new JsonResponse($this->pairing->start());
            }
            if (!is_string($body->attemptId ?? null)) {
                throw new TrainingError('绑定会话无效，请重新生成二维码。');
            }

            return new JsonResponse($this->pairing->cancel($body->attemptId));
        } catch (TrainingError $error) {
            return new ApiErrorResponse($error->status, match ($error->status) {
                403 => 'forbidden', 409 => 'conflict', 410 => 'expired', 413 => 'payload_too_large', 415 => 'unsupported_media_type', 503 => 'unavailable', default => 'validation_error',
            }, $error->getMessage());
        }
    }
}
