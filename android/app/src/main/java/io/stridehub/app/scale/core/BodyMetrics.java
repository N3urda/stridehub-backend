package io.stridehub.app.scale.core;

import java.util.EnumMap;

/** Local, versioned estimates. Missing values are absent, never represented by zero. */
public final class BodyMetrics {
    public static final int ALGORITHM_VERSION = 2;

    public enum Metric {
        WEIGHT("体重", "kg", 2),
        BMI("BMI", "", 1),
        BODY_FAT("体脂率", "%", 1),
        MUSCLE("肌肉率", "%", 2),
        MUSCLE_MASS("肌肉重量", "kg", 2),
        WATER("水分", "%", 2),
        WATER_MASS("水分重量", "kg", 2),
        PROTEIN("蛋白质", "%", 2),
        BONE("骨量", "kg", 1),
        BONE_PERCENT("骨量比例", "%", 2),
        VISCERAL_FAT("内脏脂肪等级", "级", 0),
        BMR("基础代谢", "kcal/天", 1),
        FAT_MASS("脂肪重量", "kg", 2),
        LEAN_MASS("去脂体重", "kg", 2),
        LEAN_PERCENT("去脂体重比例", "%", 2),
        BODY_AGE("身体年龄", "岁", 0),
        STANDARD_WEIGHT_MIN("正常体重下限", "kg", 2),
        STANDARD_WEIGHT_MAX("正常体重上限", "kg", 2),
        FAT_INDEX("体脂指数", "", 0),
        OBESITY_DEGREE("肥胖级别", "", 0),
        SCORE("身体评分", "分", 0),
        WEIGHT_CONTROL("体重控制量", "kg", 2),
        FAT_CONTROL("脂肪控制量", "kg", 2),
        MUSCLE_CONTROL("肌肉控制量", "kg", 2),
        WATER_CONTROL("水分控制量", "kg", 2),
        FMI("脂肪质量指数 FMI", "kg/m²", 2),
        FFMI("去脂体重指数 FFMI", "kg/m²", 2);

        public final String title;
        public final String unit;
        public final int digits;

        Metric(String title, String unit, int digits) {
            this.title = title;
            this.unit = unit;
            this.digits = digits;
        }
    }

    public final EnumMap<Metric, Double> values = new EnumMap<>(Metric.class);
    public final EnumMap<Metric, String> grades = new EnumMap<>(Metric.class);
    public final EnumMap<Metric, String> references = new EnumMap<>(Metric.class);
    public final EnumMap<Metric, Double> scores = new EnumMap<>(Metric.class);
    public String bodyShape;
    public String calculationBasis;
    public int algorithmVersion = ALGORITHM_VERSION;

    public Double get(Metric metric) { return values.get(metric); }

    public void put(Metric metric, double value) {
        if (Double.isFinite(value)) values.put(metric, value);
    }
}
