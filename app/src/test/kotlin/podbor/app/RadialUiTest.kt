package podbor.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.Face
import podbor.app.ui.PRESS_TAG
import podbor.app.ui.PodborTheme
import podbor.app.ui.PressButton
import podbor.core.Button

/** UI радиальной кнопки: тап, лепесток «выкл» долгим нажатием с протяжкой, «включить». Robolectric, без телефона. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadialUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(face: Face) = compose.setContent {
        PodborTheme { PressButton(face, onPress = { calls += "press" }, onOff = { calls += "off" }, onOn = { calls += "on" }) }
    }

    /** Точка лепестка «выкл»: над центром кнопки на ~110 dp — за краем кнопки (80 dp), внутри области жеста. */
    private fun petal(center: Offset): Offset = Offset(center.x, center.y - with(compose.density) { 110.dp.toPx() })

    @Test
    fun tapPresses() {
        show(Face.Press(Button.Ready))
        compose.onNodeWithTag(PRESS_TAG).performTouchInput { down(center); advanceEventTime(100); up() }
        assertEquals(listOf("press"), calls)
    }

    @Test
    fun longPressAndDragToPetalTurnsOff() {
        show(Face.Press(Button.Green))
        compose.onNodeWithTag(PRESS_TAG).performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(Offset(center.x, center.y - 40f))
            moveTo(petal(center))
        }
        compose.onNodeWithText("ВЫКЛ").assertIsDisplayed()
        compose.onNodeWithTag(PRESS_TAG).performTouchInput { up() }
        assertEquals(listOf("off"), calls)
    }

    @Test
    fun releaseAwayFromPetalDoesNothing() {
        show(Face.Press(Button.Green))
        compose.onNodeWithTag(PRESS_TAG).performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(Offset(center.x + 300f, center.y))
            up()
        }
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun petalHiddenUntilLongPress() {
        show(Face.Press(Button.Ready))
        compose.onNodeWithText("ВЫКЛ").assertDoesNotExist()
    }

    @Test
    fun accessibilityActionTurnsOff() {
        show(Face.Press(Button.Ready))
        val off = compose.onNodeWithTag(PRESS_TAG).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Выключить VPN" }
        compose.runOnIdle { off.action() }
        assertEquals(listOf("off"), calls)
    }

    @Test
    fun vpnOffShowsTurnOnAndTapTurnsOn() {
        show(Face.TurnOn)
        compose.onNodeWithTag(PRESS_TAG).assertTextContains("ВКЛЮЧИТЬ", substring = true, ignoreCase = false)
        compose.onNodeWithTag(PRESS_TAG).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("on"), calls)
    }

    @Test
    fun vpnOffHasNoPetal() {
        show(Face.TurnOn)
        compose.onNodeWithTag(PRESS_TAG).performTouchInput { down(center); advanceEventTime(600); moveTo(petal(center)); up() }
        compose.onNodeWithTag(PRESS_TAG)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun searchingIgnoresTapButPetalStillWorks() {
        show(Face.Press(Button.Searching))
        compose.onNodeWithTag(PRESS_TAG).performTouchInput { down(center); advanceEventTime(100); up() }
        compose.onNodeWithTag(PRESS_TAG).performTouchInput { down(center); advanceEventTime(600); moveTo(petal(center)); up() }
        assertEquals(listOf("off"), calls)
    }
}
