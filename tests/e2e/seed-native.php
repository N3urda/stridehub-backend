<?php

declare(strict_types=1);

use App\Domain\Health\ZeppDataParser;
use App\Domain\Health\ZeppSleepParser;
use App\Domain\Health\ZeppWorkoutParser;
use App\Domain\Settings\DbalSettingsRepository;
use App\Domain\Settings\SettingsGroup;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Time\Clock\SystemClock;
use App\Tests\Infrastructure\Eventing\SpyEventBus;

if ('1' !== getenv('STRIDEHUB_NATIVE_E2E') || '/var/www/var/native-e2e/db' !== getenv('DATABASE_DIRECTORY')) {
    throw new RuntimeException('Only the isolated native Android fixture database is allowed.');
}
require dirname(__DIR__, 2).'/vendor/autoload.php';
new Symfony\Component\Dotenv\Dotenv()->bootEnv(dirname(__DIR__, 2).'/.env');
$kernel = new App\Kernel('dev', true);
$kernel->boot();
$connection = $kernel->getContainer()->get('doctrine')->getConnection();
if (realpath(dirname($connection->getParams()['path'] ?? '')) !== realpath('/var/www/var/native-e2e/db')) {
    throw new RuntimeException('Resolved database is not the isolated native fixture database.');
}
$settings = new DbalSettingsRepository($connection, new SpyEventBus());
// Reruns preserve existing settings, measurements, meals and acceptance edits.
$settings->saveGroup(SettingsGroup::GENERAL, [...$settings->findGroup(SettingsGroup::GENERAL), 'firstName' => 'DEMO', 'lastName' => 'Native', 'birthday' => '1990-01-01']);
$settings->saveGroup(SettingsGroup::APPEARANCE, [...$settings->findGroup(SettingsGroup::APPEARANCE), 'locale' => 'en_US', 'unitSystem' => 'metric']);
$repository = new TrainingRepository($connection, new SystemClock(null));
$save = static function (string $kind, string $id, array $payload) use ($repository): void {
    $old = $repository->find($kind, $id);
    $repository->save($kind, $id, $payload, $old['version'] ?? 0);
};
$zone = new DateTimeZone($repository->find('profile', 'default')['timezone'] ?? 'Asia/Shanghai');
$now = new DateTimeImmutable('now', $zone);
$today = $now->setTime(0, 0);
$sleepParser = new ZeppSleepParser();
$dailyParser = new ZeppDataParser($sleepParser);
$workoutParser = new ZeppWorkoutParser();
$prefix = 'zepp-de00000000000001-';
$workoutIds = [];
$dates = [];

$repository->transactional(static function () use ($save, $today, $now, $zone, $dailyParser, $workoutParser, $prefix, &$dates, &$workoutIds): void {
    for ($daysAgo = 0; $daysAgo < 4; ++$daysAgo) {
        $day = $today->modify('-'.$daysAgo.' days');
        $date = $day->format('Y-m-d');
        $dates[] = $date;
        $sleepStart = $day->modify('-1 day')->setTime(23, 0);
        $sleepEnd = $day->setTime(7, 0);
        $heartRateBytes = str_repeat(chr(0), 1440);
        // Two real sample clusters separated by an eight-minute hole; no gap zero-fill.
        foreach ([600 => 65, 601 => 67, 602 => 66, 610 => 72, 611 => 70] as $minute => $bpm) {
            $heartRateBytes[$minute] = chr($bpm + $daysAgo);
        }
        $band = ['date_time' => $date, 'data_hr' => base64_encode($heartRateBytes), 'fixture' => 'DEMO synthetic native acceptance', 'summary' => [
            'tz' => $zone->getOffset($day), 'goal' => 10000, 'pai' => ['restHr' => 58 + $daysAgo],
            'stp' => ['ttl' => 8200 - $daysAgo * 700, 'dis' => 6200 - $daysAgo * 400, 'cal' => 330 - $daysAgo * 20, 'wk' => 45, 'rn' => 30],
            'slp' => ['st' => $sleepStart->getTimestamp(), 'ed' => $sleepEnd->getTimestamp(), 'dp' => 90, 'lt' => 300 - $daysAgo * 5,
                'dt' => 60, 'wk' => 30 + $daysAgo * 5, 'supRem' => true, 'ss' => 85 - $daysAgo, 'rhr' => 58 + $daysAgo, 'wc' => 2],
        ]];
        $daily = $dailyParser->band($band) ?? throw new RuntimeException('Synthetic daily parser fixture is invalid.');
        $rawId = hash('sha256', 'DEMO-native-band-'.$date);
        $save('zepp-raw', $rawId, ['source' => 'zepp', 'kind' => 'band_detail', 'sourceDate' => $date, 'payload' => $band,
            'decodedSummary' => $dailyParser->summary($band), 'fetchedAt' => $now->format(DATE_ATOM)]);
        $daily['rawRecordIds'] = [$rawId];
        $daily['fieldSources'] = ['activity.steps' => [$rawId], 'heartRate.restingBpm' => [$rawId], 'samples.heartRate' => [$rawId], 'sleep' => [$rawId]];
        $daily['fixtureLabel'] = 'DEMO synthetic native acceptance';
        $sleep = $daily['sleep'] ?? throw new RuntimeException('Synthetic sleep parser fixture is invalid.');
        $details = ['samples' => $daily['samples'], 'sleepStages' => $daily['sleepStages'], 'activityStages' => $daily['activityStages']];
        unset($daily['samples'], $daily['sleepStages'], $daily['activityStages']);
        $save('zepp-day', $prefix.$date, [...$daily, 'lastSyncedAt' => $now->format(DATE_ATOM)]);
        $save('zepp-detail', $prefix.$date, $details);
        $save('sleep', $prefix.$date, [...$sleep, 'lastSyncedAt' => $now->format(DATE_ATOM)]);

        $start = $day->setTime(8, 0);
        $sport = [1, 9, 6, 1][$daysAgo];
        $label = match ($sport) {
            9 => 'DEMO Zepp ride', 6 => 'DEMO Zepp walk', default => 'DEMO Zepp run'
        };
        $summary = ['trackid' => $start->getTimestamp(), 'start_time' => $start->getTimestamp(), 'end_time' => $start->getTimestamp() + 2100,
            'type' => $sport, 'sport_title' => $label, 'dis' => 5000, 'run_time' => 2000, 'calorie' => 340,
            'avg_heart_rate' => 146, 'max_heart_rate' => 170, 'avg_frequency' => 164, 'altitude_ascend' => 120, 'altitude_descend' => 70,
            'exercise_load' => 55, 'te' => 2.7, 'source' => 'DEMO synthetic fixture'];
        $workout = $workoutParser->summary($summary, $zone) ?? throw new RuntimeException('Synthetic workout parser fixture is invalid.');
        $id = $prefix.$workout['sourceId'];
        $workoutIds[] = $id;
        $rawDetail = ['trackid' => $summary['trackid'], 'heart_rate' => '0,145;1,1;1,-1;298,5;1,-2',
            'speed' => '0,2.5;1,3;299,2;1,2.5', 'currentDistance' => '0,0;60,15000;240,75000;300,150000;1400,500000',
            'time' => '0;60;240;300', 'longitude_latitude' => '3100000000,12100000000;10000,10000;20000,10000;10000,20000',
            'altitude' => '10000;11000;22000;15000', 'gait' => '0,0,110,164;60,0,112,166;240,0,108,162',
            'fixture' => 'DEMO synthetic native acceptance'];
        $streams = $workoutParser->streams($rawDetail, $workout['startedAt'], $workout['endedAt']);
        foreach ($streams as $kind => $samples) {
            $workout['streamCounts'][$kind] = count($samples);
        }
        $sourceIds = [];
        foreach (['summary' => $summary, 'detail' => $rawDetail] as $kind => $payload) {
            $sourceId = hash('sha256', 'DEMO-native-workout-'.$date.'-'.$kind);
            $sourceIds[] = $sourceId;
            $save('zepp-workout-source', $sourceId, ['source' => 'zepp', 'workoutId' => $id, 'kind' => $kind, 'payload' => $payload, 'fetchedAt' => $now->format(DATE_ATOM)]);
        }
        $save('zepp-workout', $id, [...$workout, 'detailState' => 'available', 'lastSyncedAt' => $now->format(DATE_ATOM)]);
        $save('zepp-workout-detail', $id, ['streams' => $streams, 'sourceSummary' => $summary, 'sourceDetail' => $rawDetail,
            'sourceRecordIds' => $sourceIds, 'sourceDetailFetchedAt' => $now->format(DATE_ATOM)]);
    }
});

// Prepare a real parser fixture for the native picker. Do not enqueue or import it here.
$tcx = file_get_contents(__DIR__.'/../Domain/Import/FileParser/fixtures/activity.tcx');
if (false === $tcx) {
    throw new RuntimeException('Could not read TCX parser fixture.');
}
$pickerStart = $today->setTime(10, 0)->setTimezone(new DateTimeZone('UTC'));
$tcx = str_replace(['2021-09-08T00:00:00Z', '2021-09-08T00:00:10Z', 'Garmin Edge 530'],
    [$pickerStart->format('Y-m-d\TH:i:s\Z'), $pickerStart->modify('+10 seconds')->format('Y-m-d\TH:i:s\Z'), 'DEMO native picker'], $tcx);
$artifactDirectory = dirname(__DIR__, 2).'/var/native-artifacts';
if (!is_dir($artifactDirectory) && !mkdir($artifactDirectory, 0700, true) && !is_dir($artifactDirectory)) {
    throw new RuntimeException('Could not create native acceptance artifact directory.');
}
if (false === file_put_contents($artifactDirectory.'/DEMO-native.TCX', $tcx)) {
    throw new RuntimeException('Could not prepare TCX picker fixture.');
}
$kernel->shutdown();
echo json_encode(['fixture' => 'DEMO synthetic; no network', 'dates' => $dates, 'daily' => 4, 'sleep' => 4, 'workouts' => $workoutIds,
    'pickerFile' => 'var/native-artifacts/DEMO-native.TCX', 'pickerImported' => false], JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE)."\n";
