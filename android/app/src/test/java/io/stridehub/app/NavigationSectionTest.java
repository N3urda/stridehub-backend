package io.stridehub.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public final class NavigationSectionTest {
    private final ServerAddress server = ServerAddress.parse("https://nas.example/stridehub", false);

    @Test public void mapsFilteredAndNestedPagesUnderInstallDirectory() {
        assertEquals(0, NavigationSection.selected(server, "https://nas.example/stridehub/admin/insights?from=2026-10-01#plans"));
        assertEquals(1, NavigationSection.selected(server, "https://nas.example/stridehub/admin/health/records"));
        assertEquals(2, NavigationSection.selected(server, "https://nas.example/stridehub/admin/activities/42"));
        assertEquals(3, NavigationSection.selected(server, "https://nas.example/stridehub/admin/training/today"));
        assertEquals(3, NavigationSection.selected(server, "https://nas.example/stridehub/admin/plans"));
    }

    @Test public void doesNotSelectUnrelatedOrUntrustedPages() {
        assertEquals(-1, NavigationSection.selected(server, "https://other.example/stridehub/admin/health"));
        assertEquals(-1, NavigationSection.selected(server, "https://nas.example/admin/health"));
        assertEquals(-1, NavigationSection.selected(server, "https://nas.example/stridehub/admin/health-check"));
        assertEquals(-1, NavigationSection.selected(server, "https://nas.example/stridehub/admin/nutrition"));
        assertEquals(-1, NavigationSection.selected(server, null));
    }

    @Test public void homeAcceptsPeriodStateButNotOtherPathsOrOrigins() {
        assertTrue(NavigationSection.isHome(server,"https://nas.example/stridehub/admin/insights?days=30#overview"));
        assertFalse(NavigationSection.isHome(server,"https://other.example/stridehub/admin/insights?days=30"));
        assertFalse(NavigationSection.isHome(server,"https://nas.example/stridehub/admin/insights/other"));
        assertFalse(server.isRouteUrl("https://nas.example/stridehub/admin/login?anything=1", "/admin/login"));
    }
}
