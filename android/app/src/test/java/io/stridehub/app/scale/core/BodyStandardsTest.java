package io.stridehub.app.scale.core;

import static io.stridehub.app.scale.core.BodyMetrics.Metric.*;

/** Synthetic cases checked against the original local standard/score tables. */
public final class BodyStandardsTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void near(double expected, Double actual, String message) {
        check(actual != null && Math.abs(expected - actual) < .0002,
                message + ": expected " + expected + ", got " + actual);
    }
    private static Profile profile(boolean male) { return new Profile(175, "1990-01-01", male, 1); }
    private static BodyMetrics sample(boolean male, int age, double weight, double bmi, Double fat,
                                      double muscle, double bone, double water) {
        BodyMetrics result = new BodyMetrics();
        result.put(BMI, bmi);
        result.put(MUSCLE, muscle);
        result.put(BONE, bone);
        result.put(WATER, water);
        result.put(BMR, 1500);
        result.put(BODY_AGE, 30);
        BodyStandards.enrich(result, profile(male), age, weight, fat);
        return result;
    }
    public static void main(String[] args) {
        BodyMetrics a = sample(true, 30, 70, 22, 20.0, 53.6, 2.9, 58.08);
        near(18, a.get(PROTEIN), "Protein at BMI 22");
        near(8, a.get(VISCERAL_FAT), "Visceral rating from male age normal-fat upper bound");
        near(4, a.get(FAT_INDEX), "Fat index is an ordinal");
        near(1, a.get(OBESITY_DEGREE), "Obesity degree differs from body shape and BMI");
        near(94.58333, a.get(SCORE), "Piecewise BMI/fat score plus component scores");
        near(56.65625, a.get(STANDARD_WEIGHT_MIN), "Normal weight lower limit");
        near(73.5, a.get(STANDARD_WEIGHT_MAX), "Normal weight upper limit");
        near(0, a.get(WEIGHT_CONTROL), "No weight adjustment inside normal interval");
        near(0, a.get(FAT_CONTROL), "No fat adjustment inside normal interval");
        near(0, a.get(MUSCLE_CONTROL), "No muscle adjustment above lower limit");
        near(0, a.get(WATER_CONTROL), "No water adjustment inside normal interval");
        near(37.52, a.get(MUSCLE_MASS), "Muscle mass");
        near(40.656, a.get(WATER_MASS), "Water mass keeps calculation precision");
        check("健康型".equals(a.bodyShape), "Body shape independent category");
        check("正常".equals(a.grades.get(BODY_FAT)), "Normal fat grade");
        check("达标".equals(a.grades.get(BMR)), "Original BMR grade depends on fat");
        check("正常".equals(a.grades.get(BODY_AGE)), "Body age equal to actual age");
        near(97, a.scores.get(BMI), "BMI card score differs from overall BMI contribution");
        near(90, a.scores.get(BODY_FAT), "Fat card score from the original interpolation table");
        near(91, a.scores.get(MUSCLE), "Muscle card score");
        near(98, a.scores.get(WATER), "Water card score");
        near(99, a.scores.get(PROTEIN), "Protein card score cap");
        near(93, a.scores.get(VISCERAL_FAT), "Visceral card score");
        near(95, a.scores.get(FAT_INDEX), "Fat-index card score");
        near(99, a.scores.get(OBESITY_DEGREE), "Obesity card score cap");

        BodyMetrics peak = sample(true, 30, 70, 21.5, 19.5, 53.6, 2.9, 58.08);
        near(99, peak.get(SCORE), "Original score caps at 99");
        BodyMetrics overweight = sample(true, 30, 80, 28, 30.0, 46.9, 3.2, 50.82);
        near(6.5, overweight.get(WEIGHT_CONTROL), "Weight distance from upper limit");
        near(7.2, overweight.get(FAT_CONTROL), "Fat mass distance from age upper limit");
        near(3.28, overweight.get(MUSCLE_CONTROL), "Muscle deficit");
        near(3.344, overweight.get(WATER_CONTROL), "Water deficit");
        near(3, overweight.get(OBESITY_DEGREE), "Obesity upper boundary belongs to earlier interval");
        near(6, overweight.get(FAT_INDEX), "Fat index overlapping boundary picks first table row");
        check("肥胖型".equals(overweight.bodyShape), "Body shape obesity branch");
        check("未达标".equals(overweight.grades.get(BMR)), "Original BMR grade beyond fat band");

        double[][] maleBands = {{10,21,26},{11,22,27},{13,24,29}};
        double[][] femaleBands = {{20,34,39},{21,35,40},{22,36,41}};
        int[] ages = {18,39,40,59,60,80};
        for (boolean male : new boolean[]{true,false}) for (int age : ages) {
            double[] band = (male ? maleBands : femaleBands)[age < 40 ? 0 : age < 60 ? 1 : 2];
            String[] at = {"偏低","正常","偏胖"};
            String[] after = {"正常","偏胖","肥胖"};
            for (int i=0; i<3; i++) {
                check(at[i].equals(sample(male,age,70,22,band[i],52,2.9,58).grades.get(BODY_FAT)),
                        "Fat inclusive upper boundary, sex="+male+" age="+age+" boundary="+i);
                check(after[i].equals(sample(male,age,70,22,band[i]+.1,52,2.9,58).grades.get(BODY_FAT)),
                        "Fat after upper boundary, sex="+male+" age="+age+" boundary="+i);
            }
        }
        check("标准".equals(sample(true,30,60,22,20.0,51,2.8,55).grades.get(MUSCLE)), "Report muscle threshold included");
        check("标准".equals(sample(false,30,60,22,30.0,46,2.4,45).grades.get(MUSCLE)), "Female muscle threshold");
        check("正常".equals(sample(true,30,60,22,20.0,51,2.8,55).grades.get(BONE)), "Bone weight boundary 60");
        check("偏低".equals(sample(true,30,75,22,20.0,51,3.0,55).grades.get(BONE)), "Bone weight boundary 75");
        check("正常".equals(sample(true,30,60,22,20.0,51,(double) 2.8f,55).grades.get(BONE)),
                "Native bone float must match decimal threshold");
        long when = java.time.LocalDateTime.parse("2026-01-01T12:00:00")
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        BodyMetrics nativeBone = BodyComposition.calculate(profile(true), when, 70, 22.0, 500, "test");
        near(2.8, nativeBone.get(BONE), "Native formula yields exact displayed boundary");
        check("正常".equals(nativeBone.grades.get(BONE)), "Native formula bone boundary classifies normally");
        double[][] nativeBoundaries = {{55,15,2.4},{55,8,2.6},{70,22,2.8},{70,17,3.0},{80,25,3.1},{80,20,3.3}};
        for (int i=0; i<nativeBoundaries.length; i++) {
            double[] row = nativeBoundaries[i];
            BodyMetrics boundary = BodyComposition.calculate(profile(true),when,row[0],row[1],500,"test");
            near(row[2],boundary.get(BONE),"Native bone threshold fixture " + i);
            check((i%2==0 ? "正常" : "偏高").equals(boundary.grades.get(BONE)), "Native bone threshold grade " + i);
        }
        check("正常".equals(sample(false,30,60,22,30.0,46,2.4,45).grades.get(BONE)),
                "Decimal and float bone inputs share the original float domain");
        check("正常：≥ 2.8 且 < 3 kg".equals(nativeBone.references.get(BONE)),"Reference text hides binary float expansion");
        check("偏高".equals(sample(true,30,60,22,20.0,51,3,65).grades.get(WATER)), "Water upper boundary excluded");
        near(1,sample(false,60,70,22,5.0,60,2.5,60).get(VISCERAL_FAT),"Visceral clamp low");
        near(30,sample(true,18,70,22,75.0,20,2.5,20).get(VISCERAL_FAT),"Visceral clamp high");
        near(20.5,sample(true,30,70,1,20.0,52,2.9,58).get(PROTEIN),"Protein upper clamp");
        near(15.5,sample(true,30,70,100,20.0,52,2.9,58).get(PROTEIN),"Protein lower clamp");
        check("偏瘦型".equals(sample(true,30,70,18,15.0,52,2.9,58).bodyShape),"Body shape thin branch");
        check("消瘦型".equals(sample(true,30,70,18,8.0,52,2.9,58).bodyShape),"Body shape lean branch");
        check("苗条型".equals(sample(true,30,70,20,12.0,52,2.9,58).bodyShape),"Male slim branch");
        check("模特型".equals(sample(false,30,70,20,22.0,52,2.9,58).bodyShape),"Female model branch");
        check("运动型".equals(sample(true,30,70,25,12.0,52,2.9,58).bodyShape),"Male sport branch");
        check("健美型".equals(sample(false,30,70,25,22.0,52,2.9,58).bodyShape),"Female build branch");
        check("隐性肥胖型".equals(sample(true,30,70,20,25.0,52,2.9,58).bodyShape),"Hidden-fat branch");
        check("偏胖型".equals(sample(true,30,70,25,22.0,52,2.9,58).bodyShape),"Slightly-fat branch");

        BodyMetrics weightOnly = new BodyMetrics(); weightOnly.put(BMI,22);
        BodyStandards.enrich(weightOnly,profile(true),30,70,null);
        check(weightOnly.get(PROTEIN)==null && weightOnly.get(SCORE)==null && weightOnly.bodyShape==null,
                "Missing fat must not invent composition estimates");
        check(weightOnly.get(STANDARD_WEIGHT_MIN)!=null, "Weight-only still has height reference");
        long highBoneWhen = java.time.Instant.parse("2026-06-15T12:00:00Z").toEpochMilli();
        Profile highBoneProfile = new Profile(175,"1996-01-01",false,1);
        BodyMetrics highBone = BodyComposition.calculate(highBoneProfile,highBoneWhen,190,5.0,500,"合成测试");
        near(10.6,highBone.get(BONE),"Native formula can exceed the bone standard table");
        check(!highBone.grades.containsKey(BONE),"Bone at or above 10 kg has no original standard row");
        double scoreWithoutBoneRow=highBone.get(SCORE);
        highBone.put(BONE,9.9);
        BodyStandards.enrich(highBone,highBoneProfile,30,190,5.0);
        near(scoreWithoutBoneRow+1,highBone.get(SCORE),"No bone row contributes zero; matching high-bone row contributes one");
        check("极胖".equals(sample(true,30,190,200,20.0,51,3.1,55).grades.get(BMI)),"Original BMI query falls back to 60 beyond table bounds");
        check(weightOnly.scores.get(BMI)!=null && weightOnly.scores.get(BODY_FAT)==null,
                "Original BMI card score works without body fat");
        BodyMetrics missing = new BodyMetrics();
        BodyStandards.enrich(missing,null,30,70,20.0);
        check(missing.values.isEmpty() && missing.bodyShape==null,"Missing profile does not invent metrics");
        System.out.println("BodyStandardsTest: " + assertions + " assertions passed");
    }
}
