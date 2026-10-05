package thinkspace.example

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "ThinkspaceExample"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Ensure bottom navigation bar insets are respected so buttons are never hidden
    // behind the Android system navigation bar (3-button or gesture bar).
    val contentView = findViewById<View>(android.R.id.content)
    if (contentView != null) {
      ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
        val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        view.setPadding(0, 0, 0, navInsets.bottom)
        insets
      }
    }
  }
}

