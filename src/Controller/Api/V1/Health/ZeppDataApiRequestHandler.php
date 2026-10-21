<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\ZeppDataService;
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
final readonly class ZeppDataApiRequestHandler
{
    public function __construct(private ZeppDataService $zepp, private CsrfTokenManagerInterface $csrf)
    {
    }

    #[Route('/api/v1/health/zepp/{path}', name: 'api_v1_health_zepp', requirements: ['path' => 'status|sync|sources|days(?:/[^/]+)?|raw/[a-f0-9]{64}'], priority: 45)]
    #[Route('/admin/health/api/zepp/{path}', name: 'admin_health_zepp', requirements: ['path' => 'status|sync|sources|days(?:/[^/]+)?|raw/[a-f0-9]{64}'], priority: 45)]
    public function handle(Request $request, string $path): Response
    {
        try {
            $method = 'sync' === $path ? 'POST' : 'GET';
            if (!$request->isMethod($method)) {
                return new ApiErrorResponse(405, 'method_not_allowed', '此接口不支持该方法。', ['Allow' => $method]);
            }
            if ('GET' !== $method && str_starts_with($request->getPathInfo(), '/admin/') && !$this->csrf->isTokenValid(new CsrfToken('health', $request->headers->get('X-CSRF-Token')))) {
                throw new TrainingError('页面校验已过期，请刷新后重试。', 403);
            }
            if ('status' === $path) {
                return new JsonResponse($this->zepp->status());
            }
            if ('sources' === $path) {
                $limit = filter_var($request->query->get('limit', '100'), FILTER_VALIDATE_INT);
                if (false === $limit) {
                    throw new TrainingError('limit 必须是整数。');
                }

                return new JsonResponse($this->zepp->sources($request->query->get('kind'), $request->query->get('after'), $limit));
            }
            if ('days' === $path) {
                return new JsonResponse(['records' => $this->zepp->list($request->query->get('from'), $request->query->get('to'))]);
            }
            if (str_starts_with($path, 'days/')) {
                return new JsonResponse($this->zepp->day(substr($path, 5)));
            }
            if (str_starts_with($path, 'raw/')) {
                return new JsonResponse($this->zepp->raw(substr($path, 4)));
            }
            if ('application/json' !== strtolower(trim(explode(';', $request->headers->get('Content-Type') ?? '')[0]))) {
                throw new TrainingError('请求必须使用 application/json。', 415);
            }
            if (strlen($request->getContent()) > 4096) {
                throw new TrainingError('请求超过 4 KiB。', 413);
            }
            try {
                $body = json_decode($request->getContent(), false, 4, JSON_THROW_ON_ERROR);
                if (!$body instanceof \stdClass || array_diff(array_keys(get_object_vars($body)), ['days'])) {
                    throw new \JsonException();
                }
            } catch (\JsonException) {
                throw new TrainingError('请求必须是只包含 days 的 JSON 对象。');
            }
            if (property_exists($body, 'days') && !is_int($body->days)) {
                throw new TrainingError('days 必须是整数。');
            }
            $status = $this->zepp->sync($body->days ?? 30);

            return new JsonResponse($status, in_array($status['state'], ['not_configured', 'auth_required', 'error'], true) ? 503 : 200);
        } catch (TrainingError $error) {
            return new ApiErrorResponse($error->status, match ($error->status) {
                403 => 'forbidden', 404 => 'not_found', 409 => 'conflict', 413 => 'payload_too_large', 415 => 'unsupported_media_type', default => 'validation_error',
            }, $error->getMessage());
        }
    }
}
