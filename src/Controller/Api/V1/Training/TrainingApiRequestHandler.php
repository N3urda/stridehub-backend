<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Training;

use App\Domain\Training\TrainingActivities;
use App\Domain\Training\TrainingBriefing;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingFeedback;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingService;
use App\Domain\Training\TrainingToday;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Security\Csrf\CsrfToken;
use Symfony\Component\Security\Csrf\CsrfTokenManagerInterface;

#[AsController]
final readonly class TrainingApiRequestHandler
{
    public function __construct(private TrainingService $training, private TrainingActivities $activities, private TrainingBriefing $briefing, private CsrfTokenManagerInterface $csrf, private KernelProjectDir $projectDir, private TrainingFeedback $feedback, private TrainingToday $today)
    {
    }

    #[Route('/api/v1/training/{path}', name: 'api_v1_training', requirements: ['path' => '.+'], methods: ['GET', 'POST', 'PUT', 'DELETE'], priority: 20)]
    #[Route('/admin/training/api/{path}', name: 'admin_training_api', requirements: ['path' => '.+'], methods: ['GET', 'POST', 'PUT', 'DELETE'], priority: 20)]
    public function handle(Request $request, string $path): Response
    {
        try {
            if (str_starts_with($request->getPathInfo(), '/admin/') && !$request->isMethod('GET') && !$this->csrf->isTokenValid(new CsrfToken('training', $request->headers->get('X-CSRF-Token')))) {
                throw new TrainingError('页面校验已过期，请刷新后重试。', 403);
            }
            $response = $this->dispatch($request, $path);
        } catch (TrainingError $error) {
            $response = new ApiErrorResponse($error->status, match ($error->status) {
                404 => 'not_found', 409 => 'conflict', 428 => 'version_required', 403 => 'forbidden', 405 => 'method_not_allowed', 413 => 'payload_too_large', 415 => 'unsupported_media_type', default => 'validation_error',
            }, $error->getMessage());
        }
        $response->headers->set('Cache-Control', 'no-store');

        return $response;
    }

    private function dispatch(Request $request, string $path): Response
    {
        $method = $request->getMethod();
        if ('GET' === $method && 'briefing' === $path) {
            return new JsonResponse($this->briefing->generate($request->query->get('sessionId')));
        }
        if ('GET' === $method && 'openapi.json' === $path) {
            return new JsonResponse(file_get_contents($this->projectDir.'/docs/stridehub/openapi.json'), json: true);
        }
        if ('GET' === $method && 'activities' === $path) {
            return new JsonResponse(['items' => $this->activities->list()]);
        }
        if ('GET' === $method && 'today' === $path) {
            return new JsonResponse($this->today->get());
        }
        if ('GET' === $method && 'reconciliation' === $path) {
            return new JsonResponse($this->training->reconciliation($request->query->get('from'), $request->query->get('to')));
        }
        if (preg_match('#^sessions/([a-zA-Z0-9_-]+)/feedback$#D', $path, $match) && 'PUT' === $method) {
            return new JsonResponse($this->feedback->save($match[1], $this->body($request)));
        }
        if ('profile' === $path) {
            return match ($method) {
                'GET' => new JsonResponse($this->training->profile()),
                'PUT' => new JsonResponse($this->training->update('profile', 'default', $this->body($request))),
                default => throw new TrainingError('此接口不支持该方法。', 405),
            };
        }
        if ('sessions/batch' === $path && 'PUT' === $method) {
            $body = $this->body($request);
            if (!is_array($body['sessions'] ?? null)) {
                throw new TrainingError('必须提供 sessions 列表。');
            }

            return new JsonResponse(['items' => $this->training->batch($body['sessions'])]);
        }
        if (preg_match('#^sessions/([a-zA-Z0-9_-]+)/comparison$#D', $path, $match) && 'GET' === $method) {
            return new JsonResponse($this->training->comparison($match[1]));
        }
        if (preg_match('#^sessions/([a-zA-Z0-9_-]+)/link$#D', $path, $match) && 'POST' === $method) {
            $body = $this->body($request);
            if (array_diff(array_keys($body), ['version', 'activityId', 'activityIds']) || (!array_key_exists('activityId', $body) && !array_key_exists('activityIds', $body))) {
                throw new TrainingError('必须提供 version 和 activityIds（或兼容的 activityId）。');
            }

            return new JsonResponse($this->training->update('sessions', $match[1], [...$body, 'version' => TrainingInput::version($body['version'] ?? null)]));
        }
        if (!preg_match('#^(sessions|races|check-ins|fuel-logs)(?:/([a-zA-Z0-9_-]+))?$#D', $path, $match)) {
            throw new TrainingError('接口不存在。', 404);
        }
        $kind = $match[1];
        $id = $match[2] ?? null;
        if ('GET' === $method) {
            return new JsonResponse(null === $id ? ['items' => $this->training->list($kind, $request->query->get('from'), $request->query->get('to'), $request->query->get('status'))] : $this->training->get($kind, $id));
        }
        if ('POST' === $method && null === $id) {
            return new JsonResponse($this->training->create($kind, $this->body($request)), 201);
        }
        if ('PUT' === $method && null !== $id) {
            $this->training->get($kind, $id);

            return new JsonResponse($this->training->update($kind, $id, $this->body($request)));
        }
        if ('DELETE' === $method && null !== $id) {
            $version = $request->query->get('version');
            if (null !== $version && !ctype_digit($version)) {
                throw new TrainingError('version 必须为整数。');
            }
            $this->training->delete($kind, $id, TrainingInput::version(null === $version ? null : (int) $version));

            return new Response(status: 204);
        }

        throw new TrainingError('此接口不支持该方法。', 405);
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

            return json_decode($raw, true, 32, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            throw new TrainingError('请求必须是有效 JSON 对象。');
        }
    }
}
