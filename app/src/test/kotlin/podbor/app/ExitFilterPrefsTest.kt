package podbor.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.net.ExitFilterPrefs
import podbor.core.ExitFilter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExitFilterPrefsTest {
    @Test fun persistsExclusionsAcrossInstancesAndCanReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("exit-filter-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first = ExitFilterPrefs(prefs)
        assertEquals(ExitFilter(), first.read())
        first.save(ExitFilter(setOf("RU", "BY")))
        assertEquals(ExitFilter(setOf("RU", "BY")), ExitFilterPrefs(prefs).read())
        first.save(ExitFilter())
        assertEquals(ExitFilter(), ExitFilterPrefs(prefs).read())
    }
}
