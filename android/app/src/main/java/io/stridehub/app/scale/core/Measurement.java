package io.stridehub.app.scale.core;

import java.time.Instant;
import java.time.ZoneId;

/** Every stable scale result is a separate record; nullable values stay unknown. */
public final class Measurement {
    public long id;
    public long measuredAt;
    public String localDay;
    public double weightKg;
    public Double bodyFatPercent;
    public int impedance;
    public Double bmi;
    public String source;
    public String deviceAddress;
    public String rawPacket;
    public String profileSnapshot;
    public BodyMetrics metrics;
    public long metricsCalculatedAt;
    public String metricsProfileSnapshot;

    public static Measurement create(Profile profile, long when, double weight, Double fat,
                                     int impedance, String source, String device, String raw) {
        if (!Double.isFinite(weight) || weight < 2 || weight > 300)
            throw new IllegalArgumentException("体重应在 2–300 千克之间");
        if (fat != null && (!Double.isFinite(fat) || fat < 1 || fat > 75))
            throw new IllegalArgumentException("体脂率应在 1–75% 之间，未知时请留空");
        if (when <= 0) throw new IllegalArgumentException("测量时间无效");
        Measurement value = new Measurement();
        value.measuredAt = when;
        value.localDay = Instant.ofEpochMilli(when).atZone(ZoneId.systemDefault()).toLocalDate().toString();
        value.weightKg = weight;
        value.bodyFatPercent = fat;
        value.impedance = Math.max(0, impedance);
        value.source = source == null ? "unknown" : source;
        value.deviceAddress = device;
        value.rawPacket = raw;
        if (profile != null && profile.validationError(when) == null) {
            double meters = profile.heightCm / 100.0;
            value.bmi = weight / (meters * meters);
            value.profileSnapshot = profile.snapshot();
        }
        // Keep the original measurement saveable even if an optional estimate fails.
        try {
            Profile calculationProfile=value.profileSnapshot==null?null:profile;
            value.metrics=BodyComposition.calculate(calculationProfile,when,weight,fat,value.impedance,value.source);
            value.metricsCalculatedAt=System.currentTimeMillis();
            value.metricsProfileSnapshot=value.profileSnapshot;
        } catch (RuntimeException ignored) { }
        return value;
    }
}
