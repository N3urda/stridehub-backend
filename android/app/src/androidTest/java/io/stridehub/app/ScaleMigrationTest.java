package io.stridehub.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import io.stridehub.app.scale.data.LocalStore;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class ScaleMigrationTest extends AndroidTestCase {
    public void testAtomicImportAndReplay() throws Exception {
        DisposableDatabaseContext context = new DisposableDatabaseContext(getContext());
        try (LocalStore store = new LocalStore(context)) {
            File actual = new File(store.getWritableDatabase().getPath()).getCanonicalFile();
            File expected = context.getDatabasePath("tinyyumi.db").getCanonicalFile();
            assertEquals("SQLiteOpenHelper must open the disposable test database", expected, actual);
            assertEquals(context.directory.getCanonicalFile(), actual.getParentFile());
            assertFalse("Migration fixture must never open the application weighing database",
                    getContext().getDatabasePath("tinyyumi.db").getCanonicalFile().equals(actual));
            assertTrue("Every test invocation must start without prior measurements", store.all().isEmpty());
            try (Cursor imports = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM legacy_imports", null)) {
                assertTrue(imports.moveToFirst());
                assertEquals("Disposable database must have no previous CSV tombstones", 0, imports.getInt(0));
            }

            String header = "记录ID,测量时间,记录时本地日期,体重kg,体脂率%,BMI,阻抗,来源,设备地址,测量时资料快照\n";
            String row = "1,2026-10-21T08:30:00+08:00,2026-10-21,70,,,0,手工,,\n";
            assertEquals(1, store.importCsv(header + row));
            assertEquals(0, store.importCsv(header + row));
            assertEquals(1, store.all().size());
            assertNull(store.all().get(0).bodyFatPercent);
            try {
                store.importCsv(header + row.replace("1,2026", "2,2026") + row.replace("70,,,", "NaN,,,"));
                fail("malformed import accepted");
            } catch (IllegalArgumentException expectedError) {
                // The whole malformed batch must roll back, including its otherwise valid row.
            }
            assertEquals(1, store.all().size());
            long id = store.all().get(0).id;
            store.delete(id);
            assertEquals(0, store.importCsv(header + row));
            assertTrue(store.all().isEmpty());
        } finally {
            context.cleanup();
        }
    }

    /** SQLiteOpenHelper may use getDatabasePath or either open overload depending on API level. */
    private static final class DisposableDatabaseContext extends ContextWrapper {
        private final File directory;
        private final String scope = "scale_migration_" + UUID.randomUUID();
        private final Set<String> preferences = new HashSet<>();

        DisposableDatabaseContext(Context base) throws IOException {
            super(base);
            directory = new File(base.getCacheDir(), scope);
            if (!directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Could not create disposable migration-test directory");
            }
        }

        @Override public Context getApplicationContext() {
            return this;
        }

        @Override public File getDatabasePath(String name) {
            if (!"tinyyumi.db".equals(name)) {
                throw new IllegalArgumentException("Unexpected migration-test database name");
            }
            return new File(directory, name);
        }

        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory);
        }

        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, errorHandler);
        }

        @Override public boolean deleteDatabase(String name) {
            return SQLiteDatabase.deleteDatabase(getDatabasePath(name));
        }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            String scoped = scope + "_" + name;
            preferences.add(scoped);
            return super.getSharedPreferences(scoped, mode);
        }

        void cleanup() {
            deleteDatabase("tinyyumi.db");
            for (String name : preferences) {
                getBaseContext().deleteSharedPreferences(name);
            }
            directory.delete();
        }
    }
}
