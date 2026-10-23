<?php

declare(strict_types=1);

namespace App\Domain\Mobile;

use App\Domain\Activity\ActivityId;
use App\Domain\Activity\DeleteActivity\DeleteActivity;
use App\Domain\Activity\EnrichActivity\EnrichActivity;
use App\Domain\Activity\Lap\ActivityLapRepository;
use App\Domain\Activity\ManuallyCreateActivity\ManuallyCreateActivity;
use App\Domain\Activity\Split\ActivitySplitRepository;
use App\Domain\Activity\SportType\SportType;
use App\Domain\Activity\Stream\ActivityStreamRepository;
use App\Domain\Activity\UpdateActivity\UpdateActivity;
use App\Domain\Activity\UpdateManuallyAddedActivity\UpdateManuallyAddedActivity;
use App\Domain\Activity\WorkoutType;
use App\Domain\Gear\GearRepository;
use App\Domain\Health\HealthDataService;
use App\Domain\Health\SleepService;
use App\Domain\Health\WorkoutCatalog;
use App\Domain\Health\ZeppDataService;
use App\Domain\Health\ZeppWorkoutService;
use App\Domain\Import\ActivityFileName;
use App\Domain\Import\ImportMode;
use App\Domain\Import\WatchDirectory;
use App\Domain\Settings\SettingsRepository;
use App\Domain\Training\TrainingError;
use App\Domain\Training\TrainingRepository;
use App\Infrastructure\CQRS\Command\Bus\CommandBus;
use App\Infrastructure\Time\Clock\Clock;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use Doctrine\DBAL\Connection;
use League\Flysystem\FilesystemOperator;
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\Lock\Store\FlockStore;

/** Explicit native workflow adapters. No command names or server paths come from clients. */
final readonly class MobileWorkflowService
{
    private const array IMPORT_FIELDS = ['name', 'description', 'sportType', 'gearId', 'calories', 'deviceName', 'isCommute', 'isGroupActivity'];
    private const array MANUAL_FIELDS = ['name', 'description', 'sportType', 'gearId', 'calories', 'isCommute', 'isGroupActivity', 'startDateTime', 'duration', 'distance', 'elevation', 'averageHeartRate', 'maxHeartRate', 'workoutType'];

    public function __construct(
        private Connection $connection,
        private TrainingRepository $repository,
        private WorkoutCatalog $catalog,
        private ImportMode $importMode,
        private SettingsRepository $settings,
        private GearRepository $gears,
        private CommandBus $commands,
        private WatchDirectory $watch,
        private FilesystemOperator $defaultStorage,
        private KernelProjectDir $projectDir,
        private Clock $clock,
        private ZeppDataService $daily,
        private SleepService $sleep,
        private ZeppWorkoutService $workouts,
        private ActivityStreamRepository $streams,
        private ActivityLapRepository $laps,
        private ActivitySplitRepository $splits,
    ) {
    }

    /**
     * @return array<string, mixed>
     */
    public function workouts(?string $from, ?string $to, int $page, int $limit, string $sportType = 'all'): array
    {
        if ($page < 1 || $limit < 1 || $limit > 100) {
            throw new TrainingError('page 必须大于零，limit 必须在 1 到 100 之间。');
        }
        $zone = new \DateTimeZone($this->repository->find('profile', 'default')['timezone'] ?? 'Asia/Shanghai');
        $to ??= $this->clock->getCurrentDateTimeImmutable()->setTimezone($zone)->format('Y-m-d');
        HealthDataService::range($from, $to);
        $from ??= new \DateTimeImmutable($to)->modify('-365 days')->format('Y-m-d');
        $overview = $this->catalog->overview($from, $to, $zone, $sportType);
        $total = count($overview['records']);
        $overview['records'] = array_slice($overview['records'], ($page - 1) * $limit, $limit);
        $overview['pagination'] = ['page' => $page, 'limit' => $limit, 'total' => $total, 'pages' => (int) ceil($total / $limit), 'hasMore' => $page * $limit < $total];

        return $overview;
    }

    /**
     * @return array<string, mixed>
     */
    public function options(): array
    {
        $units = $this->settings->appearance()->getUnitSystem();

        return ['canCreate' => $this->importMode->isFiles(), 'importMode' => $this->importMode->isFiles() ? 'files' : 'stravaApi',
            'sportTypes' => array_map(static fn (SportType $type): array => ['value' => $type->value, 'label' => $type->value], SportType::cases()),
            'workoutTypes' => array_map(static fn (WorkoutType $type): array => ['value' => $type->value, 'label' => $type->value], WorkoutType::cases()),
            'gears' => array_map(static fn ($gear): array => ['id' => (string) $gear->getId(), 'name' => $gear->getName(), 'imported' => $gear->getType()->isImported()], $this->gears->findAll()->sortByIsRetired()->toArray()),
            'units' => ['distance' => $units->distanceSymbol(), 'elevation' => $units->elevationSymbol()]];
    }

    /**
     * @return array<string, mixed>
     */
    public function activity(string $id): array
    {
        if (str_starts_with($id, 'zepp-')) {
            $record = $this->workouts->detail($id);

            return ['id' => $id, 'version' => (string) $record['version'], 'fields' => $record,
                'capabilities' => ['edit' => false, 'delete' => false, 'enrich' => false, 'editableFields' => [], 'deleteEffects' => []]];
        }
        $row = $this->activityRow($id);
        $manual = 'manual' === $row['importSource'];
        $editable = $manual ? ($this->importMode->isFiles() ? self::MANUAL_FIELDS : []) : self::IMPORT_FIELDS;
        $gearReadOnly = false;
        if ($this->importMode->isStravaApi()) {
            foreach ($this->gears->findAll() as $gear) {
                if ((string) $gear->getId() === ($row['gearId'] ?? '') && $gear->getType()->isImported()) {
                    $gearReadOnly = true;
                }
            }
            if ($gearReadOnly) {
                $editable = array_values(array_diff($editable, ['gearId']));
            }
        }
        $units = $this->settings->appearance()->getUnitSystem();
        $seconds = (int) $row['movingTimeInSeconds'];
        $fields = array_intersect_key($row, array_flip(['name', 'description', 'sportType', 'gearId', 'calories', 'averageHeartRate', 'maxHeartRate', 'workoutType', 'deviceName']));
        $fields = [...$fields, 'startDateTime' => new \DateTimeImmutable($row['startDateTime'])->format('Y-m-d\TH:i'),
            'duration' => ['hours' => intdiv($seconds, 3600), 'minutes' => intdiv($seconds % 3600, 60), 'seconds' => $seconds % 60],
            'distance' => (float) $row['distance'] / $units->distance(1)->toMeter()->toFloat(),
            'elevation' => (float) $row['elevation'] / $units->elevation(1)->toMeter()->toFloat(),
            'isCommute' => (bool) $row['isCommute'], 'isGroupActivity' => (bool) $row['isGroupActivity']];

        return ['id' => $id, 'source' => $row['importSource'], 'version' => hash('sha256', json_encode($row, JSON_THROW_ON_ERROR)), 'fields' => $fields,
            'capabilities' => ['edit' => [] !== $editable, 'delete' => true, 'enrich' => null !== $row['startingCoordinateLatitude'], 'editableFields' => $editable,
                'deleteEffects' => ['运动记录及曲线、分段、计圈、最佳成绩、路线、原始导入文件和关联图片将被删除。', '关联训练和来源合并存在时，须先解除关联。']]];
    }

    /**
     * @return array<string, mixed>
     */
    public function activityStreams(string $id): array
    {
        $this->activityRow($id);
        $activityId = ActivityId::fromString($id);
        $streams = [];
        foreach ($this->streams->findByActivityId($activityId) as $stream) {
            $streams[$stream->getStreamType()->value] = $stream->getData();
        }

        return ['streams' => (object) $streams,
            'laps' => array_map(static fn ($lap): array => $lap->exportForAITooling(), $this->laps->findBy($activityId)->toArray()),
            'splits' => array_map(static fn ($split): array => $split->exportForAITooling(), $this->splits->findBy($activityId, $this->settings->appearance()->getUnitSystem())->toArray())];
    }

    /**
     * @param array<string, mixed> $input
     *
     * @return array<string, mixed>
     */
    public function createActivity(array $input): array
    {
        if (!$this->importMode->isFiles()) {
            throw new TrainingError('当前导入模式不允许手动新增运动。', 403);
        }
        $this->known($input, self::MANUAL_FIELDS);
        $this->validateActivity($input);

        // The transaction serializes the insertion and its identity lookup on SQLite.
        return $this->connection->transactional(function () use ($input): array {
            $ids = $this->connection->fetchFirstColumn('SELECT activityId FROM Activity');
            $this->commands->dispatch(ManuallyCreateActivity::fromPayload($input));
            $created = array_values(array_diff($this->connection->fetchFirstColumn('SELECT activityId FROM Activity'), $ids));
            if (1 !== count($created)) {
                throw new \RuntimeException('Could not identify newly created activity.');
            }

            return $this->activity($created[0]);
        });
    }

    /**
     * @param array<string, mixed> $input
     *
     * @return array<string, mixed>
     */
    public function updateActivity(string $id, array $input): array
    {
        return $this->connection->transactional(function () use ($id, $input): array {
            $current = $this->checkActivityVersion($id, $input['version'] ?? null);
            if (!$current['capabilities']['edit']) {
                throw new TrainingError('此来源的运动不可编辑。', 403);
            }
            /** @var list<string> $editableFields */
            $editableFields = $current['capabilities']['editableFields'];
            $this->known($input, ['version', ...$editableFields]);
            unset($input['version']);
            $this->validateActivity($input);
            $fields = [...$current['fields'], ...$input, 'activityId' => $id];
            $this->commands->dispatch('manual' === $current['source'] ? UpdateManuallyAddedActivity::fromPayload($fields) : UpdateActivity::fromPayload($fields));

            return $this->activity($id);
        });
    }

    public function deleteActivity(string $id, mixed $version): void
    {
        $this->connection->transactional(function () use ($id, $version): void {
            $current = $this->checkActivityVersion($id, $version);
            if (!$current['capabilities']['delete']) {
                throw new TrainingError('此来源的运动不可删除。', 403);
            }
            if (false !== $this->connection->fetchOne('SELECT sessionId FROM TrainingActivityLink WHERE activityId = ?', [$id])
                || array_any($this->repository->list('workout-links'), static fn (array $link): bool => $link['activityId'] === $id)) {
                throw new TrainingError('请先解除训练和来源关联，再删除运动。', 409);
            }
            $this->commands->dispatch(new DeleteActivity(ActivityId::fromString($id)));
        });
    }

    /**
     * @param array<string, mixed> $input
     *
     * @return array<string, mixed>
     */
    public function enrichActivity(string $id, array $input): array
    {
        $this->known($input, ['version']);

        return $this->connection->transactional(function () use ($id, $input): array {
            $current = $this->checkActivityVersion($id, $input['version'] ?? null);
            if (!$current['capabilities']['enrich']) {
                throw new TrainingError('此运动没有可补充的地理坐标。', 422);
            }
            $this->commands->dispatch(new EnrichActivity(ActivityId::fromString($id)));

            return $this->activity($id);
        });
    }

    /**
     * @return array<string, mixed>
     */
    private function activityRow(string $id): array
    {
        if ('' === $id || strlen($id) > 255) {
            throw new TrainingError('运动 ID 无效。');
        }

        return $this->connection->fetchAssociative('SELECT * FROM Activity WHERE activityId = ? AND (markedForDeletion IS NULL OR markedForDeletion = 0)', [$id]) ?: throw new TrainingError('运动不存在。', 404);
    }

    /**
     * @return array<string, mixed>
     */
    private function checkActivityVersion(string $id, mixed $version): array
    {
        if (!is_string($version) || '' === $version) {
            throw new TrainingError('需要当前 version。', 428);
        }
        $current = $this->activity($id);
        if (!hash_equals($current['version'], $version)) {
            throw new TrainingError('运动已改变，请读取最新记录后核对草稿。', 409);
        }

        return $current;
    }

    /**
     * @param array<string, mixed> $input
     */
    private function validateActivity(array $input): void
    {
        foreach ($input as $key => $value) {
            if (in_array($key, ['isCommute', 'isGroupActivity'], true) && !is_bool($value)) {
                throw new TrainingError($key.' 必须是布尔值。');
            }
            if (in_array($key, ['name', 'sportType', 'startDateTime'], true) && !is_string($value)) {
                throw new TrainingError($key.' 必须是文本。');
            }
            if (in_array($key, ['description', 'deviceName', 'gearId', 'workoutType'], true) && null !== $value && !is_string($value)) {
                throw new TrainingError($key.' 必须是文本或 null。');
            }
            if (in_array($key, ['distance', 'elevation', 'calories', 'averageHeartRate', 'maxHeartRate'], true) && null !== $value && (!is_int($value) && !is_float($value) || !is_finite((float) $value) || $value < 0)) {
                throw new TrainingError($key.' 必须是非负数。');
            }
        }
        if (isset($input['gearId']) && '' !== $input['gearId']) {
            $gear = array_find($this->gears->findAll()->toArray(), static fn ($gear): bool => (string) $gear->getId() === $input['gearId']);
            if (null === $gear || ($this->importMode->isStravaApi() && !$gear->getType()->isCustom())) {
                throw new TrainingError('请选择当前模式允许的装备。');
            }
        }
        if (isset($input['duration'])) {
            if (!is_array($input['duration']) || array_diff(array_keys($input['duration']), ['hours', 'minutes', 'seconds'])) {
                throw new TrainingError('duration 必须包含 hours、minutes、seconds。');
            }
            foreach ($input['duration'] as $value) {
                if (!is_int($value) || $value < 0 || $value > 100000) {
                    throw new TrainingError('时长必须为非负整数。');
                }
            }
        }
    }

    /**
     * @return array<string, mixed>
     */
    public function imports(): array
    {
        return ['records' => array_map(fn (array $record): array => $this->import($record['id']), $this->repository->list('mobile-import'))];
    }

    /**
     * @return array<string, mixed>
     */
    public function import(string $id): array
    {
        $record = $this->repository->find('mobile-import', $id) ?? throw new TrainingError('导入回执不存在。', 404);
        if (in_array($record['state'], ['queued', 'staging'], true)) {
            $result = $this->connection->fetchAssociative('SELECT fileImportId, status, activityId, errorMessage FROM FileImport WHERE originalFilename = ? ORDER BY importedOn DESC LIMIT 1', [$record['queuedFilename']]);
            if (false !== $result) {
                $record = $this->repository->save('mobile-import', $id, [...$record, 'state' => $result['status'], 'activityId' => $result['activityId'], 'error' => $result['errorMessage'], 'fileImportId' => $result['fileImportId']], $record['version']);
            } elseif ('queued' === $record['state'] && !$this->defaultStorage->fileExists('watch/'.$record['queuedFilename'])) {
                $importLock = $this->connection->fetchOne('SELECT value FROM KeyValue WHERE key = ?', ['lock.importData']);
                $heartbeat = false === $importLock ? 0 : (json_decode($importLock, true, flags: JSON_THROW_ON_ERROR)['heartbeat'] ?? 0);
                if ($heartbeat < $this->clock->getCurrentDateTimeImmutable()->getTimestamp() - 1200) {
                    $record = $this->repository->save('mobile-import', $id, [...$record, 'state' => 'interrupted', 'error' => '排队文件已不存在且未找到导入结果，请重试。'], $record['version']);
                }
            }
        }

        return $this->publicImport($record);
    }

    /**
     * @return array<string, mixed>
     */
    public function upload(string $filename, string $contents): array
    {
        if (!$this->importMode->isFiles()) {
            throw new TrainingError('当前模式不接受文件导入。', 403);
        }
        if ('' === $contents || strlen($contents) > 25 * 1024 * 1024) {
            throw new TrainingError('文件必须在 1 字节到 25 MiB 之间。', 413);
        }
        $name = ActivityFileName::fromString($filename);
        $id = hash('sha256', $contents);
        $lock = $this->workerLocks()->createLock('mobile-import-'.$id, 60);
        if (!$lock->acquire()) {
            throw new TrainingError('此文件正在上传，请稍后读取回执。', 409);
        }
        try {
            if (null !== $this->repository->find('mobile-import', $id)) {
                return $this->import($id);
            }
            $this->defaultStorage->write('watch/.uploads/mobile-imports/'.$id, $contents);
            $queued = 'mobile-'.$id.'-1.'.$name->getExtension()->value;
            $record = $this->repository->save('mobile-import', $id, ['filename' => (string) $name, 'queuedFilename' => $queued, 'state' => 'staging', 'attempt' => 1, 'activityId' => null, 'error' => null], 0);
            $actual = $this->watch->writeFile(ActivityFileName::fromString($queued), $contents);
            $record = $this->repository->save('mobile-import', $id, [...$record, 'queuedFilename' => (string) $actual, 'state' => 'queued'], $record['version']);

            return $this->publicImport($record);
        } finally {
            $lock->release();
        }
    }

    /**
     * @return array<string, mixed>
     */
    public function retryImport(string $id): array
    {
        $lock = $this->workerLocks()->createLock('mobile-import-'.$id, 60);
        if (!$lock->acquire()) {
            throw new TrainingError('此文件正在排队。', 409);
        }
        try {
            $this->import($id);
            $record = $this->repository->find('mobile-import', $id) ?? throw new TrainingError('导入回执不存在。', 404);
            if (!in_array($record['state'], ['failed', 'interrupted', 'staging'], true)) {
                return $this->publicImport($record);
            }
            // A staging write may have completed before the process stopped. Never enqueue it twice.
            if ($this->defaultStorage->fileExists('watch/'.$record['queuedFilename'])) {
                return $this->publicImport($this->repository->save('mobile-import', $id, [...$record, 'state' => 'queued'], $record['version']));
            }
            ++$record['attempt'];
            $queued = 'mobile-'.$id.'-'.$record['attempt'].'.'.ActivityFileName::fromString($record['filename'])->getExtension()->value;
            $record = $this->repository->save('mobile-import', $id, [...$record, 'queuedFilename' => $queued, 'state' => 'staging', 'error' => null], $record['version']);
            $actual = $this->watch->writeFile(ActivityFileName::fromString($queued), $this->defaultStorage->read('watch/.uploads/mobile-imports/'.$id));

            return $this->publicImport($this->repository->save('mobile-import', $id, [...$record, 'queuedFilename' => (string) $actual, 'state' => 'queued'], $record['version']));
        } finally {
            $lock->release();
        }
    }

    /**
     * @param array<string, mixed> $record
     *
     * @return array<string, mixed>
     */
    private function publicImport(array $record): array
    {
        unset($record['queuedFilename']);
        if ('staging' === $record['state']) {
            $record['state'] = 'interrupted';
            $record['error'] = '文件尚未完成排队，请重试以恢复。';
        }

        return $record;
    }

    /**
     * @return array<string, mixed>
     */
    public function syncJobs(): array
    {
        return ['records' => array_map(fn (array $job): array => $this->syncJob($job['id']), $this->repository->list('mobile-sync')),
            'sources' => ['daily' => $this->daily->status(), 'sleep' => $this->sleep->status(), 'workouts' => $this->workouts->status()]];
    }

    /**
     * @param array<string, mixed> $input
     *
     * @return array<string, mixed>
     */
    public function startSync(array $input): array
    {
        $this->known($input, ['kind', 'days']);
        $kind = $input['kind'] ?? null;
        $days = $input['days'] ?? 30;
        if (!in_array($kind, ['daily', 'sleep', 'workouts'], true) || !is_int($days) || $days < 1 || $days > 90) {
            throw new TrainingError('kind 必须为 daily、sleep 或 workouts，days 必须为 1 到 90 的整数。');
        }
        $lock = $this->workerLocks()->createLock('mobile-sync-submit', 30);
        if (!$lock->acquire()) {
            throw new TrainingError('同步正在排队，请稍后重试。', 409);
        }
        try {
            foreach ($this->repository->list('mobile-sync') as $job) {
                $job = $this->syncJob($job['id']);
                if ($job['kind'] === $kind && in_array($job['state'], ['queued', 'running'], true)) {
                    return $job;
                }
            }

            return $this->repository->save('mobile-sync', bin2hex(random_bytes(16)), ['kind' => $kind, 'days' => $days, 'state' => 'queued', 'result' => null, 'error' => null, 'startedAt' => null, 'finishedAt' => null], 0);
        } finally {
            $lock->release();
        }
    }

    /**
     * @return array<string, mixed>
     */
    public function syncJob(string $id): array
    {
        $job = $this->repository->find('mobile-sync', $id) ?? throw new TrainingError('同步任务不存在。', 404);
        if ('running' === $job['state']) {
            // The worker keeps a flock for its full lifetime. An absent owner is an interruption,
            // never a success and never a blind automatic replay of remote work.
            $lock = $this->workerLocks()->createLock('mobile-sync-job-'.$id, 1800);
            if ($lock->acquire()) {
                try {
                    $job = $this->repository->save('mobile-sync', $id, [...$job, 'state' => 'interrupted', 'error' => '同步进程中断，请核对来源状态后重新发起。', 'finishedAt' => $this->now()], $job['version']);
                } finally {
                    $lock->release();
                }
            }
        }

        return $job;
    }

    /** Run from the durable worker, never from a mobile HTTP request. */
    public function runQueued(int $limit = 10): int
    {
        $worker = $this->workerLocks()->createLock('mobile-workflows-worker');
        if (!$worker->acquire()) {
            return 0;
        }
        try {
            return $this->processQueued($limit, $worker);
        } finally {
            $worker->release();
        }
    }

    private function processQueued(int $limit, \Symfony\Component\Lock\LockInterface $worker): int
    {
        $processed = 0;
        foreach ($this->repository->list('mobile-sync') as $job) {
            if ($processed >= $limit || 'queued' !== $job['state']) {
                continue;
            }
            $worker->refresh();
            $lock = $this->workerLocks()->createLock('mobile-sync-job-'.$job['id'], 1800);
            if (!$lock->acquire()) {
                continue;
            }
            try {
                $job = $this->repository->find('mobile-sync', $job['id']) ?? throw new TrainingError('同步任务不存在。', 404);
                if ('queued' !== $job['state']) {
                    continue;
                }
                $job = $this->repository->save('mobile-sync', $job['id'], [...$job, 'state' => 'running', 'startedAt' => $this->now()], $job['version']);
                try {
                    $result = match ($job['kind']) {
                        'daily' => $this->daily->sync($job['days']),
                        'sleep' => $this->sleep->sync($job['days']),
                        'workouts' => $this->workouts->sync($job['days']),
                    };
                    $state = in_array($result['state'], ['success', 'partial', 'no_data'], true) ? $result['state'] : 'failed';
                    $error = $result['lastError'] ?? null;
                } catch (\Throwable $error) {
                    $result = null;
                    $state = 'failed';
                    // Raw exceptions can contain remote credentials or response bodies.
                    $error = $error instanceof TrainingError ? $error->getMessage() : '同步失败，请检查连接状态后重试。';
                }
                $this->repository->save('mobile-sync', $job['id'], [...$job, 'state' => $state, 'result' => $result, 'error' => $error, 'finishedAt' => $this->now()], $job['version']);
                ++$processed;
            } finally {
                $lock->release();
            }
        }

        return $processed;
    }

    private function workerLocks(): LockFactory
    {
        // Process-owned locks survive arbitrarily long remote syncs and are released by the
        // operating system on worker termination. The shared watch volume makes web and
        // daemon containers see the same ownership. Existing Zepp locks guard sources.
        return new LockFactory(new FlockStore($this->projectDir.'/watch/.uploads/mobile-locks'));
    }

    private function now(): string
    {
        return $this->clock->getCurrentDateTimeImmutable()->format(DATE_ATOM);
    }

    /**
     * @param array<string, mixed> $input
     * @param list<string>         $allowed
     */
    private function known(array $input, array $allowed): void
    {
        if (array_diff(array_keys($input), $allowed)) {
            throw new TrainingError('请求包含不支持的字段。');
        }
    }
}
