package io.stridehub.app.scale.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static io.stridehub.app.scale.core.BodyMetrics.Metric.*;

/** Synthetic reference outputs captured by invoking the supplied APK's native library. */
public final class NativeCompositionReferenceTest {
    public static void main(String[] args) throws Exception {
        List<String> rows = Files.readAllLines(Path.of("app/src/test/resources/body-composition-native.csv"));
        BodyMetrics.Metric[] metrics = {BODY_FAT, BMI, MUSCLE, BONE, WATER, BMR, BODY_AGE};
        long when = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli();
        int cases = 0, compared = 0;
        for (int row = 1; row < rows.size(); row++) {
            String[] c = rows.get(row).split(",", -1);
            int sex = Integer.parseInt(c[3]);
            double fat = Double.parseDouble(c[10]);
            // formValue in the original app always uses Chinese mode 0; other native
            // branches and invalid inputs remain in the fixture for audit purposes.
            if (!c[7].equals("0") || !c[8].equals("zh") || (sex != 1 && sex != 2) || fat < 5 || fat > 75) continue;
            int age = Integer.parseInt(c[2]);
            Profile profile = new Profile(Integer.parseInt(c[1]), (2026 - age) + "-01-01", sex == 1, 1);
            BodyMetrics actual = BodyComposition.calculate(profile, when, Double.parseDouble(c[4]), fat,
                    (int) Double.parseDouble(c[5]), "合成测试");
            cases++;
            for (int m = 0; m < metrics.length; m++) {
                Double value = actual.get(metrics[m]);
                float expected = Float.parseFloat(c[12 + m]);
                if (value == null || Float.floatToIntBits(value.floatValue()) != Float.floatToIntBits(expected)) {
                    throw new AssertionError(c[0] + " " + metrics[m] + " native=" + expected + " actual=" + value);
                }
                compared++;
            }
        }
        if (cases < 1000) throw new AssertionError("Unexpectedly low reference coverage: " + cases);
        System.out.println("NativeCompositionReferenceTest: " + cases + " cases / " + compared + " float-bit comparisons passed");
    }
}
