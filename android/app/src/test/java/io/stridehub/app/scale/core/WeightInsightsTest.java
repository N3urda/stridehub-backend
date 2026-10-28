package io.stridehub.app.scale.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public final class WeightInsightsTest {
    private static int checks;
    private static final String TODAY = "2026-09-29";
    private static long now;

    public static void main(String[] args) {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            now = time(TODAY, 18);
            calendarWindows();
            regressionAndChange();
            insufficientAndInvalid();
            movingMeans();
            System.out.println("WeightInsightsTest: " + checks + " checks passed");
        } finally { TimeZone.setDefault(previous); }
    }

    private static void calendarWindows() {
        Measurement oldSameDay = reading("2026-09-29", 60, 8, 1);
        Measurement latest = reading("2026-09-29", 80, 10, 2);
        Measurement tie = reading("2026-09-29", 82, 10, 3);
        List<Measurement> input = Arrays.asList(latest, reading("2026-09-22", 70),
                oldSameDay, reading("2026-08-31", 90), tie,
                reading("2026-09-23", 78), reading("2026-08-30", 100),
                reading("2026-09-30", 150), reading(TODAY, 160, 20, 9));
        WeightInsights.Summary summary = WeightInsights.summarize(input, now);
        near(summary.average7.mean, 80, "7 calendar day mean uses latest only and excludes future");
        eq(summary.average7.count, 2, "missing days not filled");
        same(summary.average7.fromDay, "2026-09-23", "7day inclusive start");
        same(summary.average7.toDay, TODAY, "window ends today");
        near(summary.average30.mean, 80, "30day inclusive window");
        eq(summary.average30.count, 4, "August31 in, August30 out");
        eq(summary.totalDays, 5, "entire captured history");
        same(summary.firstDay, "2026-08-30", "first captured day");
        same(summary.lastDay, TODAY, "last captured day");
        near(summary.totalKg, -18, "cumulative kg");
        near(summary.totalPercent, -18, "cumulative percent relative first");
        eq(input.size(), 9, "input unchanged");
    }

    private static void regressionAndChange() {
        List<Measurement> linear = new ArrayList<>();
        for (int i = 0; i < 28; i++)
            linear.add(reading(LocalDate.parse(TODAY).minusDays(i).toString(), 70 + i * .1));
        linear.add(reading("2026-09-01", 200)); // Outside regression window.
        Collections.reverse(linear);
        WeightInsights.Summary result = WeightInsights.summarize(linear, now);
        near(result.weeklyKg, -.7, "regression exact linear slope");
        near(result.weeklyPercent, -.7 / 71.35 * 100, "speed percent relative mean weight");
        eq(result.trendCount, 28, "regression28days");
        same(result.trendFromDay, "2026-09-02", "actual regression first date");
        same(result.trendToDay, TODAY, "actual regression last date");

        List<Measurement> sparse = Arrays.asList(reading("2026-09-02", 78.1),
                reading("2026-09-09", 76), reading("2026-09-29", 70));
        near(WeightInsights.summarize(sparse, now).weeklyKg, -2.1, "uses dates not sample indices");
        near(WeightInsights.summarize(Arrays.asList(reading("2026-09-22", 80),
                reading("2026-09-25", 80), reading(TODAY, 80)), now).weeklyKg, 0, "constant series valid zero");
        List<Measurement> eightDays = Arrays.asList(reading("2026-09-22", 72),
                reading("2026-09-25", 72.6), reading(TODAY, 73.4));
        near(WeightInsights.summarize(eightDays, now).weeklyKg, 1.4, "exact 7elapsed days allowed");
    }

    private static void insufficientAndInvalid() {
        WeightInsights.Summary empty = WeightInsights.summarize(Collections.emptyList(), now);
        missing(empty.average7.mean, "empty average");
        missing(empty.weeklyKg, "empty weekly");
        missing(empty.totalKg, "empty total");
        eq(empty.totalDays, 0, "empty count");
        WeightInsights.Summary one = WeightInsights.summarize(Arrays.asList(reading(TODAY, 70)), now);
        near(one.average7.mean, 70, "one valid average with count");
        missing(one.totalPercent, "one day has no change");
        missing(one.weeklyPercent, "one day no speed");
        missing(WeightInsights.summarize(Arrays.asList(reading("2026-09-22", 73), reading(TODAY, 70)), now).weeklyKg, "two days no trend");
        missing(WeightInsights.summarize(Arrays.asList(reading("2026-09-23", 73),
                reading("2026-09-25", 72), reading(TODAY, 70)), now).weeklyKg, "too short span");
        WeightInsights.Summary stale = WeightInsights.summarize(Arrays.asList(reading("2026-08-01", 70),
                reading("2026-08-02", 71)), now);
        missing(stale.average30.mean, "old data is not recent");
        near(stale.totalKg, 1, "dated all-history change still valid");

        Measurement malformed = reading(TODAY, 60); malformed.localDay = "bad-date";
        Measurement invalidLast = reading(TODAY, Double.NaN, 14, 4);
        WeightInsights.Summary invalid = WeightInsights.summarize(Arrays.asList(null, malformed,
                reading(TODAY, 70, 8, 2), invalidLast, reading("2026-09-28", 0),
                reading("2026-09-27", Double.POSITIVE_INFINITY)), now);
        missing(invalid.average7.mean, "invalid latest not replaced by earlier measurement");
        eq(invalid.totalDays, 0, "invalid readings excluded");
    }

    private static void movingMeans() {
        List<Measurement> readings = Arrays.asList(reading("2026-09-01", 70),
                reading("2026-09-07", 84), reading("2026-09-08", 90),
                reading(TODAY, 100), reading("2026-09-30", 200));
        Map<String, Double> means = WeightInsights.movingAverages(readings, 7, now);
        eq(means.size(), 4, "only observed past dates have means");
        near(means.get("2026-09-01"), 70, "first day partial window");
        near(means.get("2026-09-07"), 77, "seventh day includes first day");
        near(means.get("2026-09-08"), 87, "eighth day expires first day");
        near(means.get(TODAY), 100, "gap no carry forward");
        missing(means.get("2026-09-02"), "no invented missing date");
        near(WeightInsights.movingAverages(readings, 30, now).get(TODAY), 86, "30days include prior displayed-range history");
        near(WeightInsights.movingAverages(readings, 1, now).get("2026-09-08"), 90, "1day raw point");
        boolean rejected = false;
        try { WeightInsights.movingAverages(readings, 0, now); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid window rejected");
    }

    private static Measurement reading(String day, double kg) { return reading(day, kg, 12, 1); }
    private static Measurement reading(String day, double kg, int hour, long id) {
        Measurement m = new Measurement(); m.id = id; m.localDay = day;
        m.measuredAt = time(day, hour); m.weightKg = kg; return m;
    }
    private static long time(String day, int hour) {
        return LocalDate.parse(day).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
    private static void eq(int actual, int expected, String name) { check(actual == expected, name + ": " + actual); }
    private static void same(String actual, String expected, String name) { check(expected.equals(actual), name + ": " + actual); }
    private static void missing(Object value, String name) { check(value == null, name + ": " + value); }
    private static void near(Double actual, double expected, String name) { check(actual != null && Math.abs(actual - expected) < 1e-8, name + ": " + actual + " != " + expected); }
    private static void check(boolean passed, String name) { checks++; if (!passed) throw new AssertionError(name); }
}
