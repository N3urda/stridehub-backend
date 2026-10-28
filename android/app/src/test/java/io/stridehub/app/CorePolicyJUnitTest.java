package io.stridehub.app;

import org.junit.Test;

/** Exposes the dependency-free policy cases to Gradle's standard unit-test task. */
public final class CorePolicyJUnitTest {
    @Test public void serverAddressPolicy() {
        ServerAddressTest.main(new String[0]);
    }

}
