package tr.borsatakip.v5.ui

import android.content.Context
import android.content.Intent

enum class AppRoute {
    HOME, OPPORTUNITY_CONTROL, STOCKS, STOCK_DETAIL, TECHNICAL_ANALYSIS, NEWS,
    SIGNAL_HISTORY_FORWARD, VIOP, VIOP_CONTRACTS, VIOP_DETAIL, FAVORITES,
    SETTINGS, NOTIFICATIONS, SCAN_RESULTS
}

object NavigationRoutes {
    fun intent(context: Context, route: AppRoute): Intent = Intent(context, when (route) {
        AppRoute.HOME -> MainActivity::class.java
        AppRoute.OPPORTUNITY_CONTROL -> OpportunityActivity::class.java
        AppRoute.STOCKS -> StocksActivity::class.java
        AppRoute.STOCK_DETAIL -> StockDetailActivity::class.java
        AppRoute.TECHNICAL_ANALYSIS -> TechnicalAnalysisActivity::class.java
        AppRoute.NEWS -> NewsActivity::class.java
        AppRoute.SIGNAL_HISTORY_FORWARD -> SignalHistoryActivity::class.java
        AppRoute.VIOP -> ViopActivity::class.java
        AppRoute.VIOP_CONTRACTS -> ViopContractsActivity::class.java
        AppRoute.VIOP_DETAIL -> ViopDetailActivity::class.java
        AppRoute.FAVORITES -> FavoritesActivity::class.java
        AppRoute.SETTINGS -> SettingsActivity::class.java
        AppRoute.NOTIFICATIONS -> NotificationsActivity::class.java
        AppRoute.SCAN_RESULTS -> ScanResultsActivity::class.java
    })
}
