package tr.borsatakip.v5.ui

import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import tr.borsatakip.v5.R

open class BaseActivity : AppCompatActivity() {
    protected fun setupBottomNav() {
        applySystemInsets()
        val bottomNav = findViewById<View>(R.id.bottomNav)
        bottomNav?.let { nav ->
            val baseHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 72f, resources.displayMetrics).toInt()
            val startLeft = nav.paddingLeft; val startTop = nav.paddingTop; val startRight = nav.paddingRight
            ViewCompat.setOnApplyWindowInsetsListener(nav) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val lp = view.layoutParams
                val targetHeight = baseHeight + bars.bottom
                if (lp.height != targetHeight) { lp.height = targetHeight; view.layoutParams = lp }
                view.setPadding(startLeft, startTop, startRight, bars.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(nav)
        }
        bindNav(R.id.navHome, MainActivity::class.java)
        bindNav(R.id.navBist, StocksActivity::class.java)
        bindNav(R.id.navViop, ViopActivity::class.java)
        bindNav(R.id.navFav, FavoritesActivity::class.java)
        bindNav(R.id.navSettings, SettingsActivity::class.java)
    }

    private fun applySystemInsets() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: return
        val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(l + bars.left, t + bars.top, r + bars.right, b)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindNav(id: Int, target: Class<out AppCompatActivity>) {
        findViewById<TextView>(id)?.setOnClickListener {
            if (this::class.java == target) return@setOnClickListener
            startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
