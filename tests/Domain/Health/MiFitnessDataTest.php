<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\MiFitnessClient;
use App\Domain\Health\MiFitnessCredentials;
use App\Domain\Health\MiFitnessDataService;
use App\Domain\Health\MiFitnessSyncError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use App\Tests\Domain\Training\Notification\ReminderTestClock;
use Doctrine\DBAL\DriverManager;
use GuzzleHttp\Client;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Psr7\Response;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\Lock\Store\InMemoryStore;

final class MiFitnessDataTest extends TestCase
{
    public function testAllTypesCorrectionsOverlapMissingValuesAndFailures(): void
    {
        $directory = sys_get_temp_dir().'/mi-data-test-'.bin2hex(random_bytes(8));
        mkdir($directory, 0700);
        $file = $directory.'/credentials.json';
        $_ENV['MI_FITNESS_CREDENTIALS_FILE'] = $file;
        file_put_contents($file, json_encode(['baseUrl' => 'http://127.0.0.1:8321', 'apiKey' => 'fixture-key', 'userId' => 'fixture']));
        chmod($file, 0600);
        try {
            $clock = new ReminderTestClock('2026-11-06T00:00:00Z');
            $db = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
            $db->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
            $repo = new TrainingRepository($db, $clock);
            $queue = [];
            $row = static fn (string $id, array $values): array => ['sourceId' => hash('sha256', $id), 'sourceDeviceKey' => hash('sha256', $id), 'recordedAt' => '2026-11-05T20:00:00+08:00', 'values' => $values];
            foreach ([0, 1, 2, 3] as $run) {
                foreach (MiFitnessDataService::TYPES as $type) {
                    if (3 === $run && 'heart_rate' === $type) {
                        $queue[] = new Response(502);
                        continue;
                    }
                    $rows = 'steps' === $type ? [$row('phone', ['steps' => 10, 'distance' => 5]), $row('band', ['steps' => $run >= 2 ? 25 : 20, 'distance' => 10])] : ('calories' === $type ? [$row('cal', ['calories' => 0])] : ('heart_rate' === $type ? [$row('hr', ['hr' => 65])] : []));
                    if (3 === $run && 'steps' === $type) {
                        $rows = [];
                    }
                    foreach ([['user_id' => 'fixture'], ['status' => 'ok', 'kind' => $type, 'count' => count($rows), 'data' => $rows], ['user_id' => 'fixture']] as $body) {
                        $queue[] = new Response(200, [], json_encode($body));
                    }
                }
            }
            $service = new MiFitnessDataService($repo, new MiFitnessCredentials(KernelProjectDir::fromString($directory)), new MiFitnessClient(new Client(['handler' => HandlerStack::create(new MockHandler($queue))])), $clock, new LockFactory(new InMemoryStore()));
            self::assertSame('success', $service->sync()['state']);
            self::assertSame(2, $service->status()['sources']['steps']['imported']);
            self::assertSame(2, $service->sync()['sources']['steps']['unchanged']);
            self::assertSame(1, $service->sync()['sources']['steps']['updated']);
            $summary = $service->overview('2026-11-05', '2026-11-06');
            self::assertSame(25.0, $summary['daily'][0]['steps']);
            self::assertSame(10.0, $summary['daily'][0]['distanceMeters']);
            self::assertNull($summary['daily'][0]['stepCalories']);
            self::assertSame(0.0, $summary['daily'][0]['activityCalories']);
            self::assertSame(2, $summary['counts']['steps']);
            self::assertSame(0, $summary['counts']['stress']);
            self::assertSame(1, $service->records('steps', limit: 1)['nextOffset']);
            self::assertNull($service->records('steps', offset: 1, limit: 1)['nextOffset']);
            self::assertSame('partial', $service->sync()['state']);
            self::assertSame('no_data', $service->status()['sources']['steps']['state']);
            self::assertSame('error', $service->status()['sources']['heart_rate']['state']);
            self::assertSame(2, $service->records('steps')['total']);
            self::assertSame(1, $service->records('heart_rate')['total']);
            $repo->save('mi-fitness-account', 'default', ['accountKey' => hash('sha256', 'other')], 1);
            $this->expectException(MiFitnessSyncError::class);
            $service->sync();
        } finally {
            unset($_ENV['MI_FITNESS_CREDENTIALS_FILE']);
            unlink($file);
            rmdir($directory);
        }
    }
}
