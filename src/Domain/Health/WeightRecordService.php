<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Settings\DbalSettingsRepository;
use App\Domain\Settings\SettingsName;
use App\Domain\Settings\SettingsRepository;
use App\Domain\Settings\SettingsWereUpdated;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Infrastructure\Eventing\EventBus;
use App\Infrastructure\Measurement\UnitSystem;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\Exception\UniqueConstraintViolationException;
use Symfony\Component\DependencyInjection\Attribute\Autowire;

/** A kilogram API over the existing weight history, never a second measurements store. */
final readonly class WeightRecordService
{
    public function __construct(
        private Connection $connection,
        #[Autowire(service: DbalSettingsRepository::class)] private SettingsRepository $settings,
        private EventBus $eventBus,
    ) {
    }

    /** @return array<string, mixed> */
    public function list(?string $from = null, ?string $to = null): array
    {
        foreach ([$from, $to] as $date) {
            if (null !== $date) {
                TrainingInput::date($date);
            }
        }
        if (null !== $from && null !== $to && $from > $to) {
            throw new TrainingError('from 不能晚于 to。');
        }
        $raw = $this->raw();
        $unit = $this->settings->appearance()->getUnitSystem();
        $records = [];
        foreach ($this->entries($raw) as $entry) {
            if (!is_array($entry) || !is_numeric($entry['weight'] ?? null)) {
                throw new TrainingError('已有体重数据格式无效，请先核对原记录。');
            }
            $date = TrainingInput::date($entry['on'] ?? null);
            if ((null !== $from && $date < $from) || (null !== $to && $date > $to)) {
                continue;
            }
            $records[$date] = ['date' => $date, 'weightKg' => round($unit->weight((float) $entry['weight'])->toKilogram()->toFloat(), 3), 'source' => $entry['source'] ?? 'unknown', 'sourceLabel' => $entry['sourceLabel'] ?? '', 'notes' => $entry['notes'] ?? ''];
        }
        krsort($records);

        return ['records' => array_values($records), 'version' => $this->version($raw, $unit), 'unit' => 'kg'];
    }

    /** @param array<string, mixed> $input
     * @return array<string, mixed>
     */
    public function save(string $date, array $input): array
    {
        TrainingInput::date($date);
        HealthContextInput::knownFields($input, ['version', 'weightKg', 'source', 'sourceLabel', 'notes'], 'weight');
        $version = $this->validateVersion($input['version'] ?? null);
        $weight = $input['weightKg'] ?? null;
        // Data-entry bounds, not a target, diagnosis or recommended body weight.
        if ((!is_int($weight) && !is_float($weight)) || !is_finite((float) $weight) || $weight <= 0 || $weight > 1000) {
            throw new TrainingError('weightKg 必须为大于 0 且不超过 1000 的数字。');
        }
        if (array_key_exists('source', $input) && !in_array($input['source'], ['manual', 'import', 'unknown'], true)) {
            throw new TrainingError('source 必须为 manual、import、unknown。');
        }
        foreach (['sourceLabel' => 200, 'notes' => 2000] as $field => $limit) {
            if (array_key_exists($field, $input) && (!is_string($input[$field]) || mb_strlen($input[$field]) > $limit)) {
                throw new TrainingError($field.' 必须为最多 '.$limit.' 字符的文本。');
            }
        }

        $this->mutate($version, function (array $entries, UnitSystem $unit) use ($date, $weight, $input): array {
            $existing = [];
            foreach ($entries as $entry) {
                if (is_array($entry) && $date === ($entry['on'] ?? null)) {
                    $existing = $entry;
                }
            }
            $entries = $this->without($entries, $date);
            $entries[] = ['on' => $date, 'weight' => UnitSystem::IMPERIAL === $unit ? $weight / 0.45359237 : $weight,
                'source' => $input['source'] ?? $existing['source'] ?? ([] === $existing ? 'manual' : 'unknown'),
                'sourceLabel' => $input['sourceLabel'] ?? $existing['sourceLabel'] ?? '', 'notes' => $input['notes'] ?? $existing['notes'] ?? ''];

            return $entries;
        });

        return $this->list();
    }

    public function delete(string $date, mixed $version): void
    {
        TrainingInput::date($date);
        $this->mutate($this->validateVersion($version), function (array $entries, UnitSystem $unit) use ($date): array {
            $updated = $this->without($entries, $date);
            if (count($entries) === count($updated)) {
                throw new TrainingError('体重记录不存在。', 404);
            }

            return $updated;
        });
    }

    private function raw(): string|false
    {
        return $this->connection->fetchOne('SELECT value FROM Setting WHERE settingsGroup = ? AND name = ?', [SettingsName::WEIGHT_HISTORY->group()->value, SettingsName::WEIGHT_HISTORY->value]);
    }

    /** @return list<mixed> */
    private function entries(string|false $raw): array
    {
        $entries = false === $raw ? [] : json_decode($raw, true, 512, JSON_THROW_ON_ERROR);

        return is_array($entries) ? array_values($entries) : [];
    }

    private function version(string|false $raw, UnitSystem $unit): string
    {
        return hash('sha256', $unit->value.'|'.(false === $raw ? '<absent>' : $raw));
    }

    private function validateVersion(mixed $version): string
    {
        if (null === $version) {
            throw new TrainingError('修改需要提供 GET /health/weight 返回的 version。', 428);
        }
        if (!is_string($version) || !preg_match('/^[a-f0-9]{64}$/D', $version)) {
            throw new TrainingError('version 必须为读取体重记录时返回的字符串。');
        }

        return $version;
    }

    /** @param callable(list<mixed>, UnitSystem): list<mixed> $operation */
    private function mutate(string $expectedVersion, callable $operation): void
    {
        $this->connection->transactional(function () use ($expectedVersion, $operation): void {
            $raw = $this->raw();
            $unit = $this->settings->appearance()->getUnitSystem();
            if (!hash_equals($this->version($raw, $unit), $expectedVersion)) {
                throw new TrainingError('体重记录或单位设置已改变，请重新读取后再编辑。', 409);
            }
            $value = json_encode($operation($this->entries($raw), $unit), JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);
            $key = ['settingsGroup' => SettingsName::WEIGHT_HISTORY->group()->value, 'name' => SettingsName::WEIGHT_HISTORY->value];
            try {
                if (false === $raw) {
                    $this->connection->insert('Setting', [...$key, 'value' => $value]);
                } elseif (1 !== $this->connection->update('Setting', ['value' => $value], [...$key, 'value' => $raw])) {
                    throw new TrainingError('体重记录已改变，请重新读取后再编辑。', 409);
                }
            } catch (UniqueConstraintViolationException) {
                throw new TrainingError('体重记录已改变，请重新读取后再编辑。', 409);
            }
            $this->eventBus->publishEvents([new SettingsWereUpdated(SettingsName::WEIGHT_HISTORY->group())]);
        });
    }

    /** @param list<mixed> $entries
     * @return list<mixed>
     */
    private function without(array $entries, string $date): array
    {
        return array_values(array_filter($entries, static fn (mixed $entry): bool => !is_array($entry) || $date !== ($entry['on'] ?? null)));
    }
}
