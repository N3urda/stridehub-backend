package io.stridehub.app.nativeui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeNavigationTest {
    @Test fun independentTabHistoryRestoresAndBackPopsOnlyCurrentTab() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val state=SavedStateHandle();val first=AppModel(app,state)
        val body=route("records","measurements");val workout=route("records","activities")
        first.select(1);first.push(body);first.select(2);first.push(workout)
        val restored=AppModel(app,state)
        assertEquals(2,restored.tab);assertEquals(workout,restored.currentRoute)
        restored.pop();assertEquals("",restored.currentRoute)
        restored.select(1);assertEquals(body,restored.currentRoute)
        restored.pop();assertEquals("",restored.currentRoute)
    }
}
