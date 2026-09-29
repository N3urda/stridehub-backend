<?php

declare(strict_types=1);

namespace App\Domain\Training;

/** Partial quick feedback never replaces the session's unrelated plan or feedback. */
final readonly class TrainingFeedback
{
    public function __construct(private TrainingService $training)
    {
    }

    /** @param array<string, mixed> $input
     * @return array<string, mixed>
     */
    public function save(string $id, array $input): array
    {
        if (array_diff(array_keys($input), ['version', 'feedback'])) {
            throw new TrainingError('快速反馈只接受 version 和 feedback。');
        }
        $version = TrainingInput::version($input['version'] ?? null);
        $patch = $input['feedback'] ?? null;
        if (!is_array($patch) || array_is_list($patch)) {
            throw new TrainingError('feedback 必须是至少包含一项的对象。');
        }
        $session = $this->training->get('sessions', $id);
        if ($session['version'] !== $version) {
            throw new TrainingError('训练已被修改，请重新读取后核对反馈。', 409);
        }
        $current = $session['feedback'] ?? [];
        if (isset($patch['fuel']) && is_array($patch['fuel']) && ([] === $patch['fuel'] || !array_is_list($patch['fuel']))) {
            $patch['fuel'] = array_replace($current['fuel'] ?? [], $patch['fuel']);
        }

        return $this->training->update('sessions', $id, ['version' => $version, 'feedback' => array_replace($current, $patch)]);
    }
}
