package io.stridehub.app.scale.core;

import java.time.Instant;
import static io.stridehub.app.scale.core.BodyMetrics.Metric.*;

public final class BodyCompositionTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(Double actual, double expected, String message) {
        check(actual != null && Math.abs(actual - expected) < .001, message + ": " + actual);
    }
    private static void precise(Double actual, double expected, String message) {
        check(actual != null && Math.abs(actual - expected) < 1e-12, message + ": " + actual);
    }
    private static BodyMetrics.Metric metric(String name) {
        for (BodyMetrics.Metric metric : BodyMetrics.Metric.values())
            if (metric.name().equals(name)) return metric;
        throw new AssertionError(name + " must be a registered metric");
    }
    private static void compositionIndices(Profile profile, long when) {
        BodyMetrics.Metric fmi = metric("FMI"), ffmi = metric("FFMI");
        check(BodyMetrics.ALGORITHM_VERSION == 2, "Additional indices have a new persisted algorithm version");
        check(fmi.title.equals("脂肪质量指数 FMI") && ffmi.title.equals("去脂体重指数 FFMI"), "Index names distinguish fat and fat-free mass");
        check(fmi.unit.equals("kg/m²") && ffmi.unit.equals("kg/m²") && fmi.digits == 2 && ffmi.digits == 2, "Index display units and precision");
        check(fmi.ordinal() > WATER_CONTROL.ordinal() && ffmi.ordinal() > fmi.ordinal(), "New metrics append without changing existing enum order");

        Profile exactProfile = new Profile(173, "1991-01-01", true, 1);
        double weight = 73.12345, fat = 18.76543, heightSquared = 1.73 * 1.73;
        BodyMetrics exact = BodyComposition.calculate(exactProfile, when, weight, fat, 0, "手动录入");
        precise(exact.get(fmi), weight * (fat / 100.0) / heightSquared, "FMI uses unrounded double inputs");
        precise(exact.get(ffmi), weight * (1.0 - fat / 100.0) / heightSquared, "FFMI uses unrounded double inputs");
        precise(exact.get(fmi) + exact.get(ffmi), weight / heightSquared, "FMI plus FFMI equals unrounded BMI");
        check(Math.abs(exact.get(fmi) + exact.get(ffmi) - exact.get(BMI)) > .001, "Indices do not derive from the original rounded BMI");
        check(Math.abs(exact.get(fmi) - exact.get(FAT_MASS) / heightSquared) > .00001, "FMI does not derive from rounded fat mass");
        check(Math.abs(exact.get(ffmi) - exact.get(LEAN_MASS) / heightSquared) > .00001, "FFMI does not derive from rounded lean mass");
        check(!exact.grades.containsKey(fmi) && !exact.grades.containsKey(ffmi)
                && !exact.scores.containsKey(fmi) && !exact.scores.containsKey(ffmi), "Indices do not invent grades or scores");
        check(exact.references.get(fmi) != null && exact.references.get(fmi).contains("体脂指数")
                && exact.references.get(fmi).contains("1–7"), "FMI explanation distinguishes the vendor fat index");
        check(exact.references.get(ffmi) != null && exact.references.get(ffmi).contains("肌肉")
                && exact.references.get(ffmi).contains("骨"), "FFMI explanation describes fat-free mass rather than pure muscle");
        check(exact.calculationBasis.contains("手动录入") && exact.calculationBasis.contains("无阻抗"), "Known manual fat needs no impedance and retains provenance");

        for (double boundaryFat : new double[]{1, 4.9, 5, 75}) {
            BodyMetrics boundary = BodyComposition.calculate(profile, when, 70, boundaryFat, 0, "手动录入");
            precise(boundary.get(fmi), 70 * (boundaryFat / 100.0) / (1.75 * 1.75), "FMI accepts valid fat " + boundaryFat);
            precise(boundary.get(ffmi), 70 * (1.0 - boundaryFat / 100.0) / (1.75 * 1.75), "FFMI accepts valid fat " + boundaryFat);
        }
        for (Double invalidFat : new Double[]{null, 0.999, 75.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            BodyMetrics missing = BodyComposition.calculate(profile, when, 70, invalidFat, 500, "本机估算");
            check(missing.get(fmi) == null && missing.get(ffmi) == null, "Missing or invalid fat leaves indices absent: " + invalidFat);
        }
        for (Profile invalidProfile : new Profile[]{null, new Profile(79, "1991-01-01", true, 1),
                new Profile(251, "1991-01-01", true, 1), new Profile(175, "invalid", true, 1),
                new Profile(175, "2010-01-01", true, 1), new Profile(175, "1991-01-01", true, 0)}) {
            BodyMetrics missing = BodyComposition.calculate(invalidProfile, when, 70, 20.0, 500, "本机估算");
            check(missing.get(fmi) == null && missing.get(ffmi) == null, "Indices require a valid measurement-time profile");
        }
        for (double invalidWeight : new double[]{1.99, 300.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            BodyMetrics missing = BodyComposition.calculate(profile, when, invalidWeight, 20.0, 500, "本机估算");
            check(missing.get(fmi) == null && missing.get(ffmi) == null, "Invalid weight leaves indices absent");
        }
    }
    public static void main(String[] args) {
        long when = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli();
        Profile male = new Profile(175, "1991-01-01", true, 1);
        BodyMetrics full = BodyComposition.calculate(male, when, 70, 20.0, 500, "本机估算");
        near(full.get(BMI), 22.9, "Original rounded BMI");
        near(full.get(MUSCLE), 53.6, "Muscle percent");
        near(full.get(WATER), 58.08, "Water percent");
        near(full.get(BONE), 2.9, "Male bone mass");
        near(full.get(BMR), 1579.6, "Composition-based BMR");
        near(full.get(BODY_AGE), 34, "Body age uses age at measurement");
        near(full.get(FAT_MASS), 14, "Fat mass");
        near(full.get(LEAN_MASS), 56, "Lean mass distinct from fat mass");
        near(full.get(LEAN_PERCENT), 80, "Lean ratio is mass divided by weight");
        near(full.get(BONE_PERCENT), 4.142857, "Bone ratio is mass divided by weight");
        BodyMetrics female = BodyComposition.calculate(new Profile(175, "1991-01-01", false, 1), when, 70, 20.0, 500, "本机估算");
        near(female.get(BONE), 3.3, "Female bone mass coefficients");
        BodyMetrics noFat = BodyComposition.calculate(male, when, 70, null, 0, "蓝牙体重");
        check(noFat.get(MUSCLE) == null && noFat.get(WATER) == null && noFat.get(BONE) == null && noFat.get(FAT_MASS) == null, "Missing fat never becomes composition zeros");
        near(noFat.get(BMR), 1600, "Original fallback BMR rounds down to hundreds");
        BodyMetrics noProfile = BodyComposition.calculate(null, when, 70, 20.0, 0, "手动录入");
        check(noProfile.get(BMI) == null && noProfile.get(BODY_AGE) == null && noProfile.get(BONE) == null, "No invented profile");
        near(noProfile.get(FAT_MASS), 14, "Known manual fat can derive fat mass");
        BodyMetrics manual = BodyComposition.calculate(male, when, 70, 20.0, 0, "手动录入");
        near(manual.get(MUSCLE), 53.6, "Manual fat has explicit derived estimates");
        check(manual.calculationBasis.contains("手动"), "Manual provenance retained");
        BodyMetrics lowFat = BodyComposition.calculate(male, when, 70, 4.9, 0, "手动录入");
        near(lowFat.get(BODY_FAT), 4.9, "Keep manually supplied raw fat below algorithm range");
        check(lowFat.get(MUSCLE) == null && lowFat.calculationBasis.contains("5%"), "Do not extrapolate original composition below its supported fat range");
        BodyMetrics invalid = BodyComposition.calculate(male, when, 70, Double.NaN, 500, "本机估算");
        check(invalid.get(BODY_FAT) == null && invalid.get(MUSCLE) == null, "Nonfinite fat rejected");
        check(BodyComposition.calculate(male, when, Double.NaN, 20.0, 500, "").values.isEmpty(), "Nonfinite weight rejected");
        compositionIndices(male, when);
        System.out.println("BodyCompositionTest: " + assertions + " assertions passed");
    }
}
