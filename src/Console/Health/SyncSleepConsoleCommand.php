<?php

declare(strict_types=1);

namespace App\Console\Health;

use App\Domain\Health\SleepService;
use App\Infrastructure\Doctrine\Migrations\RequiresUpToDateDatabaseSchema;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;

#[RequiresUpToDateDatabaseSchema]
#[AsCommand(name: SyncSleepConsoleCommand::NAME, description: 'Synchronize Zepp sleep summaries without logging health measurements or credentials')]
final class SyncSleepConsoleCommand extends Command
{
    public const string NAME = 'app:health:sleep:sync';

    public function __construct(private readonly SleepService $sleep)
    {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('days', null, InputOption::VALUE_REQUIRED, 'Number of recent calendar days, 1 to 90', '30');
        $this->addOption('if-configured', null, InputOption::VALUE_NONE, 'Skip unconfigured accounts during scheduled runs');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        if ($input->getOption('if-configured') && !$this->sleep->status()['configured']) {
            return Command::SUCCESS;
        }
        $days = filter_var($input->getOption('days'), FILTER_VALIDATE_INT);
        if (false === $days || $days < 1 || $days > 90) {
            $output->writeln('days must be an integer from 1 to 90.');

            return Command::INVALID;
        }
        $result = $this->sleep->sync($days);
        $output->writeln(json_encode($result, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE), OutputInterface::OUTPUT_RAW);

        return in_array($result['state'], ['success', 'no_data'], true) ? Command::SUCCESS : Command::FAILURE;
    }
}
