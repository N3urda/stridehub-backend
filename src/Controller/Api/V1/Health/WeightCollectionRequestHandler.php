<?php

declare(strict_types=1);

namespace App\Controller\Api\V1\Health;

use App\Domain\Health\WeightCollection;
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
final readonly class WeightCollectionRequestHandler
{
    public function __construct(private WeightCollection $collection, private CsrfTokenManagerInterface $csrf)
    {
    }

    #[Route('/admin/health/api/collection/{path}', name: 'admin_collection', requirements: ['path' => '.+'], priority: 70)]
    #[Route('/api/v1/health/collection/{path}', name: 'api_collection', requirements: ['path' => 'weight.*'], priority: 70)]
    #[Route('/device/v1/{path}', name: 'device_collection', requirements: ['path' => '.*'], priority: 70)]
    public function handle(Request $request, string $path): Response
    {
        try {
            $deviceRoute = str_starts_with($request->getPathInfo(), '/device/');
            $device = null;
            if ($deviceRoute) {
                $header = ($request->headers->get('Authorization') ?? '');
                $device = $this->collection->authenticate(str_starts_with($header, 'Bearer ') ? substr($header, 7) : '');
            } elseif (str_starts_with($request->getPathInfo(), '/admin/') && !$request->isMethod('GET') && !$this->csrf->isTokenValid(new CsrfToken('health', $request->headers->get('X-CSRF-Token')))) {
                throw new TrainingError('CSRF token invalid.', 403);
            }
            $method = $request->getMethod();
            if (!$deviceRoute && 'csrf' === $path && 'GET' === $method) {
                return new JsonResponse(['csrfToken' => $this->csrf->getToken('health')->getValue()], headers: ['Cache-Control' => 'no-store']);
            }
            if (!$deviceRoute && 'devices' === $path) {
                if ('GET' === $method) {
                    return new JsonResponse(['devices' => $this->collection->devices()]);
                }
                if ('POST' === $method) {
                    $body = $this->body($request);
                    if (!is_string($body['name'] ?? null)) {
                        throw new TrainingError('name required.');
                    }

                    if (isset($body['deviceId']) && !is_string($body['deviceId'])) {
                        throw new TrainingError('deviceId must be a string.');
                    }

                    return new JsonResponse($this->collection->pair($body['name'], $body['deviceId'] ?? null), 201, ['Cache-Control' => 'no-store']);
                }
            }
            if (!$deviceRoute && str_starts_with($path, 'devices/') && 'DELETE' === $method) {
                $this->collection->revoke(substr($path, 8));

                return new Response(status: 204);
            }
            if ('weight' === $path && 'GET' === $method) {
                $offset = $request->query->get('offset', '0');
                $limit = $request->query->get('limit', '50');
                if (!ctype_digit($offset) || !ctype_digit($limit)) {
                    throw new TrainingError('Invalid pagination.');
                }

                return new JsonResponse($this->collection->history($device, (int) $offset, (int) $limit));
            }
            if (str_starts_with($path, 'weight/')) {
                $id = substr($path, 7);
                if ($deviceRoute && 'GET' === $method) {
                    return new JsonResponse(['measurement' => $this->collection->get($device, $id)]);
                }
                if ($deviceRoute && 'PUT' === $method) {
                    return new JsonResponse($this->collection->capture($device, $id, $this->body($request)));
                }
                if (!$deviceRoute && 'DELETE' === $method) {
                    return new JsonResponse($this->collection->delete($request->query->get('deviceId', ''), $id));
                }
            }
            throw new TrainingError('Route or method unavailable.', 404);
        } catch (TrainingError $e) {
            return new ApiErrorResponse($e->status, match ($e->status) {
                401 => 'unauthorized', 403 => 'forbidden', 404 => 'not_found', 409 => 'conflict', 413 => 'payload_too_large', 415 => 'unsupported_media_type', default => 'validation_error',
            }, $e->getMessage());
        }
    }

    /** @return array<string, mixed> */
    private function body(Request $request): array
    {
        if ('application/json' !== strtolower(trim(explode(';', $request->headers->get('Content-Type') ?? '')[0]))) {
            throw new TrainingError('application/json required.', 415);
        }
        $raw = $request->getContent();
        if (strlen($raw) > 32768) {
            throw new TrainingError('Body exceeds 32 KiB.', 413);
        }
        try {
            $object = json_decode($raw, false, 16, JSON_THROW_ON_ERROR);
            if (!$object instanceof \stdClass) {
                throw new \JsonException();
            }
            foreach (['profile', 'derived'] as $f) {
                if (isset($object->$f) && !$object->$f instanceof \stdClass) {
                    throw new \JsonException();
                }
            }
            if (isset($object->derived->values) && !$object->derived->values instanceof \stdClass) {
                throw new \JsonException();
            }

            return get_object_vars($object);
        } catch (\JsonException) {
            throw new TrainingError('Valid JSON object required.');
        }
    }
}
