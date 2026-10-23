package io.stridehub.app.nativeui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCacheIsolationTest {
    @Test fun logoutClearsOnlyAccountAndConflictDraftSurvivesRefresh() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,NativeDatabase::class.java).build()
        try {
            val cache=db.cache();val alice=AccountScope.key("https://health.test/a","alice");val bob=AccountScope.key("https://health.test/a","bob")
            cache.put(CachedResponse(alice,"/health","{\"weight\":71}",1));cache.put(CachedResponse(bob,"/health","{\"weight\":82}",2))
            cache.putDraft(SavedDraft(alice,"meals:1","{\"version\":3,\"foods\":\"my unsaved meal\"}"));cache.putDraft(SavedDraft(bob,"meals:1","{\"version\":2,\"foods\":\"other meal\"}"))
            // A server refresh changes the snapshot without destroying an unresolved local draft.
            cache.put(CachedResponse(alice,"/health","{\"weight\":70}",3))
            assertTrue(cache.draft(alice,"meals:1")!!.payload.contains("my unsaved meal"))
            assertFalse(cache.response(alice,"/health")!!.payload.contains("82"))
            cache.clear(alice)
            assertNull(cache.response(alice,"/health"));assertNull(cache.draft(alice,"meals:1"))
            assertNotNull(cache.response(bob,"/health"));assertNotNull(cache.draft(bob,"meals:1"))
        } finally {db.close()}
    }
}
