package podbor.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import podbor.app.ui.MainActivity
import podbor.app.vpn.PodborVpnService
import podbor.screen.Offer

/**
 * Экран Android на общем ViewModel. Activity — только до onCreate: окна нет, бесконечные анимации кнопки не крутят
 * главный поток Robolectric.
 *
 * Экран Android на общем ViewModel: обновлялка помнит ответ GitHub между запусками, пересоздание его не теряет. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainViewModelWiringTest {

    /**
     * Служба не запущена: привязка без создания не удаётся (как на телефоне до первого старта VPN). Разрешения уже
     * спрошены: шторка геолокации — своё окно, и пересозданный видимый экран крутил бы её кадры без конца.
     */
    @Before
    fun noServiceAndAsked() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PodborVpnService::class.java))
        app.getSharedPreferences("podbor", Context.MODE_PRIVATE).edit().putBoolean("permissions_asked", true).commit()
    }

    @Test
    fun `известный релиз из прошлых запусков — чип сразу, без сети`() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("podbor", Context.MODE_PRIVATE).edit()
            .putString("update_tag", "v99.0.0")
            .putString("update_url", "https://example.org/podbor-99.0.0.apk")
            .commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertEquals(Offer("99.0.0", "https://example.org/podbor-99.0.0.apk"), activity.vm.update.value)
    }

    @Test
    fun `пересоздание экрана (тема, шрифт) — тот же ViewModel`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val before = controller.get().vm
        controller.recreate()
        assertSame(before, controller.get().vm)
    }
}
