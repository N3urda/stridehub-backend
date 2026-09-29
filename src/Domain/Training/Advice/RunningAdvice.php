<?php

declare(strict_types=1);

namespace App\Domain\Training\Advice;

/** Explainable planning heuristics, without physiological scores or automatic plan changes. */
final class RunningAdvice
{
    /**
     * @param array<string, mixed>       $session
     * @param array<string, mixed>       $profile
     * @param array<string, mixed>       $forecast
     * @param array<string, mixed>|null  $checkIn
     * @param list<array<string, mixed>> $feedbackHistory
     *
     * @return array<string, mixed>
     */
    public function advise(array $session, array $profile, array $forecast, ?array $checkIn = null, array $feedbackHistory = []): array
    {
        $result = [
            'summary' => '信息不足，暂不能判断本次户外训练条件。',
            'clothing' => [],
            'reasons' => [],
            'warnings' => [],
            'training' => 'unknown',
            'alternatives' => [],
            'personalization' => [],
            'dataQuality' => [],
            'riskFactors' => [],
        ];
        if ('rest' === ($session['type'] ?? null)) {
            return array_replace($result, ['summary' => '按计划休息，不额外安排跑步。', 'training' => 'keep', 'reasons' => ['本次计划为休息日。']]);
        }
        [$thermalAdjustment, $personalization] = $this->personalize($profile, $feedbackHistory);
        $result['personalization'] = $personalization;
        if (null === $checkIn) {
            $result['dataQuality'][] = '未填写跑前睡眠、疲劳、酸痛和疼痛状态；当前无法判断个人恢复情况。';
        }
        if (!isset($profile['thermalPreference'])) {
            $result['dataQuality'][] = '未设置个人体感偏好，穿衣先按一般体感给出。';
        }
        $start = $this->date($session['startAt'] ?? null);
        $duration = $session['durationMinutes'] ?? null;
        if (!$start instanceof \DateTimeImmutable || !is_int($duration) || $duration < 1 || $duration > 1440) {
            $result['dataQuality'][] = '训练开始时间或预计时长缺失，无法匹配完整天气区间。';

            return $this->withCheckIn($result, $checkIn);
        }
        $allHours = is_array($forecast['hours'] ?? null) ? $forecast['hours'] : [];
        $hours = $this->hoursFor($allHours, $start->getTimestamp(), $duration);
        if ('available' !== ($forecast['status'] ?? null) || !$hours) {
            $result['dataQuality'][] = is_string($forecast['message'] ?? null) ? $forecast['message'] : '预报未覆盖完整训练时段，暂不生成天气判断或改期时段。';
            $result['clothing'][] = '临出门查看实际天气，选择可以增减的衣层。';

            return $this->withCheckIn($result, $checkIn);
        }

        $missing = [];
        foreach (['apparentTemperature' => '体感温度', 'humidity' => '湿度', 'precipitationProbability' => '降水概率', 'precipitation' => '降水量', 'windSpeed' => '风速', 'windGusts' => '阵风', 'weatherCode' => '天气现象', 'uvIndex' => '紫外线', 'isDay' => '昼夜信息'] as $key => $label) {
            if (count(array_filter($hours, static fn (array $hour): bool => isset($hour[$key]))) !== count($hours)) {
                $missing[] = $key;
                $result['dataQuality'][] = $label.'有缺失，未将未知数值当作零。';
            }
        }
        $conditions = $this->conditions($hours);
        if (null === $conditions['minimum'] || null === $conditions['maximum']) {
            $result['dataQuality'][] = '缺少可用的温度信息，无法生成穿衣与训练强度建议。';

            return $this->withCheckIn($result, $checkIn);
        }
        $type = $session['type'] ?? 'easy';
        $intense = in_array($type, ['tempo', 'interval', 'race'], true);
        $typeLabel = ['easy' => '轻松跑', 'long' => '长距离跑', 'tempo' => '节奏跑', 'interval' => '间歇跑', 'recovery' => '恢复跑', 'race' => '比赛'][$type] ?? '跑步';
        $severity = $this->severity($conditions, $duration, $intense);
        $result['training'] = [0 => 'keep', 1 => 'consider_easier', 2 => 'indoor_or_reschedule'][$severity];
        if (0 === $severity && array_intersect($missing, ['precipitation', 'windSpeed', 'windGusts', 'weatherCode'])) {
            $result['training'] = 'unknown';
        }
        $temperatureLabel = in_array('apparentTemperature', $missing, true) ? '温度（缺失体感值时参考气温）' : '体感温度';
        $result['reasons'][] = sprintf('覆盖全程 %d 分钟，%s约 %s–%s°C；以全程较不利条件判断。', $duration, $temperatureLabel, $this->number($conditions['minimum']), $this->number($conditions['maximum']));
        $result['reasons'][] = $intense ? $typeLabel.'强度较高，炎热时宜降低强度或缩短训练。' : '本次为'.$typeLabel.'，穿衣兼顾热身和持续跑动。';
        if ($duration >= 90) {
            $result['reasons'][] = '预计持续至少 90 分钟，需留意途中天气变化并提前安排补水和补给。';
        }
        if ($conditions['maximum'] >= 28) {
            $result['riskFactors'][] = 'heat';
            $result['warnings'][] = '体感偏热，减少暴晒并留意补水；出现明显不适时停止训练。';
        }
        if ($conditions['minimum'] <= 0) {
            $result['riskFactors'][] = 'cold';
            $result['warnings'][] = '体感接近或低于冰点，注意保暖和路面结冰。';
        }
        if ($conditions['storm']) {
            $result['riskFactors'][] = 'storm';
            $result['warnings'][] = '预报包含雷暴，建议改为室内或另选时段，出发前核对当地预警。';
        }
        if ($conditions['freezingRain']) {
            $result['riskFactors'][] = 'freezing_rain';
            $result['warnings'][] = '预报包含冻雨，路面可能结冰，建议避免本时段户外跑步。';
        }
        if (null !== $conditions['wind'] && ($conditions['wind'] >= 15 || ($conditions['gusts'] ?? 0) >= 30)) {
            $result['reasons'][] = sprintf('全程最大风速约 %s km/h，最大阵风约 %s km/h。', $this->number($conditions['wind']), null === $conditions['gusts'] ? '未知' : $this->number($conditions['gusts']));
        }
        if (($conditions['wind'] ?? 0) >= 25 || ($conditions['gusts'] ?? 0) >= 60) {
            $result['riskFactors'][] = 'wind';
        }
        if (($conditions['wind'] ?? 0) >= 40 || ($conditions['gusts'] ?? 0) >= 60) {
            $result['warnings'][] = '风或阵风较强，避开树木、高处和暴露路线，优先室内或改期。';
        }
        if (($conditions['rain'] ?? 0) >= 7.5) {
            $result['riskFactors'][] = 'heavy_rain';
            $result['warnings'][] = sprintf('小时降水量最高约 %s mm，有强降雨风险，建议室内或改期。', $this->number($conditions['rain']));
        } elseif (($conditions['rain'] ?? 0) > 0 || ($conditions['probability'] ?? 0) >= 30) {
            $result['reasons'][] = sprintf('最高降水概率 %s%%，小时降水量最高约 %s mm；概率与雨量分别判断。', null === $conditions['probability'] ? '未知' : $this->number($conditions['probability']), null === $conditions['rain'] ? '未知' : $this->number($conditions['rain']));
        }
        if (($conditions['humidity'] ?? 0) >= 80 && $conditions['maximum'] >= 25) {
            $result['reasons'][] = '湿度较高且偏热，体感温度已包含湿度影响，训练宜更保守。';
        }

        $clothingTemperature = $conditions['minimum'] + $thermalAdjustment + ($intense ? 2 : 0);
        $result['clothing'] = match (true) {
            $clothingTemperature <= 5 => ['排汗长袖打底，搭配轻量保暖层和长裤。', '备薄手套、护耳或帽子，热身后按体感增减。'],
            $clothingTemperature <= 12 => ['轻薄长袖搭配长裤或七分裤，备可收纳的外层。'],
            $clothingTemperature <= 18 => ['轻薄长袖或短袖配可脱卸袖套，避免穿得过厚。'],
            default => ['选择轻薄透气的速干短袖或背心，搭配跑步短裤。'],
        };
        if (($conditions['wind'] ?? 0) >= 20 && $conditions['minimum'] < 18) {
            $result['clothing'][] = '加一件轻薄防风外层，跑热后及时调整。';
        }
        if (($conditions['rain'] ?? 0) >= 0.5 || ($conditions['probability'] ?? 0) >= 50) {
            $result['clothing'][] = '备轻量防雨外层或帽檐，注意透气、防滑及电子设备防水。';
        }
        if ($conditions['night']) {
            $result['clothing'][] = '加入反光装备或照明，选择熟悉且有照明的路线。';
            $result['reasons'][] = '训练覆盖非日照时段，需要提高可见性。';
        }
        if (($conditions['uv'] ?? 0) >= 3 && $conditions['day']) {
            $result['clothing'][] = '日照时段加帽子、太阳镜并做好防晒。';
            $result['reasons'][] = '逐小时紫外线指数最高约 '.$this->number($conditions['uv']).'。';
        }
        if ($conditions['maximum'] - $conditions['minimum'] >= 6) {
            $result['clothing'][] = '全程温差较大，优先可脱卸的分层穿法。';
        }
        $result['dataQuality'][] = '建议来自逐小时预报与经验规则，不代表个人生理准备度；预报无法反映所有路线微气候。';
        if ($severity > 0 && !($checkIn['pain'] ?? false)) {
            $result['alternatives'] = $this->alternatives($allHours, $start, $duration, $intense, $severity, $forecast);
        }
        $result['summary'] = match ($result['training']) {
            'keep' => '预报支持按计划进行，出门前再结合体感和实况确认。',
            'consider_easier' => '建议降低强度或缩短本次训练，并按全程天气调整穿衣。',
            'indoor_or_reschedule' => '本时段天气不利，建议改为室内训练或选择天气较缓和的时段。',
            default => '部分关键天气数据缺失，暂不能完整判断户外训练条件。',
        };

        return $this->withCheckIn($result, $checkIn);
    }

    /**
     * @param array<string, mixed>       $profile
     * @param list<array<string, mixed>> $history
     *
     * @return array{float, list<string>}
     */
    private function personalize(array $profile, array $history): array
    {
        $preference = $profile['thermalPreference'] ?? 'neutral';
        $adjustment = match ($preference) {
            'cold' => -3.0,
            'warm' => 3.0,
            default => 0.0,
        };
        $notes = [match ($preference) {
            'cold' => '按怕冷偏好增加穿衣保暖倾向。',
            'warm' => '按怕热偏好减少穿衣厚度。',
            default => '按一般体感选择衣层。',
        }];
        $feelings = [];
        foreach ($history as $entry) {
            $feeling = $entry['thermalFeeling'] ?? $entry['feedback']['thermalFeeling'] ?? null;
            if (in_array($feeling, ['cold', 'comfortable', 'hot'], true)) {
                $feelings[] = $feeling;
            }
        }
        $feelings = array_slice($feelings, -5);
        if (count($feelings) >= 2) {
            $counts = array_count_values($feelings);
            if (($counts['cold'] ?? 0) > count($feelings) / 2) {
                $adjustment -= 2;
                $notes[] = sprintf('最近 %d 次体感反馈中多数偏冷，穿衣建议向保暖微调。', count($feelings));
            } elseif (($counts['hot'] ?? 0) > count($feelings) / 2) {
                $adjustment += 2;
                $notes[] = sprintf('最近 %d 次体感反馈中多数偏热，穿衣建议向轻薄微调。', count($feelings));
            } else {
                $notes[] = sprintf('已参考最近 %d 次体感反馈，暂无一致的冷暖偏向。', count($feelings));
            }
            $notes[] = '体感反馈只用于衣层微调，尚未按气候或强度建立个人模型。';
        } else {
            $notes[] = '体感反馈不足两次，暂不进行历史反馈微调。';
        }

        return [$adjustment, $notes];
    }

    /**
     * @param array<string, mixed>      $result
     * @param array<string, mixed>|null $checkIn
     *
     * @return array<string, mixed>
     */
    private function withCheckIn(array $result, ?array $checkIn): array
    {
        if (null === $checkIn) {
            return $result;
        }
        $incomplete = false;
        foreach (['fatigue' => '疲劳', 'soreness' => '酸痛', 'pain' => '疼痛'] as $field => $label) {
            if (null === ($checkIn[$field] ?? null)) {
                $incomplete = true;
                $result['dataQuality'][] = '跑前记录缺少'.$label.'状态，不能视作正常或无不适。';
            }
        }
        $ease = false;
        if (isset($checkIn['sleepHours']) && is_numeric($checkIn['sleepHours']) && $checkIn['sleepHours'] < 6) {
            $ease = true;
            $result['riskFactors'][] = 'poor_sleep';
            $result['reasons'][] = '跑前记录显示睡眠不足 6 小时，建议优先恢复，酌情减轻训练。';
        } elseif (!isset($checkIn['sleepHours'])) {
            $incomplete = true;
            $result['dataQuality'][] = '跑前记录缺少睡眠时长。';
        }
        if (($checkIn['fatigue'] ?? 0) >= 4 || ($checkIn['soreness'] ?? 0) >= 4) {
            $ease = true;
            $result['riskFactors'][] = 'fatigue';
            $result['reasons'][] = '自评疲劳或酸痛较明显，建议降低强度并根据实际感受决定是否继续。';
        }
        if (true === ($checkIn['pain'] ?? false)) {
            $ease = true;
            $result['riskFactors'][] = 'pain';
            $result['summary'] = '已记录疼痛，建议先暂停跑步，重新评估本次训练。';
            $result['warnings'][] = '已记录疼痛，先暂停跑步；持续或明显不适时寻求专业意见。';
        }
        if ($ease && 'indoor_or_reschedule' !== $result['training']) {
            $result['training'] = 'consider_easier';
            $result['summary'] = ($checkIn['pain'] ?? false) ? '已记录疼痛，建议先暂停跑步，重新评估本次训练。' : '跑前状态提示需要恢复，建议降低强度或缩短训练。';
        }
        if ($incomplete && 'keep' === $result['training']) {
            $result['summary'] = '预报未显示明显天气障碍；跑前状态尚未填写完整，请结合实际感受再决定。';
        }

        return $result;
    }

    /**
     * @param array<mixed> $hours
     *
     * @return list<array<string, mixed>>
     */
    private function hoursFor(array $hours, int $start, int $duration): array
    {
        $end = $start + $duration * 60;
        $selected = [];
        foreach ($hours as $hour) {
            if (!is_array($hour) || !(($time = $this->date($hour['time'] ?? null)) instanceof \DateTimeImmutable)) {
                continue;
            }
            $timestamp = $time->getTimestamp();
            if ($timestamp < $end && $timestamp + 3600 > $start) {
                $selected[$timestamp] = $hour;
            }
        }
        ksort($selected);
        $covered = $start;
        foreach ($selected as $timestamp => $hour) {
            if ($timestamp > $covered) {
                return [];
            }
            $covered = $timestamp + 3600;
        }

        return $covered >= $end ? array_values($selected) : [];
    }

    /**
     * @param list<array<string, mixed>> $hours
     *
     * @return array<string, mixed>
     */
    private function conditions(array $hours): array
    {
        $values = static fn (string $key): array => array_values(array_filter(array_column($hours, $key), static fn (mixed $value): bool => (is_int($value) || is_float($value)) && is_finite((float) $value)));
        $temperatures = [];
        foreach ($hours as $hour) {
            $value = $hour['apparentTemperature'] ?? $hour['temperature'] ?? null;
            if ((is_int($value) || is_float($value)) && is_finite((float) $value)) {
                $temperatures[] = (float) $value;
            }
        }
        $maximum = static fn (array $values): ?float => [] !== $values ? (float) max($values) : null;
        $codes = $values('weatherCode');
        $day = array_column($hours, 'isDay');

        return [
            'minimum' => [] !== $temperatures ? min($temperatures) : null,
            'maximum' => $maximum($temperatures),
            'humidity' => $maximum($values('humidity')),
            'rain' => $maximum($values('precipitation')),
            'probability' => $maximum($values('precipitationProbability')),
            'wind' => $maximum($values('windSpeed')),
            'gusts' => $maximum($values('windGusts')),
            'uv' => $maximum($values('uvIndex')),
            'storm' => (bool) array_intersect($codes, [95, 96, 99]),
            'freezingRain' => (bool) array_intersect($codes, [56, 57, 66, 67]),
            'night' => in_array(false, $day, true) || in_array(0, $day, true),
            'day' => in_array(true, $day, true) || in_array(1, $day, true),
        ];
    }

    /** @param array<string, mixed> $conditions */
    private function severity(array $conditions, int $duration, bool $intense): int
    {
        if ($conditions['storm'] || $conditions['freezingRain'] || ($conditions['rain'] ?? 0) >= 7.5 || ($conditions['wind'] ?? 0) >= 40 || ($conditions['gusts'] ?? 0) >= 60 || ($conditions['maximum'] ?? 0) >= 35 || ($conditions['minimum'] ?? 10) <= -10) {
            return 2;
        }
        if (($conditions['maximum'] ?? 0) >= 28 || ($conditions['minimum'] ?? 10) <= 0 || ($conditions['rain'] ?? 0) >= 2.5 || ($conditions['wind'] ?? 0) >= 25 || (($intense || $duration >= 90) && ($conditions['maximum'] ?? 0) >= 25)) {
            return 1;
        }

        return 0;
    }

    /**
     * @param array<mixed>         $hours
     * @param array<string, mixed> $forecast
     *
     * @return list<array<string, mixed>>
     */
    private function alternatives(array $hours, \DateTimeImmutable $start, int $duration, bool $intense, int $currentSeverity, array $forecast): array
    {
        $now = $this->date($forecast['requestedAt'] ?? $forecast['fetchedAt'] ?? null);
        if (!$now instanceof \DateTimeImmutable) {
            return [];
        }
        if (is_string($forecast['timezone'] ?? null)) {
            try {
                $start = $start->setTimezone(new \DateTimeZone($forecast['timezone']));
            } catch (\Exception) {
                return [];
            }
        }
        $alternatives = [];
        foreach ([-120, -60, 60, 120] as $minutes) {
            $candidate = $start->setTimestamp($start->getTimestamp() + $minutes * 60);
            $candidateHours = $this->hoursFor($hours, $candidate->getTimestamp(), $duration);
            if ($candidate < $now || !$candidateHours) {
                continue;
            }
            foreach ($candidateHours as $hour) {
                foreach (['apparentTemperature', 'precipitation', 'windSpeed', 'windGusts', 'weatherCode'] as $key) {
                    if (!isset($hour[$key])) {
                        continue 3;
                    }
                }
            }
            $conditions = $this->conditions($candidateHours);
            $severity = $this->severity($conditions, $duration, $intense);
            if ($severity >= $currentSeverity) {
                continue;
            }
            $end = $candidate->setTimestamp($candidate->getTimestamp() + $duration * 60);
            $alternatives[] = [
                'startAt' => $candidate->format(DATE_ATOM),
                'endAt' => $end->format(DATE_ATOM),
                'label' => $candidate->format('m-d H:i').'–'.$end->format('H:i'),
                'reasons' => [sprintf('该时段完整 %d 分钟预报的天气风险较原时段降低；体感约 %s–%s°C。', $duration, $this->number($conditions['minimum']), $this->number($conditions['maximum'])), '仅供调整计划时参考，尚未修改原训练；出发前仍需复核。'],
            ];
        }

        return array_slice($alternatives, 0, 2);
    }

    private function date(mixed $value): ?\DateTimeImmutable
    {
        if (!is_string($value) || !preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:[+-]\d{2}:\d{2}|Z)$/D', $value)) {
            return null;
        }
        try {
            return new \DateTimeImmutable($value);
        } catch (\Exception) {
            return null;
        }
    }

    private function number(float $value): string
    {
        return rtrim(rtrim(number_format($value, 1, '.', ''), '0'), '.');
    }
}
