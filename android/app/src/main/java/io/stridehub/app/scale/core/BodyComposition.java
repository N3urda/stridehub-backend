package io.stridehub.app.scale.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import static io.stridehub.app.scale.core.BodyMetrics.Metric.*;

/** Offline reconstruction of the supplied application's adult Chinese-mode calculations. */
public final class BodyComposition {
    private BodyComposition() {}

    public static BodyMetrics calculate(Profile profile, long when, double weightKg,
                                        Double fatPercent, int impedance, String source) {
        BodyMetrics result = new BodyMetrics();
        if (!Double.isFinite(weightKg) || weightKg < 2 || weightKg > 300) {
            result.calculationBasis = "体重无效";
            return result;
        }
        result.put(WEIGHT, weightKg);
        Double validFat = fatPercent != null && Double.isFinite(fatPercent)
                && fatPercent >= 1 && fatPercent <= 75 ? fatPercent : null;
        float weight = (float) weightKg;
        if (validFat != null) {
            result.put(BODY_FAT, validFat);
            float mass = decimal((validFat.floatValue() / 100f) * weight, 2);
            result.put(FAT_MASS, mass);
            float lean = decimal(weight - mass, 2);
            result.put(LEAN_MASS, lean);
            result.put(LEAN_PERCENT, (lean / weight) * 100f);
        }
        String origin = source == null ? "未知来源" : source;
        result.calculationBasis = origin + (impedance > 0 ? " · 含阻抗" : " · 无阻抗")
                + "；衍生指标为公式估算";
        if (profile == null || profile.validationError(when) != null) {
            result.calculationBasis += "；缺少有效的测量时资料";
            return result;
        }
        if (validFat != null) {
            // These ratios use raw double inputs, independently of the legacy rounded masses and BMI.
            double heightMeters = profile.heightCm / 100.0;
            double heightSquared = heightMeters * heightMeters;
            double fatFraction = validFat / 100.0;
            result.put(FMI, weightKg * fatFraction / heightSquared);
            result.put(FFMI, weightKg * (1.0 - fatFraction) / heightSquared);
            result.references.put(FMI, "脂肪重量 ÷ 身高²；不同于原版「体脂指数」1–7 档，不作评级");
            result.references.put(FFMI, "去脂体重 ÷ 身高²；包含肌肉、骨、水分和器官等，不等于纯肌肉量，不作评级");
        }
        int age = profile.ageAt(when);
        float height = profile.heightCm / 100f;
        float bmi = roundedFloat(weight / (height * height), 10f);
        result.put(BMI, bmi);
        if (validFat == null || validFat < 5) {
            result.put(BMR, fallbackBmr(weight, profile.heightCm, age, profile.male));
            result.calculationBasis += validFat == null ? "；未取得体脂" : "；体脂低于原算法的 5% 下限";
            result.calculationBasis += "，基础代谢按身高、年龄、性别估算";
        } else {
            float fat = validFat.floatValue();
            float muscle = roundedDecimal((100f - fat) * .67f, 100f);
            float boneBase = ((muscle / 100f) * weight) * 4f / 7f;
            double boneFactor = profile.male ? .22 : .34;
            double boneSecond = profile.male ? (double) .6f : (double) .45f;
            float bone = (profile.heightCm - 170) / 100f
                    + (float) (boneBase * boneFactor * boneSecond);
            float water = (float) ((100f - fat) * .726);
            float bmrCoefficient = (1f - fat / 100f) * 21.6f;
            // Float inputs have an exact product in double within the supported range;
            // one final float conversion reproduces the native fused multiply/add.
            float bmr = (float) ((double) bmrCoefficient * weight + 370.0);
            result.put(MUSCLE, muscle);
            float roundedBone = roundedDecimal(bone, 10f);
            result.put(BONE, roundedBone);
            result.put(BONE_PERCENT, (roundedBone / weight) * 100f);
            result.put(WATER, roundedDecimal(water, 100f));
            result.put(BMR, roundedFloat(bmr, 10f));
            result.put(BODY_AGE, bodyAge(bmi, age));
        }
        BodyStandards.enrich(result, profile, age, weightKg,
                validFat != null && validFat >= 5 ? validFat : null);
        return result;
    }

    /** Original Java-side rounding uses the float's decimal string, not its double expansion. */
    public static float decimal(float value, int places) {
        if (!Float.isFinite(value)) return 0f;
        return new BigDecimal(Float.toString(value)).setScale(places, RoundingMode.HALF_UP).floatValue();
    }

    private static float roundedFloat(float value, float scale) {
        return (float) (int) ((double) (value * scale) + .5) / scale;
    }

    private static float roundedDecimal(float value, float scale) {
        return (float) ((int) ((double) (value * scale) + .5) / (double) scale);
    }

    private static int fallbackBmr(float weight, int height, int age, boolean male) {
        int value = (int) (weight * 10f + height * 6.25 - age * 5f + (male ? 5 : -161));
        return value > 100 ? value / 100 * 100 : value;
    }

    private static int bodyAge(float bmi, int age) {
        float factor = 1f;
        if (bmi > 40f) factor = (1f + (bmi - 35f) / 100f)
                * (1f + (bmi - 28f) / 100f) * (1f + (bmi - 40f) / 100f);
        else if (bmi > 35f) factor = (1f + (bmi - 28f) / 100f) * (1f + (bmi - 35f) / 100f);
        else if (bmi > 28f) factor = 1f + (bmi - 28f) / 100f;
        float delta = bmi - 24f;
        float offset = (float) Math.sqrt(Math.abs(delta));
        if (delta < 0) offset = -offset;
        return (int) ((age + offset) * factor + .5f);
    }
}
