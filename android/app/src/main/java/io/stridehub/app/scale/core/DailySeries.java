package io.stridehub.app.scale.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class DailySeries {
    private DailySeries() {}

    /** Last reading on each captured local day, ascending by day; days=0 means all. */
    public static List<Measurement> latestPerDay(List<Measurement> records, int days, long now) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();
        String from = days > 0 ? today.minusDays(days - 1L).toString() : "";
        String to = today.toString();
        Map<String, Measurement> daily = new TreeMap<>();
        for (Measurement record : records) {
            if (record.measuredAt > now) continue;
            if (record.localDay == null || record.localDay.compareTo(from) < 0 || record.localDay.compareTo(to) > 0) continue;
            Measurement previous = daily.get(record.localDay);
            if (previous == null || record.measuredAt > previous.measuredAt ||
                    (record.measuredAt == previous.measuredAt && record.id > previous.id)) {
                daily.put(record.localDay, record);
            }
        }
        return new ArrayList<>(daily.values());
    }
}
