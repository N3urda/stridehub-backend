<?php

declare(strict_types=1);

namespace App\Controller\Mobile;

use App\Domain\Health\WeightCollection;
use App\Domain\Mobile\MobileSessionService;
use App\Domain\Training\TrainingError;
use App\Infrastructure\Http\Api\ApiErrorResponse;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;

#[AsController]
final readonly class MobileSessionRequestHandler
{
    public function __construct(private MobileSessionService $sessions, private WeightCollection $collection)
    {
    }

    #[Route('/mobile/v1/sessions', name: 'mobile_session_create', methods: ['POST'], priority: 80)]
    public function create(Request $request): Response
    {
        return $this->respond(function () use ($request): Response {
            $body = $this->body($request);
            foreach (['username', 'password', 'deviceName'] as $field) {
                if (!is_string($body[$field] ?? null)) {
                    throw new TrainingError('username, password and deviceName must be strings.');
                }
            }
            if (strlen($body['username']) > 255 || strlen($body['password']) > 4096) {
                throw new TrainingError('Invalid username or password.', 401);
            }

            return new JsonResponse($this->sessions->create($body['username'], $body['password'], $body['deviceName'], $request->getClientIp() ?? 'unknown'), 201);
        });
    }

    #[Route('/mobile/v1/session', name: 'mobile_session_current', methods: ['GET', 'DELETE'], priority: 80)]
    public function current(Request $request): Response
    {
        return $this->respond(function () use ($request): Response {
            $token = $this->bearer($request);
            if ($request->isMethod('DELETE')) {
                $this->sessions->revoke($token);

                return new Response(status: 204);
            }

            return new JsonResponse($this->sessions->authenticate($token));
        });
    }

    #[Route('/mobile/v1/collection/devices', name: 'mobile_collection_pair', methods: ['POST'], priority: 80)]
    public function pair(Request $request): Response
    {
        return $this->respond(function () use ($request): Response {
            $body = $this->body($request);
            if (!is_string($body['name'] ?? null) || !is_string($body['deviceId'] ?? null)) {
                throw new TrainingError('name and deviceId must be strings.');
            }

            return new JsonResponse($this->collection->pair($body['name'], $body['deviceId']), 201);
        });
    }

    private function bearer(Request $request): string
    {
        $header = $request->headers->get('Authorization') ?? '';

        return str_starts_with($header, 'Bearer ') ? substr($header, 7) : '';
    }

    private function respond(callable $operation): Response
    {
        try {
            $response = $operation();
        } catch (TrainingError $error) {
            $response = new ApiErrorResponse($error->status, match ($error->status) {
                401 => 'invalid_credentials', 429 => 'too_many_requests', 413 => 'payload_too_large', 415 => 'unsupported_media_type', default => 'validation_error',
            }, $error->getMessage(), 429 === $error->status ? ['Retry-After' => '900'] : []);
        }
        $response->headers->set('Cache-Control', 'no-store, private');

        return $response;
    }

    /** @return array<string, mixed> */
    private function body(Request $request): array
    {
        if ('application/json' !== strtolower(trim(explode(';', $request->headers->get('Content-Type') ?? '')[0]))) {
            throw new TrainingError('application/json required.', 415);
        }
        if (strlen($request->getContent()) > 16384) {
            throw new TrainingError('Body exceeds 16 KiB.', 413);
        }
        try {
            $body = json_decode($request->getContent(), false, 8, JSON_THROW_ON_ERROR);
            if (!$body instanceof \stdClass) {
                throw new \JsonException();
            }

            return get_object_vars($body);
        } catch (\JsonException) {
            throw new TrainingError('Valid JSON object required.');
        }
    }
}
