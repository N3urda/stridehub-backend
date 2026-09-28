<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training;

use App\Domain\Training\TrainingActivities;
use Doctrine\DBAL\DriverManager;
use PHPUnit\Framework\TestCase;

final class TrainingActivitiesTest extends TestCase
{
    public function testOlderActivityCanBeMatchedAfterMoreThanFiveHundredNewRuns(): void
    {
        $db = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $db->executeStatement('CREATE TABLE Activity (activityId TEXT, name TEXT, startDateTime TEXT, distance INTEGER, movingTimeInSeconds INTEGER, averageHeartRate INTEGER, sportType TEXT, markedForDeletion INTEGER)');
        $base = ['name' => '跑步', 'startDateTime' => '2020-01-01 06:30:00', 'distance' => 10000, 'movingTimeInSeconds' => 3600, 'sportType' => 'Run', 'markedForDeletion' => 0];
        $db->insert('Activity', ['activityId' => 'old', ...$base]);
        for ($i = 0; $i < 501; ++$i) {
            $db->insert('Activity', [...$base, 'activityId' => 'new-'.$i, 'startDateTime' => '2026-01-01 06:30:00']);
        }
        $repo = new TrainingActivities($db, new \App\Infrastructure\ValueObject\Time\SerializableTimezone('Asia/Shanghai'));
        self::assertCount(500, $repo->list());
        $matches = $repo->near(new \DateTimeImmutable('2019-12-31T22:30:00Z'));
        self::assertSame(['old'], array_column($matches, 'id'));
        self::assertSame('2020-01-01T06:30:00+08:00', $matches[0]['startAt']);
        $db->close();
    }
}
