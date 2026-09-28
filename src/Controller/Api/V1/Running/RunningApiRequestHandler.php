<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Running;

use App\Domain\Running\RunningDetails;
use App\Domain\Running\RunningOverview;
use App\Domain\Training\TrainingError;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use Symfony\Component\HttpFoundation\Exception\BadRequestException;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;

#[AsController]
final readonly class RunningApiRequestHandler
{
    public function __construct(private RunningOverview $overview, private RunningDetails $details, private KernelProjectDir $projectDir)
    {
    }

    #[Route('/api/v1/running/{path}', name: 'api_v1_running', requirements: ['path' => '.+'], methods: ['GET'], priority: 20)]
    #[Route('/admin/running/api/{path}', name: 'admin_running_api', requirements: ['path' => '.+'], methods: ['GET'], priority: 20)]
    public function handle(Request $request, string $path): Response
    {
        try {
            if ('openapi.json' === $path) {
                $response = new JsonResponse(file_get_contents($this->projectDir.'/docs/stridehub/running-openapi.json'), json: true);
            } elseif ('overview' === $path) {
                $page = $request->query->get('page', '1');
                if (!ctype_digit($page)) {
                    throw new TrainingError('page 必须为正整数。');
                }
                $response = new JsonResponse($this->overview->build($request->query->get('from'), $request->query->get('to'), $request->query->get('sportType') ?? 'all', (int) $page));
            } elseif (preg_match('#^activities/([^/]{1,255})$#D', $path, $matches)) {
                $response = new JsonResponse($this->details->get($matches[1]));
            } else {
                throw new TrainingError('接口不存在。', 404);
            }
        } catch (TrainingError $error) {
            $response = new ApiErrorResponse($error->status, 404 === $error->status ? 'not_found' : 'validation_error', $error->getMessage());
        } catch (BadRequestException) {
            $response = new ApiErrorResponse(422, 'validation_error', '查询参数必须是标量。');
        }
        $response->headers->set('Cache-Control', 'no-store');

        return $response;
    }
}
