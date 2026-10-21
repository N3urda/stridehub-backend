package io.stridehub.app;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import io.stridehub.app.scale.data.LocalStore;
@SuppressWarnings("deprecation")
public final class ScaleMigrationTest extends AndroidTestCase {
    public void testAtomicImportAndReplay() throws Exception {
        RenamingDelegatingContext context=new RenamingDelegatingContext(getContext(),"migration_test_");
        context.deleteDatabase("tinyyumi.db");
        LocalStore store=new LocalStore(context);
        String header="记录ID,测量时间,记录时本地日期,体重kg,体脂率%,BMI,阻抗,来源,设备地址,测量时资料快照\n";
        String row="1,2026-10-21T08:30:00+08:00,2026-10-21,70,,,0,手工,,\n";
        try {
            assertEquals(1,store.importCsv(header+row));assertEquals(0,store.importCsv(header+row));
            assertEquals(1,store.all().size());assertNull(store.all().get(0).bodyFatPercent);
            try {store.importCsv(header+row.replace("1,2026","2,2026")+row.replace("70,,,","NaN,,,"));fail("malformed import accepted");}catch(IllegalArgumentException expected){}
            assertEquals(1,store.all().size());
            long id=store.all().get(0).id;store.delete(id);
            assertEquals(0,store.importCsv(header+row));assertTrue(store.all().isEmpty());
        } finally {store.close();context.deleteDatabase("tinyyumi.db");}
    }
}
