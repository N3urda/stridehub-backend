<?php

declare(strict_types=1);

namespace App\Tests\Controller\Api\V1\Running;

use App\Domain\Activity\ActivityRepository;
use App\Domain\Activity\ActivityWithRawData;
use App\Domain\Activity\SportType\SportType;
use App\Domain\Api\Token;
use App\Infrastructure\Measurement\Length\Kilometer;
use App\Tests\Controller\Admin\AdminWebTestCase;
use App\Tests\Domain\Activity\ActivityBuilder;

final class RunningApiTest extends AdminWebTestCase
{
    protected function shouldSeedActivity(): bool
    {
        return false;
    }

    public function testCompressedSensorsSplitsAndLapsProduceMeasuredAnalysis(): void
    {
        $activity = ActivityBuilder::fromDefaults()->withSportType(SportType::RUN)->build();
        self::getContainer()->get(ActivityRepository::class)->add(ActivityWithRawData::fromState($activity, []));
        $db = self::getContainer()->get(\Doctrine\DBAL\Connection::class);
        $id = (string) $activity->getId();
        foreach (['time' => [0, 1, 10], 'heartrate' => [100, 160, 170], 'velocity_smooth' => [3, 3, 3]] as $type => $values) {
            $db->insert('ActivityStream', ['activityId' => $id, 'streamType' => $type, 'data' => (string) \App\Infrastructure\Serialization\Json::encodeAndCompress($values), 'dataSize' => count($values), 'createdOn' => '2026-01-01 00:00:00']);
        }
        foreach ([[1000, 300], [3000, 900], [1000, 270], [1000, 270]] as $i => [$distance, $seconds]) {
            $db->insert('ActivitySplit', ['activityId' => $id, 'unitSystem' => 'metric', 'splitNumber' => $i + 1, 'distance' => $distance, 'elapsedTimeInSeconds' => $seconds, 'movingTimeInSeconds' => $seconds, 'elevationDifference' => 0, 'averageSpeed' => 12, 'minAverageSpeed' => 10, 'maxAverageSpeed' => 14, 'paceZone' => 2, 'gapPaceInSecondsPerKm' => 295]);
        }
        $db->insert('ActivityLap', ['lapId' => 'lap-running-test', 'activityId' => $id, 'lapNumber' => 1, 'name' => '主训练', 'elapsedTimeInSeconds' => 1740, 'movingTimeInSeconds' => 1740, 'distance' => 6000, 'averageSpeed' => 3.45, 'minAverageSpeed' => 3, 'maxAverageSpeed' => 4, 'maxSpeed' => 4, 'elevationDifference' => 0, 'averageHeartRate' => null]);
        $this->client->loginUser($this->adminUser());
        $this->client->request('GET', '/admin/running/api/activities/'.$id);
        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true);
        self::assertEquals(154, $data['analysis']['metrics']['weightedHeartRate']);
        self::assertEquals(10, $data['analysis']['coverage']['analyzedSeconds']);
        self::assertCount(4, $data['splits']);
        self::assertEquals(295, $data['splits'][0]['gapPaceSecondsPerKm']);
        // The 3 km split crosses halfway: the two 3 km halves take 900 and 840 seconds.
        self::assertEquals(-6.67, $data['splitSummary']['secondHalfPaceChangePercent']);
        self::assertEquals(270, $data['splitSummary']['fastestFullKmPace']);
        self::assertEquals(5.05, $data['splitSummary']['paceVariationPercent']);
        self::assertCount(1, $data['laps']);
        self::assertEquals(6, $data['laps'][0]['distanceKm']);
        self::assertNull($data['laps'][0]['averageHeartRate']);
    }

    public function testAllRecordsContributeWhileListIsPaginatedAndSportsAreFiltered(): void
    {
        $activity = ActivityBuilder::fromDefaults()->withSportType(SportType::RUN)->withDistance(Kilometer::from(10))->withMovingTimeInSeconds(3600)->build();
        self::getContainer()->get(ActivityRepository::class)->add(ActivityWithRawData::fromState($activity, []));
        $db = self::getContainer()->get(\Doctrine\DBAL\Connection::class);
        $row = $db->fetchAssociative('SELECT * FROM Activity WHERE activityId = ?', [(string) $activity->getId()]);
        for ($i = 0; $i < 501; ++$i) {
            $db->insert('Activity', [...$row, 'activityId' => 'activity-run-'.$i]);
        }
        $db->insert('Activity', [...$row, 'activityId' => 'activity-ride', 'sportType' => 'Ride']);
        $db->insert('Activity', [...$row, 'activityId' => 'activity-deleted', 'markedForDeletion' => 1]);
        $data = self::getContainer()->get(\App\Domain\Running\RunningOverview::class)->build('2020-01-01', '2030-01-01', page: 11);
        self::assertSame(502, $data['summary']['count']);
        self::assertEquals(5020, $data['summary']['distanceKm']);
        self::assertEquals(360, $data['summary']['paceSecondsPerKm']);
        self::assertCount(2, $data['activities']['items']);
        self::assertSame(502, $data['activities']['total']);
        self::assertNull($data['summary']['averageHeartRate']);
        self::assertSame(0, self::getContainer()->get(\App\Domain\Running\RunningOverview::class)->build('2020-01-01', '2030-01-01', 'TrailRun')['summary']['count']);
    }

    public function testOverviewAndDetailsAreAuthenticatedAndOnlyUseRealRuns(): void
    {
        $token = (string) Token::generate();
        $_SERVER['DREEVE_API_KEY'] = $_ENV['DREEVE_API_KEY'] = $token;
        $this->client->request('GET', '/api/v1/running/overview');
        self::assertResponseStatusCodeSame(401);
        $server = ['HTTP_AUTHORIZATION' => 'Bearer '.$token];
        $this->client->request('GET', '/api/v1/running/overview?from=2020-01-01&to=2030-01-01', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame(0, json_decode($this->client->getResponse()->getContent(), true)['summary']['count']);
        $activity = ActivityBuilder::fromDefaults()->withSportType(SportType::RUN)->withDistance(Kilometer::from(10))->withMovingTimeInSeconds(3600)->build();
        self::getContainer()->get(ActivityRepository::class)->add(ActivityWithRawData::fromState($activity, []));
        $this->client->request('GET', '/api/v1/running/overview?from=2020-01-01&to=2030-01-01', server: $server);
        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true);
        self::assertSame(1, $data['summary']['count']);
        self::assertEquals(360, $data['summary']['paceSecondsPerKm']);
        $this->client->request('GET', '/api/v1/running/activities/'.$activity->getId(), server: $server);
        self::assertResponseIsSuccessful();
        $detail = json_decode($this->client->getResponse()->getContent(), true);
        self::assertSame([], $detail['analysis']['timeline']);
        self::assertNull($detail['analysis']['metrics']['weightedHeartRate']);
        $this->client->request('GET', '/api/v1/running/overview?from=2026-02-30', server: $server);
        self::assertResponseStatusCodeSame(422);
        $this->client->request('GET', '/api/v1/running/activities/absent', server: $server);
        self::assertResponseStatusCodeSame(404);
        $this->client->request('GET', '/api/v1/running/openapi.json', server: $server);
        self::assertResponseIsSuccessful();
        self::assertSame('3.1.0', json_decode($this->client->getResponse()->getContent(), true)['openapi']);
        $this->client->request('GET', '/api/v1/running/overview?from[]=invalid', server: $server);
        self::assertResponseStatusCodeSame(422);
        $this->client->loginUser($this->adminUser());
        $this->client->request('GET', '/admin/running');
        self::assertResponseIsSuccessful();
        self::assertSelectorExists('#running-app');
    }
}
