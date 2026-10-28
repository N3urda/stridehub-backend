<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\HealthContextInput;
use App\Domain\Health\HealthDataService;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Security\Csrf\CsrfToken;
use Symfony\Component\Security\Csrf\CsrfTokenManagerInterface;

#[AsController]
final readonly class HealthDataApiRequestHandler
{
    public function __construct(private HealthDataService $data, private CsrfTokenManagerInterface $csrf)
    {
    }

    #[Route('/api/v1/health/{resource}/{id}/{operation}', name: 'api_v1_health_data', requirements: ['resource' => 'meal-plans|meals|weight-goals|measurements', 'id' => '[a-zA-Z0-9_-]+', 'operation' => 'history'], defaults: ['id' => null, 'operation' => null], priority: 35)]
    #[Route('/admin/health/api/{resource}/{id}/{operation}', name: 'admin_health_data', requirements: ['resource' => 'meal-plans|meals|weight-goals|measurements', 'id' => '[a-zA-Z0-9_-]+', 'operation' => 'history'], defaults: ['id' => null, 'operation' => null], priority: 35)]
    public function handle(Request $request, string $resource, ?string $id, ?string $operation): Response
    {
        $methods = null !== $operation || null === $id ? ['GET'] : ('batch' === $id ? ['PUT'] : ['GET', 'PUT', 'DELETE']);
        try {
            if (!in_array($request->getMethod(), $methods, true)) {
                throw new TrainingError('此接口不支持该方法。', 405);
            }
            if (str_starts_with($request->getPathInfo(), '/admin/') && !$request->isMethod('GET') && !$this->csrf->isTokenValid(new CsrfToken('health', $request->headers->get('X-CSRF-Token')))) {
                throw new TrainingError('页面校验已过期，请刷新。', 403);
            }
            if ($request->isMethod('GET')) {
                return new JsonResponse(null === $id
                    ? ['records' => $this->data->list($resource, $request->query->get('from'), $request->query->get('to'))]
                    : (null === $operation ? $this->data->get($resource, $id) : ['records' => $this->data->history($resource, $id)]));
            }
            if (null === $id) {
                throw new TrainingError('此接口需要记录 ID。', 405);
            }
            if ($request->isMethod('PUT')) {
                $body = $this->body($request);
                if ('batch' === $id) {
                    HealthContextInput::knownFields($body, ['records'], 'batch');
                    if (!is_array($body['records'] ?? null)) {
                        throw new TrainingError('records 必须为列表。');
                    }

                    return new JsonResponse(['records' => $this->data->batch($resource, $body['records'])]);
                }

                return new JsonResponse($this->data->save($resource, $id, $body));
            }
            $version = $request->query->get('version');
            if (null !== $version && !ctype_digit($version)) {
                throw new TrainingError('version 必须为整数。');
            }
            $this->data->delete($resource, $id, TrainingInput::version(null === $version ? null : (int) $version));

            return new Response(status: 204);
        } catch (TrainingError $e) {
            return new ApiErrorResponse($e->status, match ($e->status) {
                404 => 'not_found', 409 => 'conflict', 428 => 'version_required', 403 => 'forbidden', 405 => 'method_not_allowed', 413 => 'payload_too_large', 415 => 'unsupported_media_type', default => 'validation_error',
            }, $e->getMessage(), 405 === $e->status ? ['Allow' => implode(', ', $methods)] : []);
        }
    }

    /** @return array<string, mixed> */
    private function body(Request $request): array
    {
        if ('application/json' !== strtolower(trim(explode(';', $request->headers->get('Content-Type') ?? '')[0]))) {
            throw new TrainingError('请求必须使用 application/json。', 415);
        }
        $raw = $request->getContent();
        if (strlen($raw) > 1048576) {
            throw new TrainingError('请求超过 1 MiB。', 413);
        }
        try {
            if (!json_decode($raw, false, 16, JSON_THROW_ON_ERROR) instanceof \stdClass) {
                throw new \JsonException();
            }

            return json_decode($raw, true, 16, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            throw new TrainingError('请求必须为 JSON 对象。');
        }
    }
}
