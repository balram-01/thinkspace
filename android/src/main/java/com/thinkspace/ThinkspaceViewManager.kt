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

  @ReactProp(name = "documentJson")
  override fun setDocumentJson(view: ThinkspaceView?, value: String?) {
    view?.setDocumentFromJson(value)
  }

  @ReactProp(name = "annotationsJson")
  override fun setAnnotationsJson(view: ThinkspaceView?, value: String?) {
    view?.setAnnotationsFromJson(value)
  }

  @ReactProp(name = "isSqueezed")
  override fun setIsSqueezed(view: ThinkspaceView?, value: Boolean) {
    view?.isSqueezed = value
    view?.invalidate()
  }

  @ReactProp(name = "splitRatio")
  override fun setSplitRatio(view: ThinkspaceView?, value: Float) {
    if (view == null || value <= 0f) return
    view.splitRatio = value
    view.invalidate()
  }

  @ReactProp(name = "activeTool")
  override fun setActiveTool(view: ThinkspaceView?, value: String?) {
    view?.activeTool = value ?: "select"
  }

  @ReactProp(name = "selectedColor")
  override fun setSelectedColor(view: ThinkspaceView?, value: String?) {
    if (view == null || value.isNullOrEmpty()) return
    try {
      view.selectedColor = Color.parseColor(value)
    } catch (e: Exception) {
      // Ignored
    }
  }

  @ReactProp(name = "pattern")
  override fun setPattern(view: ThinkspaceView?, value: String?) {
    view?.pattern = value ?: "looseleaf"
    view?.invalidate()
  }

  @ReactProp(name = "strokesJson")
  override fun setStrokesJson(view: ThinkspaceView?, value: String?) {
    view?.setStrokesFromJson(value)
  }

  @ReactProp(name = "excerptsJson")
  override fun setExcerptsJson(view: ThinkspaceView?, value: String?) {
    view?.setCardsFromJson(value)
  }

  @ReactProp(name = "inkLinksJson")
  override fun setInkLinksJson(view: ThinkspaceView?, value: String?) {
    view?.setLinksFromJson(value)
  }

  @ReactProp(name = "panX")
  override fun setPanX(view: ThinkspaceView?, value: Float) {
    if (view == null) return
    view.panX = value
    view.invalidate()
  }

  @ReactProp(name = "panY")
  override fun setPanY(view: ThinkspaceView?, value: Float) {
    if (view == null) return
    view.panY = value
    view.invalidate()
  }

  @ReactProp(name = "scale")
  override fun setScale(view: ThinkspaceView?, value: Float) {
    if (view == null || value <= 0f) return
    view.scaleFactor = value
    view.invalidate()
  }

  override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> {
    val map = super.getExportedCustomDirectEventTypeConstants() ?: mutableMapOf()
    val events = listOf(
      "topAddStroke" to "onAddStroke",
      "topEraseStroke" to "onEraseStroke",
      "topExcerptMoveEnd" to "onExcerptMoveEnd",
      "topExcerptPress" to "onExcerptPress",
      "topCardDelete" to "onCardDelete",
      "topChangeCardColor" to "onChangeCardColor",
      "topUpdateCardComment" to "onUpdateCardComment",
      "topHoldCard" to "onHoldCard",
      "topTransformChange" to "onTransformChange",
      "topSplitRatioChange" to "onSplitRatioChange",
      "topExtractExcerpt" to "onExtractExcerpt",
      "topToggleSqueeze" to "onToggleSqueeze",
      "topUndoStateChange" to "onUndoStateChange"
    )
    for ((top, on) in events) {
      map[top] = mapOf("registrationName" to on)
    }
    return map
  }

  override fun receiveCommand(root: ThinkspaceView, commandId: String, args: com.facebook.react.bridge.ReadableArray?) {
    when (commandId) {
      "openSearch", "1" -> root.promptSearchDialog()
      "closeSearch", "2" -> root.closeSearch()
      "nextMatch", "3" -> root.goToNextMatch()
      "prevMatch", "4" -> root.goToPreviousMatch()
      "undo", "5" -> root.undo()
      "redo", "6" -> root.redo()
    }
  }

  override fun receiveCommand(root: ThinkspaceView, commandId: Int, args: com.facebook.react.bridge.ReadableArray?) {
    when (commandId) {
      1 -> root.promptSearchDialog()
      2 -> root.closeSearch()
      3 -> root.goToNextMatch()
      4 -> root.goToPreviousMatch()
      5 -> root.undo()
      6 -> root.redo()
    }
  }

  companion object {
    const val NAME = "ThinkspaceView"
  }
}
