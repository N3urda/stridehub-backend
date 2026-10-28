package io.stridehub.app.scale.core;

import java.time.*;
import java.util.*;

public final class RecordsTest {
    static int assertions;
    static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    static long time(String date) { return LocalDateTime.parse(date).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    static void rejects(Runnable action) { try { action.run(); throw new AssertionError("Invalid value accepted"); } catch (IllegalArgumentException expected) { assertions++; } }
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        long now = time("2026-09-28T12:00:00");
        Profile profile = new Profile(175, "1990-09-29", true, 1);
        check(profile.ageAt(now) == 35, "Age before birthday");
        check(profile.ageAt(time("2026-09-29T00:00:00")) == 36, "Age on birthday");
        check(profile.validationError(now) == null, "Valid profile");
        check(new Profile(175, "1946-09-28", true, 65535).validationError(now) == null, "Age and id upper bounds");
        check(new Profile(175, "1945-09-28", true, 1).validationError(now) != null, "Reject unsupported age");
        check(new Profile(175, "1990-09-28", true, 65536).validationError(now) != null, "Reject oversized id");
        check(new Profile(0, "1990-01-01", true, 1).validationError(now) != null, "Missing height");
        check(new Profile(175, "2026-02-30", true, 1).validationError(now) != null, "Impossible date");
        check(new Profile(175, "2027-01-01", true, 1).validationError(now) != null, "Future birthday");
        Measurement a = Measurement.create(profile, time("2026-09-27T09:00:00"), 70, 20.0, 400, "manual", null, null);
        Measurement b = Measurement.create(profile, time("2026-09-27T20:00:00"), 71, null, 0, "manual", null, null);
        Measurement c = Measurement.create(null, time("2026-09-28T09:00:00"), 69, 19.0, 0, "manual", null, null);
        Measurement old = Measurement.create(null, time("2026-09-21T09:00:00"), 72, null, 0, "manual", null, null);
        check(a.localDay.equals("2026-09-27"), "Local measurement day");
        check(Math.abs(a.bmi - 22.8571428) < .0001, "BMI uses profile height");
        check(b.bodyFatPercent == null, "Absent fat remains null");
        check(c.bmi == null, "Absent profile does not invent BMI");
        check(a.profileSnapshot.contains("1990-09-29"), "Profile snapshot preserved");
        check(Measurement.create(null, now, 2, 1.0, 0, "manual", null, null).weightKg == 2, "Lower bound");
        check(Measurement.create(null, now, 300, 75.0, 0, "manual", null, null).weightKg == 300, "Upper bound");
        rejects(() -> Measurement.create(null, now, Double.NaN, null, 0, "manual", null, null));
        rejects(() -> Measurement.create(null, now, Double.POSITIVE_INFINITY, null, 0, "manual", null, null));
        rejects(() -> Measurement.create(null, now, 1.9, null, 0, "manual", null, null));
        rejects(() -> Measurement.create(null, now, 70, 0.0, 0, "manual", null, null));
        rejects(() -> Measurement.create(null, now, 70, Double.NaN, 0, "manual", null, null));
        List<Measurement> records = Arrays.asList(c, a, old, b);
        List<Measurement> all = DailySeries.latestPerDay(records, 0, now);
        check(all.size() == 3 && all.get(0) == old && all.get(1) == b && all.get(2) == c, "Chronological daily latest, no averaging");
        check(all.get(1).bodyFatPercent == null, "Missing latest fat does not inherit earlier fat");
        List<Measurement> week = DailySeries.latestPerDay(records, 7, now);
        check(week.size() == 2 && week.get(0) == b, "7 days includes today and prior six days");
        Measurement sameTime = Measurement.create(null, c.measuredAt, 68, 18.0, 0, "manual", null, null);
        c.id = 1; sameTime.id = 2;
        check(DailySeries.latestPerDay(Arrays.asList(sameTime,c), 0, now).get(0) == sameTime, "Tie broken by newest persisted id");
        check(DailySeries.latestPerDay(Collections.emptyList(), 30, now).isEmpty(), "Empty remains empty");
        Measurement futureToday = Measurement.create(null, time("2026-09-28T13:00:00"), 90, 25.0, 0, "manual", null, null);
        List<Measurement> noFuture = DailySeries.latestPerDay(Arrays.asList(futureToday, c), 0, now);
        check(noFuture.size() == 1 && noFuture.get(0) == c, "Future timestamp on today's date cannot replace the observed daily reading");
        System.out.println("RecordsTest: " + assertions + " assertions passed");
    }
}
