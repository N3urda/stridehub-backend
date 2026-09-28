<?php

declare(strict_types=1);

namespace App\Tests\Console\Notification;

use App\Console\Notification\TrainingReminderConsoleCommand;
use App\Domain\Integration\Notification\Shoutrrr\Shoutrrr;
use App\Domain\Settings\DaemonSettings;
use App\Domain\Training\Notification\TrainingReminderService;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Daemon\Cron\CronActionId;
use App\Infrastructure\Time\Clock\Clock;
use App\Tests\ContainerTestCase;
use App\Tests\Domain\Integration\Notification\Shoutrrr\SpyShoutrrr;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;

final class TrainingReminderConsoleCommandTest extends ContainerTestCase
{
    public function testDryRunPrintsPreviewWithoutSendingOrRecordingDelivery(): void
    {
        $this->seed();
        $tester = $this->tester();
        self::assertSame(Command::SUCCESS, $tester->execute(['--dry-run' => true]));
        $output = json_decode($tester->getDisplay(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($output['dryRun']);
        self::assertCount(1, $output['previews']);
        self::assertSame([], $this->repository()->list('deliveries'));
        /** @var SpyShoutrrr $sender */
        $sender = self::getContainer()->get(Shoutrrr::class);
        self::assertSame([], $sender->getNotifications());
    }

    public function testOptOutIsDefaultAndCronRequiresOptIn(): void
    {
        $tester = $this->tester();
        self::assertSame(Command::SUCCESS, $tester->execute([]));
        self::assertSame('disabled', json_decode($tester->getDisplay(), true)['status']);
        self::assertSame('*/5 * * * *', CronActionId::RUN_TRAINING_REMINDERS->defaultCronExpression());
        self::assertSame('bin/console app:training:remind', CronActionId::RUN_TRAINING_REMINDERS->command());
        self::assertSame([], iterator_to_array(DaemonSettings::fromArray(null)->getConfiguredCronActions()));
    }

    public function testRepeatedCommandDoesNotSendDuplicateNotifications(): void
    {
        $this->seed();
        $tester = $this->tester();
        self::assertSame(Command::SUCCESS, $tester->execute([]));
        $first = json_decode($tester->getDisplay(), true, 512, JSON_THROW_ON_ERROR);
        self::assertGreaterThan(0, $first['sent']);
        self::assertSame(Command::SUCCESS, $tester->execute([]));
        self::assertSame(0, json_decode($tester->getDisplay(), true, 512, JSON_THROW_ON_ERROR)['sent']);
    }

    private function tester(): CommandTester
    {
        return new CommandTester(new TrainingReminderConsoleCommand(self::getContainer()->get(TrainingReminderService::class)));
    }

    private function repository(): TrainingRepository
    {
        return self::getContainer()->get(TrainingRepository::class);
    }

    private function seed(): void
    {
        $now = self::getContainer()->get(Clock::class)->getCurrentDateTimeImmutable();
        $this->repository()->save('profile', 'default', [...TrainingInput::defaults('profile'), 'notificationsEnabled' => true], 0);
        $this->repository()->save('sessions', 'morning-run', [...TrainingInput::defaults('sessions'), 'title' => '晨跑', 'startAt' => $now->modify('+45 minutes')->format(DATE_ATOM), 'durationMinutes' => 60, 'type' => 'easy'], 0);
    }
}
