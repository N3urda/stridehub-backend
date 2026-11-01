<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\BodyCompositionReadModel;
use App\Domain\Health\BodyHeightSelection;
use PHPUnit\Framework\TestCase;

final class BodyCompositionReadModelTest extends TestCase
{
    public function testOriginalEstimatesRemainCompleteAndBoneAgeAreNotClinicalAlerts(): void
    {
        $capture = ['weightKg' => 75.0, 'localDate' => '2026-01-10', 'profile' => (object) ['heightCm' => 170, 'birthDate' => '1990-01-01'],
            'fatPercent' => 23.1, 'bmi' => 25.95, 'derived' => (object) ['algorithmVersion' => 'yunmai-2', 'values' => (object) ['BODY_FAT' => 23.1, 'BONE' => 3.4, 'BODY_AGE' => 40, 'BMR' => 1800, 'DEMO_UNKNOWN' => 0, 'WATER' => null],
                'grades' => (object) ['BODY_FAT' => '偏胖', 'BONE' => '偏高', 'BODY_AGE' => '偏大'],
                'references' => (object) ['BODY_FAT' => '正常：> 10% 且 ≤ 21%'], 'scores' => (object) ['BODY_FAT' => 88]]];
        $before = json_encode($capture);
        $result = BodyCompositionReadModel::present($capture, ['status' => 'report', 'source' => ['heightCm' => 171.5]]);
        $metrics = array_column($result['metrics'], null, 'key');
        self::assertSame(25.5, $result['reportBmi']['value']);
        self::assertSame('超重', $result['reportBmi']['classification']);
        self::assertSame(25.95, $result['capturedBmi']);
        self::assertSame(170.0, $result['capturedHeightCm']);
        self::assertTrue($metrics['BODY_FAT']['highlight']);
        self::assertSame(88.0, $metrics['BODY_FAT']['score']);
        self::assertSame('estimate', $metrics['BODY_FAT']['kind']);
        self::assertFalse($metrics['BONE']['highlight']);
        self::assertFalse($metrics['BODY_AGE']['highlight']);
        self::assertStringContainsString('不是全天热量目标', $metrics['BMR']['note']);
        self::assertSame(0.0, $metrics['DEMO_UNKNOWN']['value']);
        self::assertSame('', $metrics['DEMO_UNKNOWN']['unit']);
        self::assertNull($metrics['WATER']['value']);
        self::assertSame($before, json_encode($capture));
        self::assertNull(BodyCompositionReadModel::present($capture, ['status' => 'stale'])['reportBmi']);
    }

    public function testBmiBoundariesAndMissingOrMinorAge(): void
    {
        foreach ([['体重过低', 18.49], ['正常', 18.5], ['正常', 23.999], ['超重', 24.0], ['超重', 27.999], ['肥胖', 28.0]] as [$label, $value]) {
            self::assertSame($label, BodyCompositionReadModel::bmiClass($value));
        }
        $capture = ['weightKg' => 50, 'localDate' => '2026-01-10', 'profile' => (object) ['birthDate' => '2015-01-01']];
        self::assertNull(BodyCompositionReadModel::present($capture, ['status' => 'report', 'source' => ['heightCm' => 150]])['reportBmi']['classification']);
        $capture['profile'] = new \stdClass();
        self::assertNull(BodyCompositionReadModel::present($capture, ['status' => 'report', 'source' => ['heightCm' => 150]])['reportBmi']['classification']);
        self::assertNull(BodyCompositionReadModel::present($capture, ['status' => 'capture'])['bodyFatPercent']);
    }

    public function testHeightCandidatesRequireExactNumericHeightAndExplicitUnit(): void
    {
        $report = ['id' => 'demo', 'title' => 'DEMO', 'reportDate' => '2026-01-01', 'sourceLabel' => 'DEMO', 'needsReview' => false,
            'findings' => [['name' => '身高', 'valueText' => '171.5', 'unit' => 'cm'], ['name' => '身高', 'valueText' => '1.715', 'unit' => 'm'],
                ['name' => '身高', 'valueText' => '171.5（待核对）', 'unit' => 'cm'], ['name' => '腰围', 'valueText' => '171.5', 'unit' => 'cm']]];
        $rows = BodyHeightSelection::candidates(['reports' => [$report]]);
        self::assertCount(1, $rows);
        self::assertSame(171.5, $rows[0]['heightCm']);
        self::assertSame(0, $rows[0]['findingIndex']);
        $report['findings'][0]['valueText'] = '172';
        self::assertNotSame($rows[0]['findingFingerprint'], BodyHeightSelection::candidates(['reports' => [$report]])[0]['findingFingerprint']);
    }
}
