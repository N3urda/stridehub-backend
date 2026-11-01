<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;

/** Display preference only. Immutable collection profiles are never rewritten. */
final readonly class BodyHeightSelection
{
    public function __construct(private TrainingRepository $repository, private HealthContextService $context)
    {
    }

    /** @return array<string,mixed> */
    public function get(): array
    {
        $context = $this->context->get();
        $candidates = self::candidates($context);
        $saved = $this->repository->find('body-height-selection', 'default') ?? ['source' => null, 'version' => 0, 'updatedAt' => null];
        $source = $saved['source'];
        $valid = null === $source || null !== array_find($candidates, static fn (array $c): bool => $c['findingFingerprint'] === $source['findingFingerprint']);

        return ['version' => $saved['version'], 'updatedAt' => $saved['updatedAt'], 'source' => $source,
            'status' => null === $source ? 'capture' : ($valid ? 'report' : 'stale'),
            'candidates' => $candidates, 'healthContextVersion' => $context['version']];
    }

    /** @param array<string,mixed> $input
     * @return array<string,mixed>
     */
    public function save(array $input): array
    {
        HealthContextInput::knownFields($input, ['version', 'reportId', 'findingIndex', 'findingFingerprint'], 'heightSelection');
        $version = TrainingInput::version($input['version'] ?? null);

        return $this->repository->transactional(function () use ($input, $version): array {
            $current = $this->get();
            if ($version !== $current['version']) {
                throw new TrainingError('身高依据已经改变，请重新读取后选择。', 409);
            }
            if (!array_key_exists('reportId', $input)) {
                throw new TrainingError('请明确选择报告身高，或以 reportId=null 使用采集时资料。');
            }
            $source = null;
            if (null !== $input['reportId']) {
                if (!is_string($input['reportId']) || !is_int($input['findingIndex'] ?? null) || !is_string($input['findingFingerprint'] ?? null)) {
                    throw new TrainingError('报告身高需要 reportId、整数 findingIndex 和 findingFingerprint。');
                }
                $source = array_find($current['candidates'], static fn (array $c): bool => $c['reportId'] === $input['reportId'] && $c['findingIndex'] === $input['findingIndex'] && hash_equals($c['findingFingerprint'], $input['findingFingerprint']));
                if (null === $source) {
                    throw new TrainingError('报告身高条目已变化或不再有效，请重新读取并选择。', 409);
                }
                $source['healthContextVersion'] = $current['healthContextVersion'];
            } elseif (null !== ($input['findingIndex'] ?? null) || null !== ($input['findingFingerprint'] ?? null)) {
                throw new TrainingError('使用采集时资料时不应指定报告条目。');
            }
            $saved = $this->repository->save('body-height-selection', 'default', ['source' => $source], $version);
            $this->repository->save('body-height-selection-history', (string) $saved['version'], ['selectionVersion' => $saved['version'], 'source' => $source, 'previousSource' => $current['source']], 0);

            return $this->get();
        });
    }

    /** @param array<string,mixed> $context
     * @return list<array<string,mixed>>
     */
    public static function candidates(array $context): array
    {
        $candidates = [];
        foreach ($context['reports'] as $report) {
            foreach ($report['findings'] as $index => $finding) {
                if (!in_array(mb_strtolower(trim($finding['name'])), ['身高', 'height', 'heightcm'], true)
                    || !in_array(mb_strtolower(trim($finding['unit'])), ['cm', '厘米'], true)
                    || !preg_match('/^\d{2,3}(?:\.\d+)?$/D', trim($finding['valueText']))) {
                    continue;
                }
                $height = (float) $finding['valueText'];
                if ($height < 80 || $height > 250) {
                    continue;
                }
                $source = ['reportId' => $report['id'], 'reportTitle' => $report['title'], 'reportDate' => $report['reportDate'],
                    'sourceLabel' => $report['sourceLabel'], 'needsReview' => $report['needsReview'], 'findingIndex' => $index,
                    'finding' => $finding, 'heightCm' => $height];
                $source['findingFingerprint'] = hash('sha256', json_encode($source, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE));
                $candidates[] = $source;
            }
        }
        usort($candidates, static fn (array $a, array $b): int => [$b['reportDate'], $b['reportId'], $b['findingIndex']] <=> [$a['reportDate'], $a['reportId'], $a['findingIndex']]);

        return $candidates;
    }
}
