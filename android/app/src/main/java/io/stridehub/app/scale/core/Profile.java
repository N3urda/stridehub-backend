package io.stridehub.app.scale.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Explicit user-entered local profile. A missing profile must never be synthesized. */
public final class Profile {
    public final int heightCm;
    public final String birthDate;
    public final boolean male;
    public final int userId;

    public Profile(int heightCm, String birthDate, boolean male, int userId) {
        this.heightCm = heightCm;
        this.birthDate = birthDate;
        this.male = male;
        this.userId = userId;
    }

    public int ageAt(long when) {
        try {
            LocalDate birthday = LocalDate.parse(birthDate == null ? "" : birthDate);
            LocalDate date = Instant.ofEpochMilli(when).atZone(ZoneId.systemDefault()).toLocalDate();
            if (birthday.isAfter(date)) return -1;
            return Period.between(birthday, date).getYears();
        } catch (DateTimeParseException e) { return -1; }
    }

    public String validationError(long when) {
        if (heightCm < 80 || heightCm > 250) return "请输入 80–250 厘米之间的身高";
        if (birthDate == null || !birthDate.matches("\\d{4}-\\d{2}-\\d{2}")) return "请选择出生日期";
        int age = ageAt(when);
        if (age < 0) return "出生日期无效，不能晚于测量日期";
        if (age < 18 || age > 80) return "当前体脂估算仅支持 18–80 岁成人";
        if (userId < 1 || userId > 65535) return "用户编号无效";
        return null;
    }

    public String snapshot() {
        return "{\"heightCm\":" + heightCm + ",\"birthDate\":\"" +
                (birthDate == null ? "" : birthDate.replace("\\", "\\\\").replace("\"", "\\\"")) +
                "\",\"male\":" + male + ",\"userId\":" + userId + "}";
    }
}
