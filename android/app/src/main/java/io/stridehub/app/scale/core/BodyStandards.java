package io.stridehub.app.scale.core;

import java.util.Locale;
import static io.stridehub.app.scale.core.BodyMetrics.Metric.*;

/** Original application's local adult reference rules; see docs/body-metrics-audit.md. */
public final class BodyStandards {
    private BodyStandards() {}
    private static final String[] FAT_GRADES = {"偏低", "正常", "偏胖", "肥胖"};
    private static final String[] INDEX_GRADES = {"过低", "偏低", "正常", "良好", "偏高", "过高", "极高"};
    private static final String[] OBESITY_GRADES = {"不肥胖", "微胖", "偏胖", "肥胖", "肥胖"};
    private static final double[][] MALE_FAT = {{10,21,26},{11,22,27},{13,24,29}};
    private static final double[][] FEMALE_FAT = {{20,34,39},{21,35,40},{22,36,41}};
    private static final double[][] MALE_SHAPE = {{10,15,21,26},{11,16,22,27},{12,17,24,29}};
    private static final double[][] FEMALE_SHAPE = {{20,23,34,39},{21,24,35,40},{22,25,36,41}};
    private static final String[] SHAPES = {"", "偏瘦型", "健康型", "偏胖型", "消瘦型", "苗条型",
            "模特型", "运动型", "健美型", "隐性肥胖型", "肥胖型"};

    public static void enrich(BodyMetrics result, Profile profile, int age, double weightKg, Double fatPercent) {
        if (result == null || profile == null || profile.heightCm < 80 || profile.heightCm > 250
                || !Double.isFinite(weightKg) || weightKg <= 0) return;
        // The original normal-weight card is an interval, not a single ideal weight.
        double height = profile.heightCm * .01f;
        double minimum = (float) (height * height * 18.5);
        double maximum = (float) (height * height * 24);
        result.put(STANDARD_WEIGHT_MIN, minimum);
        result.put(STANDARD_WEIGHT_MAX, maximum);
        result.grades.put(WEIGHT, weightKg < minimum ? "偏轻" : weightKg >= maximum ? "偏重" : "正常");
        control(result, WEIGHT_CONTROL, weightKg, minimum, maximum);
        Double bmi = result.get(BMI);
        if (bmi != null && Double.isFinite(bmi) && bmi > 0) {
            String[] labels = {"偏瘦","正常","偏胖","肥胖","极胖"};
            result.grades.put(BMI, labels[lowerInclusiveBand(bmi, new double[]{18.5,24,28,35})]);
            result.scores.put(BMI, (double) bmiCardScore(bmi.floatValue(), profile.male, age));
        }
        for (BodyMetrics.Metric metric : result.values.keySet()) {
            String reference = referenceRange(metric, profile, age, weightKg);
            if (reference != null) result.references.put(metric, reference);
        }
        if (age < 18 || age > 80 || fatPercent == null || !Double.isFinite(fatPercent)
                || fatPercent < 5 || fatPercent > 75 || bmi == null || !Double.isFinite(bmi)) return;

        boolean male = profile.male;
        float fat = fatPercent.floatValue();
        float weight = (float) weightKg;
        double[] fatBand = (male ? MALE_FAT : FEMALE_FAT)[ageBand(age)];
        String fatGrade = FAT_GRADES[upperInclusiveBand(fat, fatBand)];
        result.grades.put(BODY_FAT, fatGrade);
        if (result.get(FAT_MASS) != null) result.grades.put(FAT_MASS, fatGrade);

        // Preserve the original float operation order before decimal-string HALF_UP rounding.
        float bmiFloat = bmi.floatValue();
        double proteinDelta = bmiFloat > 22f ? -Math.pow(bmiFloat - 22f, .25)
                : Math.pow(22f - bmiFloat, .3333333432674408);
        proteinDelta = Math.max(-2.5, Math.min(2.5, proteinDelta));
        float protein = BodyComposition.decimal(((float) (((weight / 100f) * (proteinDelta + 18)
                / 100.0) * 100.0) / weight) * 100f, 2);
        result.put(PROTEIN, protein);
        float displayedProtein = BodyComposition.decimal(protein, 1);
        result.grades.put(PROTEIN, displayedProtein < 16 ? "偏低" : displayedProtein < 20 ? "标准" : "偏高");

        float fatDifference = fat - (float) fatBand[1];
        float divisor = fatDifference > 0 ? 1.1f : male ? 1.4f : 1.8f;
        int visceral = Math.max(1, Math.min(30, (int) ((double) (9 + fatDifference / divisor) + .5)));
        result.put(VISCERAL_FAT, visceral);
        result.grades.put(VISCERAL_FAT, visceral < 10 ? "正常" : visceral < 15 ? "偏高" : "超高");

        double[] indexBounds = male ? new double[]{7,12,16,21,26,30} : new double[]{13,20,27,34,39,45};
        int fatIndex = upperInclusiveBand(fat, indexBounds);
        result.put(FAT_INDEX, fatIndex + 1);
        result.grades.put(FAT_INDEX, INDEX_GRADES[fatIndex]);
        double[] obesityBounds = male ? new double[]{21,26,30,35} : new double[]{34,39,45,50};
        int obesity = upperInclusiveBand(fat, obesityBounds);
        result.put(OBESITY_DEGREE, obesity + 1);
        result.grades.put(OBESITY_DEGREE, OBESITY_GRADES[obesity]);
        result.bodyShape = SHAPES[shape(bmi, fat, male, age)];

        control(result, FAT_CONTROL, weight * (fat / 100f),
                weight * ((float) fatBand[0] / 100f), weight * ((float) fatBand[1] / 100f));
        Double muscle = result.get(MUSCLE);
        double muscleMinimum = male ? 51 : 46;
        if (valid(muscle)) {
            result.grades.put(MUSCLE, muscle < muscleMinimum ? "偏低" : "标准");
            result.put(MUSCLE_MASS, weight * (muscle.floatValue() / 100f));
            control(result, MUSCLE_CONTROL, weight * (muscle.floatValue() / 100f),
                    weight * ((float) muscleMinimum / 100f), Double.POSITIVE_INFINITY);
        }
        Double water = result.get(WATER);
        double waterLow = male ? 55 : 45, waterHigh = male ? 65 : 60;
        if (valid(water)) {
            result.grades.put(WATER, water < waterLow ? "偏低" : water < waterHigh ? "标准" : "偏高");
            result.put(WATER_MASS, weight * (water.floatValue() / 100f));
            control(result, WATER_CONTROL, weight * (water.floatValue() / 100f),
                    weight * ((float) waterLow / 100f), weight * ((float) waterHigh / 100f));
        }
        Double bone = result.get(BONE);
        // Original rows also stop at bone < 10 kg, independently of the weight band.
        double[] boneRange = valid(bone) && bone < 10 ? boneRange(male, weightKg) : null;
        if (valid(bone) && boneRange != null) {
            result.grades.put(BONE, bone.floatValue() < boneRange[0] ? "偏低" : bone.floatValue() < boneRange[1] ? "正常" : "偏高");
        }
        Double bodyAge = result.get(BODY_AGE);
        if (valid(bodyAge)) result.grades.put(BODY_AGE, bodyAge < age ? "年轻" : bodyAge > age ? "偏大" : "正常");
        if (valid(result.get(BMR))) {
            double low = male ? (age < 40 ? 5 : age < 60 ? 5.5 : 6.5) : (age < 40 ? 10 : age < 60 ? 10.5 : 11);
            result.grades.put(BMR, fat >= low && fat <= fatBand[2] ? "达标" : "未达标");
        }
        if (valid(muscle) && valid(bone) && valid(water)) {
            float total = score(1, bmiFloat, male, age) + score(2, fat, male, age)
                    + (muscle >= muscleMinimum ? 2f : 1f)
                    + (boneRange == null ? 0f : bone.floatValue() >= boneRange[0] && bone.floatValue() < boneRange[1] ? 2f : 1f)
                    + (water >= waterLow && water < waterHigh ? 2f : 1f) + 4f;
            result.put(SCORE, Math.min(99f, total));
        }
        result.scores.put(BODY_FAT, (double) fatCardScore(fat, male, age));
        if (result.get(FAT_MASS) != null) result.scores.put(FAT_MASS, result.scores.get(BODY_FAT));
        result.scores.put(FAT_INDEX, (double) cardRound(100f - Math.abs(3f - (fatIndex + 1)) / 8f * 40f));
        result.scores.put(OBESITY_DEGREE, (double) cardRound(100f - Math.abs(1f - (obesity + 1)) / 5f * 40f));
        if (valid(muscle)) result.scores.put(MUSCLE, (double) muscleCardScore(muscle.floatValue(), male));
        if (valid(water)) result.scores.put(WATER, (double) waterCardScore(water.floatValue(), male));
        float proteinScore = protein < 16f ? 90f - (16f - protein) / 16f * 30f
                : protein > 20f ? 90f + (protein - 20f) / 10f * 30f
                : 100f - Math.abs(18f - protein) / 4f * 10f;
        result.scores.put(PROTEIN, (double) cardRound(proteinScore));
        float visceralScore = visceral < 10 ? 99f - (visceral - 1f) / 10f * 9f
                : visceral < 15 ? 90f - Math.abs(10f - visceral) / 5f * 10f
                : 80f - (visceral - 15f) / 15.1f * 15f;
        result.scores.put(VISCERAL_FAT, (double) cardRound(visceralScore));
        for (BodyMetrics.Metric metric : result.values.keySet()) {
            String reference = referenceRange(metric, profile, age, weightKg);
            if (reference != null) result.references.put(metric, reference);
        }
    }

    /** Display-only reference text, saved with the measurement rather than recalculated from today's profile. */
    public static String referenceRange(BodyMetrics.Metric metric, Profile profile, int age, double weightKg) {
        if (profile == null || profile.heightCm <= 0) return null;
        boolean male = profile.male;
        double height = profile.heightCm * .01f;
        double[] fat = (male ? MALE_FAT : FEMALE_FAT)[ageBand(age)];
        switch (metric) {
            case BMI: return "18.5 ≤ BMI < 24";
            case WEIGHT:
            case STANDARD_WEIGHT_MIN:
            case STANDARD_WEIGHT_MAX:
            case WEIGHT_CONTROL: return format(height * height * 18.5) + "–" + format(height * height * 24) + " kg";
            case BODY_FAT: return "正常：> " + number(fat[0]) + "% 且 ≤ " + number(fat[1]) + "%";
            case FAT_CONTROL: return "脂肪重量进入对应年龄体脂正常区间的差值；区间内为 0";
            case FAT_MASS: return format(weightKg * fat[0] / 100) + "–" + format(weightKg * fat[1] / 100) + " kg";
            case MUSCLE: return "≥ " + (male ? "51" : "46") + "%";
            case MUSCLE_MASS: return "≥ " + format(weightKg * (male ? .51 : .46)) + " kg";
            case MUSCLE_CONTROL: return "肌肉重量距参考下限的差值；达到下限后为 0";
            case WATER: return (male ? "55% ≤ 水分 < 65%" : "45% ≤ 水分 < 60%");
            case WATER_MASS: return format(weightKg * (male ? .55 : .45)) + "–" + format(weightKg * (male ? .65 : .60)) + " kg";
            case WATER_CONTROL: return "水分重量距参考区间的差值；区间内为 0";
            case PROTEIN: return "16% ≤ 蛋白质 < 20%（按 1 位小数分级）";
            case BONE:
                double[] bone = boneRange(male, weightKg);
                return bone == null ? null : "正常：≥ " + number(bone[0]) + " 且 < " + number(bone[1]) + " kg";
            case VISCERAL_FAT: return "1–9 级正常；10–14 级偏高；15–30 级超高";
            case BMR: return "原版以体脂率范围判定达标；数值为每日静息能量估算";
            case FAT_INDEX: return "1–7 档；3 为正常，4 为良好（按性别和体脂率分级）";
            case OBESITY_DEGREE: return "1 不肥胖，2 微胖，3 偏胖，4–5 肥胖（按性别和体脂率分级）";
            case BODY_AGE: return "与测量时实际年龄 " + age + " 岁比较";
            case SCORE: return "原版 BMI、体脂、肌肉、骨量、水分综合分；最高 99 分";
            default: return null;
        }
    }

    private static boolean valid(Double value) { return value != null && Double.isFinite(value) && value > 0; }
    private static int ageBand(int age) { return age < 40 ? 0 : age < 60 ? 1 : 2; }
    private static int lowerInclusiveBand(double value, double[] thresholds) {
        int index = 0; while (index < thresholds.length && value >= thresholds[index]) index++; return index;
    }
    private static int upperInclusiveBand(double value, double[] thresholds) {
        int index = 0; while (index < thresholds.length && value > thresholds[index]) index++; return index;
    }
    private static void control(BodyMetrics result, BodyMetrics.Metric metric, double actual, double low, double high) {
        result.put(metric, actual < low ? low - actual : actual > high ? actual - high : 0);
        result.grades.put(metric, actual < low ? "低于参考下限" : actual > high ? "高于参考上限" : "参考范围内");
    }
    private static double[] boneRange(boolean male, double weight) {
        if (weight < 0 || weight >= 200) return null; // Original score/report table has no row beyond this weight.
        double low = male ? (weight < 60 ? 2.4 : weight < 75 ? 2.8 : 3.1)
                : (weight < 45 ? 1.7 : weight < 60 ? 2.1 : 2.4);
        // Native values arrive as floats expanded into Double. Compare in the same
        // domain, so a displayed 2.8f is not considered below a double literal 2.8.
        return new double[]{(float) low, (float) (Math.round((low + .2) * 10) / 10.0)};
    }
    private static int shape(double bmi, double fat, boolean male, int age) {
        double[] f = (male ? MALE_SHAPE : FEMALE_SHAPE)[ageBand(age)];
        if (bmi < 18.5 && fat < f[0]) return 4;
        if (bmi < 18.5 && fat >= f[0] && fat < f[2]) return 1;
        if (fat < f[1]) {
            if (bmi >= 18.5 && bmi < 21) return male ? 5 : 6;
            if (bmi >= 21 && bmi < 24) return male ? 6 : 7;
            if (bmi >= 24) return male ? 7 : 8;
        }
        if (bmi >= 18.5 && bmi < 24 && fat >= f[1] && fat < f[2]) return 2;
        if (bmi < 21 && fat >= f[2]) return 9;
        if (bmi >= 21 && fat >= f[1]) {
            if (bmi >= 28 || fat >= f[3]) return 10;
            return bmi < 24 && fat < f[2] ? 2 : 3;
        }
        return 2;
    }
    private static String format(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static String number(double value) { return value == (int) value ? Integer.toString((int) value) : Float.toString((float) value); }

    // These per-card scores are independent of the weighted whole-body score.
    // Original body/p methods a,c,d,e,g,h,i,j cap their rounded result at 99.
    private static final float[][] BMI_CARD_BOUNDS = {
        {10,17,18.5f,21,22,23,24.5f,35,60},
        {10,17,18.5f,21.5f,23,24,26,35,60},
        {10,17,18.5f,22,24,25.5f,27,35,60},
        {10,16,18.5f,19,19.5f,20.5f,22,35,60},
        {10,16,18.5f,19.5f,21,22.5f,25,35,60},
        {10,16,19,20.5f,22,23.5f,26,35,60}
    };
    private static final float[] BMI_CARD_POINTS = {75,85,92,99,99,92,85,75,65};
    private static final float[][] FAT_CARD_BOUNDS = {
        {7,10,14,18,21,24,27,30,75},
        {7,12,15,19,22,25,27,30,75},
        {7,10,15,19,21,23,27,30,75},
        {10,16,21,24,26,28,31,34,75},
        {10,18,22,25,27,31,34,75},
        {10,20,22,25,28,31,35,75}
    };
    private static final float[] MALE_FAT_POINTS = {92,95,99,99,92,85,80,75,70,60};
    private static final float[] FEMALE_YOUNG_FAT_POINTS = {92,99,99,95,92,88,85,80,70,60};
    private static final float[] FEMALE_FAT_POINTS = {92,99,99,95,92,88,80,70,60};
    private static int scoreGroup(boolean male, int age) { return (male ? 0 : 3) + (age < 30 ? 0 : age < 40 ? 1 : 2); }
    private static int cardRound(float value) { return Math.min(99, (int) Math.floor((double) value + .5)); }
    private static int bmiCardScore(float bmi, boolean male, int age) {
        if (bmi < 10 || bmi > 60) return 59;
        if (bmi == 10) return 75;
        float[] bounds = BMI_CARD_BOUNDS[scoreGroup(male, age)];
        for (int i=1; i<bounds.length; i++) {
            float low = bounds[i-1], high = bounds[i];
            if (bmi > low && bmi <= high) {
                if (i == 8) return cardRound(65f - (bmi - low) / (high - low) * 6f);
                float score = i <= 3 ? BMI_CARD_POINTS[i] - (high - bmi) / (high - low)
                        * (BMI_CARD_POINTS[i] - BMI_CARD_POINTS[i-1])
                        : BMI_CARD_POINTS[i] - (bmi - low) / (high - low)
                        * (BMI_CARD_POINTS[i] - BMI_CARD_POINTS[i+1]);
                return cardRound(score);
            }
        }
        return 59;
    }
    private static int fatCardScore(float fat, boolean male, int age) {
        if (fat < (male ? 7 : 10) || fat > 75) return 59;
        // Preserve this special case, including its discontinuity for male profiles.
        if (fat == 10) return 75;
        float[] bounds = FAT_CARD_BOUNDS[scoreGroup(male, age)];
        float[] points = male ? MALE_FAT_POINTS : bounds.length == 9 ? FEMALE_YOUNG_FAT_POINTS : FEMALE_FAT_POINTS;
        for (int i=1; i<bounds.length; i++) {
            float low = bounds[i-1], high = bounds[i];
            if (fat > low && fat <= high) {
                float score = i <= (male ? 2 : 1) ? points[i] - (high - fat) / (high - low) * (points[i] - points[i-1])
                        : points[i] - (fat - low) / (high - low) * (points[i] - points[i+1]);
                return cardRound(score);
            }
        }
        return 59;
    }
    private static int muscleCardScore(float muscle, boolean male) {
        float veryLow = male ? 45 : 40, minimum = male ? 51 : 46, maximum = male ? 63 : 60;
        if (muscle < veryLow) return 60;
        if (muscle < minimum) return cardRound(90f - Math.abs(minimum - muscle) / (male ? 9f : 6f) * 10f);
        if (muscle <= maximum) return cardRound(99f - Math.abs(maximum - muscle) / (male ? 12f : 14f) * 10f);
        return 99;
    }
    private static int waterCardScore(float water, boolean male) {
        float minimum = male ? 55 : 45, maximum = male ? 65 : 60;
        if (water < minimum) return cardRound(90f - (minimum - water) / minimum * 30f);
        if (water > maximum) return cardRound(90f - (water - maximum) / 40f * 30f);
        return cardRound(100f - Math.abs((male ? 60f : 52.25f) - water) / (male ? 10f : 15f) * 10f);
    }

    private static float score(int type, float value, boolean male, int age) {
        for (float[] row : SCORE_SEGMENTS) {
            if (row[0] == type && row[1] == (male ? 1 : 2) && age >= row[2] && age < row[3]
                    && value >= row[4] && value < row[5]) {
                float distance = row[8] <= 3 ? row[5] - value : value - row[4];
                return row[7] - row[6] * (distance / (row[5] - row[4]));
            }
        }
        return 0;
    }

    // Numeric piecewise coefficients: type, sex, age lower/upper, value lower/upper,
    // slope numerator, score at the high-scoring endpoint, direction index.
    // All ranges are lower-inclusive/upper-exclusive. Source: APK assets/xmstandarddata.db Score.
    private static final float[][] SCORE_SEGMENTS = {
        {1f, 1f, 0f, 30f, 0f, 17f, 32f, 42f, 1f},
        {1f, 1f, 0f, 30f, 17f, 18.5f, 10f, 52f, 2f},
        {1f, 1f, 0f, 30f, 18.5f, 21f, 8f, 60f, 3f},
        {1f, 1f, 0f, 30f, 21f, 22f, 9f, 60f, 4f},
        {1f, 1f, 0f, 30f, 22f, 23f, 2f, 51f, 5f},
        {1f, 1f, 0f, 30f, 23f, 24.5f, 3f, 49f, 6f},
        {1f, 1f, 0f, 30f, 24.5f, 35f, 21f, 46f, 7f},
        {1f, 1f, 0f, 30f, 35f, 60f, 15f, 25f, 8f},
        {1f, 1f, 30f, 40f, 0f, 17f, 32f, 42f, 1f},
        {1f, 1f, 30f, 40f, 17f, 18.5f, 10f, 52f, 2f},
        {1f, 1f, 30f, 40f, 18.5f, 21.5f, 8f, 60f, 3f},
        {1f, 1f, 30f, 40f, 21.5f, 23f, 11f, 60f, 4f},
        {1f, 1f, 30f, 40f, 23f, 24f, 4f, 49f, 5f},
        {1f, 1f, 30f, 40f, 24f, 26f, 5f, 45f, 6f},
        {1f, 1f, 30f, 40f, 26f, 35f, 18f, 40f, 7f},
        {1f, 1f, 30f, 40f, 35f, 60f, 12f, 22f, 8f},
        {1f, 1f, 40f, 120f, 0f, 17f, 30f, 40f, 1f},
        {1f, 1f, 40f, 120f, 17f, 18.5f, 12f, 52f, 2f},
        {1f, 1f, 40f, 120f, 18.5f, 22f, 8f, 60f, 3f},
        {1f, 1f, 40f, 120f, 22f, 24f, 14f, 60f, 4f},
        {1f, 1f, 40f, 120f, 24f, 25.5f, 4f, 46f, 5f},
        {1f, 1f, 40f, 120f, 25.5f, 27f, 8f, 42f, 6f},
        {1f, 1f, 40f, 120f, 27f, 35f, 15f, 34f, 7f},
        {1f, 1f, 40f, 120f, 35f, 60f, 9f, 19f, 8f},
        {1f, 2f, 0f, 30f, 0f, 16f, 34f, 44f, 1f},
        {1f, 2f, 0f, 30f, 16f, 18.5f, 10f, 54f, 2f},
        {1f, 2f, 0f, 30f, 18.5f, 19f, 6f, 60f, 3f},
        {1f, 2f, 0f, 30f, 19f, 19.5f, 6f, 60f, 4f},
        {1f, 2f, 0f, 30f, 19.5f, 20.5f, 3f, 54f, 5f},
        {1f, 2f, 0f, 30f, 20.5f, 22f, 2f, 51f, 6f},
        {1f, 2f, 0f, 30f, 22f, 35f, 19f, 49f, 7f},
        {1f, 2f, 0f, 30f, 35f, 60f, 20f, 30f, 8f},
        {1f, 2f, 30f, 40f, 0f, 16f, 32f, 42f, 1f},
        {1f, 2f, 30f, 40f, 16f, 18.5f, 10f, 52f, 2f},
        {1f, 2f, 30f, 40f, 18.5f, 19.5f, 8f, 60f, 3f},
        {1f, 2f, 30f, 40f, 19.5f, 21f, 8f, 60f, 4f},
        {1f, 2f, 30f, 40f, 21f, 22.5f, 3f, 52f, 5f},
        {1f, 2f, 30f, 40f, 22.5f, 25f, 4f, 49f, 6f},
        {1f, 2f, 30f, 40f, 25f, 35f, 20f, 45f, 7f},
        {1f, 2f, 30f, 40f, 35f, 60f, 15f, 25f, 8f},
        {1f, 2f, 40f, 120f, 0f, 16f, 32f, 42f, 1f},
        {1f, 2f, 40f, 120f, 16f, 19f, 10f, 52f, 2f},
        {1f, 2f, 40f, 120f, 19f, 20.5f, 8f, 60f, 3f},
        {1f, 2f, 40f, 120f, 20.5f, 22f, 8f, 60f, 4f},
        {1f, 2f, 40f, 120f, 22f, 23.5f, 3f, 52f, 5f},
        {1f, 2f, 40f, 120f, 23.5f, 26f, 4f, 49f, 6f},
        {1f, 2f, 40f, 120f, 26f, 35f, 20f, 45f, 7f},
        {1f, 2f, 40f, 120f, 35f, 60f, 15f, 25f, 8f},
        {2f, 1f, 0f, 30f, 0f, 10f, 18f, 23f, 1f},
        {2f, 1f, 0f, 30f, 10f, 14f, 3f, 26f, 2f},
        {2f, 1f, 0f, 30f, 14f, 18f, 4f, 30f, 3f},
        {2f, 1f, 0f, 30f, 18f, 20f, 4.5f, 30f, 4f},
        {2f, 1f, 0f, 30f, 20f, 25f, 7.5f, 25.5f, 5f},
        {2f, 1f, 0f, 30f, 25f, 76f, 13f, 18f, 6f},
        {2f, 1f, 30f, 40f, 0f, 11f, 18f, 23f, 1f},
        {2f, 1f, 30f, 40f, 11f, 15f, 3f, 26f, 2f},
        {2f, 1f, 30f, 40f, 15f, 19.5f, 4f, 30f, 3f},
        {2f, 1f, 30f, 40f, 19.5f, 21.5f, 7f, 30f, 4f},
        {2f, 1f, 30f, 40f, 21.5f, 26.5f, 8f, 23f, 5f},
        {2f, 1f, 30f, 40f, 26.5f, 76f, 10f, 15f, 6f},
        {2f, 1f, 40f, 120f, 0f, 12f, 18f, 23f, 1f},
        {2f, 1f, 40f, 120f, 12f, 17f, 3f, 26f, 2f},
        {2f, 1f, 40f, 120f, 17f, 21f, 4f, 30f, 3f},
        {2f, 1f, 40f, 120f, 21f, 23f, 8f, 30f, 4f},
        {2f, 1f, 40f, 120f, 23f, 30f, 9f, 22f, 5f},
        {2f, 1f, 40f, 120f, 30f, 76f, 8f, 13f, 6f},
        {2f, 2f, 0f, 30f, 0f, 16f, 10f, 15f, 1f},
        {2f, 2f, 0f, 30f, 16f, 19f, 10f, 25f, 2f},
        {2f, 2f, 0f, 30f, 19f, 22f, 5f, 30f, 3f},
        {2f, 2f, 0f, 30f, 22f, 24f, 4.5f, 30f, 4f},
        {2f, 2f, 0f, 30f, 24f, 30f, 3f, 25.5f, 5f},
        {2f, 2f, 0f, 30f, 30f, 76f, 17.5f, 22.5f, 6f},
        {2f, 2f, 30f, 40f, 0f, 18f, 9f, 14f, 1f},
        {2f, 2f, 30f, 40f, 18f, 21f, 10f, 24f, 2f},
        {2f, 2f, 30f, 40f, 21f, 25f, 6f, 30f, 3f},
        {2f, 2f, 30f, 40f, 25f, 27f, 4.5f, 30f, 4f},
        {2f, 2f, 30f, 40f, 27f, 35f, 7.5f, 25.5f, 5f},
        {2f, 2f, 30f, 40f, 35f, 76f, 13f, 18f, 6f},
        {2f, 2f, 40f, 120f, 0f, 20f, 9f, 14f, 1f},
        {2f, 2f, 40f, 120f, 20f, 22f, 10f, 24f, 2f},
        {2f, 2f, 40f, 120f, 22f, 26f, 6f, 30f, 3f},
        {2f, 2f, 40f, 120f, 26f, 28f, 4.5f, 30f, 4f},
        {2f, 2f, 40f, 120f, 28f, 35f, 7.5f, 25.5f, 5f},
        {2f, 2f, 40f, 120f, 35f, 76f, 13f, 18f, 6f},
    };
}
