<?php

// Seeds ONLY the named disposable E2E database. Never run against personal data.
declare(strict_types=1);
require dirname(__DIR__, 2).'/vendor/autoload.php';
use App\Infrastructure\Serialization\Json;
use Doctrine\DBAL\DriverManager;

$db = DriverManager::getConnection(['driver' => 'pdo_sqlite', 'path' => dirname(__DIR__, 2).'/var/runtime/analytics-e2e-database/dreeve.db']);
if (0 !== (int) $db->fetchOne('SELECT COUNT(*) FROM Activity')) {
    throw new RuntimeException('Fixture database is not empty.');
}
$base = ['activityType' => 'Run', 'sportType' => 'Run', 'worldType' => 'realWorld', 'importSource' => 'fitFile', 'name' => 'DEMO 轻松跑', 'description' => '', 'distance' => 10000, 'elevation' => 38, 'averageSpeed' => 10, 'maxSpeed' => 14, 'movingTimeInSeconds' => 3600, 'elapsedTimeInSeconds' => 3620, 'averageHeartRate' => 145, 'averageCadence' => 85, 'totalImageCount' => 0, 'routeGeography' => '[]', 'data' => '{}', 'deviceName' => 'DEMO Amazfit'];
$now = new DateTimeImmutable('today');
for ($i = 0; $i < 560; ++$i) {
    $km = 0 === $i % 7 ? 24 : 8 + $i % 8;
    $db->insert('Activity', [...$base, 'activityId' => 'activity-demo-'.$i, 'startDateTime' => $now->modify('-'.$i.' days')->setTime(6, 30)->format('Y-m-d H:i:s'), 'distance' => $km * 1000, 'movingTimeInSeconds' => $km * (320 + $i % 40), 'sportType' => 0 === $i % 9 ? 'TrailRun' : 'Run', 'name' => 0 === $i ? 'DEMO 长距离节奏跑' : 'DEMO 跑步 '.$i]);
}
$db->insert('Activity', [...$base, 'activityId' => 'activity-demo-missing', 'startDateTime' => $now->format('Y-m-d').' 07:00:00', 'name' => 'DEMO 无心率记录 <img src=x onerror=alert(1)>', 'averageHeartRate' => null, 'averageCadence' => null]);
$streams = array_fill_keys(['time', 'distance', 'heartrate', 'velocity_smooth', 'cadence', 'altitude', 'watts', 'moving'], []);
for ($t = 0; $t <= 7200; $t += 5) {
    $streams['time'][] = $t;
    $streams['distance'][] = $t * 3.33333;
    $streams['heartrate'][] = 135 + (int) ($t / 600) + (int) (sin($t / 150) * 6);
    $streams['velocity_smooth'][] = 3.33333 + sin($t / 180) * 0.16;
    $streams['cadence'][] = 85 + (int) (sin($t / 120) * 4);
    $streams['altitude'][] = 12 + sin($t / 600) * 8;
    $streams['watts'][] = 220 + (int) (sin($t / 150) * 25);
    $streams['moving'][] = true;
}
foreach ($streams as $type => $values) {
    $db->insert('ActivityStream', ['activityId' => 'activity-demo-0', 'streamType' => $type, 'data' => (string) Json::encodeAndCompress($values), 'dataSize' => count($values), 'createdOn' => $now->format('Y-m-d H:i:s')]);
}
for ($i = 1; $i <= 24; ++$i) {
    $db->insert('ActivitySplit', ['activityId' => 'activity-demo-0', 'unitSystem' => 'metric', 'splitNumber' => $i, 'distance' => 1000, 'elapsedTimeInSeconds' => 310, 'movingTimeInSeconds' => 290 + $i, 'elevationDifference' => $i % 3 - 1, 'averageSpeed' => 12, 'minAverageSpeed' => 11, 'maxAverageSpeed' => 13, 'paceZone' => 2, 'gapPaceInSecondsPerKm' => 288 + $i]);
}
foreach ([1000 => 280, 5000 => 1480, 10000 => 3020, 21097 => 6430] as $distance => $seconds) {
    $db->insert('ActivityBestEffort', ['activityId' => 'activity-demo-0', 'distanceInMeter' => $distance, 'sportType' => 'TrailRun', 'timeInSeconds' => $seconds]);
}
foreach (['birthday' => '1990-01-01', 'firstName' => 'DEMO', 'lastName' => 'Runner', 'maxHeartRateFormula' => 'fox'] as $name => $value) {
    $db->executeStatement('INSERT OR REPLACE INTO Setting(settingsGroup,name,value) VALUES(?,?,?)', ['general', $name, json_encode($value)]);
}
echo "Seeded isolated DEMO database: 561 runs, eight sensor streams, splits and best efforts.\n";
