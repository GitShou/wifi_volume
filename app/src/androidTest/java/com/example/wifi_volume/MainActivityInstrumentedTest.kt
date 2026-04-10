package com.example.wifi_volume

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.wifi_volume.data.SettingsRepository
import com.example.wifi_volume.model.ReapplyMode
import com.example.wifi_volume.monitor.ConnectionMonitorService
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext

    @Before
    fun setUp() {
        stopMonitoringService()
        assertCleanState()
    }

    @After
    fun tearDown() {
        stopMonitoringService()
    }

    @Test
    fun launch_showsDefaultFallbackRule() {
        launchMainActivity().use {
            waitForUi()

            onView(withId(R.id.titleText)).check(matches(withText(R.string.screen_title)))
            onView(allOf(withId(R.id.ruleTitleText), withText(R.string.rule_label_fallback)))
                .check(matches(withText(R.string.rule_label_fallback)))
            onView(withId(R.id.statusText)).check(matches(withText(containsString("現在:"))))
        }
    }

    @Test
    fun tabSwitch_showsGlobalSettingsAndReturnsToVolumeSettings() {
        launchMainActivity().use {
            waitForUi()

            onView(
                allOf(
                    withText(R.string.tab_global_settings),
                    isDescendantOfA(withId(R.id.settingsTabLayout)),
                ),
            ).perform(click())
            waitForUi()

            onView(withId(R.id.globalSettingsContent))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            onView(withId(R.id.reapplySwitch)).check(matches(isDisplayed()))
            onView(withId(R.id.permissionsButton)).check(matches(isDisplayed()))
            onView(withId(R.id.volumeSettingsContent))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))

            onView(
                allOf(
                    withText(R.string.tab_volume_settings),
                    isDescendantOfA(withId(R.id.settingsTabLayout)),
                ),
            ).perform(click())
            waitForUi()

            onView(withId(R.id.volumeSettingsContent))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            onView(withId(R.id.globalSettingsContent))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    @Test
    fun addRenameDeleteAndPersistRule() {
        val ruleName = "実機テスト設定"
        val renamedRuleName = "実機テスト設定-更新"

        launchMainActivity().use {
            waitForUi()

            onView(withId(R.id.addConditionButton)).perform(click())
            onView(isAssignableFrom(EditText::class.java))
                .inRoot(isDialog())
                .perform(replaceText(ruleName), closeSoftKeyboard())
            onView(withText(R.string.dialog_ok)).inRoot(isDialog()).perform(click())
            waitForUi()

            onView(withText(ruleName)).check(matches(isDisplayed()))

            onView(
                nthMatch(
                    allOf(withId(R.id.renameRuleButton), withText(R.string.action_rename_setting)),
                    0,
                ),
            ).perform(click())
            onView(isAssignableFrom(EditText::class.java))
                .inRoot(isDialog())
                .perform(replaceText(renamedRuleName), closeSoftKeyboard())
            onView(withText(R.string.dialog_ok)).inRoot(isDialog()).perform(click())
            waitForUi()

            onView(withText(renamedRuleName)).check(matches(isDisplayed()))

            onView(
                allOf(
                    withText(R.string.tab_global_settings),
                    isDescendantOfA(withId(R.id.settingsTabLayout)),
                ),
            ).perform(click())
            onView(withId(R.id.reapplySwitch)).perform(scrollTo(), click())
            onView(withId(R.id.ruleChangeNotificationSwitch)).perform(scrollTo(), click())
            onView(withId(R.id.saveButton)).perform(click())
            waitForUi()
        }

        launchMainActivity().use {
            waitForUi()
            onView(withText(renamedRuleName)).check(matches(isDisplayed()))
        }

        val restoredSettings = runBlocking {
            SettingsRepository(targetContext).getSettings()
        }
        assertTrue(restoredSettings != null)
        assertEquals(ReapplyMode.ALWAYS, restoredSettings?.reapplyMode)
        assertTrue(restoredSettings?.notifyOnRuleChange == true)
        assertTrue(restoredSettings?.rules?.any { it.name == renamedRuleName } == true)

        launchMainActivity().use {
            waitForUi()

            onView(withId(R.id.deleteSettingButton)).perform(click())
            waitForUi()

            onView(
                nthMatch(
                    allOf(withId(R.id.renameRuleButton), withText(R.string.action_delete)),
                    0,
                ),
            ).perform(click())
            onView(withText(R.string.dialog_ok)).inRoot(isDialog()).perform(click())
            waitForUi()

            onView(withText(renamedRuleName)).check(doesNotExist())
            onView(withText(R.string.rule_label_fallback)).check(matches(isDisplayed()))
        }
    }

    private fun launchMainActivity(): ActivityScenario<MainActivity> {
        val intent = Intent(targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_SKIP_PERMISSION_REQUESTS, true)
            putExtra(MainActivity.EXTRA_SKIP_MONITORING_SERVICE_START, true)
        }
        return ActivityScenario.launch(intent)
    }

    private fun assertCleanState() {
        val repository = SettingsRepository(targetContext)
        val settings = runBlocking { repository.getSettings() }
        val activeState = runBlocking { repository.getActiveRuleState() }
        check(settings == null) {
            "Expected clean debug app state before test, but found settings for ${targetContext.packageName}"
        }
        check(activeState.ruleId == null && activeState.label == null) {
            "Expected empty active rule state before test, but found $activeState for ${targetContext.packageName}"
        }
    }

    private fun waitForUi() {
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
    }

    private fun stopMonitoringService() {
        targetContext.stopService(Intent(targetContext, ConnectionMonitorService::class.java))
        instrumentation.waitForIdleSync()
    }

    private fun nthMatch(matcher: Matcher<View>, index: Int): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            private var currentIndex = 0

            override fun describeTo(description: Description) {
                description.appendText("match #$index for: ")
                matcher.describeTo(description)
            }

            override fun matchesSafely(item: View): Boolean {
                if (!matcher.matches(item)) {
                    return false
                }
                return currentIndex++ == index
            }
        }
    }
}
