<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training\Notification;

use App\Domain\Integration\Notification\Shoutrrr\ShoutrrrUrl;
use App\Domain\Training\Notification\ReminderDelivery;
use App\Domain\Training\TrainingRepository;
use Doctrine\DBAL\DriverManager;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\Lock\Store\InMemoryStore;

final class ReminderDeliveryTest extends TestCase
{
    private TrainingRepository $repository;
    private ReminderTestClock $clock;
    private ReminderTestSender $sender;
    private ReminderDelivery $delivery;
    private array $channels;

    protected function setUp(): void
    {
        $connection = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $connection->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY (kind,id), UNIQUE (kind,naturalKey))');
        $this->clock = new ReminderTestClock('2026-10-04T05:35:00+08:00');
        $this->repository = new TrainingRepository($connection, $this->clock);
        $this->sender = new ReminderTestSender();
        $this->channels = [ShoutrrrUrl::fromString('ntfy://first.invalid/private-token'), ShoutrrrUrl::fromString('ntfy://second.invalid/other-secret')];
        $this->delivery = new ReminderDelivery($this->repository, $this->sender, $this->clock, new LockFactory(new InMemoryStore()));
    }

    public function testSuccessIsPerChannelAndNeverRepeated(): void
    {
        self::assertSame(['sent', 'sent'], $this->send());
        self::assertSame(['already_sent', 'already_sent'], $this->send());
        self::assertCount(2, $this->sender->calls);
        $records = $this->repository->list('deliveries');
        self::assertCount(2, $records);
        self::assertSame(['sent', 'sent'], array_column($records, 'status'));
        self::assertStringNotContainsString('private-token', json_encode($records));
        self::assertStringNotContainsString('ntfy://', json_encode($records));
    }

    public function testPartialFailureRetriesFailedChannelOnlyAndKeepsErrorsSanitized(): void
    {
        $this->sender->failures[(string) $this->channels[1]] = 1;
        self::assertSame(['sent', 'failed'], $this->send());
        self::assertStringNotContainsString('other-secret', json_encode($this->repository->list('deliveries')));
        self::assertContains('transport_failed', array_column($this->repository->list('deliveries'), 'errorCode'));
        self::assertSame(['already_sent', 'retry_wait'], $this->send());
        $this->clock->now = '2026-10-04T05:40:00+08:00';
        self::assertSame(['already_sent', 'sent'], $this->send());
        self::assertCount(3, $this->sender->calls);
        self::assertSame(1, count(array_filter($this->sender->calls, fn (string $url): bool => $url === (string) $this->channels[0])));
        self::assertStringNotContainsString('other-secret', json_encode($this->repository->list('deliveries')));
    }

    public function testDryRunNeverSendsOrChangesDeliveriesAndMissingChannelsNeverClaimsSuccess(): void
    {
        self::assertSame(['preview', 'preview'], $this->send(dryRun: true));
        self::assertSame([], $this->repository->list('deliveries'));
        self::assertSame([], $this->sender->calls);
        self::assertSame(['no_channels'], $this->send(channels: []));
        self::assertSame([], $this->repository->list('deliveries'));
    }

    public function testExpiredEventsAreNotSentOrClaimed(): void
    {
        $this->clock->now = '2026-10-04T06:30:00+08:00';
        self::assertSame(['expired', 'expired'], $this->send());
        self::assertSame([], $this->repository->list('deliveries'));
    }

    public function testRetryIsBoundedEvenWhenChannelKeepsFailing(): void
    {
        $this->sender->failures[(string) $this->channels[0]] = 20;
        for ($attempt = 0; $attempt < 3; ++$attempt) {
            $this->clock->now = '2026-10-04T05:'.(35 + $attempt * 5).':00+08:00';
            self::assertSame(['failed'], $this->send(channels: [$this->channels[0]]));
        }
        $this->clock->now = '2026-10-04T05:55:00+08:00';
        self::assertSame(['exhausted'], $this->send(channels: [$this->channels[0]]));
        self::assertCount(3, $this->sender->calls);
    }

    public function testAConcurrentInvocationCannotSendTheSameDelivery(): void
    {
        $nested = null;
        $this->sender->duringSend = function () use (&$nested): void {
            $nested = $this->send(channels: [$this->channels[0]]);
        };
        self::assertSame(['sent'], $this->send(channels: [$this->channels[0]]));
        self::assertSame(['busy'], $nested);
        self::assertCount(1, $this->sender->calls);
    }

    public function testActiveClaimsAreSkippedAndExpiredClaimsCanRecover(): void
    {
        $record = $this->claim('2026-10-04T05:37:00+08:00');
        self::assertSame(['busy'], $this->send(channels: [$this->channels[0]]));
        $this->clock->now = '2026-10-04T05:40:00+08:00';
        self::assertSame(['sent'], $this->send(channels: [$this->channels[0]]));
        self::assertSame(2, $this->repository->find('deliveries', $record['id'])['attempts']);
    }

    public function testWeatherChangeNeedsDeliveredBaselineAndOnlyNotifiesOncePerChannel(): void
    {
        self::assertSame(['no_baseline', 'no_baseline'], $this->send(type: 'weather_change', fingerprint: 'changed'));
        $this->send(fingerprint: 'initial');
        self::assertSame(['unchanged', 'unchanged'], $this->send(type: 'weather_change', fingerprint: 'initial'));
        self::assertSame(['sent', 'sent'], $this->send(type: 'weather_change', fingerprint: 'changed'));
        self::assertSame(['already_sent', 'already_sent'], $this->send(type: 'weather_change', fingerprint: 'changed-again'));
        self::assertCount(4, $this->sender->calls);
    }

    public function testDuplicateConfiguredChannelIsSentOnlyOnce(): void
    {
        self::assertSame(['sent'], $this->send(channels: [$this->channels[0], $this->channels[0]]));
        self::assertCount(1, $this->sender->calls);
    }

    private function send(string $type = 'pre_run', string $fingerprint = 'weather', bool $dryRun = false, ?array $channels = null): array
    {
        return array_column($this->delivery->deliver($this->event($type), $channels ?? $this->channels, '训练提醒', '请确认天气。', $fingerprint, $dryRun), 'status');
    }

    private function event(string $type = 'pre_run'): array
    {
        return ['type' => $type, 'sessionId' => 'run-one', 'occurrence' => '2026-10-04T06:30:00+08:00', 'scheduledAt' => '2026-10-04T05:30:00+08:00', 'expiresAt' => '2026-10-04T06:30:00+08:00'];
    }

    private function claim(string $until): array
    {
        $channelId = hash('sha256', (string) $this->channels[0]);
        $id = ReminderDelivery::key($this->event(), $channelId);

        return $this->repository->save('deliveries', $id, [...$this->event(), 'channelId' => $channelId, 'status' => 'claimed', 'attempts' => 1, 'claimedUntil' => $until, 'fingerprint' => 'weather'], 0);
    }
}
