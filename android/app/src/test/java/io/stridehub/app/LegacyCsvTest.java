package io.stridehub.app;
import org.junit.Test;
import static org.junit.Assert.*;
import io.stridehub.app.scale.core.LegacyCsv;
import io.stridehub.app.scale.core.Measurement;
import java.util.List;
public final class LegacyCsvTest {
    private static final String HEADER="记录ID,测量时间,记录时本地日期,体重kg,体脂率%,BMI,阻抗,来源,设备地址,测量时资料快照\r\n";
    @Test public void capturesOffsetAndMissingValues() {
        List<List<String>> rows=LegacyCsv.parse("\ufeff"+HEADER+"1,2026-10-21T08:30:00+08:00,2026-10-21,70.25,,,0,手工,,\r\n");
        Measurement m=LegacyCsv.measurement(rows.get(1));
        assertEquals(70.25,m.weightKg,0);assertNull(m.bodyFatPercent);assertNull(m.bmi);
        assertEquals(java.time.Instant.parse("2026-10-21T00:30:00Z").toEpochMilli(),m.measuredAt);
    }
    @Test public void preservesEscapedMultilineCells() {
        List<List<String>> rows=LegacyCsv.parse(HEADER+"1,2026-10-21T08:30:00+08:00,2026-10-21,70,20,,500,\"云麦,秤\n备注\",,\"{\"\"heightCm\"\":170}\"\r\n");
        assertEquals("云麦,秤\n备注",rows.get(1).get(7));assertEquals("{\"heightCm\":170}",rows.get(1).get(9));
    }
    @Test public void rejectsTruncationAndInvalidMeasurements() {
        for(String s:new String[]{HEADER+"\"unfinished",HEADER+"1,2", "wrong header"}) {
            try{LegacyCsv.parse(s);fail("invalid CSV accepted");}catch(IllegalArgumentException expected){}
        }
        List<List<String>> rows=LegacyCsv.parse(HEADER+"1,2026-10-21T08:30:00+08:00,2026-10-21,NaN,,,0,手工,,\n");
        try{LegacyCsv.measurement(rows.get(1));fail();}catch(IllegalArgumentException expected){}
    }
}
