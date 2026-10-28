<?php

declare(strict_types=1);

namespace App\Console\Mobile;

use App\Domain\Mobile\MobileWorkflowService;
use App\Infrastructure\Doctrine\Migrations\RequiresUpToDateDatabaseSchema;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;

#[RequiresUpToDateDatabaseSchema]
#[AsCommand(name: RunMobileWorkflowsConsoleCommand::NAME, description: 'Process durable mobile sync jobs; safe to schedule every minute')]
final class RunMobileWorkflowsConsoleCommand extends Command
{
    public const string NAME = 'app:mobile:workflows:run';

    public function __construct(private readonly MobileWorkflowService $workflows)
    {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('limit', null, InputOption::VALUE_REQUIRED, 'Maximum jobs per invocation', '10');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $limit = filter_var($input->getOption('limit'), FILTER_VALIDATE_INT);
        if (false === $limit || $limit < 1 || $limit > 100) {
            return Command::INVALID;
        }
        $output->writeln(sprintf('Processed %d mobile sync job(s).', $this->workflows->runQueued($limit)));

        return Command::SUCCESS;
    }
}
