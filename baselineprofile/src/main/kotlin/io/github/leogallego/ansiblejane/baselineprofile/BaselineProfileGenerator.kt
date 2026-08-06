package io.github.leogallego.ansiblejane.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates Baseline + Startup profiles for cold-start optimization (#214).
 *
 * Critical journeys when an AAP instance is already configured on the device:
 * launch → Dashboard, Settings, Templates, Chat (Jane AI).
 *
 * Without credentials the generator still covers cold start through Auth, which
 * is enough for Startup Profile / DEX layout rules. Re-run on a logged-in
 * device (or CI with seeded DataStore) for full main-tab coverage.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // Splash → Main (nav_dashboard) or Auth (field_url)
        val reachedMain = device.wait(Until.hasObject(res(TAG_NAV_DASHBOARD)), UI_TIMEOUT_MS)
        if (reachedMain) {
            navigateMainJourneys()
        } else {
            exerciseAuthScreen()
        }
    }

    private fun MacrobenchmarkScope.navigateMainJourneys() {
        // Dashboard (default tab) — already visible when we enter this path
        waitAndFind(TAG_NAV_DASHBOARD)
        device.waitForIdle()

        // Templates
        click(TAG_NAV_TEMPLATES)
        device.waitForIdle()

        // Chat (Jane AI)
        click(TAG_NAV_ASSISTANT)
        device.waitForIdle()

        // Settings (top-bar action, not a bottom nav tab)
        click(TAG_BUTTON_SETTINGS)
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()

        // Return to Dashboard
        click(TAG_NAV_DASHBOARD)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.exerciseAuthScreen() {
        click(TAG_FIELD_URL)
        click(TAG_FIELD_TOKEN)
        device.waitForIdle()
    }

    /** Match Compose testTag resource-ids set via testTagsAsResourceId (bare tag, no package). */
    private fun res(tag: String): BySelector = By.res(tag)

    private fun MacrobenchmarkScope.waitAndFind(tag: String): UiObject2 {
        val selector = res(tag)
        check(device.wait(Until.hasObject(selector), UI_TIMEOUT_MS)) {
            "Timed out waiting for testTag/resource-id '$tag' (${UI_TIMEOUT_MS}ms)"
        }
        return checkNotNull(device.findObject(selector)) {
            "testTag/resource-id '$tag' vanished after wait"
        }
    }

    private fun MacrobenchmarkScope.click(tag: String) {
        waitAndFind(tag).click()
    }

    companion object {
        private const val PACKAGE_NAME = "io.github.leogallego.ansiblejane"
        private const val UI_TIMEOUT_MS = 10_000L

        // Compose testTags exposed as resource-id via testTagsAsResourceId in MainActivity
        private const val TAG_NAV_DASHBOARD = "nav_dashboard"
        private const val TAG_NAV_TEMPLATES = "nav_templates"
        private const val TAG_NAV_ASSISTANT = "nav_assistant"
        private const val TAG_BUTTON_SETTINGS = "button_settings"
        private const val TAG_FIELD_URL = "field_url"
        private const val TAG_FIELD_TOKEN = "field_token"
    }
}
