<?php

declare(strict_types=1);

namespace App\Console\Health;

use App\Domain\Health\ZeppWorkoutService;
use App\Infrastructure\Doctrine\Migrations\RequiresUpToDateDatabaseSchema;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;

#[RequiresUpToDateDatabaseSchema]
#[AsCommand(name: SyncZeppWorkoutsConsoleCommand::NAME, description: 'Store Zepp workout summaries, source details and measured tracks without logging measurements')]
final class SyncZeppWorkoutsConsoleCommand extends Command
{
    public const string NAME = 'app:health:zepp:workouts:sync';

    public function __construct(private readonly ZeppWorkoutService $zepp)
    {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('days', null, InputOption::VALUE_REQUIRED, 'Recent calendar days, 1 to 90', '30');
        $this->addOption('if-configured', null, InputOption::VALUE_NONE, 'Skip unconfigured scheduled connections');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        if ($input->getOption('if-configured') && !$this->zepp->status()['configured']) {
            return Command::SUCCESS;
        }
        $days = filter_var($input->getOption('days'), FILTER_VALIDATE_INT);
        if (false === $days || $days < 1 || $days > 90) {
            return Command::INVALID;
        }
        $status = $this->zepp->sync($days);
        // Counts and source availability only; no account IDs or health readings.
        $output->writeln(json_encode($status, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE), OutputInterface::OUTPUT_RAW);

        return in_array($status['state'], ['success', 'partial', 'no_data'], true) ? Command::SUCCESS : Command::FAILURE;
    }
}
