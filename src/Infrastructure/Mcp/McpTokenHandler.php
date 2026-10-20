<?php

declare(strict_types=1);

namespace App\Infrastructure\Mcp;

use App\Infrastructure\Security\Api\ApiUser;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Security\Core\Exception\BadCredentialsException;
use Symfony\Component\Security\Http\AccessToken\AccessTokenHandlerInterface;
use Symfony\Component\Security\Http\Authenticator\Passport\Badge\UserBadge;

final readonly class McpTokenHandler implements AccessTokenHandlerInterface
{
    public function __construct(#[Autowire('%env(string:STRIDEHUB_MCP_TOKEN)%')] private string $token)
    {
    }

    public function getUserBadgeFrom(#[\SensitiveParameter] string $accessToken): UserBadge
    {
        if (strlen($this->token) < 32 || !hash_equals($this->token, $accessToken)) {
            throw new BadCredentialsException();
        }

        return new UserBadge(ApiUser::IDENTIFIER, static fn (): ApiUser => new ApiUser());
    }
}
