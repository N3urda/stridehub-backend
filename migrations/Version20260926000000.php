<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260926000000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'StrideHub versioned training resources and notification delivery records';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE TrainingRecord (kind VARCHAR(32) NOT NULL, id VARCHAR(80) NOT NULL, payload CLOB NOT NULL, version INTEGER NOT NULL, updatedAt VARCHAR(40) NOT NULL, naturalKey VARCHAR(255) DEFAULT NULL, PRIMARY KEY(kind, id))');
        $this->addSql('CREATE UNIQUE INDEX TrainingRecord_naturalKey ON TrainingRecord (kind, naturalKey)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP TABLE TrainingRecord');
    }
}
