<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingInput;
use App\Domain\Training\TrainingRepository;

final readonly class WeightCollection
{
    public function __construct(private TrainingRepository $records, private WeightRecordService $daily)
    {
    }

    /** @return array<string, mixed> */
    public function pair(string $name): array
    {
        if ('' === trim($name) || mb_strlen($name) > 100) {
            throw new TrainingError('name must contain 1..100 characters.');
        }
        $id = bin2hex(random_bytes(16));
        $token = 'shdevice_'.bin2hex(random_bytes(32));
        $this->records->save('collection-devices', $id, ['name' => trim($name), 'tokenHash' => hash('sha256', $token), 'revoked' => false], 0);

        return ['deviceId' => $id, 'name' => trim($name), 'token' => $token];
    }

    /** @return list<array<string, mixed>> */
    public function devices(): array
    {
        return array_map(static fn (array $d): array => ['deviceId' => $d['id'], 'name' => $d['name'], 'revoked' => $d['revoked']], $this->records->list('collection-devices'));
    }

    public function revoke(string $id): void
    {
        $d = $this->records->find('collection-devices', $id) ?? throw new TrainingError('Device not found.', 404);
        $this->records->save('collection-devices', $id, [...$d, 'revoked' => true], $d['version']);
    }

    public function authenticate(string $token): string
    {
        if (preg_match('/^shdevice_[a-f0-9]{64}$/D', $token)) {
            foreach ($this->records->list('collection-devices') as $d) {
                if (!$d['revoked'] && hash_equals($d['tokenHash'], hash('sha256', $token))) {
                    return $d['id'];
                }
            }
        }
        throw new TrainingError('Invalid device credential.', 401);
    }

    /** @param array<string, mixed> $input
     * @return array<string, mixed>
     */
    public function capture(string $device, string $id, array $input): array
    {
        $payload = $this->normalize($id, $input);

        return $this->records->transactional(function () use ($device, $id, $payload): array {
            $key = $device.':'.$id;
            $old = $this->records->find('collection-weight', $key);
            if (null !== $old) {
                if ($old['deleted'] || json_encode($old['capture'], JSON_THROW_ON_ERROR) !== json_encode($payload, JSON_THROW_ON_ERROR)) {
                    throw new TrainingError('Measurement ID already used with different content or deleted.', 409);
                }

                return ['measurement' => $old, 'replayed' => true, 'projection' => $this->project($payload['localDate'])];
            }
            $saved = $this->records->save('collection-weight', $key, ['deviceId' => $device, 'measurementId' => $id, 'capture' => $payload, 'deleted' => false], 0);

            return ['measurement' => $saved, 'replayed' => false, 'projection' => $this->project($payload['localDate'])];
        });
    }

    /** @return array<string, mixed> */
    public function get(string $device, string $id): array
    {
        return $this->records->find('collection-weight', $device.':'.$id) ?? throw new TrainingError('Measurement not found.', 404);
    }

    /** @return array<string, mixed> */
    public function history(?string $device, int $offset, int $limit): array
    {
        if ($offset < 0 || $offset > 100000 || $limit < 1 || $limit > 100) {
            throw new TrainingError('Invalid pagination.');
        }
        $rows = array_values(array_filter($this->records->list('collection-weight'), static fn (array $r): bool => null === $device || $device === $r['deviceId']));
        usort($rows, static fn (array $a, array $b): int => [$b['capture']['measuredAt'], $b['measurementId'], $b['deviceId']] <=> [$a['capture']['measuredAt'], $a['measurementId'], $a['deviceId']]);

        return ['records' => array_slice($rows, $offset, $limit), 'total' => count($rows), 'offset' => $offset, 'limit' => $limit];
    }

    /** @return array<string, mixed> */
    public function delete(string $device, string $id): array
    {
        return $this->records->transactional(function () use ($device, $id): array {
            $old = $this->get($device, $id);
            if (!$old['deleted']) {
                $this->records->save('collection-weight', $device.':'.$id, [...$old, 'deleted' => true], $old['version']);
            }

            return ['projection' => $this->project($old['capture']['localDate'])];
        });
    }

    /** @return array<string, mixed> */
    private function project(string $date): array
    {
        // Select from the full store: pagination must never change daily projection.
        $rows = array_values(array_filter($this->records->list('collection-weight'), static fn (array $r): bool => !$r['deleted'] && $r['capture']['localDate'] === $date));
        usort($rows, static fn (array $a, array $b): int => [$b['capture']['measuredAt'], $b['measurementId'], $b['deviceId']] <=> [$a['capture']['measuredAt'], $a['measurementId'], $a['deviceId']]);
        $latest = $rows[0] ?? null;
        $list = $this->daily->list($date, $date);
        $current = $list['records'][0] ?? null;
        $owner = $this->records->find('collection-projection', $date);
        $owned = null !== $owner && null !== $current && $current == $owner['daily'];
        if ((null !== $current && !$owned) || (null === $current && null !== $owner && null !== $owner['daily'])) {
            return ['status' => 'manual_conflict', 'measurementId' => $latest['measurementId'] ?? null];
        }
        if (null === $latest) {
            if ($owned) {
                $this->daily->delete($date, $list['version']);
                $this->records->save('collection-projection', $date, ['daily' => null], $owner['version']);
            }

            return ['status' => 'unchanged', 'measurementId' => null];
        }
        $this->daily->save($date, ['version' => $list['version'], 'weightKg' => $latest['capture']['weightKg'], 'source' => 'import', 'sourceLabel' => 'StrideHub collection', 'notes' => '']);
        $snapshot = $this->daily->list($date, $date)['records'][0];
        $this->records->save('collection-projection', $date, ['daily' => $snapshot], $owner['version'] ?? 0);

        return ['status' => 'projected', 'measurementId' => $latest['measurementId']];
    }

    /** @param array<string, mixed> $p
     * @return array<string, mixed>
     */
    private function normalize(string $id, array $p): array
    {
        HealthContextInput::knownFields($p, ['measurementId', 'measuredAt', 'localDate', 'weightKg', 'fatPercent', 'bmi', 'impedanceOhms', 'profile', 'derived'], 'capture');
        if (!preg_match('/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/D', $id) || ($p['measurementId'] ?? null) !== $id) {
            throw new TrainingError('measurementId must match UUID route.');
        }
        $date = TrainingInput::date($p['localDate'] ?? null);
        $at = $p['measuredAt'] ?? null;
        if (!is_string($at) || !preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/D', $at)) {
            throw new TrainingError('measuredAt requires ISO offset.');
        }
        try {
            $instant = new \DateTimeImmutable($at);
        } catch (\Exception) {
            throw new TrainingError('Invalid measuredAt.');
        }
        if (false !== \DateTimeImmutable::getLastErrors()) {
            throw new TrainingError('Invalid measuredAt calendar date.');
        }
        $out = ['measurementId' => $id, 'measuredAt' => $instant->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d\TH:i:s.u\Z'), 'localDate' => $date];
        foreach (['weightKg' => [2, 300], 'fatPercent' => [0, 100], 'bmi' => [1, 100], 'impedanceOhms' => [0, 5000]] as $field => [$min,$max]) {
            $v = $p[$field] ?? null;
            if (null === $v && 'weightKg' !== $field) {
                $out[$field] = null;
                continue;
            }
            if ((!is_int($v) && !is_float($v)) || !is_finite((float) $v) || $v < $min || $v > $max) {
                throw new TrainingError('Invalid '.$field);
            }
            $out[$field] = (float) $v;
        }
        foreach (['profile', 'derived'] as $f) {
            if (!isset($p[$f]) || !$p[$f] instanceof \stdClass) {
                throw new TrainingError($f.' must be object.');
            }
        }
        if (!is_string($p['derived']->algorithmVersion ?? null) || '' === trim($p['derived']->algorithmVersion) || !($p['derived']->values ?? null) instanceof \stdClass) {
            throw new TrainingError('derived requires algorithmVersion and values.');
        }
        $out['profile'] = $this->canonical($p['profile']);
        $out['derived'] = $this->canonical($p['derived']);

        return $out;
    }

    private function canonical(mixed $value): mixed
    {
        if ($value instanceof \stdClass) {
            $properties = get_object_vars($value);
            ksort($properties);
            foreach ($properties as &$property) {
                $property = $this->canonical($property);
            }

            return (object) $properties;
        }
        if (is_array($value)) {
            return array_map($this->canonical(...), $value);
        }

        return $value;
    }
}
