package com.thinkspace

import android.graphics.Color
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.ThinkspaceViewManagerInterface
import com.facebook.react.viewmanagers.ThinkspaceViewManagerDelegate

@ReactModule(name = ThinkspaceViewManager.NAME)
class ThinkspaceViewManager : SimpleViewManager<ThinkspaceView>(),
  ThinkspaceViewManagerInterface<ThinkspaceView> {
  private val mDelegate: ViewManagerDelegate<ThinkspaceView>

  init {
    mDelegate = ThinkspaceViewManagerDelegate(this)
  }

  override fun getDelegate(): ViewManagerDelegate<ThinkspaceView>? {
    return mDelegate
  }

  override fun getName(): String {
    return NAME
  }

  public override fun createViewInstance(context: ThemedReactContext): ThinkspaceView {
    return ThinkspaceView(context)
  }

  @ReactProp(name = "color")
  override fun setColor(view: ThinkspaceView?, color: Int?) {
    view?.setBackgroundColor(color ?: Color.TRANSPARENT)
  }

  companion object {
    const val NAME = "ThinkspaceView"
  }
}
