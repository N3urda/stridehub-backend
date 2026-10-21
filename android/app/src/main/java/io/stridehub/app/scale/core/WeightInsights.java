package io.stridehub.app.scale.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Calendar-day statistics of captured weight readings, never medical predictions. */
public final class WeightInsights {
    private WeightInsights() {}

    public static final class Window {
        public Double mean;
        public int count;
        public String fromDay, toDay;
    }

    public static final class Summary {
        public Window average7 = new Window(), average30 = new Window();
        public Double weeklyKg, weeklyPercent, totalKg, totalPercent;
        public int trendCount, totalDays;
        public String trendFromDay, trendToDay, firstDay, lastDay;
    }

    public static Summary summarize(List<Measurement> records, long now) {
        List<Measurement> daily = validDaily(records, now);
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();
        Summary result = new Summary();
        result.average7 = window(daily, today, 7);
        result.average30 = window(daily, today, 30);
        result.totalDays = daily.size();
        if (!daily.isEmpty()) {
            Measurement first = daily.get(0), last = daily.get(daily.size() - 1);
            result.firstDay = first.localDay;
            result.lastDay = last.localDay;
            if (daily.size() > 1) {
                result.totalKg = last.weightKg - first.weightKg;
                result.totalPercent = result.totalKg / first.weightKg * 100;
            }
        }
        List<Measurement> recent = new ArrayList<>();
        String from = today.minusDays(27).toString();
        for (Measurement measurement : daily)
            if (measurement.localDay.compareTo(from) >= 0) recent.add(measurement);
        result.trendCount = recent.size();
        if (recent.isEmpty()) return result;
        result.trendFromDay = recent.get(0).localDay;
        result.trendToDay = recent.get(recent.size() - 1).localDay;
        long firstDay = LocalDate.parse(result.trendFromDay).toEpochDay();
        long elapsedDays = LocalDate.parse(result.trendToDay).toEpochDay() - firstDay;
        if (recent.size() < 3 || elapsedDays < 7) return result;
        double meanDay = 0, meanWeight = 0;
        for (Measurement measurement : recent) {
            meanDay += LocalDate.parse(measurement.localDay).toEpochDay() - firstDay;
            meanWeight += measurement.weightKg;
        }
        meanDay /= recent.size();
        meanWeight /= recent.size();
        double covariance = 0, variance = 0;
        for (Measurement measurement : recent) {
            double day = LocalDate.parse(measurement.localDay).toEpochDay() - firstDay - meanDay;
            covariance += day * (measurement.weightKg - meanWeight);
            variance += day * day;
        }
        result.weeklyKg = 7 * covariance / variance;
        result.weeklyPercent = result.weeklyKg / meanWeight * 100;
        return result;
    }

    public static Map<String, Double> movingAverages(List<Measurement> records, int windowDays, long now) {
        if (windowDays < 1) throw new IllegalArgumentException("Window must contain at least one day");
        List<Measurement> daily = validDaily(records, now);
        Map<String, Double> averages = new TreeMap<>();
        int first = 0;
        double sum = 0;
        for (int last = 0; last < daily.size(); last++) {
            Measurement current = daily.get(last);
            sum += current.weightKg;
            String from = LocalDate.parse(current.localDay).minusDays(windowDays - 1L).toString();
            while (daily.get(first).localDay.compareTo(from) < 0)
                sum -= daily.get(first++).weightKg;
            averages.put(current.localDay, sum / (last - first + 1));
        }
        return averages;
    }

    private static Window window(List<Measurement> daily, LocalDate today, int days) {
        Window result = new Window();
        result.fromDay = today.minusDays(days - 1L).toString();
        result.toDay = today.toString();
        double sum = 0;
        for (Measurement measurement : daily) {
            if (measurement.localDay.compareTo(result.fromDay) < 0) continue;
            sum += measurement.weightKg;
            result.count++;
        }
        if (result.count > 0) result.mean = sum / result.count;
        return result;
    }

    private static List<Measurement> validDaily(List<Measurement> records, long now) {
        List<Measurement> captured = new ArrayList<>();
        if (records != null) for (Measurement measurement : records) {
            if (measurement == null || measurement.measuredAt <= 0 || measurement.measuredAt > now) continue;
            try {
                LocalDate.parse(measurement.localDay);
                captured.add(measurement);
            } catch (RuntimeException invalidDate) { /* Invalid dates cannot enter calendar windows. */ }
        }
        List<Measurement> valid = new ArrayList<>();
        // Select the day's final reading first; a missing/invalid last value never reuses an older reading.
        for (Measurement measurement : DailySeries.latestPerDay(captured, 0, now))
            if (Double.isFinite(measurement.weightKg) && measurement.weightKg >= 2 && measurement.weightKg <= 300)
                valid.add(measurement);
        return valid;
    }
}
