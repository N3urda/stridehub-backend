# Synthetic native formula reference data

`body-composition-native.csv` contains **3,405 synthetic test vectors**, generated on 2026-09-29 in an isolated Android 15 ARM64 emulator. It contains no user profile, measurement, device address, BLE capture, account data, or vendor executable code.

The local oracle loads only `libconfig.so` extracted from the user-supplied Yunmai 5.6.4 APK and invokes `com.yunmai.haoqing.logic.config.FormulaJNI.formFat` / `getValue`. The vendor library and test APK remain ignored local tooling and are not shipped in TinyYumi.

Native library SHA-256: `b4962250c445d105a1a592b475171f7c67706a0ee16e0a1a95e05db64d5856c3`.

`getValue` output mapping: `[fat, bmi, muscle, bone, water, bmr, somaAge]`. Every row was independently checked against direct calls to `getBMI`, `getMuscle`, `getBone`, `getWater`, `getBMR`, and `getSomaAge`: **0 raw float bit differences** across all 3,405 rows.

`inputFat` is the actual float passed into `getValue`; it may be explicitly supplied or taken from `formFat`. The separate `formFat` column is the native estimate computed from profile, weight, impedance, product, flag, language, and source. Sex values are `1` male and `2` female. One unsupported sex-0 row is retained for audit.

Coverage includes heights 80/250 and ages 18/80; explicit fat 5/12.3/20/30/45/75; BMI boundaries 28/35/40 ±0.1 at height 200; impedance/product/language/source branches; and 200 random synthetic cases generated with Python `random.Random(20260929)`.

Some rows deliberately fall outside supported application ranges. Production-path comparisons should filter `language=zh`, `flag=0`, sex 1/2, and valid profile/fat/impedance ranges as appropriate. The original derived-metrics Java wrapper always uses source `devices`.

The complete local JSON oracle also stores IEEE754 float bits. CSV numerical precision is sufficient to recover those float values using `Float.parseFloat`. Local build/run scripts and JNI ABI notes are in the ignored `.tools/formula-oracle/` directory.

## Body-fat precision regression vectors

`fat-native-precision.csv` contains 46 additional synthetic cases selected around one-decimal truncation boundaries and then independently executed by the same native oracle. All 46 failed the prior Java estimator: 40 incorrectly rejected a native result of 75.0% because the untruncated intermediate exceeded 75; 3 differed by 0.1 percentage points due to non-fused age multiplication/addition; 3 differed by 0.1 because `cbrt` does not reproduce native `powf(abs(delta)/50, 1/3f)`.

These references exercise the public protocol-to-measurement path. Inputs are synthetic edge cases, including extreme but syntactically accepted impedances; they are not actual body measurements. The same seven-value direct-call cross-check passed with zero float-bit differences for all 46 rows.
