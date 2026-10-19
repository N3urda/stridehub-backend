<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\BloodPressureService;
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
final readonly class BloodPressureApiRequestHandler
{
    public function __construct(private BloodPressureService $bloodPressure, private CsrfTokenManagerInterface $csrf)
    {
    }

    #[Route('/api/v1/health/blood-pressure/{id}', name: 'api_v1_blood_pressure', defaults: ['id' => null], requirements: ['id' => '[a-zA-Z0-9_-]+'], priority: 30)]
    #[Route('/admin/health/api/blood-pressure/{id}', name: 'admin_blood_pressure_api', defaults: ['id' => null], requirements: ['id' => '[a-zA-Z0-9_-]+'], priority: 30)]
    public function handle(Request $request, ?string $id): Response
    {
        try {
            if (!in_array($request->getMethod(), null === $id ? ['GET'] : ['GET', 'PUT', 'DELETE'], true)) {
                throw new TrainingError('此接口不支持该方法。', 405);
            }
            if (str_starts_with($request->getPathInfo(), '/admin/') && !$request->isMethod('GET') && !$this->csrf->isTokenValid(new CsrfToken('health', $request->headers->get('X-CSRF-Token')))) {
                throw new TrainingError('页面校验已过期，请刷新后重试。', 403);
            }
            if ($request->isMethod('GET')) {
                $response = new JsonResponse(null === $id ? ['records' => $this->bloodPressure->list($request->query->get('from'), $request->query->get('to'))] : $this->bloodPressure->get($id));
            } elseif (null === $id) {
                throw new TrainingError('此接口需要记录 ID。', 405);
            } elseif ($request->isMethod('PUT')) {
                $response = new JsonResponse($this->bloodPressure->save($id, $this->body($request)));
            } else {
                $rawVersion = $request->query->get('version');
                if (null !== $rawVersion && !ctype_digit($rawVersion)) {
                    throw new TrainingError('version 必须为整数。');
                }
                $this->bloodPressure->delete($id, TrainingInput::version(null === $rawVersion ? null : (int) $rawVersion));
                $response = new Response(status: 204);
            }
        } catch (TrainingError $error) {
            $response = new ApiErrorResponse($error->status, match ($error->status) {
                404 => 'not_found', 409 => 'conflict', 428 => 'version_required', 403 => 'forbidden', 405 => 'method_not_allowed', 413 => 'payload_too_large', 415 => 'unsupported_media_type', default => 'validation_error',
            }, $error->getMessage(), 405 === $error->status ? ['Allow' => null === $id ? 'GET' : 'GET, PUT, DELETE'] : []);
        }
        $response->headers->set('Cache-Control', 'no-store, private');

        return $response;
    }

    /** @return array<string, mixed> */
    private function body(Request $request): array
    {
        if ('application/json' !== strtolower(trim(explode(';', $request->headers->get('Content-Type') ?? '')[0]))) {
            throw new TrainingError('请求必须使用 application/json。', 415);
        }
        $raw = $request->getContent();
        if (strlen($raw) > 16384) {
            throw new TrainingError('单条血压记录请求超过 16 KiB。', 413);
        }
        try {
            $object = json_decode($raw, false, 16, JSON_THROW_ON_ERROR);
            if (!$object instanceof \stdClass) {
                throw new \JsonException();
            }

            return json_decode($raw, true, 16, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            throw new TrainingError('请求必须是有效 JSON 对象。');
        }
    }
}
