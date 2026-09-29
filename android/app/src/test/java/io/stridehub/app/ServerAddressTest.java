package io.stridehub.app;

/** Dependency-free tests; executable on the build JDK as well as Android CI. */
public final class ServerAddressTest {
    private static int assertions;

    public static void main(String[] args) {
        ServerAddress root = ServerAddress.parse("  HTTPS://Runner.Example:443/  ", false);
        equal("https://runner.example", root.baseUrl());
        equal("https://runner.example/admin/training/today", root.route("/admin/training/today"));
        check(!root.isHttp());
        check(root.isTrustedUrl("https://RUNNER.example:443/admin/training/today?sessionId=abc#feedback"));
        check(!root.isTrustedUrl("https://runner.example.evil.test/admin"));
        check(!root.isTrustedUrl("http://runner.example/admin"));
        check(!root.isTrustedUrl("https://runner.example:444/admin"));
        check(!root.isTrustedUrl("https://person@runner.example/admin"));
        check(!root.isTrustedUrl("javascript:alert(1)"));
        check(!root.isTrustedUrl("file:///admin"));
        check(!root.isTrustedUrl("https://runner.example/admin\\login"));
        check(!root.isTrustedUrl("https://runner.example/admin\n/login"));
        check(!root.isTrustedUrl("https://runner.example/admin\u0085/login"));
        check(root.isLoginUrl("https://runner.example:443/admin/login"));
        check(!root.isLoginUrl("https://runner.example/admin/login?redirect=evil"));
        check(!root.isLoginUrl("https://runner.example/admin/login#fragment"));
        check(!root.isLoginUrl("https://runner.example/admin/login/"));
        check(root.isRouteUrl("https://runner.example:443/admin/training/today", "/admin/training/today"));
        check(!root.isRouteUrl("https://runner.example/admin/training/today?preview=1", "/admin/training/today"));
        check(!root.isRouteUrl("https://runner.example/admin/training/today#session", "/admin/training/today"));
        check(!root.isRouteUrl("https://runner.example/admin/login", "/admin/training/today"));

        ServerAddress nested = ServerAddress.parse("https://runner.example/stridehub/", false);
        equal("https://runner.example/stridehub", nested.baseUrl());
        equal("https://runner.example/stridehub/admin/login", nested.route("/admin/login"));
        check(nested.isTrustedUrl("https://runner.example/stridehub"));
        check(nested.isTrustedUrl("https://runner.example/stridehub/"));
        check(nested.isTrustedUrl("https://runner.example/stridehub/admin?page=1"));
        check(!nested.isTrustedUrl("https://runner.example/stridehub-other/admin"));
        check(!nested.isTrustedUrl("https://runner.example/admin"));
        check(!nested.isTrustedUrl("https://runner.example/stridehub/../admin"));
        check(!nested.isTrustedUrl("https://runner.example/stridehub/%2e%2e/admin"));
        check(!nested.isTrustedUrl("https://runner.example/stridehub/%252e%252e/admin"));
        check(!nested.isTrustedUrl("https://runner.example/stridehub%2fadmin"));
        check(!nested.isTrustedUrl("https://runner.example/stridehub/%5cadmin"));
        check(!nested.isTrustedUrl("https://runner.example/stridehub/%00admin"));
        check(nested.isTrustedUrl("https://runner.example/stridehub/files/run%20one.fit"));
        check(nested.isLoginUrl("https://runner.example/stridehub/admin/login"));

        ServerAddress local = ServerAddress.parse("http://192.168.1.20:8081", true);
        check(local.isHttp());
        equal("http://192.168.1.20:8081/admin/login", local.route("/admin/login"));
        equal("http://localhost", ServerAddress.parse("http://LOCALHOST:80/", true).baseUrl());
        equal("https://[2001:db8::1]:8443", ServerAddress.parse("https://[2001:db8::1]:8443", false).baseUrl());
        check(ServerAddress.parse("http://[::1]:8081", true).isTrustedUrl("http://[::1]:8081/admin"));
        check(ServerAddress.parse("https://[2001:0db8:0:0:0:0:0:1]", false).isTrustedUrl("https://[2001:db8::1]/admin"));
        check(ServerAddress.parse("https://[2001:0db8:0:0:0:0:0:1]", false)
            .isRouteUrl("https://[2001:db8::1]/admin/training/today", "/admin/training/today"));
        check(!ServerAddress.parse("https://[::ffff:127.0.0.1]", false).isTrustedUrl("https://127.0.0.1/admin"));
        ServerAddress unicodePath = ServerAddress.parse("https://runner.example/跑步/", false);
        equal("https://runner.example/%E8%B7%91%E6%AD%A5", unicodePath.baseUrl());
        check(unicodePath.isTrustedUrl("https://runner.example/%E8%B7%91%E6%AD%A5/admin/login"));

        String[] invalid = {
            "", "runner.example", "//runner.example", "ftp://runner.example", "https:///admin",
            "https://user:pass@runner.example", "https://runner.example?query=1", "https://runner.example#hash",
            "https://runner.example/?", "https://runner.example/#", "https://runner.example\\@evil.test",
            "https://runner.example/path\n/", "https://runner.example/../admin", "https://runner.example/a/./b",
            "https://runner.example/path\u0085/",
            "https://runner.example//admin", "https://runner.example/%2e%2e", "https://runner.example/%252e",
            "https://runner.example/a%2fb", "https://runner.example/a%5cb", "https://runner.example/%00",
            "https://runner.example:0", "https://runner.example:65536", "https://runner.example:abc",
            "https://bad_host.test", "https://-bad.test", "https://bad-.test", "https://bad..test",
            "https://127.1", "https://0177.0.0.1", "https://2130706433", "https://0x7f000001",
            "https://runner.123", "https://[fe80::1%25en0]"
        };
        for (String url : invalid) rejected(() -> ServerAddress.parse(url, true), url);
        rejected(() -> ServerAddress.parse(null, false), "null");
        rejected(() -> ServerAddress.parse("http://runner.example", false), "HTTP without opt-in");
        String[] badRoutes = {"admin", "//evil.test/a", "/../admin", "/%2e%2e/admin", "/admin?x=1", "/admin#x", "/admin\\x"};
        for (String route : badRoutes) rejected(() -> nested.route(route), route);
        System.out.println("ServerAddressTest: " + assertions + " assertions passed");
    }

    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("Assertion " + assertions + " failed");
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but was " + actual);
    }

    private static void rejected(Runnable action, String label) {
        assertions++;
        try {
            action.run();
            throw new AssertionError("Should reject: " + label);
        } catch (IllegalArgumentException expected) {
            if (expected.getMessage() == null || expected.getMessage().isEmpty()) throw new AssertionError("Missing user-facing validation message");
        }
    }
}
