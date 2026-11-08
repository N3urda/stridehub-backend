<?php

declare(strict_types=1);

namespace App\Tests\Domain\Health;

use App\Domain\Health\SleepReconciler;
use PHPUnit\Framework\TestCase;

final class SleepReconcilerTest extends TestCase
{
    private static function record(string $source, array $patch = []): array
    {
        return [...['id' => $source, 'source' => $source, 'date' => '2026-11-06', 'startedAt' => '2026-11-05T23:00:00+08:00', 'endedAt' => '2026-11-06T07:00:00+08:00', 'timeZone' => '+08:00', 'totalMinutes' => 450], ...$patch];
    }

    public function testCompletenessWinsIndependentOfBrandAndMissingFieldsComplement(): void
    {
        foreach ([['zepp', 'mi_fitness'], ['mi_fitness', 'zepp']] as [$rich, $sparse]) {
            $a = self::record($rich, ['deepMinutes' => 90, 'lightMinutes' => 300, 'remMinutes' => 60, 'score' => 80]);
            $b = self::record($sparse, ['wakeCount' => 2, 'score' => 90]);
            $result = SleepReconciler::combine([$a, $b]);
            self::assertSame($rich, $result['source']);
            self::assertSame(450, $result['totalMinutes']);
            self::assertSame(2, $result['wakeCount']);
            self::assertSame($sparse, $result['fieldSources']['wakeCount']['source']);
            self::assertCount(2, $result['sourceScores']);
            self::assertSame(80, $result['score']);
            self::assertCount(2, $result['contributingSources']);
        }
    }

    public function testNonoverlappingSessionsDoNotFillFieldsAndStageBlocksNeverMix(): void
    {
        $a = self::record('zepp', ['deepMinutes' => 100, 'lightMinutes' => 350]);
        $b = self::record('mi_fitness', ['remMinutes' => 60, 'wakeCount' => 3]);
        $result = SleepReconciler::combine([$a, $b]);
        self::assertArrayNotHasKey('remMinutes', $result);
        self::assertSame(3, $result['wakeCount']);
        $b['startedAt'] = '2026-11-06T10:00:00+08:00';
        $b['endedAt'] = '2026-11-06T18:00:00+08:00';
        $result = SleepReconciler::combine([$a, $b]);
        self::assertArrayNotHasKey('wakeCount', $result);
        self::assertFalse($result['alternatives'][0]['sameNight']);
    }

    public function testIncompleteRichRecordDoesNotDisplaceCompleteSession(): void
    {
        $a = self::record('mi_fitness', ['deepMinutes' => 90, 'lightMinutes' => 300, 'remMinutes' => 60, 'isIncomplete' => true]);
        $b = self::record('zepp', ['wakeCount' => 2]);
        $result = SleepReconciler::combine([$a, $b]);
        self::assertSame('zepp', $result['source']);
    }
}
