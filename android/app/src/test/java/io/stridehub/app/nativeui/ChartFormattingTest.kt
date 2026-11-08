package io.stridehub.app.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChartFormattingTest {
    @Test fun paceAndDurationUseReadableTimeAndNeverRenderMissingAsZero() {
        assertEquals("5:08 /km",ChartValueFormat.forMetric("配速 · 秒/km","paceSecondsPerKm").label(308.0))
        assertEquals("1:01:02",ChartValueFormat.forMetric("运动时长","movingSeconds").value(3662.0))
        assertEquals("未记录",ChartValueFormat("kg").label(null))
        assertEquals("未记录",ChartValueFormat("kg").label(Double.NaN))
        assertEquals("0 步",ChartValueFormat.forMetric("每日步数","value").label(0.0))
    }
    @Test fun healthUnitsCannotBeMistakenForSimilarPrefixes() {
        assertEquals("120 mmHg",ChartValueFormat.forMetric("收缩压 · mmHg","systolic").label(120.0))
        assertEquals("82.5 cm",ChartValueFormat.forMetric("腰围 · cm","waistCm").label(82.5))
        assertEquals("62 次/分",ChartValueFormat.forMetric("脉搏 · 次/分","pulse").label(62.0))
        assertEquals("7.5 小时",ChartValueFormat.forMetric("主观睡眠 · 小时","sleepHours").label(7.5))
        assertEquals("15 m",ChartValueFormat.forMetric("海拔 · m","meters").label(15.0))
        assertEquals("3 分",ChartValueFormat.forMetric("疲劳 · 分","fatigue").label(3.0))
        assertEquals("172 步/分",ChartValueFormat.forMetric("步频 · 步/分","stepsPerMinute").label(172.0))
        assertEquals("4.5 %",ChartValueFormat.forMetric("配速变异 · %","paceVariationPercent").label(4.5))
    }
    @Test fun timeAxesKeepRecordedTimezoneAndDistanceIsNotFormattedAsSeconds() {
        val sample=JSONObject("{\"at\":\"2026-11-08T06:32:00+08:00\"}")
        assertEquals("06:32",ChartPresentation.axisLabel(sample,0))
        assertEquals("2026年11月8日 06:32",ChartPresentation.axisLabel(sample,0,full=true))
        assertEquals("1:02",ChartPresentation.axisLabel(JSONObject("{\"seconds\":62}"),0))
        assertEquals("1.25 km",ChartPresentation.axisLabel(JSONObject("{\"seconds\":1.25}"),0,ChartAxis.DISTANCE))
        assertEquals("2026年11月8日",ChartPresentation.axisLabel(JSONObject("{\"date\":\"2026-11-08\"}"),0,full=true))
    }
    @Test fun multiDayCapturesHaveDateAxesWhileDailyStreamsKeepTimeAxes() {
        val first=JSONObject("{\"at\":\"2026-11-07T07:30:00+08:00\"}")
        val next=JSONObject("{\"at\":\"2026-11-08T07:30:00+08:00\"}")
        val nextMinute=JSONObject("{\"at\":\"2026-11-07T07:31:00+08:00\"}")
        assertEquals(ChartAxis.DATE,ChartPresentation.axisFor(listOf(first,next)))
        assertEquals("11/7",ChartPresentation.axisLabel(first,0,ChartAxis.DATE))
        assertEquals("11/8",ChartPresentation.axisLabel(next,1,ChartAxis.DATE))
        assertEquals(ChartAxis.TIME,ChartPresentation.axisFor(listOf(first,nextMinute)))
        assertEquals("07:31",ChartPresentation.axisLabel(nextMinute,1,ChartAxis.TIME))
        assertEquals(ChartAxis.TIME,ChartPresentation.axisFor(listOf(first,next),ChartAxis.TIME))
    }
    @Test fun chartRangesGiveIsolatedAndFlatReadingsRoomWithoutClippingZero() {
        val flat=ChartPresentation.range(listOf(72.0))
        assertTrue(flat.first<72.0);assertTrue(flat.second>72.0)
        val zero=ChartPresentation.range(listOf(0.0))
        assertEquals(0.0,zero.first,0.0);assertTrue(zero.second>0.0)
        val negative=ChartPresentation.range(listOf(-2.0,4.0))
        assertTrue(negative.first < -2);assertTrue(negative.second > 4)
    }
}
