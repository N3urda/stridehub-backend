package io.stridehub.app;

import java.net.URI;

/** Matches actual loaded pages, including installation subdirectories and filtered views. */
final class NavigationSection {
    static final String HOME = "/admin/insights";
    static final String[] LABELS = {"总览", "身体", "运动", "计划", "采集"};
    static final String[] PATHS = {HOME, "/admin/health", "/admin/running", "/admin/plans", "native:weight"};

    static boolean isHome(ServerAddress server, String url) {
        return server.isTrustedUrl(url)
            && URI.create(url).getRawPath().equals(URI.create(server.route(HOME)).getRawPath());
    }

    static int selected(ServerAddress server, String url) {
        if (!server.isTrustedUrl(url)) return -1;
        String path = URI.create(url).getRawPath();
        String base = URI.create(server.baseUrl()).getRawPath();
        path = path.substring(base.length());
        if (within(path, HOME)) return 0;
        if (within(path, "/admin/health")) return 1;
        if (within(path, "/admin/running") || within(path, "/admin/activities")) return 2;
        if (within(path, "/admin/plans") || within(path, "/admin/training")) return 3;
        return -1;
    }

    private static boolean within(String path, String section) {
        return path.equals(section) || path.startsWith(section + "/");
    }
}
