package io.stridehub.app;

import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public class SessionLifetimeTest {
    @Test public void explicitPersistentSessionHasNoInventedExpiryEvenManyYearsLater() {
        SessionLifetime value=SessionLifetime.from(true,null);
        assertTrue(value.isUsableAt(Instant.parse("2080-01-01T00:00:00Z")));
        assertTrue(value.persistent());
    }
    @Test public void missingLegacyExpiryIsNeverInterpretedAsPermanent() {
        assertThrows(IllegalArgumentException.class,()->SessionLifetime.from(false,null));
        assertThrows(IllegalArgumentException.class,()->SessionLifetime.from(true,"2099-01-01T00:00:00Z"));
        SessionLifetime old=SessionLifetime.from(false,"2020-01-01T00:00:00Z");
        assertFalse(old.isUsableAt(Instant.parse("2026-10-27T00:00:00Z")));
        assertFalse(old.persistent());
    }
}
