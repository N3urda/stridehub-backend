<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\HealthWorkspace;
use App\Domain\Health\WorkoutCatalog;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingService;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use App\Infrastructure\Time\Clock\Clock;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Security\Csrf\CsrfToken;
use Symfony\Component\Security\Csrf\CsrfTokenManagerInterface;

#[AsController]
final readonly class HealthWorkspaceApiRequestHandler
{
    public function __construct(private HealthWorkspace $workspace, private WorkoutCatalog $workouts, private TrainingService $training, private Clock $clock, private CsrfTokenManagerInterface $csrf)
    {
    }

    #[Route('/api/v1/health/workspace', name: 'api_v1_health_workspace', methods: ['GET'], priority: 40)]
    #[Route('/admin/health/api/workspace', name: 'admin_health_workspace', methods: ['GET'], priority: 40)]
    public function read(Request $request): Response
    {
        try {
            return new JsonResponse($this->workspace->read($request->query->get('from'), $request->query->get('to')));
        } catch (TrainingError $e) {
            return new ApiErrorResponse($e->status, 'validation_error', $e->getMessage());
        }
    }

    #[Route('/api/v1/health/workouts', name: 'api_v1_health_workouts', methods: ['GET'], priority: 40)]
    #[Route('/admin/health/api/workouts', name: 'admin_health_workouts', methods: ['GET'], priority: 40)]
    public function workouts(Request $request): Response
    {
        try {
            $zone = new \DateTimeZone($this->training->profile()['timezone']);
            $to = $request->query->get('to') ?? $this->clock->getCurrentDateTimeImmutable()->setTimezone($zone)->format('Y-m-d');
            \App\Domain\Training\TrainingInput::date($to);
            $from = $request->query->get('from') ?? new \DateTimeImmutable($to)->modify('-89 days')->format('Y-m-d');

            return new JsonResponse($this->workouts->overview($from, $to, $zone));
        } catch (TrainingError $e) {
            return new ApiErrorResponse($e->status, 'validation_error', $e->getMessage());
        }
    }

    #[Route('/api/v1/health/workout-links/{id}', name: 'api_v1_health_workout_link', methods: ['GET', 'PUT', 'DELETE'], priority: 40)]
    #[Route('/admin/health/api/workout-links/{id}', name: 'admin_health_workout_link', methods: ['GET', 'PUT', 'DELETE'], priority: 40)]
    public function link(Request $request, string $id): Response
    {
        try {
            if ($request->isMethod('GET')) {
                return new JsonResponse($this->workouts->mapping($id));
            }
            if (str_starts_with($request->getPathInfo(), '/admin/') && !$this->csrf->isTokenValid(new CsrfToken('health', $request->headers->get('X-CSRF-Token')))) {
                throw new TrainingError('页面校验已过期，请刷新。', 403);
            }
            if ($request->isMethod('DELETE')) {
                $version = $request->query->get('version');
                if (null !== $version && !ctype_digit($version)) {
                    throw new TrainingError('version 必须为整数。');
                }
                $this->workouts->unlink($id, \App\Domain\Training\TrainingInput::version(null === $version ? null : (int) $version));

                return new Response(status: 204);
            }
            if ('application/json' !== strtolower(trim(explode(';', $request->headers->get('Content-Type') ?? '')[0]))) {
                throw new TrainingError('请求必须使用 application/json。', 415);
            }
            if (strlen($request->getContent()) > 4096) {
                throw new TrainingError('请求超过 4 KiB。', 413);
            }
            try {
                if (!json_decode($request->getContent(), false, 8, JSON_THROW_ON_ERROR) instanceof \stdClass) {
                    throw new \JsonException();
                }
                $input = json_decode($request->getContent(), true, 8, JSON_THROW_ON_ERROR);
            } catch (\JsonException) {
                throw new TrainingError('请求必须为 JSON 对象。');
            }

            return new JsonResponse($this->workouts->link($id, $input));
        } catch (TrainingError $e) {
            return new ApiErrorResponse($e->status, 409 === $e->status ? 'conflict' : 'validation_error', $e->getMessage());
        }
    }
}
