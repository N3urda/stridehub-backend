<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\HealthContextService;
use App\Domain\Training\TrainingError;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;

#[AsController]
final readonly class HealthApiRequestHandler
{
    public function __construct(private HealthContextService $context, private KernelProjectDir $projectDir)
    {
    }

    #[Route('/api/v1/health/{path}', name: 'api_v1_health', requirements: ['path' => '.+'], priority: 20)]
    public function handle(Request $request, string $path): Response
    {
        try {
            if ('context' === $path) {
                return match ($request->getMethod()) {
                    'GET' => new JsonResponse($this->context->get()),
                    'PUT' => new JsonResponse($this->context->update($this->body($request))),
                    default => new ApiErrorResponse(405, 'method_not_allowed', '此接口不支持该方法。', ['Allow' => 'GET, PUT']),
                };
            }
            if ('openapi.json' === $path) {
                return $request->isMethod('GET')
                    ? new JsonResponse(file_get_contents($this->projectDir.'/docs/stridehub/health-openapi.json'), json: true)
                    : new ApiErrorResponse(405, 'method_not_allowed', '此接口不支持该方法。', ['Allow' => 'GET']);
            }

            throw new TrainingError('接口不存在。', 404);
        } catch (TrainingError $error) {
            return new ApiErrorResponse($error->status, match ($error->status) {
                404 => 'not_found', 409 => 'conflict', 428 => 'version_required', 413 => 'payload_too_large', 415 => 'unsupported_media_type', default => 'validation_error',
            }, $error->getMessage());
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
            $object = json_decode($raw, false, 32, JSON_THROW_ON_ERROR);
            if (!$object instanceof \stdClass) {
                throw new \JsonException();
            }

            return get_object_vars($object);
        } catch (\JsonException) {
            throw new TrainingError('请求必须是有效 JSON 对象。');
        }
    }
}
