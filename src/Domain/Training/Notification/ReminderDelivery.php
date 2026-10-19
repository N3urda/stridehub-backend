<?php

declare(strict_types=1);

namespace App\Domain\Training\Notification;

use App\Domain\Integration\Notification\Shoutrrr\Shoutrrr;
use App\Domain\Integration\Notification\Shoutrrr\ShoutrrrUrl;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Time\Clock\Clock;
use Symfony\Component\Lock\LockFactory;

/** Successful channels are immutable; failures retry at most three times inside the event window. */
final readonly class ReminderDelivery
{
    public function __construct(private TrainingRepository $repository, private Shoutrrr $shoutrrr, private Clock $clock, private LockFactory $lockFactory)
    {
    }

    /** @param array<string, string> $event */
    public static function key(array $event, string $channelId): string
    {
        return hash('sha256', implode('|', [$event['type'], $event['sessionId'], $event['occurrence'], $channelId]));
    }

    /**
     * @param array<string, string> $event
     * @param iterable<ShoutrrrUrl> $channels
     *
     * @return list<array<string, mixed>>
     */
    public function deliver(array $event, iterable $channels, string $title, string $message, string $fingerprint, bool $dryRun = false): array
    {
        $results = [];
        $seen = [];
        foreach ($channels as $channel) {
            $channelId = hash('sha256', (string) $channel);
            if (isset($seen[$channelId])) {
                continue;
            }
            $seen[$channelId] = true;
            $id = self::key($event, $channelId);
            $status = $this->sendChannel($event, $id, $channelId, $channel, $title, $message, $fingerprint, $dryRun);
            $results[] = ['channelId' => $channelId, 'status' => $status];
        }

        return $results ?: [['channelId' => null, 'status' => 'no_channels']];
    }

    /** @param array<string, string> $event */
    private function sendChannel(array $event, string $id, string $channelId, ShoutrrrUrl $channel, string $title, string $message, string $fingerprint, bool $dryRun): string
    {
        if ($dryRun) {
            return $this->eligibility($event, $id, $channelId, $fingerprint) ?? 'preview';
        }
        // Keep a shared lock for the complete transport call, including after persisted claim expiry.
        $lock = $this->lockFactory->createLock('training_reminder_'.$id, 300.0);
        if (!$lock->acquire()) {
            return 'busy';
        }
        try {
            if (null !== $status = $this->eligibility($event, $id, $channelId, $fingerprint)) {
                return $status;
            }
            $current = $this->repository->find('deliveries', $id);
            $now = $this->clock->getCurrentDateTimeImmutable();
            try {
                $claim = $this->repository->save('deliveries', $id, [
                    ...$event, 'channelId' => $channelId, 'status' => 'claimed',
                    'attempts' => ($current['attempts'] ?? 0) + 1, 'fingerprint' => $fingerprint,
                    'claimedUntil' => $now->setTimestamp($now->getTimestamp() + 120)->format(DATE_ATOM),
                    'nextAttemptAt' => null, 'sentAt' => null, 'errorCode' => null,
                ], $current['version'] ?? 0);
            } catch (TrainingError $exception) {
                if (409 !== $exception->status) {
                    throw $exception;
                }

                return 'busy';
            }
            $status = 'sent';
            try {
                $this->shoutrrr->send($channel, $message, $title);
            } catch (\Throwable) {
                // Transport exceptions often contain authenticated URLs. Never persist or print them.
                $status = 'failed';
            }
            $finished = $this->clock->getCurrentDateTimeImmutable();
            $this->repository->save('deliveries', $id, [
                ...$claim, 'status' => $status, 'claimedUntil' => null,
                'sentAt' => 'sent' === $status ? $finished->format(DATE_ATOM) : null,
                'nextAttemptAt' => 'failed' === $status ? $finished->setTimestamp($finished->getTimestamp() + 300)->format(DATE_ATOM) : null,
                'errorCode' => 'failed' === $status ? 'transport_failed' : null,
            ], $claim['version']);

            return $status;
        } finally {
            $lock->release();
        }
    }

    /** @param array<string, string> $event */
    private function eligibility(array $event, string $id, string $channelId, string $fingerprint): ?string
    {
        $now = $this->clock->getCurrentDateTimeImmutable();
        if ($now >= new \DateTimeImmutable($event['expiresAt'])) {
            return 'expired';
        }
        if ($now < new \DateTimeImmutable($event['scheduledAt'])) {
            return 'not_due';
        }
        $current = $this->repository->find('deliveries', $id);
        if ('sent' === ($current['status'] ?? null)) {
            return 'already_sent';
        }
        if ('claimed' === ($current['status'] ?? null) && new \DateTimeImmutable($current['claimedUntil']) > $now) {
            return 'busy';
        }
        if (($current['attempts'] ?? 0) >= 3) {
            return 'exhausted';
        }
        if (null !== ($current['nextAttemptAt'] ?? null) && new \DateTimeImmutable($current['nextAttemptAt']) > $now) {
            return 'retry_wait';
        }
        if ('weather_change' === $event['type']) {
            $baseline = $this->repository->find('deliveries', self::key([...$event, 'type' => 'pre_run'], $channelId));
            if ('sent' !== ($baseline['status'] ?? null)) {
                return 'no_baseline';
            }
            if (($baseline['fingerprint'] ?? null) === $fingerprint) {
                return 'unchanged';
            }
        }

        return null;
    }
}
