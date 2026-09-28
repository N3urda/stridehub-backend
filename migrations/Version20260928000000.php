<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260928000000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Exclusive multiple-activity links for training sessions, preserving legacy single links';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE TrainingActivityLink (activityId VARCHAR(255) NOT NULL, sessionId VARCHAR(80) NOT NULL, PRIMARY KEY(activityId))');
        $this->addSql('CREATE INDEX TrainingActivityLink_sessionId ON TrainingActivityLink (sessionId)');
        $this->addSql(<<<'SQL'
            UPDATE TrainingRecord SET payload = json_set(payload, '$.activityIds', json(
                CASE WHEN json_type(payload, '$.activityIds') = 'array' THEN json_extract(payload, '$.activityIds')
                WHEN json_type(payload, '$.activityId') = 'text' THEN json_array(json_extract(payload, '$.activityId'))
                ELSE '[]' END
            )) WHERE kind = 'sessions'
            SQL);
        $this->addSql("UPDATE TrainingRecord SET payload = json_set(payload, '$.activityId', json_extract(payload, '$.activityIds[0]')) WHERE kind = 'sessions'");
        $this->addSql(<<<'SQL'
            INSERT INTO TrainingActivityLink (activityId, sessionId)
            SELECT link.value, record.id FROM TrainingRecord record, json_each(record.payload, '$.activityIds') link
            WHERE record.kind = 'sessions'
            SQL);
    }

    public function down(Schema $schema): void
    {
        // Retain all saved activityIds in the payload so rollback/reapply loses no links.
        $this->addSql('DROP TABLE TrainingActivityLink');
    }
}
