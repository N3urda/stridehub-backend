<?php

declare(strict_types=1);

namespace App\Tests\Domain\Training;

use Doctrine\DBAL\DriverManager;
use Doctrine\DBAL\Schema\Schema;
use PHPUnit\Framework\TestCase;
use Psr\Log\NullLogger;

final class TrainingActivityLinkMigrationTest extends TestCase
{
    public function testLegacyAndMultipleLinksSurviveMigrationDownAndReapply(): void
    {
        $path = dirname(__DIR__, 3).'/migrations/Version20260928000000.php';
        self::assertFileExists($path);
        require_once $path;
        $db = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'memory' => true]);
        $db->executeStatement('CREATE TABLE TrainingRecord (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL, updatedAt TEXT NOT NULL, naturalKey TEXT DEFAULT NULL, PRIMARY KEY(kind,id), UNIQUE(kind,naturalKey))');
        foreach (['legacy' => ['activityId' => 'a'], 'split' => ['activityId' => 'b', 'activityIds' => ['b', 'c']], 'empty' => ['activityId' => null]] as $id => $links) {
            $db->insert('TrainingRecord', ['kind' => 'sessions', 'id' => $id, 'payload' => json_encode([...$links, 'notes' => '保留原备注'], JSON_THROW_ON_ERROR), 'version' => 8, 'updatedAt' => '2026-09-27T00:00:00Z', 'naturalKey' => $links['activityId']]);
        }
        foreach (['up', 'down', 'up'] as $direction) {
            $migration = new \DoctrineMigrations\Version20260928000000($db, new NullLogger());
            $migration->{$direction}(new Schema());
            foreach ($migration->getSql() as $query) {
                $db->executeStatement($query->getStatement());
            }
            if ('up' === $direction) {
                self::assertSame(['a' => 'legacy', 'b' => 'split', 'c' => 'split'], $db->fetchAllKeyValue('SELECT activityId, sessionId FROM TrainingActivityLink ORDER BY activityId'));
                $legacy = json_decode($db->fetchOne("SELECT payload FROM TrainingRecord WHERE id='legacy'"), true, 512, JSON_THROW_ON_ERROR);
                self::assertSame(['a'], $legacy['activityIds']);
                self::assertSame('保留原备注', $legacy['notes']);
            }
            self::assertSame([8, 8, 8], array_map(intval(...), $db->fetchFirstColumn('SELECT version FROM TrainingRecord ORDER BY id')));
        }
        $db->close();
    }
}
