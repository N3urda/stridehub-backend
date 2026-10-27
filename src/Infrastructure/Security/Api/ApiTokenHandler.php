<?php

declare(strict_types=1);

namespace App\Infrastructure\Security\Api;

use App\Domain\Api\Token;
use App\Domain\Mobile\MobileSessionService;
use App\Domain\Training\TrainingError;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\RequestStack;
use Symfony\Component\Security\Core\Exception\BadCredentialsException;
use Symfony\Component\Security\Http\AccessToken\AccessTokenHandlerInterface;
use Symfony\Component\Security\Http\Authenticator\Passport\Badge\UserBadge;

final readonly class ApiTokenHandler implements AccessTokenHandlerInterface
{
    public function __construct(
        private Token $token,
        private ?MobileSessionService $mobileSessions = null,
        private ?RequestStack $requests = null,
    ) {
    }

    public function getUserBadgeFrom(#[\SensitiveParameter] string $accessToken): UserBadge
    {
        $request = $this->requests?->getCurrentRequest();
        if (str_starts_with($accessToken, 'shmobile_')) {
            if (null === $this->mobileSessions || null === $request || !$this->allowsMobile($request)) {
                throw new BadCredentialsException();
            }
            try {
                $this->mobileSessions->authenticate($accessToken);
            } catch (TrainingError) {
                throw new BadCredentialsException();
            }

            return new UserBadge(ApiUser::IDENTIFIER, static fn (): ApiUser => new ApiUser());
        }
        if (null !== $request && str_starts_with($request->getPathInfo(), '/mobile/v1')) {
            throw new BadCredentialsException();
        }
        if ($this->token->isEmpty() || !$this->token->hasValidFormat() || !$this->token->matches($accessToken)) {
            throw new BadCredentialsException();
        }

        return new UserBadge(ApiUser::IDENTIFIER, static fn (): ApiUser => new ApiUser());
    }

    private function allowsMobile(Request $request): bool
    {
        $path = $request->getPathInfo();
        $method = $request->getMethod();
        $rules = [
            '#^/mobile/v1/session$#D' => ['GET', 'PATCH', 'DELETE'],
            '#^/mobile/v1/collection/devices$#D' => ['POST'],
            '#^/api/v1/mobile(?:/[^/]+)+$#D' => ['GET', 'POST', 'PUT', 'DELETE'],
            '#^/api/v1/health/(?:workspace|workouts|collected/(?:weight|zepp))$#D' => ['GET'],
            '#^/api/v1/health/context$#D' => ['GET', 'PUT'],
            '#^/api/v1/health/(?:meal-plans|meals|weight-goals|measurements)(?:/[a-zA-Z0-9_-]+(?:/history)?)?$#D' => ['GET', 'PUT', 'DELETE'],
            '#^/api/v1/health/(?:blood-pressure|plans)(?:/[a-zA-Z0-9_-]+)?$#D' => ['GET', 'PUT', 'DELETE'],
            '#^/api/v1/health/weight(?:/[^/]+)?$#D' => ['GET', 'PUT', 'DELETE'],
            '#^/api/v1/health/sleep(?:/(?:status|sync))?$#D' => ['GET', 'POST'],
            '#^/api/v1/health/zepp/(?:status|sync|sources|days(?:/[^/]+)?|raw/[a-f0-9]{64})$#D' => ['GET', 'POST', 'DELETE'],
            '#^/api/v1/health/zepp/workouts(?:/(?:status|sync|sources/[a-f0-9]{64}|zepp-[a-f0-9]{16}-[0-9]{1,16}))?$#D' => ['GET', 'POST', 'DELETE'],
            '#^/api/v1/health/workout-links/[^/]+$#D' => ['GET', 'PUT', 'DELETE'],
            '#^/api/v1/health/collection/weight(?:/[^/]+)?$#D' => ['GET', 'DELETE'],
            '#^/api/v1/training/(?:today|briefing|activities|reconciliation)$#D' => ['GET'],
            '#^/api/v1/training/profile$#D' => ['GET', 'PUT'],
            '#^/api/v1/training/(?:sessions|races|check-ins|fuel-logs)(?:/[a-zA-Z0-9_-]+)?$#D' => ['GET', 'POST', 'PUT', 'DELETE'],
            '#^/api/v1/training/sessions/[a-zA-Z0-9_-]+/(?:feedback|comparison|link)$#D' => ['GET', 'POST', 'PUT'],
            '#^/api/v1/running/(?:overview|activities/[^/]{1,255})$#D' => ['GET'],
            '#^/api/v1/activities(?:/[^/]+(?:/gpx)?)?$#D' => ['GET'],
            '#^/api/v1/activity/upload$#D' => ['POST', 'PUT'],
            '#^/api/v1/athlete/weights(?:/[^/]+)?$#D' => ['GET', 'POST', 'DELETE'],
        ];
        foreach ($rules as $pattern => $methods) {
            if (in_array($method, $methods, true) && preg_match($pattern, $path)) {
                return true;
            }
        }

        return false;
    }
}
