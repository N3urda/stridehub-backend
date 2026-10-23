<?php

declare(strict_types=1);

namespace App\Infrastructure\Http\Session;

use Symfony\Component\DependencyInjection\Attribute\AsDecorator;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\DependencyInjection\Attribute\AutowireDecorated;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Session\Storage\NativeSessionStorage;
use Symfony\Component\HttpFoundation\Session\Storage\SessionStorageFactoryInterface;
use Symfony\Component\HttpFoundation\Session\Storage\SessionStorageInterface;

#[AsDecorator('session.storage.factory.native')]
final readonly class RequestAwareNativeSessionStorageFactory implements SessionStorageFactoryInterface
{
    /** @param array<string, mixed> $options */
    public function __construct(
        #[AutowireDecorated]
        private SessionStorageFactoryInterface $factory,
        #[Autowire('%session.storage.options%')]
        private array $options,
    ) {
    }

    public function createStorage(?Request $request): SessionStorageInterface
    {
        $storage = $this->factory->createStorage($request);
        if ('auto' === ($this->options['cookie_secure'] ?? null) && $storage instanceof NativeSessionStorage) {
            // NativeSessionStorageFactory sets true on HTTPS but does not undo it for
            // the next HTTP request in a persistent worker. Resolve both directions.
            $storage->setOptions(['cookie_secure' => $request?->isSecure() ?? false]);
        }

        return $storage;
    }
}
