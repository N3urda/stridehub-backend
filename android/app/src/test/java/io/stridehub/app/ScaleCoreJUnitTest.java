package io.stridehub.app;
import org.junit.Test;
import io.stridehub.app.scale.core.*;
public final class ScaleCoreJUnitTest {
    @org.junit.Test public void uploadTimestampsAlwaysContainSeconds(){
        long minute=java.time.Instant.parse("2026-10-21T08:30:00Z").toEpochMilli();
        org.junit.Assert.assertEquals("2026-10-21T08:30:00.000Z",io.stridehub.app.scale.core.Measurement.formatCapturedTime(minute,java.time.ZoneOffset.UTC));
        org.junit.Assert.assertEquals("2026-10-21T16:30:00.123+08:00",io.stridehub.app.scale.core.Measurement.formatCapturedTime(minute+123,java.time.ZoneId.of("Asia/Shanghai")));
    }
@Test public void recordsTest() throws Exception { RecordsTest.main(new String[0]); }
@Test public void weightInsightsTest() throws Exception { WeightInsightsTest.main(new String[0]); }
@Test public void protocolTest() throws Exception { ProtocolTest.main(new String[0]); }
@Test public void bodyCompositionTest() throws Exception { BodyCompositionTest.main(new String[0]); }
@Test public void bodyStandardsTest() throws Exception { BodyStandardsTest.main(new String[0]); }
@Test public void nativeCompositionReferenceTest() throws Exception { NativeCompositionReferenceTest.main(new String[0]); }
}
