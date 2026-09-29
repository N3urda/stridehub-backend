package io.stridehub.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/** A deliberately strict installation URL, shared by setup, navigation and login. */
public final class ServerAddress {
    private final String scheme;
    private final String host;
    private final String originHost;
    private final int port;
    private final String basePath;
    private final String baseUrl;

    private ServerAddress(URI uri) {
        scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        host = uri.getHost().toLowerCase(Locale.ROOT);
        originHost = canonicalHost(host);
        port = effectivePort(uri);
        String path = uri.getRawPath();
        basePath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        boolean defaultPort = port == ("https".equals(scheme) ? 443 : 80);
        baseUrl = scheme + "://" + host + (defaultPort ? "" : ":" + port) + basePath;
    }

    public static ServerAddress parse(String input, boolean allowHttp) {
        if (input == null) throw invalid("请输入服务器地址");
        // Ordinary leading/trailing spaces may come from paste; controls are never trimmed away.
        if (hasUnsafeCharacters(input)) throw invalid("服务器地址不能包含控制字符或反斜杠");
        URI uri = parseUri(input.trim());
        validateHttpUri(uri);
        if ("http".equalsIgnoreCase(uri.getScheme()) && !allowHttp) {
            throw invalid("请使用 HTTPS，或明确开启 HTTP 连接");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid("请填写服务器根地址或安装子目录，不要附带查询参数或锚点");
        }
        // WebView serializes Unicode paths as UTF-8 percent escapes.
        return new ServerAddress(parseUri(uri.toASCIIString()));
    }

    public String baseUrl() { return baseUrl; }

    /** Joins an app-owned absolute path without allowing it to replace the installation origin. */
    public String route(String path) {
        if (path == null || !path.startsWith("/") || hasUnsafeCharacters(path)) {
            throw invalid("无效的应用页面路径");
        }
        URI uri = parseUri(path);
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid("无效的应用页面路径");
        }
        validatePath(uri.getRawPath());
        return baseUrl + path;
    }

    public boolean isTrustedUrl(String url) {
        if (url == null || hasUnsafeCharacters(url)) return false;
        try {
            URI uri = parseUri(url);
            validateHttpUri(uri);
            String path = uri.getRawPath();
            return scheme.equalsIgnoreCase(uri.getScheme())
                && originHost.equals(canonicalHost(uri.getHost()))
                && port == effectivePort(uri)
                && (basePath.isEmpty() || path.equals(basePath) || path.startsWith(basePath + "/"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public boolean isLoginUrl(String url) {
        return isRouteUrl(url, "/admin/login");
    }

    public boolean isRouteUrl(String url, String path) {
        if (!isTrustedUrl(url)) return false;
        URI uri = parseUri(url);
        URI expected = parseUri(route(path));
        return uri.getRawQuery() == null && uri.getRawFragment() == null
            && expected.getRawPath().equals(uri.getRawPath());
    }

    public boolean isHttp() { return "http".equals(scheme); }

    private static URI parseUri(String input) {
        try {
            return new URI(input);
        } catch (URISyntaxException error) {
            throw invalid("服务器地址格式不正确");
        }
    }

    private static void validateHttpUri(URI uri) {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw invalid("服务器地址必须以 https:// 或 http:// 开头");
        }
        String host = uri.getHost();
        if (uri.isOpaque() || host == null || uri.getRawUserInfo() != null || uri.getRawAuthority().endsWith(":")) {
            throw invalid("服务器地址必须包含有效主机，且不能嵌入用户名或密码");
        }
        validateHost(host);
        if (uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1) {
            throw invalid("服务器端口必须在 1 到 65535 之间");
        }
        validatePath(uri.getRawPath());
    }

    private static void validateHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            // URI has already validated the IPv6 literal. Zone IDs are not portable to WebView.
            if (host.indexOf('%') >= 0) throw invalid("服务器地址不支持带区域标识的 IPv6 地址");
            return;
        }
        if (host.length() > 253 || host.endsWith(".")) throw invalid("服务器主机名不正确");
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
                throw invalid("服务器主机名不正确");
            }
        }
        String last = labels[labels.length - 1];
        if (last.matches("[0-9]+|0x[0-9a-f]+")) {
            if (labels.length != 4) throw invalid("请填写完整的 IPv4 地址");
            for (String label : labels) {
                if (!label.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(label) > 255) {
                    throw invalid("IPv4 地址格式不正确");
                }
            }
        }
    }

    private static void validatePath(String path) {
        if (path == null || path.contains("//") || hasUnsafeCharacters(path)) throw invalid("安装路径不正确");
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) throw invalid("安装路径不能包含相对目录");
        }
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '%') {
                if (i + 2 >= path.length()) throw invalid("安装路径编码不正确");
                int value;
                try { value = Integer.parseInt(path.substring(i + 1, i + 3), 16); }
                catch (NumberFormatException error) { throw invalid("安装路径编码不正确"); }
                // Reject encoded separators, traversal, double-encoding and controls.
                if (value <= 0x1f || value == 0x7f || value == 0x25 || value == 0x2e || value == 0x2f || value == 0x5c) {
                    throw invalid("安装路径包含不安全的编码");
                }
                i += 2;
            }
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }

    private static String canonicalHost(String host) {
        if (!host.startsWith("[")) return host.toLowerCase(Locale.ROOT);
        try {
            // Bracketed literals were validated by URI: no hostname lookup is performed here.
            // Keep the prefix because mapped IPv6 and IPv4 remain distinct browser origins.
            return "ipv6:" + InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException error) {
            throw invalid("IPv6 地址格式不正确");
        }
    }

    private static boolean hasUnsafeCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c <= 0x1f || c == 0x7f) return true;
        }
        return false;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
