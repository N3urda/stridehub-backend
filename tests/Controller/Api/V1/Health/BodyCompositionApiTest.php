<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Health;

use App\Domain\Health\BodyCompositionReadModel;
use App\Domain\Health\BodyHeightSelection;
use App\Domain\Health\HealthContextService;
use App\Domain\Health\WeightCollection;
use App\Domain\Training\TrainingRepository;
use App\Tests\Controller\Admin\AdminWebTestCase;

final class BodyCompositionApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testReportHeightSelectionIsVersionedAuditedAndNeverChangesCapture(): void
    {
        $this->client->loginUser($this->adminUser());
        $this->seed();
        $repo = $this->getContainer()->get(TrainingRepository::class);
        $before = json_encode($repo->list('collection-weight'));
        $this->client->request('GET', '/admin/health/api/collection/csrf');
        $csrf = $this->json()['csrfToken'];
        $this->client->request('GET', '/admin/health/api/body-composition/height-selection');
        $height = $this->json();
        self::assertSame('capture', $height['status']);
        $candidate = $height['candidates'][0];
        $payload = ['version' => 0, ...array_intersect_key($candidate, array_flip(['reportId', 'findingIndex', 'findingFingerprint']))];
        $headers = ['CONTENT_TYPE' => 'application/json', 'HTTP_X_CSRF_TOKEN' => $csrf];
        $this->client->request('PUT', '/admin/health/api/body-composition/height-selection', server: $headers, content: json_encode($payload));
        self::assertResponseIsSuccessful();
        self::assertSame(1, $this->json()['version']);
        self::assertSame('report', $this->json()['status']);
        self::assertCount(1, $repo->list('body-height-selection-history'));
        $this->client->request('PUT', '/admin/health/api/body-composition/height-selection', server: $headers, content: json_encode($payload));
        self::assertResponseStatusCodeSame(409);
        $this->client->request('GET', '/admin/health/api/body-composition?from=2026-01-10&to=2026-01-10');
        self::assertResponseIsSuccessful();
        self::assertSame(1, $this->json()['total']);
        self::assertEquals(25.5, $this->json()['records'][0]['composition']['reportBmi']['value']);
        self::assertEquals(170, $this->json()['records'][0]['capture']['profile']['heightCm']);
        $snapshot = $this->json()['snapshot'];
        $recordId = $this->json()['records'][0]['id'];
        $this->client->request('GET', '/admin/health/api/body-composition?from=2026-01-10&to=2026-01-10&selected='.urlencode($recordId));
        self::assertSame($recordId, $this->json()['selectedRecord']['id']);
        self::assertSame($before, json_encode($repo->list('collection-weight')));
        $this->client->request('GET', '/admin/health/api/workspace?from=2026-01-10&to=2026-01-10');
        self::assertResponseIsSuccessful();
        self::assertEquals(23.1, $this->json()['bodyComposition']['latest']['bodyFatPercent']);
        self::assertSame([], $this->json()['measurements']);

        $context = $this->getContainer()->get(HealthContextService::class);
        $data = $context->get();
        $data['reports'][0]['findings'][0]['valueText'] = '172';
        $context->update(['version' => $data['version'], 'reports' => $data['reports']]);
        self::assertSame('stale', $this->getContainer()->get(BodyHeightSelection::class)->get()['status']);
        self::assertNull($this->getContainer()->get(BodyCompositionReadModel::class)->summary('2026-01-10', '2026-01-10')['latest']['reportBmi']);
        $this->client->request('GET', '/admin/health/api/body-composition?from=2026-01-10&to=2026-01-10&snapshot='.$snapshot);
        self::assertResponseStatusCodeSame(409);
        $payload['version'] = 1;
        $this->client->request('PUT', '/admin/health/api/body-composition/height-selection', server: $headers, content: json_encode($payload));
        self::assertResponseStatusCodeSame(409);
        $this->client->request('PUT', '/admin/health/api/body-composition/height-selection', server: $headers, content: json_encode(['version' => 1, 'reportId' => null]));
        self::assertResponseIsSuccessful();
        self::assertSame('capture', $this->json()['status']);
        self::assertCount(2, $repo->list('body-height-selection-history'));
        self::assertSame($before, json_encode($repo->list('collection-weight')));
    }

    public function testAuthCsrfBoundsAndDeletedCaptureExclusion(): void
    {
        $url = '/admin/health/api/body-composition?from=2026-01-10&to=2026-01-10';
        $this->client->request('GET', $url);
        self::assertResponseRedirects();
        $this->client->loginUser($this->adminUser());
        $id = $this->seed();
        $this->client->request('PUT', '/admin/health/api/body-composition/height-selection', server: ['CONTENT_TYPE' => 'application/json'], content: '{"version":0,"reportId":null}');
        self::assertResponseStatusCodeSame(403);
        $this->client->request('GET', $url.'&offset=1');
        self::assertResponseStatusCodeSame(409);
        $this->client->request('GET', $url.'&limit=101');
        self::assertResponseStatusCodeSame(422);
        $this->client->request('GET', '/admin/health/api/body-composition?from=2024-01-01&to=2026-01-01');
        self::assertResponseStatusCodeSame(422);
        $this->getContainer()->get(WeightCollection::class)->delete('demo', $id);
        $this->client->request('GET', $url);
        self::assertResponseIsSuccessful();
        self::assertSame(0, $this->json()['total']);
        self::assertCount(1, $this->getContainer()->get(TrainingRepository::class)->list('collection-weight'));
        self::assertNull($this->getContainer()->get(BodyCompositionReadModel::class)->summary('2026-01-10', '2026-01-10')['latest']);
    }

    private function seed(): string
    {
        $id = 'a0000000-0000-4000-8000-000000000001';
        $this->getContainer()->get(WeightCollection::class)->capture('demo', $id, ['measurementId' => $id, 'measuredAt' => '2026-01-10T08:00:00+08:00', 'localDate' => '2026-01-10', 'weightKg' => 75, 'fatPercent' => 23.1, 'bmi' => 25.95,
            'profile' => (object) ['heightCm' => 170, 'birthDate' => '1990-01-01'], 'derived' => (object) ['algorithmVersion' => 'demo', 'values' => (object) ['BODY_FAT' => 23.1]]]);
        $this->getContainer()->get(HealthContextService::class)->update(['version' => 0, 'reports' => [['id' => 'demo-height', 'title' => 'DEMO report', 'reportDate' => '2026-01-01', 'sourceLabel' => 'DEMO', 'needsReview' => false,
            'findings' => [['name' => '身高', 'valueText' => '171.5', 'unit' => 'cm']]]]]);

        return $id;
    }

    /** @return array<string,mixed> */
    private function json(): array
    {
        return json_decode($this->client->getResponse()->getContent(), true, flags: JSON_THROW_ON_ERROR);
    }
}
