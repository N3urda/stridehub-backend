<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\BodyCompositionReadModel;
use App\Domain\Health\BodyHeightSelection;
use App\Domain\Health\DailyCollectionReadModel;
use App\Domain\Health\HealthDataService;
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
final readonly class BodyCompositionRequestHandler
{
    public function __construct(private BodyCompositionReadModel $read, private BodyHeightSelection $height, private CsrfTokenManagerInterface $csrf)
    {
    }

    #[Route('/admin/health/api/body-composition', name: 'admin_body_composition', methods: ['GET'], priority: 60)]
    #[Route('/api/v1/health/body-composition', name: 'api_body_composition', methods: ['GET'], priority: 60)]
    public function read(Request $request): Response
    {
        try {
            $from = $request->query->get('from');
            $to = $request->query->get('to');
            HealthDataService::range($from, $to);
            if (null === $from || null === $to || new \DateTimeImmutable($from)->diff(new \DateTimeImmutable($to))->days > 365) {
                throw new TrainingError('请指定不超过 366 天的日期范围。');
            }
            $offset = filter_var($request->query->get('offset', '0'), FILTER_VALIDATE_INT);
            $limit = filter_var($request->query->get('limit', '20'), FILTER_VALIDATE_INT);
            if (false === $offset || false === $limit) {
                throw new TrainingError('分页参数必须是整数。');
            }
            $data = $this->read->read($from, $to);
            $selected = $request->query->get('selected');
            if (null !== $selected && strlen($selected) > 300) {
                throw new TrainingError('所选采集编号过长。');
            }

            return new JsonResponse([...array_diff_key($data, ['records' => true]),
                'selectedRecord' => null === $selected ? null : array_find($data['records'], static fn (array $r): bool => $r['id'] === $selected),
                ...DailyCollectionReadModel::page($data['records'], $offset, $limit, $request->query->get('snapshot'))]);
        } catch (TrainingError $error) {
            return new ApiErrorResponse($error->status, 409 === $error->status ? 'conflict' : 'validation_error', $error->getMessage());
        }
    }

    #[Route('/admin/health/api/body-composition/height-selection', name: 'admin_body_height_selection', methods: ['GET', 'PUT'], priority: 60)]
    #[Route('/api/v1/health/body-composition/height-selection', name: 'api_body_height_selection', methods: ['GET', 'PUT'], priority: 60)]
    public function height(Request $request): Response
    {
        try {
            if ($request->isMethod('GET')) {
                return new JsonResponse($this->height->get());
            }
            if (str_starts_with($request->getPathInfo(), '/admin/') && !$this->csrf->isTokenValid(new CsrfToken('health', $request->headers->get('X-CSRF-Token')))) {
                throw new TrainingError('页面校验已过期，请刷新。', 403);
            }
            if ('application/json' !== strtolower(trim(explode(';', $request->headers->get('Content-Type') ?? '')[0]))) {
                throw new TrainingError('请求必须使用 application/json。', 415);
            }
            if (strlen($request->getContent()) > 4096) {
                throw new TrainingError('请求超过 4 KiB。', 413);
            }
            try {
                if (!json_decode($request->getContent(), false, 16, JSON_THROW_ON_ERROR) instanceof \stdClass) {
                    throw new \JsonException();
                }
                $input = json_decode($request->getContent(), true, 16, JSON_THROW_ON_ERROR);
            } catch (\JsonException) {
                throw new TrainingError('请求必须为 JSON 对象。');
            }

            return new JsonResponse($this->height->save($input));
        } catch (TrainingError $error) {
            return new ApiErrorResponse($error->status, 409 === $error->status ? 'conflict' : 'validation_error', $error->getMessage());
        }
    }
}
