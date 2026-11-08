<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\MiFitnessDataService;
use App\Domain\Health\MiFitnessService;
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
final readonly class MiFitnessApiRequestHandler
{
    public function __construct(private MiFitnessService $miFitness, private CsrfTokenManagerInterface $csrf, private MiFitnessDataService $data)
    {
    }

    #[Route('/api/v1/health/mi-fitness{path}', name: 'api_v1_health_mi_fitness', requirements: ['path' => '(?:/status|/sync|/data|/records)'], defaults: ['path' => ''], priority: 40)]
    #[Route('/admin/health/api/mi-fitness{path}', name: 'admin_health_mi_fitness_api', requirements: ['path' => '(?:/status|/sync|/data|/records)'], defaults: ['path' => ''], priority: 40)]
    public function handle(Request $request, string $path): Response
    {
        try {
            $method = '/sync' === $path ? 'POST' : 'GET';
            if (!$request->isMethod($method)) {
                return new ApiErrorResponse(405, 'method_not_allowed', '此接口不支持该方法。', ['Allow' => $method]);
            }
            if (str_starts_with($request->getPathInfo(), '/admin/') && !$request->isMethod('GET') && !$this->csrf->isTokenValid(new CsrfToken('health', $request->headers->get('X-CSRF-Token')))) {
                throw new TrainingError('页面校验已过期，请刷新后重试。', 403);
            }
            if ('/data' === $path) {
                return new JsonResponse($this->data->overview($request->query->get('from'), $request->query->get('to')));
            }
            if ('/records' === $path) {
                $offset = filter_var($request->query->get('offset', '0'), FILTER_VALIDATE_INT);
                $limit = filter_var($request->query->get('limit', '100'), FILTER_VALIDATE_INT);
                if (false === $offset || false === $limit) {
                    throw new TrainingError('分页参数必须是整数。');
                }

                return new JsonResponse($this->data->records($request->query->get('type', 'steps'), $request->query->get('from'), $request->query->get('to'), $offset, $limit));
            }
            if ('/status' === $path) {
                return new JsonResponse($this->miFitness->status());
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
            $status = $this->miFitness->sync($body->days ?? 7);

            return new JsonResponse($status, in_array($status['state'], ['not_configured', 'auth_required', 'error'], true) ? 503 : 200);
        } catch (TrainingError $error) {
            return new ApiErrorResponse($error->status, match ($error->status) {
                403 => 'forbidden', 409 => 'conflict', 413 => 'payload_too_large', 415 => 'unsupported_media_type', default => 'validation_error',
            }, $error->getMessage());
        }
    }
}
