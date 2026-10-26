<?php

declare(strict_types=1);

use App\Domain\Health\HealthContextInput;
use App\Domain\Health\HealthPlanInput;
use App\Domain\Health\ZeppDataParser;
use App\Domain\Health\ZeppSleepParser;
use App\Domain\Health\ZeppWorkoutParser;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\Time\Clock\SystemClock;
use Doctrine\DBAL\DriverManager;

// Run only in a helper with the dedicated stage data mounted at /target and
// its public marker mounted read-only at /target/__e2e_health.json.
$instanceId = getenv('STRIDEHUB_NATIVE_E2E_INSTANCE_ID');
if ('1' !== getenv('STRIDEHUB_NATIVE_E2E') || !is_string($instanceId) || !preg_match('/^[a-f0-9]{32}$/D', $instanceId)) {
    throw new RuntimeException('Explicit isolated fixture environment and instance ID required.');
}
$target = '/target';
$dbPath = '/target/database/dreeve.db';
$markerPath = '/target/__e2e_health.json';
if (realpath($target) !== $target || realpath(dirname($dbPath)) !== '/target/database' || realpath($dbPath) !== $dbPath
    || !is_file($markerPath) || is_link($markerPath) || is_link($target) || is_link(dirname($dbPath)) || is_link($dbPath)) {
    throw new RuntimeException('Only the existing isolated /target/database/dreeve.db is allowed.');
}
$marker = json_decode(file_get_contents($markerPath), true, 16, JSON_THROW_ON_ERROR);
if ('stridehub-native-e2e' !== ($marker['kind'] ?? null) || false !== ($marker['productionData'] ?? null)
    || true !== ($marker['allowNativeWrites'] ?? null) || !hash_equals($instanceId, $marker['instanceId'] ?? '')) {
    throw new RuntimeException('Isolated test marker does not match this instance.');
}
foreach (['fixtures.json', 'DEMO-native-run.TCX'] as $output) {
    if (is_link($target.'/'.$output)) {
        throw new RuntimeException('Fixture output must not be a symbolic link.');
    }
}
require '/var/www/vendor/autoload.php';
$connection = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'path' => $dbPath]);
$resolved = $connection->fetchOne('SELECT file FROM pragma_database_list WHERE name = ?', ['main']);
if (realpath($resolved) !== $dbPath) {
    throw new RuntimeException('Resolved database is outside the isolated target.');
}
$repository = new TrainingRepository($connection, new SystemClock(null));
$save = static function (string $kind, string $id, array $payload) use ($repository): void {
    $old = $repository->find($kind, $id);
    $repository->save($kind, $id, $payload, $old['version'] ?? 0);
};
$profile = TrainingInput::validate('profile', ['timezone' => 'Asia/Shanghai', 'usualStartTime' => '10:30', 'usualDurationMinutes' => 35,
    'runningDays' => [1, 3, 5], 'notificationsEnabled' => false]);
$context = $repository->find('health-context', 'default');
$markerNote = 'STRIDEHUB_NATIVE_E2E:'.$instanceId;
if (null !== $context && !str_contains($context['notes'], $markerNote)) {
    throw new RuntimeException('Existing health context is not owned by this test instance.');
}
if (null === $repository->find('profile', 'default')) {
    $save('profile', 'default', $profile);
}
if (null === $context) {
    $save('health-context', 'default', HealthContextInput::validate(['notes' => $markerNote.'; DEMO synthetic data, no real health records.']));
}
foreach (['general' => ['firstName' => 'DEMO', 'lastName' => 'Native', 'birthday' => '1990-01-01'],
    'appearance' => ['locale' => 'en_US', 'unitSystem' => 'metric']] as $group => $settings) {
    foreach ($settings as $key => $value) {
        $connection->executeStatement('INSERT INTO Setting (settingsGroup, name, value) VALUES (?, ?, ?) ON CONFLICT (settingsGroup, name) DO NOTHING',
            [$group, $key, json_encode($value, JSON_THROW_ON_ERROR)]);
    }
}
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


$date = $today->format('Y-m-d');
// Preserve plan items, session steps, feedback and acceptance edits on reruns.
if (null === $repository->find('health-plans', 'native-demo-plan')) {
    $save('health-plans', 'native-demo-plan', HealthPlanInput::validate(['title' => 'DEMO recovery plan', 'category' => 'recovery',
        'goal' => 'DEMO native acceptance plan', 'startDate' => $date, 'status' => 'active', 'notes' => 'DEMO synthetic fixture',
        'items' => [['id' => 'native-demo-plan-item', 'title' => 'DEMO gentle recovery', 'date' => $date, 'time' => '20:00', 'status' => 'pending', 'notes' => 'DEMO preserve this item during edits']]]));
}
if (null === $repository->find('sessions', 'native-demo-session')) {
    $save('sessions', 'native-demo-session', TrainingInput::validate('sessions', ['title' => 'DEMO easy run',
        'startAt' => $today->setTime(10, 30)->format(DATE_ATOM), 'durationMinutes' => 35, 'distanceKm' => 5,
        'type' => 'easy', 'notes' => 'DEMO synthetic fixture',
        'steps' => [['kind' => 'warmup', 'minutes' => 5, 'target' => 'DEMO easy effort'], ['kind' => 'easy', 'minutes' => 30, 'target' => 'DEMO comfortable']],
        'fuelPlan' => [['item' => 'DEMO water', 'minute' => 15, 'fluidMl' => 150, 'carbsGrams' => 0]]]));
}

// A real TCX import input only: never create Activity, Stream or FileImport rows.
$start = $today->setTime(10, 30)->setTimezone(new DateTimeZone('UTC'));
$stamp = static fn (DateTimeImmutable $time): string => $time->format('Y-m-d\TH:i:s\Z');
$weights = [];
for ($i = 0; $i < 200; ++$i) {
    $weights[] = 100 === $i ? 0.0 : 1 + .08 * sin($i / 17);
}
$scale = 5000 / (array_sum($weights) * 10);
$speeds = array_map(static fn (float $weight): float => $weight * $scale, $weights);
$speeds[] = $speeds[199];
$xml = new XMLWriter();
$xml->openMemory();
$xml->startDocument('1.0', 'UTF-8');
$xml->startElement('TrainingCenterDatabase');
$xml->writeAttribute('xmlns', 'http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2');
$xml->writeAttribute('xmlns:ext', 'http://www.garmin.com/xmlschemas/ActivityExtension/v2');
$xml->startElement('Activities');
$xml->startElement('Activity');
$xml->writeAttribute('Sport', 'Running');
$xml->writeElement('Id', $stamp($start));
$xml->startElement('Lap');
$xml->writeAttribute('StartTime', $stamp($start));
$xml->writeElement('TotalTimeSeconds', '2000');
$xml->writeElement('DistanceMeters', '5000');
$xml->writeElement('MaximumSpeed', sprintf('%.6f', max($speeds)));
$xml->writeElement('Calories', '360');
$xml->startElement('AverageHeartRateBpm');
$xml->writeElement('Value', '145');
$xml->endElement();
$xml->startElement('Track');
$distance = 0.0;
for ($i = 0; $i <= 200; ++$i) {
    if ($i > 0) {
        $distance += $speeds[$i - 1] * 10;
    }
    $xml->startElement('Trackpoint');
    $xml->writeElement('Time', $stamp($start->modify('+'.($i * 10).' seconds')));
    $xml->startElement('Position');
    $xml->writeElement('LatitudeDegrees', sprintf('%.8f', 31 + $distance / 111320));
    $xml->writeElement('LongitudeDegrees', sprintf('%.8f', 121 + .001 * sin($i / 25)));
    $xml->endElement();
    $xml->writeElement('AltitudeMeters', sprintf('%.3f', 100 + 8 * sin($i / 20)));
    $xml->writeElement('DistanceMeters', sprintf('%.6f', $distance));
    $xml->startElement('HeartRateBpm');
    $xml->writeElement('Value', (string) (130 + (int) round($i * .15)));
    $xml->endElement();
    $xml->writeElement('Cadence', (string) (84 + (int) round(2 * sin($i / 13))));
    $xml->startElement('Extensions');
    $xml->startElement('ext:TPX');
    $xml->writeElement('ext:Speed', sprintf('%.6f', $speeds[$i]));
    $xml->endElement();
    $xml->endElement();
    $xml->endElement();
}
$xml->endElement(); // Track
$xml->endElement(); // Lap
$xml->writeElement('Notes', 'DEMO synthetic native running acceptance; ten-second pause at t=1000.');
$xml->startElement('Creator');
$xml->writeElement('Name', 'DEMO native run fixture');
$xml->endElement();
$xml->endElement(); // Activity
$xml->endElement(); // Activities
$xml->endElement(); // TrainingCenterDatabase
$xml->endDocument();
$tcx = $xml->outputMemory();
$metadata = ['fixture' => 'DEMO synthetic; no network', 'instanceId' => $instanceId, 'dates' => $dates,
    'daily' => 4, 'sleep' => 4, 'workouts' => $workoutIds, 'planId' => 'native-demo-plan', 'sessionId' => 'native-demo-session',
    'tcx' => ['file' => 'DEMO-native-run.TCX', 'sha256' => hash('sha256', $tcx), 'samples' => 201, 'distanceMeters' => 5000,
        'durationSeconds' => 2000, 'pauseSeconds' => 10, 'imported' => false]];
if (false === file_put_contents($target.'/DEMO-native-run.TCX', $tcx)
    || false === file_put_contents($target.'/fixtures.json', json_encode($metadata, JSON_THROW_ON_ERROR | JSON_PRETTY_PRINT)."\n")) {
    throw new RuntimeException('Could not write isolated fixture artifacts.');
}
$connection->close();
echo json_encode($metadata, JSON_THROW_ON_ERROR)."\n";
