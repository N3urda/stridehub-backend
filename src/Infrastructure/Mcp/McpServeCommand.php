<?php

declare(strict_types=1);

namespace App\Infrastructure\Mcp;

use Mcp\Server\Transport\StdioTransport;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;

#[AsCommand(name: 'app:mcp:serve', description: 'Serve the StrideHub MCP tools over stdio (stdout is protocol only).')]
final class McpServeCommand extends Command
{
    public function __construct(private readonly McpServerFactory $factory)
    {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        return $this->factory->create()->run(new StdioTransport());
    }
}
