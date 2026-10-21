<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Training\TrainingRepository;
use App\Domain\Health\WeightCollection;
use App\Domain\Health\WeightRecordService;
use App\Tests\Controller\Admin\AdminWebTestCase;

final class DailyCollectionApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testCaptureConflictAndDeletedMarkerRemainVisible(): void
    {
        $this->client->loginUser($this->adminUser());
        $collection = $this->getContainer()->get(WeightCollection::class);
        $id = 'a0000000-0000-4000-8000-000000000001';
        $collection->capture('demo-device', $id, ['measurementId' => $id, 'measuredAt' => '2026-01-10T08:00:00+08:00', 'localDate' => '2026-01-10', 'weightKg' => 72, 'profile' => new \stdClass(), 'derived' => (object) ['algorithmVersion' => 'demo', 'values' => new \stdClass()]]);
        $weights = $this->getContainer()->get(WeightRecordService::class);
        $weights->save('2026-01-10', ['version' => $weights->list()['version'], 'weightKg' => 71, 'source' => 'manual']);
        $url = '/admin/health/api/collected/weight?from=2026-01-10&to=2026-01-10';
        $this->client->request('GET', $url);
        self::assertResponseIsSuccessful();
        $row = json_decode($this->client->getResponse()->getContent(), true)['records'][0];
        self::assertSame('manual_conflict', $row['projectionStatus']);
        self::assertNull($row['capture']['fatPercent']);
        $collection->delete('demo-device', $id);
        $this->client->request('GET', $url);
        self::assertTrue(json_decode($this->client->getResponse()->getContent(), true)['records'][0]['deleted']);
        self::assertEquals(71, $weights->list('2026-01-10', '2026-01-10')['records'][0]['weightKg']);
    }

    public function testAuthenticationBoundsAndSummaryPagination(): void
    {
        $url = '/admin/health/api/collected/zepp?from=2026-01-01&to=2026-01-31';
        $this->client->request('GET', $url);
        self::assertResponseRedirects();
        $this->client->loginUser($this->adminUser());
        $repo = $this->getContainer()->get(TrainingRepository::class);
        foreach (['one', 'two'] as $id) {
            $repo->save('zepp-raw', $id, ['kind' => 'hrv_sdnn', 'sourceDate' => '2026-01-10', 'payload' => ['deviceId' => 'private-device', 'value' => ['samples' => [['sdnn' => 40]]]]], 0);
        }
        $this->client->request('GET', $url.'&limit=1');
        self::assertResponseIsSuccessful();
        $page = json_decode($this->client->getResponse()->getContent(), true);
        self::assertSame(2, $page['total']);
        self::assertCount(1, $page['records']);
        self::assertStringNotContainsString('private-device', $this->client->getResponse()->getContent());
        $this->client->request('GET', $url.'&offset=1&limit=1');
        self::assertResponseStatusCodeSame(409);
        $this->client->request('GET', $url.'&offset=1&limit=1&snapshot='.$page['snapshot']);
        self::assertResponseIsSuccessful();
        $this->client->request('GET', $url.'&limit=101');
        self::assertResponseStatusCodeSame(422);
        $this->client->request('GET', '/admin/health/api/collected/weight?from=2024-01-01&to=2026-01-01');
        self::assertResponseStatusCodeSame(422);
    }
}
