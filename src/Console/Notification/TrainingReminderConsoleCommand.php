<?php

declare(strict_types=1);

namespace App\Console\Notification;

use App\Domain\Training\Notification\TrainingReminderService;
use App\Infrastructure\Doctrine\Migrations\RequiresUpToDateDatabaseSchema;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;

#[RequiresUpToDateDatabaseSchema]
#[AsCommand(name: TrainingReminderConsoleCommand::NAME, description: 'Send opted-in running reminders through configured notification channels')]
final class TrainingReminderConsoleCommand extends Command
{
    public const string NAME = 'app:training:remind';

    public function __construct(private readonly TrainingReminderService $reminders)
    {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('dry-run', null, InputOption::VALUE_NONE, 'Preview due reminders without sending or recording deliveries');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $result = $this->reminders->run((bool) $input->getOption('dry-run'));
        $output->writeln(json_encode($result, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT), OutputInterface::OUTPUT_RAW);

        return $result['failed'] > 0 ? Command::FAILURE : Command::SUCCESS;
    }
}
