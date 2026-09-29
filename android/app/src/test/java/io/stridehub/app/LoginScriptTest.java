package io.stridehub.app;

/** Escaping/contract tests; DOM behaviour is covered by tests/e2e/android-login.cjs. */
public final class LoginScriptTest {
    private static int assertions;

    public static void main(String[] args) {
        equal("\"quote\\\" slash\\\\ newline\\n return\\r tab\\t\\b\\f\"",
            LoginScript.jsonString("quote\" slash\\ newline\n return\r tab\t\b\f"));
        equal("\"\\u003c/script\\u003e\\u0026\\u2028\\u2029\\u0000\\u001f\\u007f\"",
            LoginScript.jsonString("</script>&\u2028\u2029\u0000\u001f\u007f"));
        equal("\"跑者🚀\"", LoginScript.jsonString("跑者🚀"));
        equal("\"\\ud800x\\udfff\"", LoginScript.jsonString("\ud800x\udfff"));
        equal("\"';globalThis.stolen=true;//\"", LoginScript.jsonString("';globalThis.stolen=true;//"));

        ServerAddress address = ServerAddress.parse("https://runner.example/stridehub", false);
        String script = LoginScript.build(address, "user\";attack();", "password\n</script>", false);
        check(script.contains("https://runner.example/stridehub/admin/login"));
        check(script.contains("https://runner.example/stridehub/admin/training/today"));
        check(script.contains("user\\\";attack();"));
        check(script.contains("password\\n\\u003c/script\\u003e"));
        check(!script.contains("password\n</script>"));
        check(script.contains("const remember = false;"));
        check(LoginScript.build(address, "user", "pass").contains("const remember = true;"));
        String probe = LoginScript.probe(address);
        check(!probe.contains("password\n</script>"));
        check(!probe.contains("requestSubmit.call"));
        check(probe.contains("away_from_login"));
        check(probe.contains("login_form"));
        rejected(() -> LoginScript.build(address, null, "pass"));
        rejected(() -> LoginScript.build(address, "user", null));
        rejected(() -> LoginScript.build(address, "", "pass"));
        rejected(() -> LoginScript.build(address, "user", ""));
        System.out.println("LoginScriptTest: " + assertions + " assertions passed");
    }

    private static void check(boolean value) {
        assertions++;
        if (!value) throw new AssertionError("Assertion " + assertions + " failed");
    }

    private static void equal(String expected, String actual) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("Escaping result differed from expected literal");
    }

    private static void rejected(Runnable action) {
        assertions++;
        try { action.run(); throw new AssertionError("Expected invalid credentials to be rejected"); }
        catch (IllegalArgumentException expected) { /* Deliberate public validation failure. */ }
    }
}
