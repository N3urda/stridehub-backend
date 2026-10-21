<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\DailyCollectionReadModel;
use App\Domain\Health\HealthDataService;
use App\Domain\Training\TrainingError;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;

#[AsController]
final readonly class DailyCollectionRequestHandler
{
    public function __construct(private DailyCollectionReadModel $read)
    {
    }

    #[Route('/admin/health/api/collected/{topic}', name: 'admin_collected_read', requirements: ['topic' => 'weight|zepp'], methods: ['GET'], priority: 50)]
    #[Route('/api/v1/health/collected/{topic}', name: 'api_collected_read', requirements: ['topic' => 'weight|zepp'], methods: ['GET'], priority: 50)]
    public function handle(Request $request, string $topic): Response
    {
        try {
            $from = $request->query->get('from');
            $to = $request->query->get('to');
            HealthDataService::range($from, $to);
            if (null === $from || null === $to || new \DateTimeImmutable($from)->diff(new \DateTimeImmutable($to))->days > 365) {
                throw new TrainingError('请指定不超过 366 天的日期范围。');
            }
            $rows = 'weight' === $topic ? $this->read->captures($from, $to) : $this->read->summaries($from, $to);
            $kind = $request->query->get('kind');
            if (null !== $kind && 'zepp' === $topic) {
                $rows = array_values(array_filter($rows, static fn (array $r): bool => $r['kind'] === $kind));
            }
            $offset = filter_var($request->query->get('offset', '0'), FILTER_VALIDATE_INT);
            $limit = filter_var($request->query->get('limit', '20'), FILTER_VALIDATE_INT);
            if (false === $offset || false === $limit) {
                throw new TrainingError('分页参数必须是整数。');
            }

            return new JsonResponse(['from' => $from, 'to' => $to, 'kinds' => DailyCollectionReadModel::LABELS, ...DailyCollectionReadModel::page($rows, $offset, $limit, $request->query->get('snapshot'))]);
        } catch (TrainingError $error) {
            return new ApiErrorResponse($error->status, 409 === $error->status ? 'conflict' : 'validation_error', $error->getMessage());
        }
    }
}
