package tr.borsatakip.v5.ui

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.TechnicalSnapshot

@RunWith(AndroidJUnit4::class)
class UiUxV3AcceptanceInstrumentationTest {
    private fun prepareOfflineSafeSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsStore(context).apply {
            baseUrl = ""
            apiKey = ""
            experimentalProvidersEnabled = false
            yahooFallbackEnabled = false
        }
        context.getSharedPreferences("viop_dashboard_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun opportunity(index: Int): Opportunity {
        val long = index % 2 == 0
        val price = 10.0 + index
        return Opportunity(
            symbol = "T%03d".format(index),
            companyName = "Test $index",
            price = price,
            dailyChangePct = if (long) 1.0 else -1.0,
            score = 70,
            riskScore = 20,
            direction = if (long) "LONG" else "SHORT",
            technicalLabel = "test",
            volumeLabel = "test",
            kapLabel = "—",
            liquidityLabel = "test",
            support = null,
            resistance = null,
            source = "instrumentation",
            dataTimestamp = 1L,
            candles = listOf(Candle(1L, price, price, price, price, 1_000.0 + index)),
            technical = TechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null),
            decisionState = DecisionState.VERIFIED_OPPORTUNITY,
            longScore = if (long) 80 else 0,
            shortScore = if (long) 0 else 80
        )
    }

    private fun assertInsideScreen(view: View) {
        val metrics = view.resources.displayMetrics
        val widthPx = metrics.widthPixels
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        assertTrue("${view.id} starts before screen", location[0] >= 0)
        assertTrue("${view.id} exceeds screen width", location[0] + view.width <= widthPx)
    }

    private fun assertTouchTargetAtLeast48Dp(view: View) {
        val minPx = (48f * view.resources.displayMetrics.density).toInt()
        assertTrue("${view.id} touch target width ${view.width}px < 48dp", view.width >= minPx)
        assertTrue("${view.id} touch target height ${view.height}px < 48dp", view.height >= minPx)
    }

    @Test
    fun bist_and_opportunity_key_controls_fit_current_360_to_430dp_profile() {
        prepareOfflineSafeSettings()
        ActivityScenario.launch(BistScanActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val widthDp = activity.resources.configuration.screenWidthDp
                assertTrue("expected CI profile 360..430dp, was $widthDp", widthDp in 360..430)
                listOf(
                    activity.findViewById<View>(R.id.btnStartScan),
                    activity.findViewById<View>(R.id.btnStopScan),
                    activity.findViewById<View>(R.id.scanMarketVolumeValue),
                    activity.findViewById<View>(R.id.bottomNav)
                ).forEach(::assertInsideScreen)
            }
        }
        ActivityScenario.launch(OpportunityActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val widthDp = activity.resources.configuration.screenWidthDp
                assertTrue("expected CI profile 360..430dp, was $widthDp", widthDp in 360..430)
                assertInsideScreen(activity.findViewById(R.id.btnSortOpportunity))
                assertInsideScreen(activity.findViewById(R.id.bottomNav))
            }
        }
    }

    @Test
    fun opportunity_recyclerview_accepts_630_canonical_rows_and_scrolls_without_stall() {
        prepareOfflineSafeSettings()
        AppSession.lastOpportunities = List(630, ::opportunity)
        val started = SystemClock.elapsedRealtime()
        ActivityScenario.launch(OpportunityActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.list)
                val adapter = list.adapter
                assertTrue("630 canonical rows must be represented; section headers may add rows", (adapter?.itemCount ?: 0) >= 630)
                list.scrollToPosition((adapter?.itemCount ?: 1) - 1)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        assertTrue("630-row render/scroll acceptance exceeded 5000 ms: $elapsed", elapsed < 5_000L)
    }


    @Test
    fun viop_filter_and_sort_state_survives_activity_recreate() {
        prepareOfflineSafeSettings()
        ActivityScenario.launch(ViopActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.EditText>(R.id.viopSearch).setText("xu030")
                activity.findViewById<View>(R.id.filterLong).performClick()
                activity.findViewById<View>(R.id.sortMode).performClick()
                activity.findViewById<View>(R.id.detailedFilter).performClick()
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("xu030", activity.findViewById<android.widget.EditText>(R.id.viopSearch).text.toString())
                assertEquals("Sırala: Sinyal", activity.findViewById<android.widget.Button>(R.id.sortMode).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.advancedFilterRow).visibility)
            }
        }
    }

    @Test
    fun viop_filter_and_sort_state_survives_full_activity_reopen() {
        prepareOfflineSafeSettings()
        ActivityScenario.launch(ViopActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.EditText>(R.id.viopSearch).setText("xu100")
                activity.findViewById<View>(R.id.filterShort).performClick()
                activity.findViewById<View>(R.id.sortMode).performClick()
                activity.findViewById<View>(R.id.detailedFilter).performClick()
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        ActivityScenario.launch(ViopActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("xu100", activity.findViewById<android.widget.EditText>(R.id.viopSearch).text.toString())
                assertEquals("Sırala: Sinyal", activity.findViewById<android.widget.Button>(R.id.sortMode).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.advancedFilterRow).visibility)
            }
        }
    }

    @Test
    fun critical_controls_fit_and_meet_touch_target_at_current_font_scale() {
        prepareOfflineSafeSettings()
        val largeFont = InstrumentationRegistry.getArguments().getString("largeFont") == "true"
        ActivityScenario.launch(OpportunityActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                if (largeFont) assertTrue("font scale must be >= 1.8 for large-font acceptance", activity.resources.configuration.fontScale >= 1.8f)
                activity.findViewById<View>(R.id.btnSortOpportunity).also {
                    assertInsideScreen(it)
                    assertTouchTargetAtLeast48Dp(it)
                }
            }
        }
        ActivityScenario.launch(ViopActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listOf(R.id.refresh, R.id.filterAll, R.id.filterLong, R.id.filterShort, R.id.filterWatch, R.id.detailedFilter, R.id.sortMode).forEach { id ->
                    activity.findViewById<View>(id).also { view ->
                        assertTouchTargetAtLeast48Dp(view)
                    }
                }
            }
        }
    }

    @Test
    fun canonical_direction_policy_is_identical_for_shared_opportunity_model() {
        val row = opportunity(0)
        assertEquals("LONG", OpportunityUiPolicy.directionLabel(row))
        AppSession.lastOpportunities = listOf(row)
        assertEquals("LONG", OpportunityUiPolicy.directionLabel(AppSession.lastOpportunities.single()))
    }
}
