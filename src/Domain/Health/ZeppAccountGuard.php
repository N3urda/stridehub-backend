<?php

declare(strict_types=1);

namespace App\Domain\Health;

use App\Domain\Training\TrainingRepository;

/** One database owner across every Zepp importer, including independent containers. */
final readonly class ZeppAccountGuard
{
    public static function claim(TrainingRepository $repository, string $userId): string
    {
        $account = hash('sha256', 'stridehub-zepp-sleep:'.$userId);

        return $repository->transactional(static function () use ($repository, $account): string {
            $owner = $repository->find('zepp-account', 'default');
            foreach ([$owner, $repository->find('sleep-sync', 'zepp'), $repository->find('zepp-sync', 'default'), $repository->find('zepp-workout-sync', 'default')] as $previous) {
                if (isset($previous['accountKey']) && !hash_equals($previous['accountKey'], $account)) {
                    throw new ZeppSyncError('error', 'Zepp 账号与已保存记录不一致。', '请恢复原账号授权；不同账号需独立数据库，避免混入其他人的数据。');
                }
            }
            if (null === $owner) {
                $repository->save('zepp-account', 'default', ['accountKey' => $account], 0);
            }

            return $account;
        });
    }
}
