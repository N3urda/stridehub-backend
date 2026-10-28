<?php

declare(strict_types=1);

namespace App\Controller\Mobile;

use App\Domain\Import\InvalidActivityFileName;
use App\Domain\Mobile\MobileWorkflowService;
use App\Domain\Training\TrainingError;
use App\Infrastructure\CQRS\Command\CouldNotProcessCommand;
use App\Infrastructure\CQRS\Command\Deserialize\CouldNotDeserializeCommand;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use Symfony\Component\HttpFoundation\File\UploadedFile;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;

#[AsController]
final readonly class MobileWorkflowRequestHandler
{
    public function __construct(private MobileWorkflowService $workflows)
    {
    }

    #[Route('/api/v1/mobile/{resource}{path}', name: 'api_v1_mobile_workflows', requirements: ['resource' => 'workouts|activity-options|activities|imports|sync', 'path' => '(?:/[^/]+(?:/(?:retry|enrich|streams))?)?'], defaults: ['path' => ''], priority: 30)]
    public function handle(Request $request, string $resource, string $path = ''): Response
    {
        $response = $this->respond($request, $resource, $path);
        $response->headers->set('Cache-Control', 'private, no-store');

        return $response;
    }

    private function respond(Request $request, string $resource, string $path): Response
    {
        $parts = explode('/', trim($path, '/'));
        $id = $parts[0];
        $action = $parts[1] ?? '';
        try {
            $allowed = match ($resource) {
                'workouts', 'activity-options' => ['GET'],
                'activities' => '' === $id ? ['POST'] : ('' === $action ? ['GET', 'PUT', 'DELETE'] : ('streams' === $action ? ['GET'] : ['POST'])),
                'imports' => '' === $id ? ['GET', 'POST'] : ('' === $action ? ['GET'] : ['POST']),
                'sync' => '' === $id ? ['GET', 'POST'] : ['GET'],
                default => throw new TrainingError('接口不存在。', 404),
            };
            if (!in_array($request->getMethod(), $allowed, true)) {
                return new ApiErrorResponse(405, 'method_not_allowed', '此接口不支持该方法。', ['Allow' => implode(', ', $allowed)]);
            }
            if ('workouts' === $resource && '' === $id) {
                return new JsonResponse($this->workflows->workouts($request->query->get('from'), $request->query->get('to'), $this->integer($request, 'page', 1), $this->integer($request, 'limit', 30), $request->query->get('sportType', 'all')));
            }
            if ('activity-options' === $resource && '' === $id) {
                return new JsonResponse($this->workflows->options());
            }
            if ('activities' === $resource) {
                if ('streams' === $action) {
                    return new JsonResponse($this->workflows->activityStreams($id));
                }
                if ('GET' === $request->getMethod() && '' === $action) {
                    return new JsonResponse($this->workflows->activity($id));
                }
                if ('DELETE' === $request->getMethod()) {
                    $this->workflows->deleteActivity($id, $request->query->get('version'));

                    return new Response('', 204);
                }
                $body = $this->body($request);
                if ('' === $id) {
                    return new JsonResponse($this->workflows->createActivity($body), 201);
                }
                if ('enrich' === $action) {
                    return new JsonResponse($this->workflows->enrichActivity($id, $body));
                }
                if ('' === $action) {
                    return new JsonResponse($this->workflows->updateActivity($id, $body));
                }
            }
            if ('imports' === $resource) {
                if ('GET' === $request->getMethod() && '' === $action) {
                    return new JsonResponse('' === $id ? $this->workflows->imports() : $this->workflows->import($id));
                }
                if ('retry' === $action) {
                    $body = $this->body($request);
                    if ([] !== $body) {
                        throw new TrainingError('重试请求必须是空 JSON 对象。');
                    }

                    return new JsonResponse($this->workflows->retryImport($id), 202);
                }
                if ('' === $id) {
                    $file = $request->files->get('file');
                    if (!$file instanceof UploadedFile || !$file->isValid()) {
                        throw new TrainingError('请通过 file 字段上传一个有效文件。');
                    }
                    if ($file->getSize() > 25 * 1024 * 1024) {
                        throw new TrainingError('文件不能超过 25 MiB。', 413);
                    }

                    return new JsonResponse($this->workflows->upload($file->getClientOriginalName(), $file->getContent()), 202);
                }
            }
            if ('sync' === $resource && '' === $action) {
                return new JsonResponse($request->isMethod('POST') ? $this->workflows->startSync($this->body($request)) : ('' === $id ? $this->workflows->syncJobs() : $this->workflows->syncJob($id)), $request->isMethod('POST') ? 202 : 200);
            }

            throw new TrainingError('接口不存在。', 404);
        } catch (TrainingError $error) {
            return new ApiErrorResponse($error->status, match ($error->status) {
                403 => 'forbidden', 404 => 'not_found', 409 => 'conflict', 413 => 'payload_too_large', 415 => 'unsupported_media_type', 428 => 'version_required', default => 'validation_error',
            }, $error->getMessage());
        } catch (CouldNotDeserializeCommand|CouldNotProcessCommand|InvalidActivityFileName|\InvalidArgumentException $error) {
            return new ApiErrorResponse(422, 'validation_error', $error->getMessage());
        }
    }

    /** @return array<string, mixed> */
    private function body(Request $request): array
    {
        if ('application/json' !== strtolower(trim(explode(';', $request->headers->get('Content-Type') ?? '')[0]))) {
            throw new TrainingError('请求必须使用 application/json。', 415);
        }
        if (strlen($request->getContent()) > 65536) {
            throw new TrainingError('请求超过 64 KiB。', 413);
        }
        try {
            $body = json_decode($request->getContent(), false, 16, JSON_THROW_ON_ERROR);
            if (!$body instanceof \stdClass) {
                throw new \JsonException();
            }

            return json_decode($request->getContent(), true, 16, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            throw new TrainingError('请求必须是有效的 JSON 对象。');
        }
    }

    private function integer(Request $request, string $key, int $default): int
    {
        $raw = $request->query->get($key);
        if (null === $raw) {
            return $default;
        }
        if (!ctype_digit($raw) || strlen($raw) > 8) {
            throw new TrainingError($key.' 必须为整数。');
        }

        return (int) $raw;
    }
}
