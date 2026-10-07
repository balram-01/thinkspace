package com.thinkspace

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.widget.EditText
import android.widget.FrameLayout
import android.content.ClipboardManager
import android.app.Dialog
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.ViewGroup
import java.util.UUID
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.facebook.react.uimanager.events.EventDispatcher
import com.thinkspace.pdfengine.api.PdfDocument
import com.thinkspace.pdfengine.api.RenderOptions
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.PageSize
import com.thinkspace.pdfengine.model.TextElement
import com.thinkspace.pdfengine.model.TextWord
import com.thinkspace.engine.*
import com.thinkspace.engine.models.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Data models moved to engine/models/ThinkspaceModels.kt

class ThinkspaceView : View {
  constructor(context: Context?) : super(context)
  constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
  constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
    context,
    attrs,
    defStyleAttr
  )

  // Undo/Redo Engine
  val undoRedoManager = UndoRedoManager()

  init {
    isFocusable = true
    isFocusableInTouchMode = true

    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
      val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
      val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
      val newImeHeight = (ime.bottom - nav.bottom).coerceAtLeast(0)
      if (imeHeight != newImeHeight) {
        imeHeight = newImeHeight
        if (editingCardId != null && imeHeight > 0) {
          val selCard = cards.find { it.id == editingCardId }
          if (selCard != null) {
            ensureCardVisibleAboveKeyboard(selCard)
          }
        }
        postInvalidateOnAnimation()
      }
      insets
    }

    undoRedoManager.onStateChanged = { canUndo, canRedo ->
      dispatchUndoStateChangeEvent(canUndo, canRedo)
    }

    post {
      loadPersistedNotebookPages()
      loadPersistedPenSettings()
      loadPersistedSemanticInkLinks()
    }
  }

  val effectiveSplitRatio: Float
    get() = if (editingCardId != null) 0f else splitRatio

  private var imeHeight: Int = 0

  internal fun isKeyboardActive(): Boolean {
    return editingCardId != null || getKeyboardHeight() > 50f * density
  }

  internal fun getKeyboardHeight(): Float {
    if (imeHeight > 0) return imeHeight.toFloat()
    val r = Rect()
    getWindowVisibleDisplayFrame(r)
    val screenH = rootView.height
    val diff = screenH - r.bottom
    if (diff > screenH * 0.15f) {
      return diff.toFloat()
    }
    val viewDiff = height.toFloat() - r.height().toFloat()
    if (viewDiff > 80f * density) {
      return viewDiff
    }
    if (editingCardId != null) {
      return 290f * density
    }
    return 0f
  }

  private fun ensureCardVisibleAboveKeyboard(card: NativeCard) {
    val kbH = getKeyboardHeight()
    val barH = 48f * density
    val visibleBottom = height.toFloat() - kbH - barH - 24f * density
    val (scLeft, scTop) = canvasWorldToScreen(card.x, card.y, 0f)
    val cardH = card.getHeight() * scaleFactor
    val scBottom = scTop + cardH

    if (scBottom > visibleBottom || scTop < 24f * density) {
      val targetTop = ((visibleBottom - cardH) / 2f).coerceIn(24f * density, 80f * density)
      panY = targetTop - card.y * scaleFactor
    }
    val scRight = scLeft + card.width * scaleFactor
    if (scLeft < 16f * density || scRight > width - 16f * density) {
      val targetLeft = ((width - card.width * scaleFactor) / 2f).coerceAtLeast(16f * density)
      panX = targetLeft - card.x * scaleFactor
    }
  }

  override fun onCheckIsTextEditor(): Boolean = editingCardId != null

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
    if (editingCardId == null) return null
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE
    return object : BaseInputConnection(this, true) {
      override fun performEditorAction(actionCode: Int): Boolean {
        if (actionCode == EditorInfo.IME_ACTION_DONE) {
          stopEditingCard()
          return true
        }
        return super.performEditorAction(actionCode)
      }

      override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null && editingCardId != null) {
          val card = cards.find { it.id == editingCardId }
          if (card != null) {
            card.undoTextStack.addLast(card.text)
            if (card.undoTextStack.size > 20) card.undoTextStack.removeFirst()
            val cur = card.text
            val cPos = cursorPosition.coerceIn(0, cur.length)
            val updated = cur.substring(0, cPos) + text + cur.substring(cPos)
            card.text = updated
            cursorPosition = cPos + text.length
            invalidate()
            return true
          }
        }
        return super.commitText(text, newCursorPosition)
      }

      override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (editingCardId != null) {
          val card = cards.find { it.id == editingCardId }
          if (card != null && card.text.isNotEmpty()) {
            val cur = card.text
            val cPos = cursorPosition.coerceIn(0, cur.length)
            val delStart = (cPos - beforeLength).coerceAtLeast(0)
            val delEnd = (cPos + afterLength).coerceAtMost(cur.length)
            if (delStart < delEnd) {
              card.undoTextStack.addLast(card.text)
              if (card.undoTextStack.size > 20) card.undoTextStack.removeFirst()
              card.text = cur.substring(0, delStart) + cur.substring(delEnd)
              cursorPosition = delStart
              invalidate()
              return true
            }
          }
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
      }
    }
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (event != null && (event.isCtrlPressed || event.isMetaPressed) && keyCode == KeyEvent.KEYCODE_Z) {
      if (event.isShiftPressed) redo() else undo()
      return true
    }
    if (editingCardId != null) {
      if (keyCode == KeyEvent.KEYCODE_BACK) {
        stopEditingCard()
        return true
      }
      val card = cards.find { it.id == editingCardId }
      if (card != null) {
        when (keyCode) {
          KeyEvent.KEYCODE_DEL -> {
            if (cursorPosition > 0 && card.text.isNotEmpty()) {
              card.undoTextStack.addLast(card.text)
              if (card.undoTextStack.size > 20) card.undoTextStack.removeFirst()
              val cur = card.text
              val cPos = cursorPosition.coerceIn(0, cur.length)
              card.text = cur.substring(0, cPos - 1) + cur.substring(cPos)
              cursorPosition = cPos - 1
              invalidate()
              return true
            }
          }
          KeyEvent.KEYCODE_ENTER -> {
            card.undoTextStack.addLast(card.text)
            if (card.undoTextStack.size > 20) card.undoTextStack.removeFirst()
            val cur = card.text
            val cPos = cursorPosition.coerceIn(0, cur.length)
            card.text = cur.substring(0, cPos) + "\n" + cur.substring(cPos)
            cursorPosition = cPos + 1
            invalidate()
            return true
          }
        }
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  private fun startEditingCard(card: NativeCard) {
    editCardInitialText = card.text
    editCardInitialFontSize = card.fontSize
    editCardInitialBold = card.isBold
    editCardInitialItalic = card.isItalic
    editCardInitialUnderline = card.isUnderline
    editCardInitialStrike = card.isStrikethrough
    editCardInitialTextColor = card.textColor
    editCardInitialStyleName = card.textStyleName
    editingCardId = card.id
    selectedCardId = card.id
    cursorPosition = card.text.length
    isCursorBlinkVisible = true
    lastCursorBlinkTime = System.currentTimeMillis()
    isFocusableInTouchMode = true
    requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    isTypographyBarVisible = false
    ensureCardVisibleAboveKeyboard(card)
    invalidate()
  }

  private fun stopEditingCard() {
    if (editingCardId != null) {
      val card = cards.find { it.id == editingCardId }
      if (card != null && (card.text != editCardInitialText || card.fontSize != editCardInitialFontSize ||
          card.isBold != editCardInitialBold || card.isItalic != editCardInitialItalic ||
          card.isUnderline != editCardInitialUnderline || card.isStrikethrough != editCardInitialStrike ||
          card.textColor != editCardInitialTextColor || card.textStyleName != editCardInitialStyleName)) {
        undoRedoManager.record(EditCardTextAction(
          cardId = card.id,
          prevText = editCardInitialText,
          newText = card.text,
          prevFontSize = editCardInitialFontSize,
          newFontSize = card.fontSize,
          prevBold = editCardInitialBold,
          newBold = card.isBold,
          prevItalic = editCardInitialItalic,
          newItalic = card.isItalic,
          prevUnderline = editCardInitialUnderline,
          newUnderline = card.isUnderline,
          prevStrike = editCardInitialStrike,
          newStrike = card.isStrikethrough,
          prevTextColor = editCardInitialTextColor,
          newTextColor = card.textColor,
          prevStyleName = editCardInitialStyleName,
          newStyleName = card.textStyleName,
          cardsList = cards,
          onTextChanged = { _, _ -> invalidate() }
        ))
      }
      editingCardId = null
      isTypographyBarVisible = false
      isStyleSheetOpen = false
      isCardColorPaletteOpen = false
      isTypoTextColorPaletteOpen = false
      val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      imm?.hideSoftInputFromWindow(windowToken, 0)
      invalidate()
    }
  }

  // LiquidText Workspace State Props
  var splitRatio: Float = 0.44f
    set(value) {
      val clamped = value.coerceIn(0.18f, 0.82f)
      if (abs(field - clamped) < 0.001f) return
      field = clamped
      invalidate()
    }
  var isSqueezed: Boolean = false
  var activeTool: String = "select" // "select", "pan", "pen", "highlighter", "eraser"
    set(value) {
      val prev = field
      field = value
      if (value != "select" && (activePdfSelection != null || isSelectingPdfText)) {
        activePdfSelection = null
        isSelectingPdfText = false
        invalidate()
      }
    }
  var selectedColor: Int = Color.parseColor("#00ADB5")
  var pattern: String = "looseleaf"
  var showCanvasToolbar: Boolean = false
  val camera = CameraState()
  var panX: Float
    get() = camera.panX
    set(value) { camera.panX = value }
  var panY: Float
    get() = camera.panY
    set(value) { camera.panY = value }
  var scaleFactor: Float
    get() = camera.scaleFactor
    set(value) { camera.scaleFactor = value }

  // Real PDF Engine State
  internal var activePdfDoc: PdfDocument? = null
  internal val pageLayouts = mutableListOf<PdfPageLayout>()
  // Cache up to 32 rendered pages in memory
  internal val pageBitmaps = LruCache<Int, Bitmap>(32)
  private val renderingPages = ConcurrentHashMap.newKeySet<Int>()
  internal val pageWordsCache = ConcurrentHashMap<Int, List<TextWord>>()
  private val extractingWords = ConcurrentHashMap.newKeySet<Int>()
  internal val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  // ── Multi-document workspace registry ───────────────────────────────────────
  /**
   * Registry of all PDF documents currently opened in this workspace.
   * Keys are documentId strings matching WorkspaceDocumentEntry.id.
   * Values are opened PdfDocument handles (shared with PdfEngineModule).
   * Documents are opened lazily when first needed.
   */
  internal val documentRegistry = ConcurrentHashMap<String, PdfDocument>()

  /**
   * Ordered list of workspace document entries (metadata only).
   * Kept in insertion order. The PDF renderer uses documentRegistry for actual docs.
   */
  internal val workspaceDocumentEntries = mutableListOf<WorkspaceDocumentEntry>()

  /**
   * ID of the document currently visible in the PDF/document viewport.
   * Changing this triggers a viewport switch without touching the workspace canvas.
   */
  internal var activeDocumentId: String = ""

  /** Pending page number and source rects to scroll to when switching documents */
  internal var pendingScrollToPage: Int? = null
  internal var pendingPulseRects: List<RectF>? = null

  /** Color palette for auto-assigning document accent colors. */
  internal val docColorPalette = listOf(
    android.graphics.Color.parseColor("#6C5CE7"),
    android.graphics.Color.parseColor("#00ADB5"),
    android.graphics.Color.parseColor("#F59E0B"),
    android.graphics.Color.parseColor("#EF4444"),
    android.graphics.Color.parseColor("#10B981"),
    android.graphics.Color.parseColor("#8B5CF6"),
    android.graphics.Color.parseColor("#F97316"),
    android.graphics.Color.parseColor("#3B82F6")
  )

  /** Returns the parsed accent Color Int for a document ID, or a default. */
  fun getDocumentAccentColor(docId: String): Int {
    val entry = workspaceDocumentEntries.find { it.id == docId }
    val hex = entry?.colorAccent ?: "#00ADB5"
    return try { android.graphics.Color.parseColor(hex) } catch (e: Exception) { docColorPalette[0] }
  }

  // ── Multi-document & Hierarchical Folder Management ──────────────────────────
  // WorkspaceFolder and WorkspaceDocumentEntry moved to engine/models/ThinkspaceModels.kt

  val workspaceFolders = mutableListOf<WorkspaceFolder>()
  internal val expandedFolderIds = mutableSetOf<String>()
  internal var documentsSheetDialog: Dialog? = null

  // Document & Annotation Models (Fallback/Demo Doc)
  internal var activeDocument: NativeDoc? = null
  internal val annotations = mutableListOf<NativeAnnotation>()
  internal var docScrollY: Float = 0f
  internal var maxDocScrollY: Float = 1000f
  internal var docScrollX: Float = 0f
  internal var maxDocScrollX: Float = 0f
  internal var pdfScaleFactor: Float = 1.0f

  // Document interaction mode: "text" or "crop"
  internal var docMode: String = "text"

  // Display density & subheader metrics
  var showDocumentHeader: Boolean = false
    set(value) {
      field = value
      invalidate()
    }
  internal val density: Float get() = context.resources.displayMetrics.density
  internal val subheaderH: Float get() = if (showDocumentHeader) 48f * density else 0f

  // LiquidText Real PDF Document Compression Engine
  val compressionEngine by lazy { DocumentCompressionEngine(density) }

  // Dedicated LiquidText Long-Press Lift Engine
  private val longPressHandler = Handler(Looper.getMainLooper())
  private var pendingLongPressRunnable: Runnable? = null
  private var longPressStartX = 0f
  private var longPressStartY = 0f
  private var docPinchInitialSpan = 0f
  private var docPinchInitialDistY = 0f
  private var isDocTwoFingerPinching = false

  // Drag & Note editing tracking
  private var dragCardStartX = 0f
  private var dragCardStartY = 0f
  private var editCardInitialText: String = ""
  private var editCardInitialFontSize: Float = 13f
  private var editCardInitialBold: Boolean = false
  private var editCardInitialItalic: Boolean = false
  private var editCardInitialUnderline: Boolean = false
  private var editCardInitialStrike: Boolean = false
  private var editCardInitialTextColor: Int = Color.parseColor("#1E293B")
  private var editCardInitialStyleName: String = "Default"

  // Canvas Collections
  internal val strokes = mutableListOf<NativeStroke>()
  internal val cards = mutableListOf<NativeCard>()
  internal val links = mutableListOf<NativeLink>()

  // ── Notebook Pages ──────────────────────────────────────────────────────────
  /** All notebook page objects currently on the canvas (world-space). */
  internal val notebookPages = mutableListOf<NativeNotebookPage>()
  /** ID of the currently-selected notebook page (null = none selected). */
  internal var selectedNotebookPageId: String? = null
  /** The page currently being dragged (finger down on page body). */
  internal var draggingNotebookPage: NativeNotebookPage? = null
  private var nbPageDragOffsetWorldX: Float = 0f
  private var nbPageDragOffsetWorldY: Float = 0f
  private var nbPageDragStartX: Float = 0f
  private var nbPageDragStartY: Float = 0f
  /** The page currently being resized (finger on bottom-right handle). */
  internal var resizingNotebookPage: NativeNotebookPage? = null
  private var nbPageResizeStartWidth: Float = 0f
  private var nbPageResizeStartHeight: Float = 0f
  private var nbPageResizeStartWorldX: Float = 0f
  private var nbPageResizeStartWorldY: Float = 0f
  /** World-space hit rects for per-page controls (keyed by page.id). */
  internal val nbPageDeleteRects = HashMap<String, RectF>()
  internal val nbPageStyleBtnRects = HashMap<String, RectF>()
  internal val nbPageDuplicateRects = HashMap<String, RectF>()
  internal val nbPageResizeRects = HashMap<String, RectF>()
  private var nbPageAttachedCards: List<NativeCard> = emptyList()
  private var nbPageAttachedStrokes: List<NativeStroke> = emptyList()
  private var nbPageAttachedCardStarts: List<Pair<NativeCard, Pair<Float, Float>>> = emptyList()
  private var nbPageAttachedStrokeStarts: List<Pair<NativeStroke, List<Pair<Float, Float>>>> = emptyList()
  /** Style picker overlay state. */
  internal var isNotebookStylePickerOpen: Boolean = false
  internal var stylePickerForPageId: String? = null
  internal val nbPageStylePickerRect = RectF()
  private data class NbStyleOption(val rect: RectF, val style: String, val label: String)

  // Document Page Inking Collection (pageIndex -> list of strokes in page-relative coords)
  val pageStrokes = ConcurrentHashMap<Int, MutableList<PdfPageStroke>>()
  private var activeDocStrokePageIndex: Int = -1
  private val activeDocPoints = mutableListOf<NativePoint>()
  private val activeDocPath = Path()
  private var activeDocStrokePageW: Float = 612f
  private var activeDocStrokePageH: Float = 792f
  private var isDrawingOnDoc: Boolean = false

  // Active inking state
  private val activePoints = mutableListOf<NativePoint>()
  private val activePath = Path()

  // LiquidText Real Pen & Semantic Inking System
  var penDrawingMode: String = "freehand" // "freehand" | "straight"
    set(value) {
      field = if (value.equals("straight", ignoreCase = true)) "straight" else "freehand"
      persistPenSettingsLocally()
      dispatchPenStateChangeEvent()
      invalidate()
    }
  var penThickness: Float = 3.5f
    set(value) {
      field = value.coerceIn(1.0f, 25.0f)
      persistPenSettingsLocally()
      dispatchPenStateChangeEvent()
      invalidate()
    }
  var penColor: Int = Color.parseColor("#00ADB5")
    set(value) {
      field = value
      selectedColor = value
      persistPenSettingsLocally()
      dispatchPenStateChangeEvent()
      invalidate()
    }
  var penFavoriteColors = mutableListOf<String>(
    "#00ADB5", "#2563EB", "#059669", "#DC2626", "#D97706", "#7C3AED",
    "#0284C7", "#10B981", "#EA580C", "#9333EA", "#E11D48", "#475569",
    "#0891B2", "#16A34A", "#CA8A04", "#4F46E5", "#BE185D"
  )
  var isPenSettingsOpen: Boolean = false
  val semanticInkLinks = mutableListOf<NativeInkLink>()

  // Live InkLink cross-zone gesture tracking
  private var isDrawingCrossZoneLink: Boolean = false
  private var inkLinkSourceDocId: String = ""
  private var inkLinkSourcePageIndex: Int = -1
  private var inkLinkSourcePdfRect: RectF = RectF()
  private var inkLinkSourcePdfPoint: NativePoint = NativePoint(0f, 0f)
  private var inkLinkSourceScreenStart: PointF = PointF()
  private var inkLinkCurrentScreenTouch: PointF = PointF()
  private var inkLinkTargetCardId: String? = null
  private var canvasPenStartCardId: String? = null

  // Active card interaction state
  internal var selectedCardId: String? = null
  private var draggingCard: NativeCard? = null
  private var dragOffsetWorldX: Float = 0f
  private var dragOffsetWorldY: Float = 0f
  private var totalDragDistance: Float = 0f
  private var heldCardId: String? = null

  // LiquidText Excerpt Card Toolbar & Typography state
  internal var isTypographyBarVisible = false
  internal var isStyleSheetOpen = false
  internal var isCardColorPaletteOpen = false
  internal var isTypoTextColorPaletteOpen = false
  internal var editingCardId: String? = null
  private var cursorPosition: Int = 0
  private var isCursorBlinkVisible = true
  private var lastCursorBlinkTime = 0L

  // Animated toolbar appearance / dismissal state (Apple spring)
  private var cardActionBarAnimProgress = 0f
  private var lastActionBarAnimTime = 0L
  private var lastSelectedCardForAnim: NativeCard? = null

  // Action Bar rects (Screen space)
  internal val cardActionBarRect = RectF()
  internal val btnCardCommentRect = RectF()
  internal val btnCardEditRect = RectF()
  internal val btnCardCopyRect = RectF()
  internal val btnCardDeleteRect = RectF()
  internal val btnCardTagsRect = RectF()
  internal val btnCardColorWheelRect = RectF()
  internal val btnCardTypographyRect = RectF()
  internal val cardColorPaletteRects = mutableListOf<Pair<RectF, Int>>()

  // Typography Bar rects (Screen space)
  internal val typographyBarRect = RectF()
  internal val btnTypoBackRect = RectF()
  internal val btnTypoStyleRect = RectF()
  internal val btnTypoBoldRect = RectF()
  internal val btnTypoItalicRect = RectF()
  internal val btnTypoUnderlineRect = RectF()
  internal val btnTypoStrikeRect = RectF()
  internal val btnTypoFontSizeRect = RectF()
  internal val btnTypoTextColorRect = RectF()
  internal val btnTypoMoreRect = RectF()
  internal val typoTextColorPaletteRects = mutableListOf<Pair<RectF, Int>>()

  // Style Sheet Popover rects (Screen space)
  internal val styleSheetRect = RectF()
  internal val styleOptionRects = mutableListOf<Triple<RectF, String, RectF>>()

  // Gesture flags
  private var isPanningCanvas = false
  internal var isDraggingDivider = false
  private var dividerDragOffsetY = 0f
  internal var lastUserDividerDragTime: Long = 0L
  internal var isScrollingDoc = false
  private var lastTouchScreenX = 0f
  private var lastTouchScreenY = 0f

  // Immersive / Distraction-Free Content Mode state
  private var isImmersive = false
  private var hadSelectionOrModalBeforeTouch = false

  // Real PDF Selection state
  internal var activePdfSelection: NativePdfSelection? = null
  internal var isSelectingPdfText = false
  internal var pdfSelectStartWord: TextWord? = null
  internal var pdfSelectPageIndex = 0

  // Figure Crop state
  internal var activeCropSelection: NativeCropSelection? = null
  internal var isDraggingCrop = false
  internal var isDraggingCropTopLeftHandle = false
  internal var isDraggingCropTopRightHandle = false
  internal var isDraggingCropBottomLeftHandle = false
  internal var isDraggingCropBottomRightHandle = false
  internal var isMovingCropSelection = false
  internal var cropDragOffsetDocX = 0f
  internal var cropDragOffsetDocY = 0f
  internal var cropStartX = 0f
  internal var cropStartY = 0f
  internal var cropPageIndex = 0

  // Text Selection Handle & Word Navigation state
  internal val paragraphLayouts = mutableListOf<ParagraphLayoutInfo>()
  internal var activeSelection: NativeDocumentSelection? = null
  internal var activeStructuredPInfo: ParagraphLayoutInfo? = null
  internal var activeStructuredStartOffset = 0
  internal var activeStructuredEndOffset = 0
  internal var isDraggingStartHandle = false
  internal var isDraggingEndHandle = false

  // Cross-Zone Lift-and-Drag state
  internal var isLiftingExcerpt = false
  internal var liftCandidateText: String? = null
  internal var liftCandidatePage: Int = 1
  internal var liftCandidateColor: Int = Color.parseColor("#00ADB5")
  internal var liftCandidateIsImage = false
  internal var liftCandidateImagePath: String? = null
  internal var liftCandidateBitmap: Bitmap? = null
  internal var liftCandidateSourceRects: List<RectF> = emptyList() // PDF-space rects for source pulse
  internal var liftGhostX = 0f
  internal var liftGhostY = 0f
  internal var liftAnchorScreenX = 0f
  internal var liftAnchorScreenY = 0f

  // Physics-based Scroll Inertia (Smooth multi-page glide)
  private val docScroller = OverScroller(context).apply {
    setFriction(0.0032f)
  }
  private var velocityTracker: VelocityTracker? = null
  private val minFlingVelocity: Int
  private val maxFlingVelocity: Int
  private val touchSlop: Int
  private var downDocX = 0f
  private var downDocY = 0f

  // Image bitmap cache for cards & instant cropping
  internal val cardBitmapCache = LruCache<String, Bitmap>(32)

  // Bidirectional Navigation Pulse
  internal var pulsePageNumber: Int? = null
  internal var pulseAlpha: Int = 0
  // Exact PDF-page-space rects to highlight during pulse (empty → pulse whole page border)
  internal var pulseSourceRects: List<RectF> = emptyList()

  // Jump button hit-rects per card (world-space, populated each draw frame)
  private val cardJumpBtnRects = HashMap<String, RectF>()
  // Original Extraction Source `>` arrow hit-rects per card (world-space)
  private val cardExtractionArrowRects = HashMap<String, RectF>()
  // Semantic InkLink anchor hit-rects (screen-space)
  private val inkLinkPdfAnchorScreenRects = HashMap<String, RectF>()
  private val inkLinkCardAnchorScreenRects = HashMap<String, RectF>()

  // Drop shockwave ripple animation
  private var rippleOriginX = 0f
  private var rippleOriginY = 0f
  private var rippleColor = Color.parseColor("#00ADB5")
  private var rippleProgress = 1f
  private var rippleAnimator: ValueAnimator? = null

  // LiquidText-style V Link Marker tap ripple feedback
  data class VLinkRipple(val x: Float, val y: Float, val color: Int, val timestamp: Long)
  private var activeVLinkRipple: VLinkRipple? = null

  // Toast feedback & HUD Notification Engine
  internal var copiedToastText: String? = null
  internal val hudToast = HudToastRenderer(density)
  private val inkLinkRenderer by lazy { InkLinkRenderer(density) }
  private var magneticTargetCardId: String? = null

  // Context colors
  private val contextColors = intArrayOf(
    Color.parseColor("#00ADB5"),
    Color.parseColor("#F59E0B"),
    Color.parseColor("#EF4444"),
    Color.parseColor("#3B82F6"),
    Color.parseColor("#8B5CF6")
  )

  // Top/Bottom Native UI Hit Rects
  private val headerRect = RectF()
  private val headerDocPillRect = RectF()
  private val headerModeTextRect = RectF()
  private val headerModeCropRect = RectF()
  private val headerSqueezeRect = RectF()
  private val rightSqueezeTabRect = RectF()
  private val headerSearchRect = RectF()
  private val headerZoomOutRect = RectF()
  private val headerZoomResetRect = RectF()
  private val headerZoomInRect = RectF()

  // Native PDF Engine Text Search State
  internal var isSearchActive = false
  internal var isSearching = false
  internal var currentSearchQuery = ""
  internal val searchMatches = mutableListOf<NativeSearchMatch>()
  internal var currentSearchIndex = 0
  // Cancels the previous incremental search when a new query starts
  internal var searchJob: kotlinx.coroutines.Job? = null
  // Native overlay panel (real Android View, not canvas-drawn)
  internal var searchOverlayView: android.view.ViewGroup? = null
  internal var searchCounterLabel: android.widget.TextView? = null

  // Native Search HUD UI Hit Rects
  private val searchHudRect = RectF()
  private val searchQueryPillRect = RectF()
  private val searchPrevBtnRect = RectF()
  private val searchNextBtnRect = RectF()
  private val searchCloseBtnRect = RectF()

  // Search Highlights Paints
  private val searchHighlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#FBBF24")
    style = Paint.Style.FILL
    alpha = 140
  }
  private val searchHighlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#D97706")
    style = Paint.Style.STROKE
    strokeWidth = 1.2f
    alpha = 200
  }
  private val searchCurrentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    style = Paint.Style.FILL
    alpha = 220
  }
  private val searchCurrentBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = 2.5f
    alpha = 255
  }
  private val searchCurrentGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00FFF5")
    style = Paint.Style.STROKE
    strokeWidth = 4.5f
    alpha = 160
  }

  private val canvasToolbarRect = RectF()
  private val toolBtnRects = mutableMapOf<String, RectF>()
  private val colorBtnRects = mutableMapOf<Int, RectF>()
  private val resetCanvasBtnRect = RectF()

  // Bottom HUD matching video: Workspaces, Text Box, Tidy, Squeeze, Pattern, Zoom Out
  private val bottomHudRect = RectF()
  private val bottomWorkspacesBtnRect = RectF()
  private val bottomTextBoxBtnRect = RectF()
  private val bottomTidyBtnRect = RectF()
  private val bottomSqueezeBtnRect = RectF()
  private val bottomPatternBtnRect = RectF()
  private val bottomZoomOutBtnRect = RectF()
  private val modeDrawingRect = RectF()
  private val modeDocumentRect = RectF()
  private val modeWorkspaceRect = RectF()
  private var currentNavMode: String = "workspace" // "drawing", "document", "workspace"

  // Paints
  private val docBgPaint = Paint().apply { color = Color.parseColor("#0B1120") }
  private val docPageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
  private val docPageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#334155")
    strokeWidth = 1.2f
    style = Paint.Style.STROKE
  }
  private val canvasBgPaint = Paint().apply { color = Color.parseColor("#0F172A") }
  private val dividerLinePaint = Paint().apply {
    color = Color.parseColor("#1E293B")
    strokeWidth = 3f
  }
  private val dividerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B")
    style = Paint.Style.FILL
  }
  private val dividerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }
  private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#334155")
    style = Paint.Style.FILL
  }
  private val gridPaint = Paint().apply {
    color = Color.parseColor("#1E293B")
    strokeWidth = 1.2f
    style = Paint.Style.STROKE
  }
  private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.FILL
  }
  private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeWidth = 2f
    style = Paint.Style.STROKE
  }
  private val cardSelectedGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 5f
    color = Color.parseColor("#00ADB5")
    alpha = 180
  }
  private val cardAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }
  private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#E0F2FE")
    style = Paint.Style.FILL
  }
  private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    strokeWidth = 1f
    style = Paint.Style.STROKE
  }
  private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    textSize = 19f
    isFakeBoldText = true
  }
  private val docSubheaderBgPaint = Paint().apply {
    color = Color.parseColor("#0F172A")
    style = Paint.Style.FILL
  }
  private val docTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#F8FAFC")
    textSize = 21f
    isFakeBoldText = true
  }
  private val docHeadingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#0F172A")
    textSize = 26f
    typeface = Typeface.SERIF
    isFakeBoldText = true
  }
  private val docParagraphPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B")
    textSize = 21f
    typeface = Typeface.SERIF
  }
  private val highlightBgPaint = Paint().apply {
    style = Paint.Style.FILL
  }
  private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
  }
  private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B")
    textSize = 22f
    textSkewX = -0.15f
  }
  private val commentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#64748B")
    textSize = 18f
  }
  private val closeBtnTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#94A3B8")
    textSize = 20f
    isFakeBoldText = true
  }
  private val toolbarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B")
    style = Paint.Style.FILL
  }
  private val toolbarBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#334155")
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }
  private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 3.5f
    pathEffect = DashPathEffect(floatArrayOf(14f, 9f), 0f)
  }
  private val linkGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 8f
    alpha = 75
  }
  private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }
  private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 3f
  }
  // ── Notebook Page Paints ───────────────────────────────────────────────────
  internal val nbPageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#40000000"); style = Paint.Style.FILL
  }
  internal val nbPageSelectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#3B82F6"); strokeWidth = 3f; style = Paint.Style.STROKE
  }
  internal val nbPageNormalBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#C8C5A6"); strokeWidth = 1.5f; style = Paint.Style.STROKE
  }
  internal val nbPageRuledLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#A8C4E8"); strokeWidth = 0.9f; style = Paint.Style.STROKE
  }
  internal val nbPageMarginLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#F0A0A0"); strokeWidth = 1.2f; style = Paint.Style.STROKE
  }
  internal val nbPageGridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#C8C8C8"); strokeWidth = 0.7f; style = Paint.Style.STROKE
  }
  internal val nbPageDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#B8B8B8"); style = Paint.Style.FILL
  }
  internal val nbPageToolbarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B"); style = Paint.Style.FILL
  }
  internal val nbPageToolbarBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#334155"); strokeWidth = 1.2f; style = Paint.Style.STROKE
  }
  internal val nbPageResizeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#3B82F6"); style = Paint.Style.FILL
  }
  private val selectionFillPaint = Paint().apply {
    color = Color.parseColor("#5900ADB5")
    style = Paint.Style.FILL
  }
  private val selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    strokeWidth = 2f
    style = Paint.Style.STROKE
  }
  private val selectionHandleBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    strokeWidth = 3.5f
    style = Paint.Style.STROKE
  }
  private val selectionHandlePinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    style = Paint.Style.FILL
  }
  private val cropDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    style = Paint.Style.STROKE
    strokeWidth = 2.5f
    pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
  }
  private val cropFillPaint = Paint().apply {
    color = Color.parseColor("#2600ADB5")
    style = Paint.Style.FILL
  }
  private val calloutBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#0F172A")
    style = Paint.Style.FILL
  }
  private val calloutBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#334155")
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }
  private val calloutPrimaryBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    style = Paint.Style.FILL
  }
  private val calloutSecondaryBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B")
    style = Paint.Style.FILL
  }
  private val calloutHighlightBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#F59E0B")
    style = Paint.Style.FILL
  }
  private val calloutTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = 17f
    isFakeBoldText = true
  }
  private val calloutSecondaryTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#38BDF8")
    textSize = 17f
    isFakeBoldText = true
  }

  // Tracks last focal point for 2-finger pan translation during pinch
  private var prevCanvasFocusX: Float = Float.NaN
  private var prevCanvasFocusY: Float = Float.NaN

  // Scale gesture detector for 2-finger zoom and pinch accordion squeeze
  private val scaleGestureListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
      // Reset focal tracking so first onScale call initializes it correctly for the right zone
      prevCanvasFocusX = Float.NaN
      prevCanvasFocusY = Float.NaN
      return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
      val fy = detector.focusY
      val splitY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio else 0f
      if (fy < splitY) {
        // Document zone multi-touch gestures
        prevCanvasFocusX = Float.NaN
        prevCanvasFocusY = Float.NaN

        val sFactor = detector.scaleFactor

        // 1. Expand squeezed document when spreading fingers apart:
        if (compressionEngine.isAnyPageCompressed() && sFactor > 1.02f) {
          compressionEngine.resetAllToNormal(animate = true) { invalidate() }
          hudToast.show("Document Expanded")
          return true
        }

        // 2. Vertical Pinch-to-Compare (Accordion Squeeze):
        // When at normal 1.0x view (pdfScaleFactor <= 1.05f), pinching fingers inward vertically
        // collapses non-annotated pages between the fingers so user can compare distant sections!
        val isVerticalPinch = detector.currentSpanY > 36f * density &&
          (detector.currentSpanY > detector.currentSpanX * 0.9f || abs(detector.currentSpan - detector.previousSpan) > 3f)

        if (pdfScaleFactor <= 1.05f && sFactor < 0.98f && isVerticalPinch) {
          if (!compressionEngine.isManualPinching) {
            val pageBounds = pageLayouts.map { it.boundsOnScreen }
            val pageIndices = pageLayouts.map { it.pageIndex }
            val annotatedPages = (annotations.map { it.pageNumber - 1 } + cards.map { it.pageNumber - 1 }).toSet()
            val y0 = detector.focusY - detector.currentSpanY / 2f
            val y1 = detector.focusY + detector.currentSpanY / 2f
            compressionEngine.onManualPinchBegin(y0, y1, pageBounds, pageIndices, annotatedPages)
          }
          if (compressionEngine.isManualPinching) {
            val y0 = detector.focusY - detector.currentSpanY / 2f
            val y1 = detector.focusY + detector.currentSpanY / 2f
            compressionEngine.onManualPinchMove(y0, y1)
            invalidate()
            return true
          }
        }

        // 3. Document Zoom In & Out:
        if (!compressionEngine.isManualPinching) {
          val prevScale = pdfScaleFactor
          pdfScaleFactor = (pdfScaleFactor * sFactor).coerceIn(1.0f, 5.0f)
          if (pdfScaleFactor != prevScale) {
            val scaleChange = pdfScaleFactor / prevScale
            val focusDocX = detector.focusX + docScrollX
            val focusDocY = detector.focusY + docScrollY
            docScrollX = (focusDocX * scaleChange - detector.focusX).coerceAtLeast(0f)
            docScrollY = (focusDocY * scaleChange - detector.focusY).coerceAtLeast(0f)
            invalidate()
            return true
          }
        }
        return true
      }

      // Canvas zone: handle BOTH zoom and 2-finger pan together here,
      // so there's a single source of truth and no drift.
      val canvasTopY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio + 14f else 0f
      val fX = detector.focusX
      val fY = detector.focusY - canvasTopY

      // Guard: initialize previous focal on first canvas-zone scale event of this gesture
      // This prevents stale values from doc-zone or previous gestures causing a jump.
      if (prevCanvasFocusX.isNaN()) {
        prevCanvasFocusX = fX
        prevCanvasFocusY = fY
      }

      // 1. Apply focal-anchored zoom (world point under fingers stays fixed)
      val newScale = (camera.scaleFactor * detector.scaleFactor).coerceIn(camera.minScale, camera.maxScale)
      val ratio = newScale / camera.scaleFactor
      camera.panX = fX - (fX - camera.panX) * ratio
      camera.panY = fY - (fY - camera.panY) * ratio
      camera.scaleFactor = newScale

      // 2. Apply 2-finger translation (focal delta = pure pan when fingers slide together)
      camera.panX += fX - prevCanvasFocusX
      camera.panY += fY - prevCanvasFocusY

      prevCanvasFocusX = fX
      prevCanvasFocusY = fY

      dispatchTransformEvent()
      invalidate()
      return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
      if (compressionEngine.isManualPinching) {
        compressionEngine.onManualPinchEnd(
          onUpdate = { invalidate() },
          onHaptic = { performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
        )
      }
    }
  }
  private val scaleGestureDetector = ScaleGestureDetector(context ?: throw IllegalStateException("Context required"), scaleGestureListener)


  // Long-press gesture detector for Android-style Text Selection (handles & copy/paste callout)
  private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onLongPress(e: MotionEvent) {
      triggerLongPressSelect(e.x, e.y)
    }
  })

  // ── Crop Selection & Callout Layout Extracted to ThinkspaceViewSelection.kt ──
  // ── InkLink Perimeter & Geometry Helpers Extracted to ThinkspaceViewInking.kt ──
  // ── Long-Press & Structured Selection Extracted to ThinkspaceViewSelection.kt ──

  init {
    setWillNotDraw(false)
    isClickable = true
    isFocusable = true
    docScroller.setFriction(0.0018f)
    val vc = ViewConfiguration.get(context)
    minFlingVelocity = vc.scaledMinimumFlingVelocity
    maxFlingVelocity = vc.scaledMaximumFlingVelocity * 3
    touchSlop = vc.scaledTouchSlop
  }

  override fun computeScroll() {
    super.computeScroll()
    if (docScroller.computeScrollOffset()) {
      docScrollX = docScroller.currX.toFloat().coerceIn(0f, maxDocScrollX)
      docScrollY = docScroller.currY.toFloat().coerceIn(0f, maxDocScrollY)
      postInvalidateOnAnimation()
    }
  }

  // ── Crop Bitmap Generation & Saving Extracted to ThinkspaceViewSelection.kt ──

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    renderScope.cancel()
    pageBitmaps.evictAll()
  }

  // ---------------------------------------------------------------------------
  // Document Configuration, Multi-Document Engine & Workspace Data Loaders
  // Extracted to ThinkspaceViewDocument.kt as extension functions
  // ---------------------------------------------------------------------------

  internal fun triggerShockwave(wx: Float, wy: Float, color: Int) {
    rippleOriginX = wx
    rippleOriginY = wy
    rippleColor = color
    rippleProgress = 0f

    rippleAnimator?.cancel()
    rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 650
      interpolator = DecelerateInterpolator()
      addUpdateListener {
        rippleProgress = it.animatedValue as Float
        postInvalidateOnAnimation()
      }
      start()
    }
  }

  internal fun canvasScreenToWorld(sx: Float, sy: Float, canvasTopY: Float): Pair<Float, Float> {
    return Pair(camera.screenToWorldX(sx), camera.screenToWorldY(sy, canvasTopY))
  }

  internal fun canvasWorldToScreen(wx: Float, wy: Float, canvasTopY: Float): Pair<Float, Float> {
    return Pair(camera.worldToScreenX(wx), camera.worldToScreenY(wy, canvasTopY))
  }

  // ── Competitor Selection Callout ─────────────────────────────────────────────
  // Extracted to ThinkspaceViewToolbar.kt as extension functions
  // ─────────────────────────────────────────────────────────────────────────────

  // ---------------------------------------------------------------------------
  // Master OnDraw (100% Native Kotlin Workspace)
  // ---------------------------------------------------------------------------
  @SuppressLint("DrawAllocation")
  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    if (!docScroller.isFinished) {
      postInvalidateOnAnimation()
    }

    val viewW = width.toFloat()
    val viewH = height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return

    val hasDoc = activePdfDoc != null || activeDocument != null
    val splitY = if (hasDoc) viewH * effectiveSplitRatio else 0f
    val docBottomY = max(0f, splitY - 14f)
    val canvasTopY = if (hasDoc && effectiveSplitRatio > 0f) splitY + 14f else 0f

    // =========================================================================
    // 1. TOP ZONE: Native Document Viewer (Continuous Multi-Page Stream)
    // =========================================================================
    if (hasDoc && docBottomY > 10f) {
      canvas.save()
      canvas.clipRect(0f, 0f, viewW, docBottomY)
      canvas.drawRect(0f, 0f, viewW, docBottomY, docBgPaint)

      if (showDocumentHeader) {
        headerRect.set(0f, 0f, viewW, subheaderH)
        canvas.drawRect(headerRect, docSubheaderBgPaint)

        val titleCandidate = activeDocument?.title?.takeIf { it.isNotEmpty() }
          ?: activePdfDoc?.metadata?.title?.takeIf { it.isNotEmpty() }
          ?: "PDF Document"
        val titleStr = if (titleCandidate.startsWith("document%", ignoreCase = true) || titleCandidate.startsWith("content:", ignoreCase = true)) {
          "Document"
        } else {
          titleCandidate
        }
        val pageTotal = activePdfDoc?.pageCount ?: activeDocument?.pageCount ?: 1

        // 1. Document Pill matching Screenshot [Kshitija_Resume (34) ▾]
        val displayTitle = if (titleStr.length > 22) titleStr.substring(0, 20) + "... ▾" else "$titleStr ▾"
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          textSize = 13f * density
          isFakeBoldText = true
        }
        val pillW = titlePaint.measureText(displayTitle) + 20f * density
        val btnTop = (subheaderH - 30f * density) / 2f
        val btnBottom = btnTop + 30f * density

        headerDocPillRect.set(12f * density, btnTop, 12f * density + pillW, btnBottom)
        val docPillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#0A2430")
          style = Paint.Style.FILL
        }
        val docPillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          alpha = 150
          strokeWidth = 1f * density
          style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(headerDocPillRect, 8f * density, 8f * density, docPillBg)
        canvas.drawRoundRect(headerDocPillRect, 8f * density, 8f * density, docPillBorder)
        titlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(displayTitle, headerDocPillRect.centerX(), btnTop + 19.5f * density, titlePaint)

        // Page Pill: p. 1/1
        val curPageNum = (pageLayouts.firstOrNull { it.boundsOnScreen.bottom > subheaderH + 20f }?.pageNumber ?: 1).coerceIn(1, pageTotal)
        val pageInd = "p. $curPageNum/$pageTotal"
        val pageIndPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#94A3B8")
          textSize = 12f * density
          isFakeBoldText = true
        }
        canvas.drawText(pageInd, headerDocPillRect.right + 12f * density, btnTop + 19.5f * density, pageIndPaint)

        // Right Side Controls matching Screenshot: [🔍 Search] then [✂ Crop] then [- 1:1 +] on far right
        val zoomPillW = 86f * density
        val zoomPillLeft = viewW - 12f * density - zoomPillW

        val cropBtnW = 76f * density
        val cropBtnLeft = zoomPillLeft - 8f * density - cropBtnW

        val searchBtnW = if (viewW > 450f * density) 76f * density else 36f * density
        val searchBtnLeft = cropBtnLeft - 8f * density - searchBtnW

        // Search Mode Toggle [🔍 Search]
        headerSearchRect.set(searchBtnLeft, btnTop, searchBtnLeft + searchBtnW, btnBottom)
        val searchBg = if (isSearchActive) Color.parseColor("#00ADB5") else Color.parseColor("#0F172A")
        val searchBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          strokeWidth = 1.2f * density
          style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(headerSearchRect, 8f * density, 8f * density, Paint().apply { color = searchBg })
        canvas.drawRoundRect(headerSearchRect, 8f * density, 8f * density, searchBorderPaint)
        val searchIconPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = if (isSearchActive) Color.WHITE else Color.parseColor("#00ADB5")
          textSize = 12f * density
          textAlign = Paint.Align.CENTER
          isFakeBoldText = true
        }
        val searchLabel = if (viewW > 450f * density) "🔍 Search" else "🔍"
        canvas.drawText(searchLabel, headerSearchRect.centerX(), btnTop + 19.5f * density, searchIconPaint)

        // Crop Mode Toggle [✂ Crop]
        headerModeCropRect.set(cropBtnLeft, btnTop, cropBtnLeft + cropBtnW, btnBottom)
        val cropBg = if (docMode == "crop") Color.parseColor("#00ADB5") else Color.parseColor("#0F172A")
        val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          strokeWidth = 1.2f * density
          style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(headerModeCropRect, 8f * density, 8f * density, Paint().apply { color = cropBg })
        canvas.drawRoundRect(headerModeCropRect, 8f * density, 8f * density, cropBorderPaint)
        val cropTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = if (docMode == "crop") Color.WHITE else Color.parseColor("#00ADB5")
          textSize = 12.5f * density
          textAlign = Paint.Align.CENTER
          isFakeBoldText = true
        }
        canvas.drawText("✂ Crop", headerModeCropRect.centerX(), btnTop + 19.5f * density, cropTextPaint)

        // Segmented Zoom Pill [- 1:1 +] on far right
        val zoomBgPaint = Paint().apply { color = Color.parseColor("#0F172A") }
        val zoomBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#334155")
          strokeWidth = 1f * density
          style = Paint.Style.STROKE
        }
        val zoomFullRect = RectF(zoomPillLeft, btnTop, zoomPillLeft + zoomPillW, btnBottom)
        canvas.drawRoundRect(zoomFullRect, 8f * density, 8f * density, zoomBgPaint)
        canvas.drawRoundRect(zoomFullRect, 8f * density, 8f * density, zoomBorderPaint)

        val zoomSegW = zoomPillW / 3f
        headerZoomOutRect.set(zoomPillLeft, btnTop, zoomPillLeft + zoomSegW, btnBottom)
        headerZoomResetRect.set(zoomPillLeft + zoomSegW, btnTop, zoomPillLeft + zoomSegW * 2f, btnBottom)
        headerZoomInRect.set(zoomPillLeft + zoomSegW * 2f, btnTop, zoomPillLeft + zoomPillW, btnBottom)

        // Segment dividing lines
        canvas.drawLine(zoomPillLeft + zoomSegW, btnTop + 4f * density, zoomPillLeft + zoomSegW, btnBottom - 4f * density, zoomBorderPaint)
        canvas.drawLine(zoomPillLeft + zoomSegW * 2f, btnTop + 4f * density, zoomPillLeft + zoomSegW * 2f, btnBottom - 4f * density, zoomBorderPaint)

        val zoomTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          textSize = 12f * density
          textAlign = Paint.Align.CENTER
          isFakeBoldText = true
        }
        canvas.drawText("-", headerZoomOutRect.centerX(), btnTop + 19.5f * density, zoomTextPaint)
        canvas.drawText("1:1", headerZoomResetRect.centerX(), btnTop + 19.5f * density, zoomTextPaint)
        canvas.drawText("+", headerZoomInRect.centerX(), btnTop + 19.5f * density, zoomTextPaint)
      }

      // -----------------------------------------------------------------------
      // Render Document Pages (Real PDF Document or Fallback Structured Sections)
      // -----------------------------------------------------------------------
      val paperMargin = 10f * density
      val basePaperW = viewW - paperMargin * 2f
      val paperW = basePaperW * pdfScaleFactor
      val paperX = paperMargin - docScrollX

      pageLayouts.clear()

      if (activePdfDoc != null) {
        val pdf = activePdfDoc!!
        val pCount = pdf.pageCount
        compressionEngine.ensurePageCount(pCount)
        val annotatedPageIndices = (annotations.map { it.pageNumber - 1 } + cards.map { it.pageNumber - 1 }).toSet()
        val colorsByPage = annotations.groupBy({ it.pageNumber - 1 }, { it.color })
        compressionEngine.updateAnnotationData(annotatedPageIndices, colorsByPage)

        val firstPageSize = runCatching { pdf.getPage(0).size }.getOrNull()
        val docAspectRatio = if (firstPageSize != null && firstPageSize.width > 0f) {
          firstPageSize.height / firstPageSize.width
        } else {
          1.294f
        }
        val standardPageH = paperW * docAspectRatio
        val standardGap = 16f * pdfScaleFactor
        val totalDocH = compressionEngine.getTotalDocHeight(standardPageH, standardGap)
        maxDocScrollY = max(0f, totalDocH - (docBottomY - subheaderH) + 60f)
        maxDocScrollX = max(0f, paperW - basePaperW)
        if (docScrollY > maxDocScrollY) {
          docScrollY = maxDocScrollY
        }

        var accumDocY = 0f
        for (pageIdx in 0 until pCount) {
          val pageNum = pageIdx + 1
          val pSize = runCatching { pdf.getPage(pageIdx).size }.getOrNull() ?: PageSize.LETTER
          val pageBaseH = if (pSize.width > 0f) paperW * (pSize.height / pSize.width) else standardPageH
          val pageH = compressionEngine.getDisplayedPageHeight(pageIdx, pageBaseH)
          val pageGap = compressionEngine.getPageGap(pageIdx, standardGap)
          val pageTopY = subheaderH + 16f - docScrollY + accumDocY
          val screenRect = RectF(paperX, pageTopY, paperX + paperW, pageTopY + pageH)
          accumDocY += pageH + pageGap

          val isCompressed = compressionEngine.isPageCompressed(pageIdx)

          pageLayouts.add(
            PdfPageLayout(
              pageIndex = pageIdx,
              pageNumber = pageNum,
              pageSize = pSize,
              topY = pageTopY,
              height = pageH,
              isFolded = isCompressed,
              boundsOnScreen = screenRect
            )
          )

          // Viewport culling: only draw pages touching the visible area
          if (screenRect.bottom >= subheaderH && screenRect.top <= docBottomY) {
            // Standard PDF Page Paper Background
            canvas.drawRoundRect(screenRect, 4f * density, 4f * density, docPageBgPaint)
            canvas.drawRoundRect(screenRect, 4f * density, 4f * density, docPageBorderPaint)

            // Real PDF Page Bitmap rendering!
            val bmp = pageBitmaps.get(pageIdx)
            if (bmp != null && !bmp.isRecycled) {
              canvas.drawBitmap(bmp, null, screenRect, null)
            } else {
              // Page loading placeholder
              val skeletonPaint = Paint().apply { color = Color.parseColor("#F1F5F9") }
              canvas.drawRoundRect(screenRect, 4f * density, 4f * density, skeletonPaint)
              if (!isCompressed) {
                canvas.drawText(
                  "Rendering Page $pageNum...",
                  screenRect.centerX() - 80f,
                  screenRect.centerY(),
                  commentPaint
                )
              }
              // Trigger background render
              if (!renderingPages.contains(pageIdx)) {
                renderingPages.add(pageIdx)
                renderScope.launch(Dispatchers.IO) {
                  try {
                    var pageBmp: Bitmap? = null

                    // 1. Primary: Try high-speed native C++ Android PdfRenderer (Google Skia)
                    val wrapper = pdf as? com.thinkspace.pdfengine.parser.PdfBoxDocumentWrapper
                    if (wrapper != null && wrapper.file.exists()) {
                      try {
                        val pfd = ParcelFileDescriptor.open(wrapper.file, ParcelFileDescriptor.MODE_READ_ONLY)
                        pfd.use { desc ->
                          val nativeRenderer = android.graphics.pdf.PdfRenderer(desc)
                          val page = nativeRenderer.openPage(pageIdx)
                          val scale = 1.6f
                          val targetW = max(1, (page.width * scale).toInt())
                          val targetH = max(1, (page.height * scale).toInt())
                          val b = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                          b.eraseColor(Color.WHITE)
                          page.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                          page.close()
                          nativeRenderer.close()
                          pageBmp = b
                        }
                      } catch (t: Throwable) {
                        t.printStackTrace()
                      }
                    }

                    // 2. Secondary fallback: Use DefaultPdfDocumentEngine.renderPage
                    if (pageBmp == null) {
                      val engine = PdfEngineModule.getOrCreateEngine(context)
                      val rendered = engine.renderPage(pdf, pageIdx, RenderOptions(scale = 1.6f))
                      pageBmp = rendered.bitmap
                    }

                    if (pageBmp != null) {
                      pageBitmaps.put(pageIdx, pageBmp)
                      postInvalidate()
                    }
                  } catch (e: Exception) {
                    e.printStackTrace()
                  } finally {
                    renderingPages.remove(pageIdx)
                  }
                }
              }
            }

            // If page is compressed, draw subtle paper compression tint & page number on left margin (matching reference image!)
            if (isCompressed) {
              val compressTint = Paint().apply {
                color = Color.parseColor("#0F172A")
                alpha = 25
                style = Paint.Style.FILL
              }
              canvas.drawRoundRect(screenRect, 4f * density, 4f * density, compressTint)
              val pLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 10.5f * density
                isFakeBoldText = true
              }
              canvas.drawText("P $pageNum", screenRect.left + 8f * density, screenRect.centerY() + 3.5f * density, pLabelPaint)
            }

              // Extract text words if not already cached
              if (!pageWordsCache.containsKey(pageIdx) && !extractingWords.contains(pageIdx)) {
                extractingWords.add(pageIdx)
                renderScope.launch(Dispatchers.IO) {
                  try {
                    val engine = PdfEngineModule.getOrCreateEngine(context)
                    val words = engine.extractText(pdf, pageIdx).filterIsInstance<TextWord>()
                    pageWordsCache[pageIdx] = words
                  } catch (e: Exception) {
                    e.printStackTrace()
                  } finally {
                    extractingWords.remove(pageIdx)
                  }
                }
              }

              // Draw persistent annotations on this page
              val pageAnns = annotations.filter { it.pageNumber == pageNum }
              val pSize = runCatching { pdf.getPage(pageIdx).size }.getOrNull() ?: com.thinkspace.pdfengine.model.PageSize.LETTER
              val pageWidth = pSize.width
              val pageHeight = pSize.height
              for (ann in pageAnns) {
                val baseColor = ann.color
                highlightBgPaint.color = baseColor
                highlightBgPaint.alpha = if (isCompressed) 215 else 85
                highlightBorderPaint.color = baseColor
                highlightBorderPaint.alpha = if (isCompressed) 240 else 180
                highlightBorderPaint.strokeWidth = if (isCompressed) 2.5f * density else 1.5f * density
                if (ann.rects.isNotEmpty()) {
                  for (r in ann.rects) {
                    val l = screenRect.left + (r.left / pageWidth) * screenRect.width()
                    val t = screenRect.top + (r.top / pageHeight) * screenRect.height()
                    val right = screenRect.left + (r.right / pageWidth) * screenRect.width()
                    val b = screenRect.top + (r.bottom / pageHeight) * screenRect.height()
                    val effectiveB = if (isCompressed) max(t + 4f * density, b) else b
                    val hRect = RectF(l, t, right, effectiveB)
                    canvas.drawRoundRect(hRect, 3f * density, 3f * density, highlightBgPaint)
                    if (hRect.width() > 24f * density && hRect.height() > 8f * density) {
                      canvas.drawRoundRect(hRect, 3f * density, 3f * density, highlightBorderPaint)
                    }
                  }
                } else {
                  canvas.drawRect(
                    screenRect.left + 20f,
                    screenRect.top + 10f,
                    screenRect.right - 20f,
                    screenRect.top + if (isCompressed) 20f else 50f,
                    highlightBgPaint
                  )
                }
              }

              // LiquidText-style color pills on right margin of compressed pages showing what colors are highlighted
              if (isCompressed && pageAnns.isNotEmpty()) {
                val uniqueColors = pageAnns.map { it.color }.distinct()
                var pillRight = screenRect.right - 10f * density
                val pillH = min(screenRect.height() - 8f * density, 11f * density)
                val pillW = 20f * density
                val pillY = screenRect.centerY() - pillH / 2f
                val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                for (col in uniqueColors) {
                  pillPaint.color = col
                  val pillRect = RectF(pillRight - pillW, pillY, pillRight, pillY + pillH)
                  canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillPaint)
                  pillRight -= pillW + 4f * density
                }
              }

              // Draw native search highlights on this page
              if (isSearchActive && searchMatches.isNotEmpty()) {
                val pSize = runCatching { pdf.getPage(pageIdx).size }.getOrNull() ?: com.thinkspace.pdfengine.model.PageSize.LETTER
                val pW = pSize.width
                val pH = pSize.height
                val pageMatches = searchMatches.filter { it.pageIndex == pageIdx }

                for (match in pageMatches) {
                  val isCurrent = (match == searchMatches.getOrNull(currentSearchIndex))
                  for (r in match.rects) {
                    val l = screenRect.left + (r.left / pW) * screenRect.width()
                    val t = screenRect.top + (r.top / pH) * screenRect.height()
                    val right = screenRect.left + (r.right / pW) * screenRect.width()
                    val b = screenRect.top + (r.bottom / pH) * screenRect.height()
                    val highlightR = RectF(l - 2f * density, t - 1.5f * density, right + 2f * density, b + 1.5f * density)

                    if (isCurrent) {
                      canvas.drawRoundRect(highlightR, 4f * density, 4f * density, searchCurrentGlowPaint)
                      canvas.drawRoundRect(highlightR, 4f * density, 4f * density, searchCurrentFillPaint)
                      canvas.drawRoundRect(highlightR, 4f * density, 4f * density, searchCurrentBorderPaint)
                    } else {
                      canvas.drawRoundRect(highlightR, 3f * density, 3f * density, searchHighlightFillPaint)
                      canvas.drawRoundRect(highlightR, 3f * density, 3f * density, searchHighlightBorderPaint)
                    }
                  }
                }
              }

              // Draw persistent and active PDF page inking strokes
              val pStrokes = pageStrokes[pageIdx]
              val hasActiveOnThisPage = (activeDocStrokePageIndex == pageIdx && activeDocPoints.isNotEmpty())
              if (!pStrokes.isNullOrEmpty() || hasActiveOnThisPage) {
                canvas.save()
                canvas.clipRect(screenRect)
                val scaleX = screenRect.width() / pageWidth
                val scaleY = screenRect.height() / pageHeight
                canvas.translate(screenRect.left, screenRect.top)
                canvas.scale(scaleX, scaleY)

                if (!pStrokes.isNullOrEmpty()) {
                  for (ps in pStrokes) {
                    if (ps.points.isEmpty()) continue
                    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                      color = ps.color
                      style = Paint.Style.STROKE
                      strokeCap = Paint.Cap.ROUND
                      strokeJoin = Paint.Join.ROUND
                      strokeWidth = ps.strokeWidth
                      if (ps.isHighlighter) {
                        alpha = 115
                        strokeWidth = max(ps.strokeWidth, 18f)
                      }
                    }
                    val path = Path()
                    path.moveTo(ps.points[0].x, ps.points[0].y)
                    for (i in 1 until ps.points.size) {
                      val pt = ps.points[i]
                      path.lineTo(pt.x, pt.y)
                    }
                    canvas.drawPath(path, strokePaint)
                  }
                }

                if (hasActiveOnThisPage && !isDrawingCrossZoneLink) {
                  val isHl = activeTool == "highlighter"
                  val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isHl) selectedColor else penColor
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = if (isHl) max(penThickness, 12f) else penThickness
                    if (isHl) alpha = 115
                  }
                  canvas.drawPath(activeDocPath, activePaint)
                }

                // Draw InkLink Anchor Pins on PDF page
                val docLinks = semanticInkLinks.filter { it.sourceDocId == activeDocumentId && it.sourcePageIndex == pageIdx }
                if (docLinks.isNotEmpty()) {
                  val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                  val pinWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                  }
                  val pinRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                  }
                  for (dl in docLinks) {
                    val px = dl.sourcePdfPoint.x
                    val py = dl.sourcePdfPoint.y
                    pinPaint.color = dl.color
                    pinRingPaint.color = dl.color
                    canvas.drawCircle(px, py, 7f, pinPaint)
                    canvas.drawCircle(px, py, 3f, pinWhitePaint)
                    canvas.drawCircle(px, py, 11f, pinRingPaint)
                  }
                }

                canvas.restore()
              }

              // Flash bidirectional navigation pulse if active
              if (pulsePageNumber == pageNum && pulseAlpha > 0) {
                if (pulseSourceRects.isNotEmpty()) {
                  val pageWidth = pSize.width
                  val pageHeight = pSize.height
                  val pulseFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#F59E0B")
                    style = Paint.Style.FILL
                    alpha = (pulseAlpha * 0.45f).toInt()
                  }
                  val pulseStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#D97706")
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f * density
                    alpha = pulseAlpha
                  }
                  for (r in pulseSourceRects) {
                    val l = screenRect.left + (r.left / pageWidth) * screenRect.width()
                    val t = screenRect.top + (r.top / pageHeight) * screenRect.height()
                    val right = screenRect.left + (r.right / pageWidth) * screenRect.width()
                    val b = screenRect.top + (r.bottom / pageHeight) * screenRect.height()
                    val highlightR = RectF(l - 3f * density, t - 1.5f * density, right + 3f * density, b + 1.5f * density)
                    canvas.drawRoundRect(highlightR, 4f * density, 4f * density, pulseFillPaint)
                    canvas.drawRoundRect(highlightR, 4f * density, 4f * density, pulseStrokePaint)
                  }
                } else {
                  val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#F59E0B")
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                    alpha = pulseAlpha
                  }
                  canvas.drawRoundRect(screenRect, 8f, 8f, pulsePaint)
                }
              }
            }
          }
        } else if (activeDocument != null) {
        // Fallback Structured Text Sections rendered as a clean resume sheet matching screenshot
        val doc = activeDocument!!
        paragraphLayouts.clear()

        val paperTopY = subheaderH + 10f * density - docScrollY
        val contentPadding = 18f * density
        val textW = max(20, (paperW - contentPadding * 2f).toInt())

        val headerNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#0F172A")
          textSize = 18f * density
          typeface = Typeface.SERIF
          isFakeBoldText = true
        }
        val headerContactPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#334155")
          textSize = 8.5f * density
          typeface = Typeface.SERIF
        }
        val sectionTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#0F172A")
          textSize = 10.5f * density
          typeface = Typeface.SERIF
          isFakeBoldText = true
        }
        val sectionRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#1E293B")
          strokeWidth = 0.8f * density
        }
        val resumeParaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#1E293B")
          textSize = 9.5f * density
          typeface = Typeface.SERIF
        }

        // Draw unified white paper sheet
        val sheetH = max((docBottomY - subheaderH) * 1.5f, 900f * density)
        val paperRect = RectF(paperX, paperTopY, paperX + paperW, paperTopY + sheetH)
        canvas.drawRect(paperRect, docPageBgPaint)
        canvas.drawRect(paperRect, docPageBorderPaint)

        pageLayouts.add(
          PdfPageLayout(
            pageIndex = 0,
            pageNumber = 1,
            pageSize = PageSize.LETTER,
            topY = paperTopY,
            height = sheetH,
            isFolded = isSqueezed,
            boundsOnScreen = paperRect
          )
        )

        var curY = paperTopY + 22f * density

        for (sec in doc.sections) {
          val secAnns = annotations.filter { it.sectionId == sec.id }
          val hasAnn = secAnns.isNotEmpty()

          if (isSqueezed && !hasAnn && sec.id != "sec-header") {
            val foldRect = RectF(paperX + contentPadding, curY, paperX + paperW - contentPadding, curY + 24f * density)
            canvas.drawRoundRect(foldRect, 6f * density, 6f * density, dividerHandlePaint)
            canvas.drawText("── ${sec.heading} (Folded) ──", paperX + contentPadding + 10f * density, curY + 16f * density, commentPaint)
            curY += 30f * density
            continue
          }

          if (sec.id == "sec-header") {
            val nameText = sec.heading
            val nameW = headerNamePaint.measureText(nameText)
            canvas.drawText(nameText, paperX + (paperW - nameW) / 2f, curY + 16f * density, headerNamePaint)
            curY += 22f * density

            for (para in sec.paragraphs) {
              val contactW = headerContactPaint.measureText(para)
              canvas.drawText(para, paperX + (paperW - contactW) / 2f, curY + 10f * density, headerContactPaint)
              curY += 16f * density
            }
            curY += 10f * density
          } else {
            val headingText = sec.heading.uppercase()
            canvas.drawText(headingText, paperX + contentPadding, curY + 12f * density, sectionTitlePaint)
            canvas.drawLine(
              paperX + contentPadding,
              curY + 16f * density,
              paperX + paperW - contentPadding,
              curY + 16f * density,
              sectionRulePaint
            )
            curY += 22f * density

            for ((pIdx, para) in sec.paragraphs.withIndex()) {
              val ann = secAnns.find { it.paragraphIndex == pIdx }
              val isHighlighted = ann != null

              val staticLayout = StaticLayout.Builder
                .obtain(para, 0, para.length, resumeParaPaint, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.25f)
                .build()

              paragraphLayouts.add(
                ParagraphLayoutInfo(
                  secId = sec.id,
                  pIdx = pIdx,
                  pageNumber = sec.pageNumber,
                  text = para,
                  paperX = paperX + contentPadding,
                  topY = curY,
                  width = textW.toFloat(),
                  layout = staticLayout
                )
              )

              val paraH = staticLayout.height.toFloat()
              if (isHighlighted) {
                val hlRect = RectF(paperX + contentPadding - 4f * density, curY - 2f * density, paperX + paperW - contentPadding + 4f * density, curY + paraH + 2f * density)
                highlightBgPaint.color = ann.color
                highlightBgPaint.alpha = 40
                canvas.drawRoundRect(hlRect, 4f * density, 4f * density, highlightBgPaint)
              }

              canvas.save()
              canvas.translate(paperX + contentPadding, curY)
              staticLayout.draw(canvas)
              canvas.restore()

              curY += paraH + 10f * density
            }
            curY += 8f * density
          }
        }
        maxDocScrollY = max(0f, curY + docScrollY - docBottomY + 40f)
      }

      // -----------------------------------------------------------------------
      // Draw Active Text Selection (Real PDF or Fallback) matching Video
      // -----------------------------------------------------------------------
      // Dynamically sync text & crop selection coordinates with current PDF page zoom & scroll
      val curSel = activePdfSelection
      if (curSel != null && activePdfDoc != null) {
        val selPl = pageLayouts.firstOrNull { it.pageIndex == curSel.pageIndex } ?: run {
          val pSize = runCatching { activePdfDoc?.getPage(curSel.pageIndex)?.size }.getOrNull() ?: PageSize.LETTER
          val pageAspectRatio = if (pSize.width > 0f) pSize.height / pSize.width else 1.294f
          val standardPageH = paperW * pageAspectRatio
          val standardGap = 16f * pdfScaleFactor
          val pageTopDocY = compressionEngine.getPageTopDocY(curSel.pageIndex, standardPageH, standardGap)
          val pH = compressionEngine.getDisplayedPageHeight(curSel.pageIndex, standardPageH)
          val pageTopY = subheaderH + 16f - docScrollY + pageTopDocY
          val screenRect = RectF(paperX, pageTopY, paperX + paperW, pageTopY + pH)
          PdfPageLayout(
            pageIndex = curSel.pageIndex,
            pageNumber = curSel.pageIndex + 1,
            pageSize = pSize,
            topY = pageTopY,
            height = pH,
            isFolded = compressionEngine.isPageCompressed(curSel.pageIndex),
            boundsOnScreen = screenRect
          )
        }
        activePdfSelection = syncPdfSelectionWithLayout(curSel, selPl)
      }

      val curCrop = activeCropSelection
      if (curCrop != null && activePdfDoc != null) {
        val cropPl = pageLayouts.firstOrNull { it.pageIndex == curCrop.pageIndex } ?: run {
          val pSize = runCatching { activePdfDoc?.getPage(curCrop.pageIndex)?.size }.getOrNull() ?: PageSize.LETTER
          val pageAspectRatio = if (pSize.width > 0f) pSize.height / pSize.width else 1.294f
          val standardPageH = paperW * pageAspectRatio
          val standardGap = 16f * pdfScaleFactor
          val pageTopDocY = compressionEngine.getPageTopDocY(curCrop.pageIndex, standardPageH, standardGap)
          val pH = compressionEngine.getDisplayedPageHeight(curCrop.pageIndex, standardPageH)
          val pageTopY = subheaderH + 16f - docScrollY + pageTopDocY
          val screenRect = RectF(paperX, pageTopY, paperX + paperW, pageTopY + pH)
          PdfPageLayout(
            pageIndex = curCrop.pageIndex,
            pageNumber = curCrop.pageIndex + 1,
            pageSize = pSize,
            topY = pageTopY,
            height = pH,
            isFolded = compressionEngine.isPageCompressed(curCrop.pageIndex),
            boundsOnScreen = screenRect
          )
        }
        val pW = cropPl.pageSize.width
        val pH = cropPl.pageSize.height
        val l = cropPl.boundsOnScreen.left + (curCrop.pageBounds.left / pW) * cropPl.boundsOnScreen.width()
        val t = cropPl.boundsOnScreen.top + (curCrop.pageBounds.top / pH) * cropPl.boundsOnScreen.height()
        val r = cropPl.boundsOnScreen.left + (curCrop.pageBounds.right / pW) * cropPl.boundsOnScreen.width()
        val b = cropPl.boundsOnScreen.top + (curCrop.pageBounds.bottom / pH) * cropPl.boundsOnScreen.height()
        curCrop.screenRect.set(l, t, r, b)
        recomputeCropCalloutRects(curCrop)
      }

      val pdfSel = activePdfSelection
      if (pdfSel != null) {
        val isSelVisible = pdfSel.highlightRects.any { it.bottom >= subheaderH && it.top <= docBottomY }
        if (isSelVisible) {
          for (r in pdfSel.highlightRects) {
            canvas.drawRoundRect(r, 3f * density, 3f * density, selectionFillPaint)
            canvas.drawLine(r.left, r.top, r.right, r.top, selectionBorderPaint)
            canvas.drawLine(r.left, r.bottom, r.right, r.bottom, selectionBorderPaint)
          }

          // Handles matching Android Copy & Paste handles
          if (pdfSel.highlightRects.isNotEmpty()) {
            val firstR = pdfSel.highlightRects.first()
            val lastR = pdfSel.highlightRects.last()

            val handleColor = Color.parseColor("#00ADB5")
            val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = handleColor
              style = Paint.Style.FILL
            }
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = handleColor
              strokeWidth = 2.5f * density
              strokeCap = Paint.Cap.ROUND
              style = Paint.Style.STROKE
            }

            // Start handle: vertical cyan bar + teardrop pin at bottom-left
            canvas.drawLine(firstR.left, firstR.top, firstR.left, firstR.bottom + 8f * density, barPaint)
            canvas.drawCircle(firstR.left - 4f * density, firstR.bottom + 8f * density, 8f * density, pinPaint)

            // End handle: vertical cyan bar + teardrop pin at bottom-right
            canvas.drawLine(lastR.right, lastR.top, lastR.right, lastR.bottom + 8f * density, barPaint)
            canvas.drawCircle(lastR.right + 4f * density, lastR.bottom + 8f * density, 8f * density, pinPaint)
          }

          // Sleek LiquidText-style Floating Text-Selection Toolbar matching Competitor Screenshot
          if (!isDraggingStartHandle && !isDraggingEndHandle) {
            drawCompetitorSelectionCallout(
              canvas = canvas,
              calloutR = pdfSel.calloutRect,
              commentBtn = pdfSel.calloutCommentBtn,
              excerptBtn = pdfSel.calloutExcerptBtn,
              bookmarkBtn = pdfSel.calloutBookmarkBtn,
              moreBtn = pdfSel.calloutMoreBtn,
              colorBtns = pdfSel.calloutColorBtns,
              clearBtn = pdfSel.calloutClearBtn,
              rainbowBtn = pdfSel.calloutRainbowBtn,
              tagsBtn = pdfSel.calloutTagsBtn,
              activeColor = selectedColor
            )
          }
      }
    }

      // Draw LiquidText Area / Photo Selection matching Screenshot
      val cropSel = activeCropSelection
      if (cropSel != null) {
        val cropColor = cropSel.color

        // Translucent fill with accent color (matches screenshot blue box)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = cropColor
          alpha = 70
          style = Paint.Style.FILL
        }
        canvas.drawRect(cropSel.screenRect, fillPaint)

        // Crisp solid border with accent color
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = cropColor
          strokeWidth = 2f * density
          style = Paint.Style.STROKE
        }
        canvas.drawRect(cropSel.screenRect, borderPaint)

        // All 4 Corner Circular Handles matching LiquidText
        val handleR = 8.5f * density
        val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = cropColor
          style = Paint.Style.FILL
        }
        val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.WHITE
          strokeWidth = 2.2f * density
          style = Paint.Style.STROKE
        }

        // Top-Left Circle Handle
        canvas.drawCircle(cropSel.screenRect.left, cropSel.screenRect.top, handleR, handleFill)
        canvas.drawCircle(cropSel.screenRect.left, cropSel.screenRect.top, handleR, handleBorder)

        // Top-Right Circle Handle
        canvas.drawCircle(cropSel.screenRect.right, cropSel.screenRect.top, handleR, handleFill)
        canvas.drawCircle(cropSel.screenRect.right, cropSel.screenRect.top, handleR, handleBorder)

        // Bottom-Left Circle Handle
        canvas.drawCircle(cropSel.screenRect.left, cropSel.screenRect.bottom, handleR, handleFill)
        canvas.drawCircle(cropSel.screenRect.left, cropSel.screenRect.bottom, handleR, handleBorder)

        // Bottom-Right Circle Handle
        canvas.drawCircle(cropSel.screenRect.right, cropSel.screenRect.bottom, handleR, handleFill)
        canvas.drawCircle(cropSel.screenRect.right, cropSel.screenRect.bottom, handleR, handleBorder)

        // Sleek LiquidText-style Floating Crop-Selection Toolbar matching Competitor Screenshot
        val isAdjustingHandles = isDraggingCropTopLeftHandle || isDraggingCropTopRightHandle ||
          isDraggingCropBottomLeftHandle || isDraggingCropBottomRightHandle || isMovingCropSelection
        if (!isDraggingCrop && !isAdjustingHandles) {
          drawCompetitorSelectionCallout(
            canvas = canvas,
            calloutR = cropSel.calloutRect,
            commentBtn = cropSel.calloutCommentBtn,
            excerptBtn = cropSel.calloutExcerptBtn,
            bookmarkBtn = cropSel.calloutBookmarkBtn,
            moreBtn = cropSel.calloutMoreBtn,
            colorBtns = cropSel.calloutColorBtns,
            clearBtn = cropSel.calloutClearBtn,
            rainbowBtn = cropSel.calloutRainbowBtn,
            tagsBtn = cropSel.calloutTagsBtn,
            activeColor = cropSel.color
          )
        }
      }

      // Floating Squeeze Tab on the right edge of the PDF document (§10 of spec / screenshot)
      val rightTabW = 22f * density
      val rightTabH = 34f * density
      val rightTabX = viewW - rightTabW - 6f * density
      val rightTabY = (subheaderH + docBottomY) / 2f - rightTabH / 2f
      rightSqueezeTabRect.set(rightTabX, rightTabY, rightTabX + rightTabW, rightTabY + rightTabH)

      val sqTabBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.FILL
      }
      val sqTabBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSqueezed) Color.parseColor("#00ADB5") else Color.parseColor("#334155")
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
      }
      canvas.drawRoundRect(rightSqueezeTabRect, 11f * density, 11f * density, sqTabBg)
      canvas.drawRoundRect(rightSqueezeTabRect, 11f * density, 11f * density, sqTabBorder)

      val sqTabText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSqueezed) Color.parseColor("#00ADB5") else Color.parseColor("#94A3B8")
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
      }
      canvas.drawText("≈", rightSqueezeTabRect.centerX(), rightSqueezeTabRect.centerY() + 5.5f * density, sqTabText)

      // ── Floating Native Search HUD ──────────────────────────────────────────
      if (isSearchActive && searchOverlayView == null) {
        val barTop = subheaderH + 8f * density
        val barHeight = 42f * density
        val barBottom = barTop + barHeight
        val barMargin = 12f * density
        val barLeft = barMargin
        val barRight = viewW - barMargin

        searchHudRect.set(barLeft, barTop, barRight, barBottom)

        // HUD Background: dark glassmorphic rounded card with teal border
        val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#0F172A")
          setShadowLayer(8f * density, 0f, 4f * density, Color.parseColor("#90000000"))
        }
        val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          strokeWidth = 1.2f * density
          style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(searchHudRect, 10f * density, 10f * density, hudBgPaint)
        canvas.drawRoundRect(searchHudRect, 10f * density, 10f * density, hudBorderPaint)

        // 1. Close button on far right [ ✕ ]
        val btnPad = 5f * density
        val closeBtnW = 32f * density
        val closeBtnLeft = barRight - closeBtnW - btnPad
        searchCloseBtnRect.set(closeBtnLeft, barTop + btnPad, closeBtnLeft + closeBtnW, barBottom - btnPad)
        val btnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E293B") }
        canvas.drawRoundRect(searchCloseBtnRect, 6f * density, 6f * density, btnBgPaint)
        val closeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#94A3B8")
          textSize = 13f * density
          textAlign = Paint.Align.CENTER
          isFakeBoldText = true
        }
        canvas.drawText("✕", searchCloseBtnRect.centerX(), searchCloseBtnRect.centerY() + 4.5f * density, closeTextPaint)

        // 2. Next button [ › ]
        val navBtnW = 34f * density
        val nextBtnLeft = closeBtnLeft - navBtnW - 6f * density
        searchNextBtnRect.set(nextBtnLeft, barTop + btnPad, nextBtnLeft + navBtnW, barBottom - btnPad)
        canvas.drawRoundRect(searchNextBtnRect, 6f * density, 6f * density, btnBgPaint)
        val navTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          textSize = 15f * density
          textAlign = Paint.Align.CENTER
          isFakeBoldText = true
        }
        canvas.drawText("›", searchNextBtnRect.centerX(), searchNextBtnRect.centerY() + 4.5f * density, navTextPaint)

        // 3. Prev button [ ‹ ]
        val prevBtnLeft = nextBtnLeft - navBtnW - 4f * density
        searchPrevBtnRect.set(prevBtnLeft, barTop + btnPad, prevBtnLeft + navBtnW, barBottom - btnPad)
        canvas.drawRoundRect(searchPrevBtnRect, 6f * density, 6f * density, btnBgPaint)
        canvas.drawText("‹", searchPrevBtnRect.centerX(), searchPrevBtnRect.centerY() + 4.5f * density, navTextPaint)

        // 4. Result Counter [ 1 / 10 ]
        val counterText = when {
          isSearching -> "Searching..."
          searchMatches.isEmpty() -> "0 / 0"
          else -> "${currentSearchIndex + 1} / ${searchMatches.size}"
        }
        val counterTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = if (searchMatches.isEmpty() && !isSearching) Color.parseColor("#EF4444") else Color.parseColor("#E2E8F0")
          textSize = 12.5f * density
          isFakeBoldText = true
          textAlign = Paint.Align.RIGHT
        }
        val counterRight = prevBtnLeft - 10f * density
        canvas.drawText(counterText, counterRight, barTop + 24f * density, counterTextPaint)

        // 5. Query Pill on left [ 🔍 "query" ]
        val counterWidth = counterTextPaint.measureText(counterText)
        val pillMaxRight = counterRight - counterWidth - 12f * density
        val pillLeft = barLeft + 8f * density
        val pillRight = maxOf(pillLeft + 40f * density, pillMaxRight)
        searchQueryPillRect.set(pillLeft, barTop + btnPad, pillRight, barBottom - btnPad)

        val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A2430") }
        val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          alpha = 120
          strokeWidth = 1f * density
          style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(searchQueryPillRect, 6f * density, 6f * density, pillBgPaint)
        canvas.drawRoundRect(searchQueryPillRect, 6f * density, 6f * density, pillBorderPaint)

        val queryDisplay = if (currentSearchQuery.length > 14) currentSearchQuery.substring(0, 12) + "..." else currentSearchQuery
        val queryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          textSize = 12f * density
          isFakeBoldText = true
        }
        canvas.drawText("🔍 \"$queryDisplay\"", pillLeft + 8f * density, barTop + 24f * density, queryPaint)
      }

      canvas.restore()
    }

    // =========================================================================
    // 2. RESIZABLE SPLIT DIVIDER matching Screenshot
    // =========================================================================
    if (hasDoc && docBottomY > 10f) {
      dividerLinePaint.color = Color.parseColor("#1E293B")
      dividerLinePaint.strokeWidth = 1f * density
      canvas.drawLine(0f, splitY, viewW, splitY, dividerLinePaint)

      // Center pill handle with cyan border and 3 horizontal grip lines (≡)
      val pillW = 56f * density
      val pillH = 18f * density
      val pillX = (viewW - pillW) / 2f
      val pillY = splitY - pillH / 2f
      val handleRect = RectF(pillX, pillY, pillX + pillW, pillY + pillH)

      val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.FILL
      }
      val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00ADB5")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
      }
      canvas.drawRoundRect(handleRect, pillH / 2f, pillH / 2f, pillBg)
      canvas.drawRoundRect(handleRect, pillH / 2f, pillH / 2f, pillBorder)

      // 3 horizontal cyan lines (≡)
      val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00ADB5")
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
      }
      val lineW = 20f * density
      val lineLeft = pillX + (pillW - lineW) / 2f
      val lineRight = lineLeft + lineW
      val lineCenterY = pillY + pillH / 2f
      val lineSpacing = 3.6f * density

      canvas.drawLine(lineLeft, lineCenterY - lineSpacing, lineRight, lineCenterY - lineSpacing, gripPaint)
      canvas.drawLine(lineLeft, lineCenterY, lineRight, lineCenterY, gripPaint)
      canvas.drawLine(lineLeft, lineCenterY + lineSpacing, lineRight, lineCenterY + lineSpacing, gripPaint)
    }

    // =========================================================================
    // 3. BOTTOM ZONE: Infinite Canvas Workspace (Inking, Cards, Bezier Links)
    // =========================================================================
    canvas.save()
    canvas.clipRect(0f, canvasTopY, viewW, viewH)
    canvas.drawRect(0f, canvasTopY, viewW, viewH, canvasBgPaint)

    canvas.save()
    canvas.translate(panX, canvasTopY + panY)
    canvas.scale(scaleFactor, scaleFactor)

    // Canvas Background Pattern
    val worldLeft = -panX / scaleFactor
    val worldTop = -panY / scaleFactor
    val worldRight = (viewW - panX) / scaleFactor
    val worldBottom = (viewH - canvasTopY - panY) / scaleFactor

    when (pattern) {
      "dots" -> {
        val step = 32f
        val startX = (worldLeft / step).toInt() * step
        val startY = (worldTop / step).toInt() * step
        var x = startX
        while (x < worldRight) {
          var y = startY
          while (y < worldBottom) {
            canvas.drawCircle(x, y, 1.8f, dotPaint)
            y += step
          }
          x += step
        }
      }
      "grid" -> {
        val step = 36f
        val startX = (worldLeft / step).toInt() * step
        var x = startX
        while (x < worldRight) {
          canvas.drawLine(x, worldTop, x, worldBottom, gridPaint)
          x += step
        }
        val startY = (worldTop / step).toInt() * step
        var y = startY
        while (y < worldBottom) {
          canvas.drawLine(worldLeft, y, worldRight, y, gridPaint)
          y += step
        }
      }
      "looseleaf" -> {
        val lineSpacing = 38f
        val startY = (worldTop / lineSpacing).toInt() * lineSpacing
        var y = startY
        while (y < worldBottom) {
          canvas.drawLine(worldLeft, y, worldRight, y, gridPaint)
          y += lineSpacing
        }
        // Left margin red line
        canvas.drawLine(100f, worldTop, 100f, worldBottom, Paint().apply {
          color = Color.parseColor("#3B82F6")
          strokeWidth = 1.5f
          alpha = 70
        })
      }
    }

    // ── Notebook Pages (rendered BELOW strokes and cards so they appear as a paper layer)
    drawNotebookPages(canvas)

    // Shockwave drop ripple
    if (rippleProgress < 1f) {
      val maxRadius = 140f
      val currentRadius = maxRadius * rippleProgress
      ripplePaint.color = rippleColor
      ripplePaint.alpha = ((1f - rippleProgress) * 255).toInt()
      ripplePaint.strokeWidth = (1f - rippleProgress) * 4f + 1f
      canvas.drawCircle(rippleOriginX, rippleOriginY, currentRadius, ripplePaint)
    }

    // Dynamic Bezier Ink Links to split divider removed:
    // When a user selects or clicks any card, no artificial string line is drawn across
    // the workspace, keeping the card and action toolbar clean and unobscured.
    // Source navigation is cleanly accessible via the card's source badge (↗ p. X)
    // and extraction arrow (>) without visual clutter.



    // Completed Canvas Strokes
    for (stroke in strokes) {
      if (stroke.points.isEmpty()) continue
      val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke.color
        strokeWidth = stroke.strokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (stroke.isHighlighter) {
          alpha = 115
          strokeWidth = 20f
        }
      }
      val path = Path()
      path.moveTo(stroke.points[0].x, stroke.points[0].y)
      for (i in 1 until stroke.points.size) {
        val p = stroke.points[i]
        path.lineTo(p.x, p.y)
      }
      canvas.drawPath(path, strokePaint)
    }

    // Active Drawing Stroke
    if (activePoints.isNotEmpty()) {
      val isHighlighter = activeTool == "highlighter"
      val curPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isHighlighter) selectedColor else penColor
        strokeWidth = if (isHighlighter) max(penThickness, 12f) else penThickness
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (isHighlighter) alpha = 115
      }
      canvas.drawPath(activePath, curPaint)
    }

    // Excerpt Cards
    cardJumpBtnRects.clear()
    cardExtractionArrowRects.clear()
    for (card in cards) {
      val cardH = card.getHeight()
      val cardRect = RectF(card.x, card.y, card.x + card.width, card.y + cardH)

      val isSnapTarget = card.id == magneticTargetCardId
      val isGrouped = card.groupedItems != null && card.groupedItems!!.size > 1

      // Draw stacked physical deck layers behind card if grouped
      if (isGrouped) {
        val deck2 = RectF(cardRect.left + 5f, cardRect.top + 5f, cardRect.right + 5f, cardRect.bottom + 5f)
        val deckPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#CBD5E1")
          style = Paint.Style.FILL
        }
        canvas.drawRoundRect(deck2, 10f, 10f, deckPaint2)
        canvas.drawRoundRect(deck2, 10f, 10f, cardBorderPaint)

        val deck1 = RectF(cardRect.left + 2.5f, cardRect.top + 2.5f, cardRect.right + 2.5f, cardRect.bottom + 2.5f)
        val deckPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#E2E8F0")
          style = Paint.Style.FILL
        }
        canvas.drawRoundRect(deck1, 10f, 10f, deckPaint1)
        canvas.drawRoundRect(deck1, 10f, 10f, cardBorderPaint)
      }

      // Magnetic Snap Target Highlight & Banner
      if (isSnapTarget) {
        val snapGlow = RectF(cardRect.left - 6f, cardRect.top - 6f, cardRect.right + 6f, cardRect.bottom + 6f)
        val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          strokeWidth = 3f * density
          style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(snapGlow, 14f, 14f, snapPaint)

        val snapBanner = RectF(cardRect.left, cardRect.top - 24f * density, cardRect.right, cardRect.top - 4f * density)
        val bannerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#0F172A")
          style = Paint.Style.FILL
        }
        canvas.drawRoundRect(snapBanner, 6f * density, 6f * density, bannerBg)
        canvas.drawRoundRect(snapBanner, 6f * density, 6f * density, snapPaint)
        val bannerText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#00ADB5")
          textSize = 11f * density
          isFakeBoldText = true
        }
        canvas.drawText("🎯 SNAP & STACK ON CARD", snapBanner.left + 8f * density, snapBanner.centerY() + 4f * density, bannerText)
      }

      // Selected Glow
      if (card.id == selectedCardId && !isSnapTarget) {
        val glowRect = RectF(cardRect.left - 4f, cardRect.top - 4f, cardRect.right + 4f, cardRect.bottom + 4f)
        canvas.drawRoundRect(glowRect, 14f, 14f, cardSelectedGlowPaint)
      }

      // Card Background & Border
      val isCardEditing = card.id == editingCardId
      canvas.drawRoundRect(cardRect, 10f, 10f, cardBgPaint)
      cardBorderPaint.color = if (isCardEditing) Color.parseColor("#2563EB") else if (card.id == selectedCardId) Color.parseColor("#2563EB") else if (isSnapTarget) card.color else Color.parseColor("#CBD5E1")
      cardBorderPaint.strokeWidth = if (isCardEditing) 3.2f else if (card.id == selectedCardId) 2.2f else 1.2f
      canvas.drawRoundRect(cardRect, 10f, 10f, cardBorderPaint)

      if (isCardEditing) {
        // LiquidText fold / resize triangle handle on right edge (Matching Screenshots 1 & 2)
        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#CBD5E1")
          style = Paint.Style.FILL
        }
        val midY = cardRect.centerY()
        val handleP = Path().apply {
          moveTo(cardRect.right - 2f, midY - 7f)
          lineTo(cardRect.right + 7f, midY)
          lineTo(cardRect.right - 2f, midY + 7f)
          close()
        }
        canvas.drawPath(handleP, handlePaint)
      } else if (card.id == selectedCardId) {
        // LiquidText selection corner resize indicator on bottom-right (matching Screenshot 1 & 2)
        val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#64748B")
          strokeWidth = 2.4f
          style = Paint.Style.STROKE
          strokeCap = Paint.Cap.ROUND
        }
        val brX = cardRect.right + 3f
        val brY = cardRect.bottom + 3f
        val brLen = 12f
        canvas.drawLine(brX - brLen, brY, brX, brY, bracketPaint)
        canvas.drawLine(brX, brY - brLen, brX, brY, bracketPaint)
      }

      // Left Accent Strip
      cardAccentPaint.color = card.color
      val accentBar = RectF(cardRect.left, cardRect.top + 6f, cardRect.left + 5f, cardRect.bottom - 6f)
      canvas.drawRoundRect(accentBar, 2f, 2f, cardAccentPaint)

      // Card Header: Page Badge & Grouped Count Badge
      val pageText = if (isGrouped) MagneticStackingEngine.formatStackedPages(card) else "p. ${card.pageNumber}"
      val pageBadgeW = badgeTextPaint.measureText(pageText) + 16f
      val badgeRect = RectF(cardRect.left + 14f, cardRect.top + 10f, cardRect.left + 14f + pageBadgeW, cardRect.top + 30f)
      canvas.drawRoundRect(badgeRect, 5f, 5f, badgeBgPaint)
      canvas.drawRoundRect(badgeRect, 5f, 5f, badgeBorderPaint)
      canvas.drawText(pageText, cardRect.left + 20f, cardRect.top + 25f, badgeTextPaint)

      if (isGrouped) {
        val groupCountText = "${card.groupedItems!!.size} grouped"
        val groupBadgeW = badgeTextPaint.measureText(groupCountText) + 16f
        val groupBadgeRect = RectF(badgeRect.right + 8f, cardRect.top + 10f, badgeRect.right + 8f + groupBadgeW, cardRect.top + 30f)
        val groupBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8B5CF6"); style = Paint.Style.FILL }
        canvas.drawRoundRect(groupBadgeRect, 5f, 5f, groupBg)
        val groupTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.WHITE
          textSize = 11f
          isFakeBoldText = true
        }
        canvas.drawText(groupCountText, groupBadgeRect.left + 8f, cardRect.top + 24f, groupTextPaint)
      }

      // ── Multi-Document Source Badge ─────────────────────────────────────────
      // Shows the source document name + page, color-coded to the doc's accent color.
      // Tapping it triggers a source jump (or document switch if needed).
      val cardDocId = card.documentId.takeIf { it.isNotEmpty() } ?: activeDocumentId
      val docEntry = workspaceDocumentEntries.find { it.id == cardDocId }
      val docAccentColor = getDocumentAccentColor(cardDocId)
      val docShortTitle = docEntry?.title?.let { t ->
        if (t.length > 12) t.substring(0, 11) + "…" else t
      } ?: ""
      val jumpText = if (docShortTitle.isNotEmpty() && workspaceDocumentEntries.size > 1) {
        "📄 $docShortTitle · p.${card.pageNumber}"
      } else {
        "↗ p.${card.pageNumber}"
      }
      val jumpTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f
        isFakeBoldText = true
      }
      val jumpTextW = jumpTextPaint.measureText(jumpText)
      val jumpBtnW = jumpTextW + 16f
      val jumpBtnRight = if (card.id == selectedCardId) cardRect.right - 36f else cardRect.right - 10f
      val jumpBtnRect = RectF(jumpBtnRight - jumpBtnW, cardRect.top + 9f, jumpBtnRight, cardRect.top + 31f)

      val jumpBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = docAccentColor  // Use document accent color instead of card color
        style = Paint.Style.FILL
      }
      canvas.drawRoundRect(jumpBtnRect, 11f, 11f, jumpBgPaint)
      canvas.drawText(jumpText, jumpBtnRect.left + 8f, jumpBtnRect.centerY() + 3.5f, jumpTextPaint)

      // Store world hit rect — tap detection uses this in onTouchEvent
      cardJumpBtnRects[card.id] = RectF(jumpBtnRect)

      // ── Card Original Extraction Source / Pen InkLink `▶` Arrow Affordance ───
      // Every card with a PDF source or pen connection displays a sleek right-pointing
      // arrow affordance on its right edge matching the LiquidText reference video.
      val cardPenLink = semanticInkLinks.findLast { it.targetCardId == card.id }
      val hasPdfSource = card.pageNumber > 0 || card.sourceRects.isNotEmpty() || card.documentId.isNotEmpty() || cardPenLink != null
      if (hasPdfSource) {
        val arrowH = 16f * density
        val arrowW = 8.5f * density
        val arrowY = if (cardPenLink?.targetCardPoint != null) {
          (cardRect.top + cardPenLink.targetCardPoint.y).coerceIn(cardRect.top + 16f * density, cardRect.bottom - 16f * density)
        } else {
          cardRect.centerY()
        }

        // Sleek right-pointing triangle attached directly to the card's right border
        val arrowPath = Path().apply {
          moveTo(cardRect.right, arrowY - arrowH / 2f)
          lineTo(cardRect.right + arrowW, arrowY)
          lineTo(cardRect.right, arrowY + arrowH / 2f)
          close()
        }

        val arrowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#E2E8F0") // Clean off-white matching reference video
          style = Paint.Style.FILL
        }
        canvas.drawPath(arrowPath, arrowBgPaint)

        val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#94A3B8") // Subtle outline
          strokeWidth = 1f * density
          style = Paint.Style.STROKE
          strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(arrowPath, arrowStrokePaint)

        // Hit rect with comfortable touch margin for effortless tapping
        cardExtractionArrowRects[card.id] = RectF(
          cardRect.right - 10f * density,
          arrowY - 18f * density,
          cardRect.right + 24f * density,
          arrowY + 18f * density
        )
      }


      // Delete Button if selected
      if (card.id == selectedCardId) {
        canvas.drawText("✕", cardRect.right - 22f, cardRect.top + 25f, closeBtnTextPaint)
      }

        // Bird's-eye view label: floating text outside the card when zoomed out,
        // always readable via: card accent color + white outline stroke (works on any bg).
        val birdEyeThreshold = 0.75f
        if (scaleFactor < birdEyeThreshold && !card.isImage && !card.isTable) {
          val t = (birdEyeThreshold - scaleFactor) / birdEyeThreshold // 0..1 as more zoomed out
          val textAlpha = (t * 230f).toInt().coerceIn(80, 230)
          // Screen-space font size (divides out the canvas scale transform for fixed screen size)
          val screenTextSize = 12f / scaleFactor.coerceAtLeast(0.05f)

          canvas.save()
          // Rotate label diagonally, anchored to card top-left area
          val labelAnchorX = cardRect.left
          val labelAnchorY = cardRect.top - screenTextSize * 0.5f
          canvas.rotate(-20f, labelAnchorX, labelAnchorY)

          val snippetText = if (card.text.length > 60) card.text.substring(0, 57) + "..." else card.text
          val labelWidth = (cardRect.width() * 2.2f).toInt().coerceAtLeast(200)

          // Step 1: White outline/stroke pass (so text pops on both white card AND dark bg)
          val outlinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            alpha = (textAlpha * 0.85f).toInt()
            textSize = screenTextSize
            isFakeBoldText = true
            style = Paint.Style.STROKE
            strokeWidth = screenTextSize * 0.18f
            strokeJoin = Paint.Join.ROUND
          }
          // Step 2: Colored fill pass using card's accent color
          val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = card.color
            alpha = textAlpha
            textSize = screenTextSize
            isFakeBoldText = true
            style = Paint.Style.FILL
          }

          canvas.translate(labelAnchorX, labelAnchorY - screenTextSize)

          val outlineLayout = android.text.StaticLayout.Builder
            .obtain(snippetText, 0, snippetText.length, outlinePaint, labelWidth)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()
          outlineLayout.draw(canvas)

          val fillLayout = android.text.StaticLayout.Builder
            .obtain(snippetText, 0, snippetText.length, fillPaint, labelWidth)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()
          fillLayout.draw(canvas)

          canvas.restore()
        }


        // Card Body: Image, Table, or Text Quote
        if (card.isImage) {
          var imgBmp = if (!card.imageUrl.isNullOrEmpty()) cardBitmapCache.get(card.imageUrl) else null
          if (imgBmp == null) {
            imgBmp = cardBitmapCache.get(card.id)
          }
          if (imgBmp == null) {
            imgBmp = cardBitmapCache.get("page_${card.pageNumber}_image")
          }
          if (imgBmp == null && !card.imageUrl.isNullOrEmpty()) {
            val imgFile = File(card.imageUrl)
            if (imgFile.exists()) {
              imgBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
              if (imgBmp != null) {
                cardBitmapCache.put(card.imageUrl, imgBmp)
                cardBitmapCache.put(card.id, imgBmp)
              }
            }
          }
          if (imgBmp == null) {
            imgBmp = generateCropBitmap(max(0, card.pageNumber - 1), BoundingBox(0f, 0f, 400f, 260f))
            if (imgBmp != null) {
              cardBitmapCache.put(card.id, imgBmp)
              cardBitmapCache.put("page_${card.pageNumber}_image", imgBmp)
            }
          }
          if (imgBmp != null) {
            val imgRect = RectF(cardRect.left + 12f, cardRect.top + 36f, cardRect.right - 12f, cardRect.bottom - 12f)
            canvas.drawBitmap(imgBmp, null, imgRect, null)
          } else {
            val phRect = RectF(cardRect.left + 12f, cardRect.top + 36f, cardRect.right - 12f, cardRect.bottom - 12f)
            val phPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#1E293B")
              style = Paint.Style.FILL
            }
            canvas.drawRoundRect(phRect, 6f, 6f, phPaint)
            canvas.drawText("📷 Figure (Page ${card.pageNumber})", cardRect.left + 20f, cardRect.top + 70f, textPaint)
          }
        } else {
          val cardTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = card.textColor
            textSize = (card.fontSize * 1.8f).coerceAtLeast(18f)
            if (card.isBold && card.isItalic) {
              typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            } else if (card.isBold) {
              typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else if (card.isItalic) {
              typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            } else {
              typeface = Typeface.DEFAULT
            }
            isUnderlineText = card.isUnderline
            isStrikeThruText = card.isStrikethrough
          }

          val preview = card.text
          val textW = Math.max(20, (card.width - 28f).toInt())
          val textToDraw = if (preview.isEmpty() && card.id == editingCardId) "Type text here..." else preview
          val layout = android.text.StaticLayout.Builder
            .obtain(textToDraw, 0, textToDraw.length, cardTextPaint, textW)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .build()

          canvas.save()
          canvas.translate(card.x + 14f, card.y + 36f)
          layout.draw(canvas)

          // If this card is actively being edited, draw the blinking cursor
          if (card.id == editingCardId && layout.lineCount > 0) {
            val now = System.currentTimeMillis()
            if (now - lastCursorBlinkTime > 500) {
              isCursorBlinkVisible = !isCursorBlinkVisible
              lastCursorBlinkTime = now
            }
            if (isCursorBlinkVisible) {
              val cPos = cursorPosition.coerceIn(0, preview.length)
              val line = layout.getLineForOffset(cPos)
              val lineTop = layout.getLineTop(line).toFloat()
              val lineBottom = layout.getLineBottom(line).toFloat()
              val curX = layout.getPrimaryHorizontal(cPos)
              val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2563EB")
                strokeWidth = 2.5f
                style = Paint.Style.STROKE
              }
              canvas.drawLine(curX, lineTop, curX, lineBottom, cursorPaint)
            }
            postInvalidateOnAnimation()
          }

          canvas.restore()
        }
      }

      canvas.restore() // Undo world transform

      // -------------------------------------------------------------------------
      // 4. FLOATING NATIVE CANVAS TOOLBAR (Only shown when drawing tool active)
      // -------------------------------------------------------------------------
      if (showCanvasToolbar && activeTool != "select") {
        val tbH = 48f
        val tbW = min(viewW - 32f, 440f)
        val tbX = (viewW - tbW) / 2f
        val tbY = viewH - tbH - 16f
        canvasToolbarRect.set(tbX, tbY, tbX + tbW, tbY + tbH)

        canvas.drawRoundRect(canvasToolbarRect, 16f, 16f, toolbarBgPaint)
        canvas.drawRoundRect(canvasToolbarRect, 16f, 16f, toolbarBorderPaint)

        toolBtnRects.clear()
        colorBtnRects.clear()

        val tools = listOf("select", "pen", "highlighter", "eraser")
        val toolIcons = listOf("👆", "✏️", "🖍️", "🧹")
        var curBtnX = tbX + 12f

        for (i in tools.indices) {
          val tId = tools[i]
          val btnR = RectF(curBtnX, tbY + 6f, curBtnX + 36f, tbY + tbH - 6f)
          toolBtnRects[tId] = btnR

          if (activeTool == tId) {
            val activeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#334155")
              style = Paint.Style.FILL
            }
            canvas.drawRoundRect(btnR, 8f, 8f, activeBg)
          }

          val iconPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f }
          canvas.drawText(toolIcons[i], btnR.left + 8f, btnR.centerY() + 6f, iconPaint)
          curBtnX += 42f
        }

        // Divider in toolbar
        canvas.drawLine(curBtnX + 2f, tbY + 10f, curBtnX + 2f, tbY + tbH - 10f, dividerBorderPaint)
        curBtnX += 12f

        // Color Swatches
        for (col in contextColors) {
          val cRect = RectF(curBtnX, tbY + 12f, curBtnX + 24f, tbY + tbH - 12f)
          colorBtnRects[col] = cRect

          val cPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }
          canvas.drawCircle(cRect.centerX(), cRect.centerY(), 11f, cPaint)

          if (selectedColor == col) {
            val selRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.WHITE
              strokeWidth = 2.5f
              style = Paint.Style.STROKE
            }
            canvas.drawCircle(cRect.centerX(), cRect.centerY(), 13.5f, selRing)
          }
          curBtnX += 30f
        }

        // Reset Canvas view button on far right
        resetCanvasBtnRect.set(tbX + tbW - 40f, tbY + 6f, tbX + tbW - 8f, tbY + tbH - 6f)
        val resetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f }
        canvas.drawText("🎯", resetCanvasBtnRect.left + 8f, resetCanvasBtnRect.centerY() + 6f, resetPaint)
      }

      canvas.restore() // Clip canvas rect

      // =========================================================================
      // 4B. PERSISTENT SEMANTIC INKLINKS (Clean Pen Stroke Connections - LiquidText)
      // =========================================================================
      inkLinkPdfAnchorScreenRects.clear()
      inkLinkCardAnchorScreenRects.clear()

      val linkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
      }
      val linkDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
      }

      for (link in semanticInkLinks) {
        val card = cards.find { it.id == link.targetCardId } ?: continue

        // 1. Compute PDF source in screen coordinates
        var srcSx = viewW / 2f
        var srcSy = if (effectiveSplitRatio > 0f) height * effectiveSplitRatio - 14f else height.toFloat()
        var isSourceVisible = false

        val isCurrentDoc = link.sourceDocId.isEmpty() || activeDocumentId.isEmpty() ||
          link.sourceDocId == activeDocumentId || link.sourceDocId == "default-doc" || activeDocumentId == "default-doc"

        if (isCurrentDoc) {
          val pl = pageLayouts.find { it.pageIndex == link.sourcePageIndex }
          if (pl != null) {
            val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
              ?: com.thinkspace.pdfengine.model.PageSize.LETTER
            val rawSx = pl.boundsOnScreen.left + (link.sourcePdfPoint.x / pSize.width) * pl.boundsOnScreen.width()
            val rawSy = pl.boundsOnScreen.top + (link.sourcePdfPoint.y / pSize.height) * pl.boundsOnScreen.height()

            val docBottom = if (effectiveSplitRatio > 0f) height * effectiveSplitRatio - 14f else height.toFloat()
            // In LiquidText: Line is drawn if the source text is within the visible document viewport
            if (rawSy >= subheaderH - 30f && rawSy <= docBottom + 30f) {
              srcSx = rawSx
              srcSy = rawSy
              isSourceVisible = true
            }
          }
        }

        // 2. Compute Card anchor position in screen coordinates from normalized anchors
        val targetWx = card.x + (link.cardAnchorX * card.width)
        val targetWy = card.y + (link.cardAnchorY * card.getHeight())

        val cardTargetSx = targetWx * scaleFactor + panX
        val cardTargetSy = canvasTopY + targetWy * scaleFactor + panY

        // 3. Render PDF Source Soft Highlight Pill & Solid Connecting Pen Line
        val linkLineColor = link.color
        val sWidth = (link.strokeWidth * density).coerceAtLeast(3f * density)

        if (isSourceVisible) {
          // Draw PDF source soft highlight pill over the text in PDF
          if (link.sourcePdfRect.width() > 0) {
            val pl = pageLayouts.find { it.pageIndex == link.sourcePageIndex }
            if (pl != null) {
              val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
                ?: com.thinkspace.pdfengine.model.PageSize.LETTER
              val hlLeft = pl.boundsOnScreen.left + (link.sourcePdfRect.left / pSize.width) * pl.boundsOnScreen.width()
              val hlTop = pl.boundsOnScreen.top + (link.sourcePdfRect.top / pSize.height) * pl.boundsOnScreen.height()
              val hlRight = pl.boundsOnScreen.left + (link.sourcePdfRect.right / pSize.width) * pl.boundsOnScreen.width()
              val hlBottom = pl.boundsOnScreen.top + (link.sourcePdfRect.bottom / pSize.height) * pl.boundsOnScreen.height()

              val pdfHlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = linkLineColor
                alpha = 115
                style = Paint.Style.FILL
              }
              val hlRect = RectF(hlLeft - 3f * density, hlTop - 1.5f * density, hlRight + 3f * density, hlBottom + 1.5f * density)
              canvas.drawRoundRect(hlRect, 4f * density, 4f * density, pdfHlPaint)
            }
          }

          // Connecting solid pen line
          linkStrokePaint.color = linkLineColor
          linkStrokePaint.strokeWidth = sWidth
          canvas.drawLine(srcSx, srcSy, cardTargetSx, cardTargetSy, linkStrokePaint)

          // Smooth rounded endpoint at PDF
          linkDotPaint.color = linkLineColor
          canvas.drawCircle(srcSx, srcSy, 5f * density, linkDotPaint)
        }

        // 4. LiquidText-style Circular V Link Marker Badge at the Card Endpoint
        // Scaled dynamically with workspace camera so it stays proportional to card content
        val badgeRadius = (13f * density).coerceAtLeast(18f)
        val vStrokeW = (2.8f * density).coerceAtLeast(3.2f)

        // Drop shadow for crisp elevation
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.argb(70, 0, 0, 0)
          style = Paint.Style.FILL
        }
        canvas.drawCircle(cardTargetSx, cardTargetSy + 1.5f * density, badgeRadius, shadowPaint)

        // Circular badge background matching the link's customized color
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = linkLineColor
          style = Paint.Style.FILL
        }
        canvas.drawCircle(cardTargetSx, cardTargetSy, badgeRadius, badgeBgPaint)

        // Dynamic luminance-based contrast for border and V shape
        val lum = (0.299 * Color.red(linkLineColor) + 0.587 * Color.green(linkLineColor) + 0.114 * Color.blue(linkLineColor)) / 255.0
        val isLight = lum > 0.55

        val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = if (isLight) Color.argb(120, 0, 0, 0) else Color.WHITE
          strokeWidth = 1.6f * density
          style = Paint.Style.STROKE
        }
        canvas.drawCircle(cardTargetSx, cardTargetSy, badgeRadius, badgeBorderPaint)

        // Distinct, bold capital "V" inside the circular badge
        val vSpan = badgeRadius * 0.58f
        val vPath = Path().apply {
          moveTo(cardTargetSx - vSpan * 0.72f, cardTargetSy - vSpan * 0.65f)
          lineTo(cardTargetSx, cardTargetSy + vSpan * 0.70f)
          lineTo(cardTargetSx + vSpan * 0.72f, cardTargetSy - vSpan * 0.65f)
        }

        val vPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = if (isLight) Color.parseColor("#0F172A") else Color.WHITE
          strokeWidth = vStrokeW
          style = Paint.Style.STROKE
          strokeCap = Paint.Cap.ROUND
          strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(vPath, vPaint)

        // Store hit rects for bidirectional tap navigation
        if (isSourceVisible) {
          inkLinkPdfAnchorScreenRects[link.id] = RectF(
            srcSx - 24f * density, srcSy - 24f * density,
            srcSx + 24f * density, srcSy + 24f * density
          )
        }
        inkLinkCardAnchorScreenRects[link.id] = RectF(
          cardTargetSx - 26f * density, cardTargetSy - 26f * density,
          cardTargetSx + 26f * density, cardTargetSy + 26f * density
        )
      }

      // Draw V Link Marker Tap Ripple if active
      activeVLinkRipple?.let { r ->
        val elapsed = System.currentTimeMillis() - r.timestamp
        if (elapsed < 320) {
          val prog = elapsed / 320f
          val radius = (10f + prog * 18f) * density
          val alpha = ((1f - prog) * 140).toInt().coerceIn(0, 255)
          val rPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = r.color
            this.alpha = alpha
            style = Paint.Style.FILL
          }
          canvas.drawCircle(r.x, r.y, radius, rPaint)
          postInvalidateOnAnimation()
        } else {
          activeVLinkRipple = null
        }
      }

      // Live preview during cross-zone drawing (from PDF across divider into Canvas Card)
      if (isDrawingCrossZoneLink) {
        val pl = pageLayouts.find { it.pageIndex == inkLinkSourcePageIndex }
        val startSx = if (pl != null) {
          val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
            ?: com.thinkspace.pdfengine.model.PageSize.LETTER
          pl.boundsOnScreen.left + (inkLinkSourcePdfPoint.x / pSize.width) * pl.boundsOnScreen.width()
        } else inkLinkSourceScreenStart.x

        val startSy = if (pl != null) {
          val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
            ?: com.thinkspace.pdfengine.model.PageSize.LETTER
          pl.boundsOnScreen.top + (inkLinkSourcePdfPoint.y / pSize.height) * pl.boundsOnScreen.height()
        } else inkLinkSourceScreenStart.y

        // Draw soft source highlight over the text in the PDF during dragging
        if (pl != null && inkLinkSourcePdfRect.width() > 0f) {
          val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
            ?: com.thinkspace.pdfengine.model.PageSize.LETTER
          val hlLeft = pl.boundsOnScreen.left + (inkLinkSourcePdfRect.left / pSize.width) * pl.boundsOnScreen.width()
          val hlTop = pl.boundsOnScreen.top + (inkLinkSourcePdfRect.top / pSize.height) * pl.boundsOnScreen.height()
          val hlRight = pl.boundsOnScreen.left + (inkLinkSourcePdfRect.right / pSize.width) * pl.boundsOnScreen.width()
          val hlBottom = pl.boundsOnScreen.top + (inkLinkSourcePdfRect.bottom / pSize.height) * pl.boundsOnScreen.height()
          val liveHlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = penColor
            alpha = 100
            style = Paint.Style.FILL
          }
          canvas.drawRoundRect(RectF(hlLeft - 3f * density, hlTop - 1.5f * density, hlRight + 3f * density, hlBottom + 1.5f * density), 4f * density, 4f * density, liveHlPaint)
        }

        val curSx = inkLinkCurrentScreenTouch.x
        val curSy = inkLinkCurrentScreenTouch.y

        val (curWx, curWy) = canvasScreenToWorld(curSx, curSy, canvasTopY)
        val worldMargin = (32f * density) / scaleFactor
        val hoverCard = cards.find {
          curWx >= it.x - worldMargin && curWx <= it.x + it.width + worldMargin &&
          curWy >= it.y - worldMargin && curWy <= it.y + it.getHeight() + worldMargin
        }

        inkLinkTargetCardId = hoverCard?.id


        val liveColor = penColor
        val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = liveColor
          strokeWidth = (penThickness * density).coerceAtLeast(3.2f * density)
          style = Paint.Style.STROKE
          strokeCap = Paint.Cap.ROUND
          strokeJoin = Paint.Join.ROUND
        }

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = liveColor
          style = Paint.Style.FILL
        }
        canvas.drawCircle(startSx, startSy, 5f * density, dotPaint)

        if (hoverCard != null) {
          // Snap connection endpoint to exact Card attachment point (clamped to card bounds)
          val cardPointX = (curWx - hoverCard.x).coerceIn(0f, hoverCard.width)
          val cardPointY = (curWy - hoverCard.y).coerceIn(0f, hoverCard.getHeight())
          val anchorSx = (hoverCard.x + cardPointX) * scaleFactor + panX
          val anchorSy = canvasTopY + (hoverCard.y + cardPointY) * scaleFactor + panY

          // Draw dynamic connection line directly to Card attachment point
          canvas.drawLine(startSx, startSy, anchorSx, anchorSy, livePaint)

          // Preview Circular V Marker at the attachment point (matching penenginev.png panel 3)
          val previewRadius = (13f * density).coerceAtLeast(18f)
          val previewHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = liveColor
            alpha = 70
            style = Paint.Style.FILL
          }
          canvas.drawCircle(anchorSx, anchorSy, previewRadius + 4f * density, previewHalo)

          val previewBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = liveColor
            style = Paint.Style.FILL
          }
          canvas.drawCircle(anchorSx, anchorSy, previewRadius, previewBg)

          val liveLum = (0.299 * Color.red(liveColor) + 0.587 * Color.green(liveColor) + 0.114 * Color.blue(liveColor)) / 255.0
          val isLiveLight = liveLum > 0.55

          val previewBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLiveLight) Color.argb(120, 0, 0, 0) else Color.WHITE
            strokeWidth = 1.6f * density
            style = Paint.Style.STROKE
          }
          canvas.drawCircle(anchorSx, anchorSy, previewRadius, previewBorder)

          val pvSpan = previewRadius * 0.58f
          val pvPath = Path().apply {
            moveTo(anchorSx - pvSpan * 0.72f, anchorSy - pvSpan * 0.65f)
            lineTo(anchorSx, anchorSy + pvSpan * 0.70f)
            lineTo(anchorSx + pvSpan * 0.72f, anchorSy - pvSpan * 0.65f)
          }
          val pvPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLiveLight) Color.parseColor("#0F172A") else Color.WHITE
            strokeWidth = (2.8f * density).coerceAtLeast(3.2f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
          }
          canvas.drawPath(pvPath, pvPaint)
        } else {
          // Dynamic live connection line toward finger
          canvas.drawLine(startSx, startSy, curSx, curSy, livePaint)
          canvas.drawCircle(curSx, curSy, (penThickness * density).coerceAtLeast(3.2f * density) / 2f, dotPaint)
        }
      }

      // =========================================================================
      // 5. FLOATING 3D CROSS-ZONE LIFT-AND-DRAG CARD (LiquidText interaction)
      // =========================================================================
      if (isLiftingExcerpt && liftCandidateText != null) {
        val isOverCanvas = liftGhostY >= canvasTopY - 20f
        val isSnapping = magneticTargetCardId != null
        val themeColor = when {
          isSnapping -> Color.parseColor("#00ADB5")
          isOverCanvas -> Color.parseColor("#10B981")
          else -> Color.parseColor("#00ADB5")
        }

        // Live Cubic Bezier Spline matching Video (§8 of spec)
        val startX = if (liftAnchorScreenX > 0f) liftAnchorScreenX else 40f
        val startY = liftAnchorScreenY
        inkLinkRenderer.drawTether(
          canvas = canvas,
          startX = startX,
          startY = startY,
          endX = liftGhostX,
          endY = liftGhostY,
          color = themeColor,
          isHeld = true
        )

        // -------------------------------------------------------------
        // Calculate Ghost Card Size
        // -------------------------------------------------------------
        val (ghostW, ghostH, textLayout) = if (liftCandidateIsImage) {
          Triple(250f, 155f, null)
        } else {
          val previewPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1E293B")
            textSize = 15f * density
            typeface = android.graphics.Typeface.SERIF
          }
          val layout = android.text.StaticLayout.Builder
            .obtain(liftCandidateText!!, 0, liftCandidateText!!.length, previewPaint, (220f * density).toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .build()
          
          val bW = (layout.width / density) + 32f
          val bH = (layout.height / density) + 80f // 80f for header/footer padding
          Triple(Math.max(220f, bW), Math.max(120f, bH), layout)
        }

        val ghostRect = android.graphics.RectF(-ghostW / 2f, -ghostH / 2f, ghostW / 2f, ghostH / 2f)

        canvas.save()
        canvas.translate(liftGhostX, liftGhostY)
        canvas.rotate(-2f)
        canvas.scale(1.06f, 1.06f)

        // For text excerpts, draw the LiquidText yellowish paper background!
        if (!liftCandidateIsImage) {
          val paperBg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#FEF3C7"); style = android.graphics.Paint.Style.FILL }
          canvas.drawRoundRect(ghostRect, 12f, 12f, paperBg)
        } else {
          canvas.drawRoundRect(ghostRect, 12f, 12f, cardBgPaint)
        }

        cardBorderPaint.color = themeColor
        cardBorderPaint.strokeWidth = 2.5f * density
        canvas.drawRoundRect(ghostRect, 12f, 12f, cardBorderPaint)

        cardAccentPaint.color = themeColor
        val leftBar = android.graphics.RectF(ghostRect.left, ghostRect.top + 8f, ghostRect.left + 5f, ghostRect.bottom - 8f)
        canvas.drawRoundRect(leftBar, 2f, 2f, cardAccentPaint)

        val headerPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
          color = themeColor
          textSize = 14f
          isFakeBoldText = true
        }
        val subheaderPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
          color = android.graphics.Color.parseColor("#64748B")
          textSize = 11f
        }

        val headerText = when {
          isSnapping -> "🎯 SNAP & STACK ON CARD"
          isOverCanvas -> if (liftCandidateIsImage) "🎯 RELEASE TO DROP FIGURE" else "🎯 RELEASE TO DROP ON CANVAS"
          else -> if (liftCandidateIsImage) "📸 DRAGGING FIGURE" else "✨ DRAGGING EXCERPT"
        }
        val subheaderText = when {
          isSnapping -> "Release thumb to group into stacked pile (+1)"
          isOverCanvas -> "Drop right here on workspace"
          else -> if (liftCandidateIsImage) "Extracted Figure - Drag thumb into workspace canvas" else "Drag thumb into workspace canvas"
        }

        canvas.drawText(headerText, ghostRect.left + 16f, ghostRect.top + 22f, headerPaint)
        canvas.drawText(subheaderText, ghostRect.left + 16f, ghostRect.top + 36f, subheaderPaint)

        if (liftCandidateIsImage && liftCandidateBitmap != null) {
          val imgR = android.graphics.RectF(ghostRect.left + 12f, ghostRect.top + 42f, ghostRect.right - 12f, ghostRect.bottom - 26f)
          canvas.drawBitmap(liftCandidateBitmap!!, null, imgR, null)

          val badgeRect = android.graphics.RectF(ghostRect.left + 14f, ghostRect.bottom - 24f, ghostRect.left + 94f, ghostRect.bottom - 6f)
          canvas.drawRoundRect(badgeRect, 4f, 4f, badgeBgPaint)
          canvas.drawRoundRect(badgeRect, 4f, 4f, badgeBorderPaint)
          canvas.drawText("🔗 Page $liftCandidatePage", ghostRect.left + 20f, ghostRect.bottom - 10f, badgeTextPaint)
        } else if (textLayout != null) {
          // Draw yellow highlight behind text
          val hlPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#FDE047"); style = android.graphics.Paint.Style.FILL; alpha = 180 }
          val hlRect = android.graphics.RectF(ghostRect.left + 12f, ghostRect.top + 46f, ghostRect.right - 12f, ghostRect.bottom - 28f)
          canvas.drawRoundRect(hlRect, 6f, 6f, hlPaint)
          
          canvas.save()
          canvas.translate(ghostRect.left + 16f, ghostRect.top + 50f)
          canvas.scale(1f / density, 1f / density) // Scale layout back down to match canvas density
          textLayout.draw(canvas)
          canvas.restore()

          val badgeRect = android.graphics.RectF(ghostRect.left + 16f, ghostRect.bottom - 24f, ghostRect.left + 94f, ghostRect.bottom - 6f)
          canvas.drawRoundRect(badgeRect, 5f, 5f, badgeBgPaint)
          canvas.drawRoundRect(badgeRect, 5f, 5f, badgeBorderPaint)
          canvas.drawText("🔗 Page $liftCandidatePage", ghostRect.left + 22f, ghostRect.bottom - 10f, badgeTextPaint)
        }

        canvas.restore()
      }

      // -------------------------------------------------------------------------
      // 6. LIQUIDTEXT EXCERPT CARD ACTION BAR, TYPOGRAPHY BAR & STYLE POPOVER
      // -------------------------------------------------------------------------
      val selCard = if (selectedCardId != null && !isLiftingExcerpt) cards.find { it.id == selectedCardId } else null
      if (selCard != null) {
        lastSelectedCardForAnim = selCard
      }

      val targetAnim = if (selCard != null) 1f else 0f
      if (cardActionBarAnimProgress != targetAnim) {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastActionBarAnimTime == 0L) 0.016f else ((now - lastActionBarAnimTime) / 1000f).coerceIn(0.001f, 0.05f)
        lastActionBarAnimTime = now
        val animSpeed = 14f // snappy Apple spring
        cardActionBarAnimProgress = if (targetAnim > cardActionBarAnimProgress) {
          (cardActionBarAnimProgress + animSpeed * dt).coerceAtMost(1f)
        } else {
          (cardActionBarAnimProgress - animSpeed * dt).coerceAtLeast(0f)
        }
        postInvalidateOnAnimation()
      } else {
        lastActionBarAnimTime = android.os.SystemClock.uptimeMillis()
      }

      val activeCardToDraw = selCard ?: (if (cardActionBarAnimProgress > 0.01f) lastSelectedCardForAnim else null)
      if (activeCardToDraw != null && cardActionBarAnimProgress > 0.01f) {
        canvas.save()
        val animScale = 0.92f + 0.08f * cardActionBarAnimProgress
        val animTransY = (1f - cardActionBarAnimProgress) * 8f * density
        val currentCenter = if (isTypographyBarVisible && !typographyBarRect.isEmpty) {
          Pair(typographyBarRect.centerX(), typographyBarRect.centerY())
        } else if (!cardActionBarRect.isEmpty) {
          Pair(cardActionBarRect.centerX(), cardActionBarRect.centerY())
        } else {
          Pair(viewW / 2f, viewH / 2f)
        }
        canvas.translate(0f, animTransY)
        canvas.scale(animScale, animScale, currentCenter.first, currentCenter.second)

        if (isTypographyBarVisible) {
          drawTypographyBar(canvas, activeCardToDraw, viewW, viewH, canvasTopY)
          if (isStyleSheetOpen) {
            drawStyleSheetPopover(canvas, activeCardToDraw, viewW, viewH)
          }
          if (isTypoTextColorPaletteOpen) {
            drawTypoTextColorPalette(canvas, activeCardToDraw)
          }
        } else {
          drawCardActionBar(canvas, activeCardToDraw, viewW, viewH, canvasTopY)
        }
        if (isCardColorPaletteOpen) {
          drawCardColorPalette(canvas, activeCardToDraw)
        }
        canvas.restore()
      }

      // Draw bottom floating toast matching video
      hudToast.draw(canvas, viewW, viewH - 64f * density)
      if (hudToast.isShowing) {
        postInvalidateOnAnimation()
      }
    }

  // ── Vector Icon Drawing Helpers & Card Toolbar / Typography Rendering ───────
  // Extracted to ThinkspaceViewToolbar.kt as extension functions
  // ─────────────────────────────────────────────────────────────────────────────

  // ---------------------------------------------------------------------------
  // Touch Handling & Unified Gesture Arbitration
  // ---------------------------------------------------------------------------
  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    val viewH = height.toFloat()
    val hasDoc = activePdfDoc != null || activeDocument != null
    val splitY = if (hasDoc) viewH * effectiveSplitRatio else 0f
    val canvasTopY = if (hasDoc && effectiveSplitRatio > 0f) splitY + 14f else 0f

    val sx = event.x
    val sy = event.y

    val dividerHitRadius = 28f * density
    val inDivider = hasDoc && (sy in (splitY - dividerHitRadius)..(splitY + dividerHitRadius))
    val midMultiY = if (event.pointerCount >= 2) (event.getY(0) + event.getY(1)) / 2f else sy
    val inDocZone = hasDoc && (sy < splitY - 14f)
    val inDocZoneMulti = hasDoc && (midMultiY < splitY - 10f)
    val inCanvasZone = sy >= canvasTopY

    if (event.pointerCount >= 2) {
      // Deterministic gesture arbitration: CANCEL long-press, selection, and handles immediately!
      pendingLongPressRunnable?.let { longPressHandler.removeCallbacks(it) }
      pendingLongPressRunnable = null
      isSelectingPdfText = false
      isDraggingCrop = false
      activePdfSelection = null
      activePoints.clear()
      activePath.reset()
      activeDocPoints.clear()
      activeDocPath.reset()
      activeDocStrokePageIndex = -1
      isDrawingOnDoc = false
    }

    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      if (!docScroller.isFinished) {
        docScroller.abortAnimation()
      }
      velocityTracker?.recycle()
      velocityTracker = VelocityTracker.obtain()
      velocityTracker?.addMovement(event)
    } else {
      velocityTracker?.addMovement(event)
    }

    // Only pass single-touch events to gestureDetector to prevent accidental long-press/selection during pinch
    if (event.pointerCount == 1 && !compressionEngine.isManualPinching) {
      gestureDetector.onTouchEvent(event)
    }

    // Always route multi-touch events to scaleGestureDetector
    if (event.pointerCount >= 2) {
      scaleGestureDetector.onTouchEvent(event)
    }

    // 1. Two or more fingers -> Canvas Pan & Zoom or Document Pinch Compression / Pan
    if (event.pointerCount >= 2) {
      if (inDocZoneMulti) {
        when (event.actionMasked) {
          MotionEvent.ACTION_POINTER_DOWN -> {
            if (event.pointerCount == 2) {
              val x0 = event.getX(0)
              val x1 = event.getX(1)
              val y0 = event.getY(0)
              val y1 = event.getY(1)
              lastTouchScreenX = (x0 + x1) / 2f
              lastTouchScreenY = (y0 + y1) / 2f
            }
          }
          MotionEvent.ACTION_MOVE -> {
            // Two-finger panning when document is zoomed in
            if (pdfScaleFactor > 1.02f && !compressionEngine.isManualPinching && event.pointerCount >= 2) {
              val x0 = event.getX(0)
              val x1 = event.getX(1)
              val y0 = event.getY(0)
              val y1 = event.getY(1)
              val midX = (x0 + x1) / 2f
              val midY = (y0 + y1) / 2f
              if (lastTouchScreenX != 0f && lastTouchScreenY != 0f) {
                val deltaX = lastTouchScreenX - midX
                val deltaY = lastTouchScreenY - midY
                docScrollX = (docScrollX + deltaX).coerceIn(0f, maxDocScrollX)
                docScrollY = (docScrollY + deltaY).coerceIn(0f, maxDocScrollY)
                invalidate()
              }
              lastTouchScreenX = midX
              lastTouchScreenY = midY
            }
          }
          MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
            if (compressionEngine.isManualPinching) {
              compressionEngine.onManualPinchEnd(
                onUpdate = { invalidate() },
                onHaptic = { performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
              )
            }
            lastTouchScreenX = 0f
            lastTouchScreenY = 0f
          }
        }
      }
      return true
    }

    // 2. Single Touch Gestures
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastTouchScreenX = sx
        lastTouchScreenY = sy
        totalDragDistance = 0f
        hadSelectionOrModalBeforeTouch = (selectedCardId != null || editingCardId != null ||
          selectedNotebookPageId != null || isStyleSheetOpen || isCardColorPaletteOpen ||
          isTypoTextColorPaletteOpen || isNotebookStylePickerOpen || activeCropSelection != null ||
          activePdfSelection != null)

        // 0A. Check LiquidText Style Sheet Popover Clicks (Screenshot 4)
        if (isStyleSheetOpen && styleSheetRect.contains(sx, sy)) {
          val selCard = cards.find { it.id == selectedCardId }
          if (selCard != null) {
            for (triple in styleOptionRects) {
              if (triple.first.contains(sx, sy)) {
                val styleName = triple.second
                selCard.undoTextStack.addLast(selCard.text)
                selCard.textStyleName = styleName
                when (styleName) {
                  "Title" -> { selCard.fontSize = 20f; selCard.isBold = true; selCard.isItalic = false }
                  "Subtitle" -> { selCard.fontSize = 16f; selCard.isBold = false; selCard.isItalic = true }
                  "Heading 1" -> { selCard.fontSize = 18f; selCard.isBold = true; selCard.isItalic = false }
                  "Heading 2" -> { selCard.fontSize = 16f; selCard.isBold = true; selCard.isItalic = false }
                  "Heading 3" -> { selCard.fontSize = 14f; selCard.isBold = true; selCard.isItalic = false }
                  else -> { selCard.fontSize = 13f; selCard.isBold = false; selCard.isItalic = false }
                }
                isStyleSheetOpen = false
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
              }
            }
          }
          isStyleSheetOpen = false
          invalidate()
          return true
        }

        // 0B. Check Color Palette Clicks
        if (isCardColorPaletteOpen) {
          val tapped = cardColorPaletteRects.find { it.first.contains(sx, sy) }
          if (tapped != null) {
            val selCard = cards.find { it.id == selectedCardId }
            if (selCard != null) {
              val prevCol = selCard.color
              val newCol = tapped.second
              selCard.color = newCol
              selectedColor = newCol
              undoRedoManager.record(ChangeCardColorAction(
                cardId = selCard.id,
                prevColor = prevCol,
                newColor = newCol,
                cardsList = cards,
                onColorChanged = { id, col ->
                  dispatchChangeCardColorEvent(id, col)
                  invalidate()
                }
              ))
              dispatchChangeCardColorEvent(selCard.id, newCol)
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            isCardColorPaletteOpen = false
            invalidate()
            return true
          }
          isCardColorPaletteOpen = false
          invalidate()
        }

        if (isTypoTextColorPaletteOpen) {
          val tapped = typoTextColorPaletteRects.find { it.first.contains(sx, sy) }
          if (tapped != null) {
            val selCard = cards.find { it.id == selectedCardId }
            if (selCard != null) {
              selCard.textColor = tapped.second
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            isTypoTextColorPaletteOpen = false
            invalidate()
            return true
          }
          isTypoTextColorPaletteOpen = false
          invalidate()
        }

        // 0C. Check Typography Bar Clicks (Screenshots 2 & 3)
        if (cardActionBarAnimProgress > 0.4f && isTypographyBarVisible && selectedCardId != null && typographyBarRect.contains(sx, sy)) {
          val selCard = cards.find { it.id == selectedCardId }
          if (selCard != null) {
            if (btnTypoBackRect.contains(sx, sy)) {
              // Return back to primary selection action bar (Comment, Edit, Copy, Delete, Tags, Color, Tt)
              isTypographyBarVisible = false
              isStyleSheetOpen = false
              isTypoTextColorPaletteOpen = false
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnTypoStyleRect.contains(sx, sy)) {
              isStyleSheetOpen = !isStyleSheetOpen
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnTypoBoldRect.contains(sx, sy)) {
              selCard.isBold = !selCard.isBold
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnTypoItalicRect.contains(sx, sy)) {
              selCard.isItalic = !selCard.isItalic
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnTypoUnderlineRect.contains(sx, sy)) {
              selCard.isUnderline = !selCard.isUnderline
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnTypoStrikeRect.contains(sx, sy)) {
              selCard.isStrikethrough = !selCard.isStrikethrough
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnTypoFontSizeRect.contains(sx, sy)) {
              val sizes = listOf(12f, 14f, 16f, 18f, 20f, 24f)
              val curIdx = sizes.indexOfFirst { it >= selCard.fontSize }.let { if (it == -1) 0 else it }
              val nextSize = sizes[(curIdx + 1) % sizes.size]
              selCard.fontSize = nextSize
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              hudToast.show("Font size: ${nextSize.toInt()}pt")
              invalidate()
              return true
            }
            if (btnTypoTextColorRect.contains(sx, sy)) {
              isTypoTextColorPaletteOpen = !isTypoTextColorPaletteOpen
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnTypoMoreRect.contains(sx, sy)) {
              isTypographyBarVisible = false
              isStyleSheetOpen = false
              isTypoTextColorPaletteOpen = false
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
          }
          return true
        }

        // 0D. Check Primary Excerpt Action Bar Clicks (Screenshot 1)
        if (cardActionBarAnimProgress > 0.4f && !isTypographyBarVisible && selectedCardId != null && cardActionBarRect.contains(sx, sy)) {
          val selCard = cards.find { it.id == selectedCardId }
          if (selCard != null) {
            if (btnCardCommentRect.contains(sx, sy)) {
              hudToast.show("Comment mode for excerpt")
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnCardEditRect.contains(sx, sy)) {
              startEditingCard(selCard)
              hudToast.show("✏️ Editing text...")
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnCardCopyRect.contains(sx, sy)) {
              copyToClipboard(selCard.text)
              hudToast.show("✓ Copied to clipboard")
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnCardDeleteRect.contains(sx, sy)) {
              val associatedLinks = links.filter { it.sourceExcerptId == selCard.id }
              cards.remove(selCard)
              links.removeAll(associatedLinks)
              undoRedoManager.record(DeleteCardAction(
                card = selCard,
                associatedLinks = associatedLinks,
                cardsList = cards,
                linksList = links,
                onUndoDispatched = { restoredCard, restoredLinks ->
                  dispatchExtractExcerptEvent(
                    restoredCard.text,
                    restoredCard.pageNumber,
                    restoredCard.color,
                    restoredCard.isImage,
                    restoredCard.imageUrl,
                    restoredCard.x,
                    restoredCard.y,
                    restoredCard.id,
                    restoredCard.sourceRects
                  )
                  invalidate()
                },
                onRedoDispatched = { c ->
                  dispatchCardDeleteEvent(c.id)
                  invalidate()
                }
              ))
              dispatchCardDeleteEvent(selCard.id)
              selectedCardId = null
              editingCardId = null
              isTypographyBarVisible = false
              isStyleSheetOpen = false
              hudToast.show("🗑️ Excerpt deleted")
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              invalidate()
              return true
            }
            if (btnCardTagsRect.contains(sx, sy)) {
              hudToast.show("Tags: #keypoint #research")
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnCardColorWheelRect.contains(sx, sy)) {
              isCardColorPaletteOpen = !isCardColorPaletteOpen
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            if (btnCardTypographyRect.contains(sx, sy)) {
              isTypographyBarVisible = !isTypographyBarVisible
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
          }
          return true
        }

        // 0E. If actively editing card, dismiss keyboard & restore split view on outside tap
        if (editingCardId != null) {
          val editCard = cards.find { it.id == editingCardId }
          if (editCard != null) {
            val (cLeft, cTop) = canvasWorldToScreen(editCard.x, editCard.y, canvasTopY)
            val cardR = RectF(cLeft, cTop, cLeft + editCard.width * scaleFactor, cTop + editCard.getHeight() * scaleFactor)
            if (!cardR.contains(sx, sy) && !cardActionBarRect.contains(sx, sy) && !typographyBarRect.contains(sx, sy)) {
              stopEditingCard()
              return true
            }
          }
        }

        // 0A. Check InkLink PDF Anchor Pin Tap (Screen space)
        val hitPdfLinkEntry = inkLinkPdfAnchorScreenRects.entries.find { it.value.contains(sx, sy) }
        if (hitPdfLinkEntry != null) {
          val link = semanticInkLinks.find { it.id == hitPdfLinkEntry.key }
          if (link != null) {
            val targetCard = cards.find { it.id == link.targetCardId }
            if (targetCard != null) {
              val targetCx = targetCard.x + targetCard.width / 2f
              val targetCy = targetCard.y + targetCard.getHeight() / 2f
              val curCanvasTopY = if (hasDoc && effectiveSplitRatio > 0f) height * effectiveSplitRatio + 14f else 0f
              val viewW = width.toFloat()
              val viewH = height - curCanvasTopY
              camera.panX = -targetCx * camera.scaleFactor + viewW / 2f
              camera.panY = -targetCy * camera.scaleFactor + viewH / 2f
              selectedCardId = targetCard.id
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("Jumped to Linked Card in Workspace")
              invalidate()
              return true
            }
          }
        }

        // 0B. Check InkLink Card Anchor Pin / V Marker Tap (Screen space)
        val hitCardLinkEntry = inkLinkCardAnchorScreenRects.entries.find { it.value.contains(sx, sy) }
        if (hitCardLinkEntry != null) {
          val link = semanticInkLinks.find { it.id == hitCardLinkEntry.key }
          if (link != null) {
            activeVLinkRipple = VLinkRipple(sx, sy, link.color, System.currentTimeMillis())
            val cardDocId = link.sourceDocId.takeIf { it.isNotEmpty() } ?: activeDocumentId
            val isCrossDoc = cardDocId.isNotEmpty() && cardDocId != activeDocumentId
            if (isCrossDoc) {
              pendingScrollToPage = link.sourcePageIndex + 1
              pendingPulseRects = listOf(link.sourcePdfRect)
              dispatchRequestDocumentSwitchEvent(link.id, cardDocId, link.sourcePageIndex + 1)
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("Switching to source document · p.${link.sourcePageIndex + 1}")
            } else {
              scrollToDocumentPage(link.sourcePageIndex + 1, listOf(link.sourcePdfRect))
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("Navigated to PDF Source p.${link.sourcePageIndex + 1}")
            }
            selectedCardId = null
            draggingCard = null
            invalidate()
            return true
          }
        }

        // 0. Check Floating Search HUD clicks if active
        if (isSearchActive && searchHudRect.contains(sx, sy)) {
          if (searchCloseBtnRect.contains(sx, sy)) {
            closeSearch()
            return true
          }
          if (searchNextBtnRect.contains(sx, sy)) {
            goToNextMatch()
            return true
          }
          if (searchPrevBtnRect.contains(sx, sy)) {
            goToPreviousMatch()
            return true
          }
          if (searchQueryPillRect.contains(sx, sy)) {
            promptSearchDialog()
            return true
          }
          return true
        }

        // Check Top Subheader UI Clicks matching Video
        if (showDocumentHeader && inDocZone && sy < subheaderH) {
          if (headerSearchRect.contains(sx, sy)) {
            promptSearchDialog()
            return true
          }
          if (headerZoomOutRect.contains(sx, sy)) {
            scaleFactor = max(0.6f, scaleFactor - 0.15f)
            hudToast.show("Zoom: ${(scaleFactor * 100).toInt()}%")
            invalidate()
            return true
          }
          if (headerZoomResetRect.contains(sx, sy)) {
            scaleFactor = 1.0f
            hudToast.show("Zoom: 100%")
            invalidate()
            return true
          }
          if (headerZoomInRect.contains(sx, sy)) {
            scaleFactor = min(2.5f, scaleFactor + 0.15f)
            hudToast.show("Zoom: ${(scaleFactor * 100).toInt()}%")
            invalidate()
            return true
          }
          if (headerModeCropRect.contains(sx, sy)) {
            docMode = if (docMode == "crop") "text" else "crop"
            if (docMode == "crop") {
              activePdfSelection = null
              val curPl = pageLayouts.firstOrNull { it.boundsOnScreen.bottom > subheaderH + 20f }
              if (curPl != null) {
                val boxW = curPl.boundsOnScreen.width() * 0.75f
                val boxH = curPl.boundsOnScreen.height() * 0.45f
                val boxLeft = curPl.boundsOnScreen.centerX() - boxW / 2f
                val boxTop = curPl.boundsOnScreen.top + 50f * density
                val sRect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
                val pW = curPl.pageSize.width
                val pH = curPl.pageSize.height
                val pageLeft = (sRect.left - curPl.boundsOnScreen.left) / curPl.boundsOnScreen.width() * pW
                val pageTop = (sRect.top - curPl.boundsOnScreen.top) / curPl.boundsOnScreen.height() * pH
                val pageRight = (sRect.right - curPl.boundsOnScreen.left) / curPl.boundsOnScreen.width() * pW
                val pageBottom = (sRect.bottom - curPl.boundsOnScreen.top) / curPl.boundsOnScreen.height() * pH

                val cW = 200f * density
                val cH = 42f * density
                val cLeft = sRect.centerX() - cW / 2f
                val cTop = sRect.bottom + 12f * density
                val calloutR = RectF(cLeft, cTop, cLeft + cW, cTop + cH)
                val excerptBtn = RectF(cLeft + 8f * density, cTop + 4f * density, cLeft + cW - 44f * density, cTop + cH - 4f * density)
                val closeBtn = RectF(cLeft + cW - 40f * density, cTop + 4f * density, cLeft + cW - 4f * density, cTop + cH - 4f * density)

                activeCropSelection = createCropSelection(
                  pageIndex = curPl.pageIndex,
                  sRect = sRect,
                  pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
                  color = selectedColor,
                  dimensionsText = "Page ${curPl.pageIndex + 1} (${pW.toInt()}x${pH.toInt()} pt)"
                )
              }
            } else {
              activeCropSelection = null
            }
            invalidate()
            return true
          }
          if (rightSqueezeTabRect.contains(sx, sy) || headerSqueezeRect.contains(sx, sy)) {
            if (isSearchActive && searchMatches.isNotEmpty()) {
              if (compressionEngine.isAnyPageCompressed()) {
                compressionEngine.resetAllToNormal(animate = true) { invalidate() }
                hudToast.show("Search Results Expanded")
              } else {
                val matchingPages = searchMatches.map { it.pageIndex }.toSet()
                compressionEngine.applySearchMatches(matchingPages, animate = true) { invalidate() }
                hudToast.show("Non-Matching Pages Compressed")
              }
            } else {
              if (compressionEngine.isAnyPageCompressed()) {
                compressionEngine.resetAllToNormal(animate = true) { invalidate() }
                hudToast.show("Document Expanded")
              } else {
                val annotatedPages = annotations.map { it.pageNumber - 1 }.toSet() + cards.map { it.pageNumber - 1 }.toSet()
                compressionEngine.applySearchMatches(annotatedPages, animate = true) { invalidate() }
                hudToast.show("Document Compressed")
              }
            }
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
            return true
          }
          if (headerDocPillRect.contains(sx, sy)) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openDocumentsSheet()
            return true
          }
          return true
        }

        // Check Floating Canvas Bottom Toolbar & HUD Clicks
        if (inCanvasZone && canvasToolbarRect.contains(sx, sy)) {
          for ((toolId, rect) in toolBtnRects) {
            if (rect.contains(sx, sy)) {
              if (toolId == "pen" && activeTool == "pen") {
                togglePenSettings()
              } else {
                activeTool = toolId
                if (toolId == "pen") {
                  isPenSettingsOpen = true
                  dispatchPenStateChangeEvent()
                }
              }
              invalidate()
              return true
            }
          }
          for ((col, rect) in colorBtnRects) {
            if (rect.contains(sx, sy)) {
              selectedColor = col
              invalidate()
              return true
            }
          }
          if (resetCanvasBtnRect.contains(sx, sy)) {
            zoomToFitCards()
            return true
          }
          return true
        }

        // Divider Drag
        if (inDivider) {
          isDraggingDivider = true
          dividerDragOffsetY = sy - splitY
          parent?.requestDisallowInterceptTouchEvent(true)
          performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
          return true
        }

        // Document Zone Gestures
        if (inDocZone) {
          // Eraser on PDF Annotations and Strokes
          if (activeTool == "eraser") {
            if (eraseDocStrokesNear(sx, sy)) return true
            if (eraseAnnotationNear(sx, sy)) return true
            return true
          }

          // Document Inking with Pen / Highlighter
          if (activeTool == "pen" || activeTool == "highlighter") {
            val pl = pageLayouts.find { it.boundsOnScreen.contains(sx, sy) }
            if (pl != null) {
              val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
                ?: com.thinkspace.pdfengine.model.PageSize.LETTER
              val pW = pSize.width
              val pH = pSize.height
              val px = ((sx - pl.boundsOnScreen.left) / pl.boundsOnScreen.width()) * pW
              val py = ((sy - pl.boundsOnScreen.top) / pl.boundsOnScreen.height()) * pH

              val pressure = event.pressure.takeIf { it > 0f } ?: 1.0f

              activeDocStrokePageIndex = pl.pageIndex
              activeDocStrokePageW = pW
              activeDocStrokePageH = pH
              isDrawingOnDoc = true

              activeDocPoints.clear()
              activeDocPoints.add(NativePoint(px, py, pressure))
              activeDocPath.reset()
              activeDocPath.moveTo(px, py)

              if (activeTool == "pen") {
                inkLinkSourceDocId = activeDocumentId.ifEmpty { "default-doc" }
                inkLinkSourcePageIndex = pl.pageIndex
                inkLinkSourcePdfPoint = NativePoint(px, py)
                val words = pageWordsCache[pl.pageIndex]
                val matchingWord = words?.find { w ->
                  val b = w.bounds
                  px in (b.left - 18f)..(b.right + 18f) && py in (b.top - 14f)..(b.bottom + 14f)
                }
                inkLinkSourcePdfRect = if (matchingWord != null) {
                  val mb = matchingWord.bounds
                  val mCenterY = (mb.top + mb.bottom) / 2f
                  val mCenterX = (mb.left + mb.right) / 2f
                  val lineWords = words?.filter { w ->
                    val b = w.bounds
                    val cy = (b.top + b.bottom) / 2f
                    val cx = (b.left + b.right) / 2f
                    kotlin.math.abs(cy - mCenterY) < 8f && kotlin.math.abs(cx - mCenterX) < 180f
                  }?.sortedBy { it.bounds.left }
                  if (!lineWords.isNullOrEmpty()) {
                    RectF(
                      lineWords.first().bounds.left,
                      lineWords.minOf { it.bounds.top },
                      lineWords.last().bounds.right,
                      lineWords.maxOf { it.bounds.bottom }
                    )
                  } else {
                    RectF(mb.left, mb.top, mb.right, mb.bottom)
                  }
                } else {
                  RectF(px - 36f, py - 12f, px + 36f, py + 12f)
                }
                inkLinkSourceScreenStart.set(sx, sy)
                inkLinkCurrentScreenTouch.set(sx, sy)
                isDrawingCrossZoneLink = false
                inkLinkTargetCardId = null
              }

              isScrollingDoc = false
              isSelectingPdfText = false
              isDraggingCrop = false
              pendingLongPressRunnable?.let { longPressHandler.removeCallbacks(it) }
              pendingLongPressRunnable = null

              invalidate()
              return true
            }
          }

          val pdfSel = activePdfSelection
          
          // 1. Check Precise Selection Handles First (CLOSEST PIN WINS - fixes single-word selection bug)
          if (pdfSel != null) {
            val firstR = pdfSel.highlightRects.firstOrNull()
            val lastR = pdfSel.highlightRects.lastOrNull()
            if (firstR != null && lastR != null) {
              val startPinX = firstR.left - 4f * density
              val startPinY = firstR.bottom + 8f * density
              val endPinX = lastR.right + 4f * density
              val endPinY = lastR.bottom + 8f * density

              val hitRadius = 36f * density
              val distToStart = hypot(sx - startPinX, sy - startPinY)
              val distToEnd = hypot(sx - endPinX, sy - endPinY)
              val startHit = distToStart <= hitRadius
              val endHit = distToEnd <= hitRadius

              // Pick the CLOSEST pin when both are within hit radius (key fix for single-word selection)
              if (startHit || endHit) {
                val grabEnd = endHit && (!startHit || distToEnd <= distToStart)
                if (grabEnd) {
                  isDraggingEndHandle = true
                  isDraggingStartHandle = false
                } else {
                  isDraggingStartHandle = true
                  isDraggingEndHandle = false
                }
                isScrollingDoc = false
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
              }
            }
          }

          // 2. Check Callout Clicks matching Competitor Bar (Comment, AutoExcerpt, Bookmark, More, Colors, Clear, Rainbow, Tags)
          if (pdfSel != null && pdfSel.calloutRect.contains(sx, sy)) {
            // 1. Comment
            if (pdfSel.calloutCommentBtn.contains(sx, sy)) {
              extractExcerptToCanvas(pdfSel.text, pdfSel.pageIndex + 1, selectedColor, pdfSel.pdfRects)
              activePdfSelection = null
              activeStructuredPInfo = null
              hudToast.show("Comment added to excerpt")
              performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
            // 2. AutoExcerpt
            if (pdfSel.calloutExcerptBtn.contains(sx, sy)) {
              extractExcerptToCanvas(pdfSel.text, pdfSel.pageIndex + 1, selectedColor, pdfSel.pdfRects)
              activePdfSelection = null
              activeStructuredPInfo = null
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              invalidate()
              return true
            }
            // 3. Bookmark
            if (pdfSel.calloutBookmarkBtn.contains(sx, sy)) {
              hudToast.show("Bookmark saved on page ${pdfSel.pageIndex + 1}")
              performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
              activePdfSelection = null
              activeStructuredPInfo = null
              invalidate()
              return true
            }
            // 4. More (•••) / Copy
            if (pdfSel.calloutMoreBtn.contains(sx, sy) || pdfSel.calloutCopyBtn.contains(sx, sy)) {
              copyToClipboard(pdfSel.text)
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("Text copied to clipboard")
              return true
            }
            // 5. Color Swatches
            for (cb in pdfSel.calloutColorBtns) {
              if (cb.first.contains(sx, sy)) {
                val colorToUse = cb.second
                selectedColor = colorToUse
                addAnnotation(pdfSel.text, pdfSel.pageIndex + 1, colorToUse, pdfSel.pdfRects)
                activePdfSelection = null
                activeStructuredPInfo = null
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
              }
            }
            // 6. Clear Swatch (White circle with diagonal slash)
            if (pdfSel.calloutClearBtn.contains(sx, sy)) {
              activePdfSelection = null
              activeStructuredPInfo = null
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              invalidate()
              return true
            }
            // 7. Rainbow Swatch
            if (pdfSel.calloutRainbowBtn.contains(sx, sy)) {
              val colorToUse = android.graphics.Color.parseColor("#8B5CF6")
              selectedColor = colorToUse
              addAnnotation(pdfSel.text, pdfSel.pageIndex + 1, colorToUse, pdfSel.pdfRects)
              activePdfSelection = null
              activeStructuredPInfo = null
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              invalidate()
              return true
            }
            // 8. Tags
            if (pdfSel.calloutTagsBtn.contains(sx, sy)) {
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("Tag added to excerpt")
              return true
            }
            if (pdfSel.calloutCloseBtn.contains(sx, sy)) {
              activePdfSelection = null
              activeStructuredPInfo = null
              invalidate()
              return true
            }
            return true
          }

          // Check if touch hits selection handles (start pin or end pin)
          if (pdfSel != null) {
            val firstR = pdfSel.highlightRects.firstOrNull()
            val lastR = pdfSel.highlightRects.lastOrNull()
            if (firstR != null && lastR != null) {
              val startPinX = firstR.left - 4f * density
              val startPinY = firstR.bottom + 8f * density
              val endPinX = lastR.right + 4f * density
              val endPinY = lastR.bottom + 8f * density

              val hitRadius = 36f * density
              val distToStartPin = hypot(sx - startPinX, sy - startPinY)
              val distToEndPin = hypot(sx - endPinX, sy - endPinY)

              val startGenerous = RectF(
                firstR.left - 28f * density,
                firstR.top - 16f * density,
                firstR.left + 28f * density,
                firstR.bottom + 36f * density
              ).contains(sx, sy)

              val endGenerous = RectF(
                lastR.right - 28f * density,
                lastR.top - 16f * density,
                lastR.right + 28f * density,
                lastR.bottom + 36f * density
              ).contains(sx, sy)

              // 2. Body Hit (LiquidText Lift)
              if (pdfSel.highlightRects.any { it.contains(sx, sy) }) {
                isLiftingExcerpt = true
                liftCandidateText = pdfSel.text
                liftCandidatePage = pdfSel.pageIndex + 1
                liftCandidateColor = android.graphics.Color.parseColor("#3B82F6")
                liftCandidateIsImage = false
                liftCandidateImagePath = null
                liftCandidateBitmap = null
                liftCandidateSourceRects = pdfSel.pdfRects

                liftAnchorScreenX = sx
                liftAnchorScreenY = sy
                liftGhostX = sx
                liftGhostY = sy

                activePdfSelection = null
                isScrollingDoc = false
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
              }

              // 3. Generous Pin Hit
              if (startGenerous) {
                isDraggingStartHandle = true
                isDraggingEndHandle = false
                isScrollingDoc = false
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
              } else if (endGenerous) {
                isDraggingEndHandle = true
                isDraggingStartHandle = false
                isScrollingDoc = false
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
              }
            }
          }

          // Check Crop Callout Clicks, Handles, & Direct Workspace Drag-and-Drop
          val cropSel = activeCropSelection
          if (cropSel != null) {
            // 1. Toolbar clicks
            if (cropSel.calloutRect.contains(sx, sy)) {
              if (cropSel.calloutCommentBtn.contains(sx, sy)) {
                extractCropToCanvas(cropSel)
                activeCropSelection = null
                docMode = "text"
                hudToast.show("Comment added to excerpt")
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
              }
              if (cropSel.calloutExcerptBtn.contains(sx, sy)) {
                extractCropToCanvas(cropSel)
                activeCropSelection = null
                docMode = "text"
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
              }
              if (cropSel.calloutBookmarkBtn.contains(sx, sy)) {
                hudToast.show("Bookmark saved on page ${cropSel.pageIndex + 1}")
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                activeCropSelection = null
                invalidate()
                return true
              }
              if (cropSel.calloutMoreBtn.contains(sx, sy)) {
                extractCropToCanvas(cropSel)
                activeCropSelection = null
                docMode = "text"
                hudToast.show("Crop saved to canvas")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
              }
              val clickedColorPair = cropSel.calloutColorBtns.find { it.first.contains(sx, sy) }
              if (clickedColorPair != null) {
                val colorToUse = clickedColorPair.second
                selectedColor = colorToUse
                cropSel.color = colorToUse
                val r = RectF(cropSel.pageBounds.left, cropSel.pageBounds.top, cropSel.pageBounds.right, cropSel.pageBounds.bottom)
                addAnnotation(cropSel.dimensionsText.ifEmpty { "Highlighted Region" }, cropSel.pageIndex + 1, colorToUse, listOf(r))
                hudToast.show("Highlighted on Page ${cropSel.pageIndex + 1}")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                activeCropSelection = null
                invalidate()
                return true
              }
              if (cropSel.calloutClearBtn.contains(sx, sy)) {
                activeCropSelection = null
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
              }
              if (cropSel.calloutRainbowBtn.contains(sx, sy)) {
                val colorToUse = Color.parseColor("#8B5CF6")
                selectedColor = colorToUse
                cropSel.color = colorToUse
                val r = RectF(cropSel.pageBounds.left, cropSel.pageBounds.top, cropSel.pageBounds.right, cropSel.pageBounds.bottom)
                addAnnotation(cropSel.dimensionsText.ifEmpty { "Highlighted Region" }, cropSel.pageIndex + 1, colorToUse, listOf(r))
                hudToast.show("Highlighted on Page ${cropSel.pageIndex + 1}")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                activeCropSelection = null
                invalidate()
                return true
              }
              if (cropSel.calloutTagsBtn.contains(sx, sy)) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hudToast.show("Tag added to crop")
                return true
              }
              if (cropSel.calloutCloseBtn.contains(sx, sy)) {
                activeCropSelection = null
                docMode = "text"
                invalidate()
                return true
              }
              return true
            }

            // 2. Check All 4 Diagonal Corner Handles (Top-Left, Top-Right, Bottom-Left, Bottom-Right)
            val distTL = hypot(sx - cropSel.screenRect.left, sy - cropSel.screenRect.top)
            val distTR = hypot(sx - cropSel.screenRect.right, sy - cropSel.screenRect.top)
            val distBL = hypot(sx - cropSel.screenRect.left, sy - cropSel.screenRect.bottom)
            val distBR = hypot(sx - cropSel.screenRect.right, sy - cropSel.screenRect.bottom)
            val handleHitRadius = 38f * density

            if (distTL <= handleHitRadius) {
              isDraggingCropTopLeftHandle = true
              isDraggingCropTopRightHandle = false
              isDraggingCropBottomLeftHandle = false
              isDraggingCropBottomRightHandle = false
              isScrollingDoc = false
              parent?.requestDisallowInterceptTouchEvent(true)
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              return true
            } else if (distTR <= handleHitRadius) {
              isDraggingCropTopRightHandle = true
              isDraggingCropTopLeftHandle = false
              isDraggingCropBottomLeftHandle = false
              isDraggingCropBottomRightHandle = false
              isScrollingDoc = false
              parent?.requestDisallowInterceptTouchEvent(true)
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              return true
            } else if (distBL <= handleHitRadius) {
              isDraggingCropBottomLeftHandle = true
              isDraggingCropTopLeftHandle = false
              isDraggingCropTopRightHandle = false
              isDraggingCropBottomRightHandle = false
              isScrollingDoc = false
              parent?.requestDisallowInterceptTouchEvent(true)
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              return true
            } else if (distBR <= handleHitRadius) {
              isDraggingCropBottomRightHandle = true
              isDraggingCropTopLeftHandle = false
              isDraggingCropTopRightHandle = false
              isDraggingCropBottomLeftHandle = false
              isScrollingDoc = false
              parent?.requestDisallowInterceptTouchEvent(true)
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              return true
            }

            // 3. Check Body Hit -> LiquidText Move / Reposition or Drag into Workspace!
            if (cropSel.screenRect.contains(sx, sy)) {
              isMovingCropSelection = true
              cropDragOffsetDocX = sx - cropSel.screenRect.left
              cropDragOffsetDocY = sy - cropSel.screenRect.top
              isScrollingDoc = false
              parent?.requestDisallowInterceptTouchEvent(true)
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              return true
            }

            // If tapped outside toolbar, handles, and selection body:
            // Dismiss selection and allow normal document scrolling
            activeCropSelection = null
            invalidate()
          }

          // Check if tapped on a compressed page -> Expand it!
          for (pl in pageLayouts) {
            if (pl.isFolded && pl.boundsOnScreen.contains(sx, sy)) {
              compressionEngine.expandPage(pl.pageIndex, animate = true) { invalidate() }
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              invalidate()
              return true
            }
          }

          // Crop Drag Start (Real PDF or Demo Document)
          if (docMode == "crop") {
            for (pl in pageLayouts) {
              if (!pl.isFolded && pl.boundsOnScreen.contains(sx, sy)) {
                isDraggingCrop = true
                cropStartX = sx
                cropStartY = sy
                cropPageIndex = pl.pageIndex
                activeCropSelection = null
                return true
              }
            }
            if (activePdfDoc == null && sy >= 46f && sy < splitY - 14f) {
              isDraggingCrop = true
              cropStartX = sx
              cropStartY = sy
              cropPageIndex = 0
              activeCropSelection = null
              return true
            }
          }

          // Normal document touch -> initialize smooth document scrolling with high-momentum physics
          if (!docScroller.isFinished) {
            docScroller.abortAnimation()
          }
          isScrollingDoc = true
          downDocX = sx
          downDocY = sy

          // Start 300ms Long-Press Timer for Android-style Text Selection (handles & callout)
          // or Area Crop Framing
          if (activeTool == "select" || activeTool == "pan") {
            longPressStartX = sx
            longPressStartY = sy
            pendingLongPressRunnable?.let { longPressHandler.removeCallbacks(it) }
            pendingLongPressRunnable = Runnable {
              isScrollingDoc = false
              triggerLongPressSelect(longPressStartX, longPressStartY)
            }
            longPressHandler.postDelayed(pendingLongPressRunnable!!, 300)
          }
          return true
        }

        // Canvas Zone Gestures
        if (inCanvasZone) {
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)

          if (activeTool == "pen" || activeTool == "highlighter") {
            val pressure = event.pressure.takeIf { it > 0f } ?: 1.0f
            activePoints.clear()
            activePoints.add(NativePoint(wx, wy, pressure))
            activePath.reset()
            activePath.moveTo(wx, wy)
            invalidate()
            return true
          }

          if (activeTool == "eraser") {
            eraseStrokesNear(wx, wy)
            return true
          }

          // 0NB. Notebook page: check style picker first if open
          if (isNotebookStylePickerOpen && stylePickerForPageId != null) {
            val styles = listOf(
              NbStyleOption(RectF(), "blank", "Blank"),
              NbStyleOption(RectF(), "ruled", "Ruled"),
              NbStyleOption(RectF(), "grid", "Grid"),
              NbStyleOption(RectF(), "dotted", "Dotted"),
              NbStyleOption(RectF(), "sketch", "Sketch"),
              NbStyleOption(RectF(), "cornell", "Cornell"),
              NbStyleOption(RectF(), "squared", "Squared"),
              NbStyleOption(RectF(), "custom", "Custom")
            )
            val rowH = 34f
            val popW = 130f
            val anchor = nbPageStyleBtnRects[stylePickerForPageId]
            if (anchor != null) {
              val popLeft = anchor.left
              val popTop = anchor.bottom + 4f
              for (i in styles.indices) {
                val rTop = popTop + i * rowH
                val optRect = RectF(popLeft, rTop, popLeft + popW, rTop + rowH)
                if (optRect.contains(wx, wy)) {
                  val page = notebookPages.find { it.id == stylePickerForPageId }
                  if (page != null) {
                    page.pageStyle = styles[i].style
                    persistNotebookPagesLocally()
                    hudToast.show("Style: ${styles[i].label}")
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                  }
                  isNotebookStylePickerOpen = false
                  stylePickerForPageId = null
                  invalidate()
                  return true
                }
              }
            }
            isNotebookStylePickerOpen = false
            stylePickerForPageId = null
            invalidate()
          }

          // 0NB2. Notebook page: check selected page's controls
          if (selectedNotebookPageId != null) {
            // Duplicate button
            val dupRect = nbPageDuplicateRects[selectedNotebookPageId!!]
            if (dupRect != null && dupRect.contains(wx, wy)) {
              duplicateNotebookPage(selectedNotebookPageId!!)
              return true
            }

            val delRect = nbPageDeleteRects[selectedNotebookPageId!!]
            if (delRect != null && delRect.contains(wx, wy)) {
              val page = notebookPages.find { it.id == selectedNotebookPageId }
              if (page != null) {
                notebookPages.remove(page)
                undoRedoManager.record(DeleteNotebookPageAction(
                  page = page,
                  pagesList = notebookPages,
                  onUndoDispatched = { invalidate() },
                  onRedoDispatched = { invalidate() }
                ))
                dispatchNotebookPageDeletedEvent(page.id)
                persistNotebookPagesLocally()
                selectedNotebookPageId = null
                isNotebookStylePickerOpen = false
                hudToast.show("🗑 Notebook page deleted")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              }
              invalidate()
              return true
            }

            // Style button
            val styleRect = nbPageStyleBtnRects[selectedNotebookPageId!!]
            if (styleRect != null && styleRect.contains(wx, wy)) {
              isNotebookStylePickerOpen = !isNotebookStylePickerOpen
              stylePickerForPageId = if (isNotebookStylePickerOpen) selectedNotebookPageId else null
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }

            // Resize handle
            val resizeRect = nbPageResizeRects[selectedNotebookPageId!!]
            if (resizeRect != null && resizeRect.contains(wx, wy)) {
              val page = notebookPages.find { it.id == selectedNotebookPageId }
              if (page != null) {
                resizingNotebookPage = page
                nbPageResizeStartWidth = page.width
                nbPageResizeStartHeight = page.height
                nbPageResizeStartWorldX = wx
                nbPageResizeStartWorldY = wy
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              }
              invalidate()
              return true
            }
          }

          // 1. Check Excerpt Card Clicks (including arrow or jump badge bounds)
          val clickedCard = cards.findLast { c ->
            val ch = c.getHeight()
            val insideBody = wx >= c.x && wx <= c.x + c.width && wy >= c.y && wy <= c.y + ch
            val insideArrow = cardExtractionArrowRects[c.id]?.contains(wx, wy) == true
            val insideJump = cardJumpBtnRects[c.id]?.contains(wx, wy) == true
            insideBody || insideArrow || insideJump
          }

          if (clickedCard != null) {
            selectedCardId = clickedCard.id

            // Check if user tapped the dedicated `▶` Extraction Arrow or source badge
            val arrowRect = cardExtractionArrowRects[clickedCard.id]
            val jumpRect = cardJumpBtnRects[clickedCard.id]
            val isExtractionArrowTap = arrowRect != null && arrowRect.contains(wx, wy)
            val isJumpBadgeTap = jumpRect != null && jumpRect.contains(wx, wy)
            val isSourceNavigationTap = isExtractionArrowTap || isJumpBadgeTap

            val penLink = semanticInkLinks.findLast { it.targetCardId == clickedCard.id }

            // Multi-document: check if this card belongs to a different document
            val cardDocId = clickedCard.documentId.takeIf { it.isNotEmpty() }
              ?: penLink?.sourceDocId?.takeIf { it.isNotEmpty() }
              ?: activeDocumentId
            val isCrossDocJump = isSourceNavigationTap && cardDocId.isNotEmpty() && cardDocId != activeDocumentId

            if (isSourceNavigationTap) {
              val targetPageNum = if (penLink != null) {
                (penLink.sourcePageIndex + 1).coerceAtLeast(1)
              } else {
                clickedCard.pageNumber.coerceAtLeast(1)
              }
              val targetRects = if (penLink != null && penLink.sourcePdfRect.width() > 0) {
                listOf(penLink.sourcePdfRect)
              } else {
                clickedCard.sourceRects
              }

              if (isCrossDocJump) {
                // Store pending page navigation so that when the document activates, it scrolls to the page
                pendingScrollToPage = targetPageNum
                pendingPulseRects = targetRects
                // Cross-document source jump: ask RN to switch documents, then scroll
                dispatchRequestDocumentSwitchEvent(clickedCard.id, cardDocId, targetPageNum)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hudToast.show("Switching to source document · p.$targetPageNum")
              } else {
                // Same-document: Bidirectional Navigation — scroll document to card's page & pulse highlight
                scrollToDocumentPage(targetPageNum, targetRects)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hudToast.show("Source: p.$targetPageNum")
              }
              invalidate()
              return true
            }

            // Update cursor position if actively editing this card
            if (editingCardId == clickedCard.id && !clickedCard.isImage && !clickedCard.isTable && clickedCard.text.isNotEmpty()) {
              val cardTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = (clickedCard.fontSize * 1.8f).coerceAtLeast(18f)
                if (clickedCard.isBold) typeface = Typeface.DEFAULT_BOLD
              }
              val textW = Math.max(20, (clickedCard.width - 28f).toInt())
              val layout = android.text.StaticLayout.Builder
                .obtain(clickedCard.text, 0, clickedCard.text.length, cardTextPaint, textW)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .build()
              val relX = wx - (clickedCard.x + 14f)
              val relY = wy - (clickedCard.y + 36f)
              if (relY >= 0 && relY <= layout.height && layout.lineCount > 0) {
                val line = layout.getLineForVertical(relY.toInt())
                cursorPosition = layout.getOffsetForHorizontal(line, relX).coerceIn(0, clickedCard.text.length)
                isCursorBlinkVisible = true
                lastCursorBlinkTime = System.currentTimeMillis()
              }
            }

            draggingCard = clickedCard
            dragCardStartX = clickedCard.x
            dragCardStartY = clickedCard.y
            dragOffsetWorldX = wx - clickedCard.x
            dragOffsetWorldY = wy - clickedCard.y
            dispatchExcerptPressEvent(clickedCard.id)

            invalidate()
            return true
          }

          selectedCardId = null
          stopEditingCard()
          isTypographyBarVisible = false
          isStyleSheetOpen = false
          isCardColorPaletteOpen = false
          isTypoTextColorPaletteOpen = false

          // 0NB3. Check notebook page body AFTER cards (cards sit on top of pages)
          // Only drag page when select or pan tool is active; in pen/highlighter/eraser mode, let touch draw on page!
          val hitPage = if (activeTool == "select" || activeTool == "pan") {
            notebookPages.findLast { p ->
              wx >= p.x && wx <= p.x + p.width && wy >= p.y && wy <= p.y + p.height
            }
          } else null

          if (hitPage != null) {
            val wasDifferentPage = selectedNotebookPageId != hitPage.id
            selectedNotebookPageId = hitPage.id
            isNotebookStylePickerOpen = false
            draggingNotebookPage = hitPage
            nbPageDragOffsetWorldX = wx - hitPage.x
            nbPageDragOffsetWorldY = wy - hitPage.y
            nbPageDragStartX = hitPage.x
            nbPageDragStartY = hitPage.y

            // Record attached cards and ink strokes to move them synchronously with the page
            val pageBounds = RectF(hitPage.x, hitPage.y, hitPage.x + hitPage.width, hitPage.y + hitPage.height)
            nbPageAttachedCards = cards.filter {
              pageBounds.contains(it.x + it.width / 2f, it.y + it.getHeight() / 2f)
            }
            nbPageAttachedCardStarts = nbPageAttachedCards.map { Pair(it, Pair(it.x, it.y)) }

            nbPageAttachedStrokes = strokes.filter { s ->
              s.points.any { p -> pageBounds.contains(p.x, p.y) }
            }
            nbPageAttachedStrokeStarts = nbPageAttachedStrokes.map { s ->
              Pair(s, s.points.map { Pair(it.x, it.y) })
            }

            parent?.requestDisallowInterceptTouchEvent(true)
            if (wasDifferentPage) {
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("📄 Notebook page selected — drag to move")
            }
            invalidate()
            return true
          }

          // Tapped empty canvas: deselect notebook page
          if (selectedNotebookPageId != null) {
            selectedNotebookPageId = null
            isNotebookStylePickerOpen = false
            invalidate()
          }

          // 2. Pan tool or Inking
          if (activeTool == "pan" || activeTool == "select") {
            isPanningCanvas = true
            return true
          } else if (activeTool == "pen" || activeTool == "highlighter") {
            activePoints.clear()
            activePoints.add(NativePoint(wx, wy))
            activePath.reset()
            activePath.moveTo(wx, wy)
            invalidate()
            return true
          } else if (activeTool == "eraser") {
            eraseStrokesNear(wx, wy)
            return true
          }
        }
      }

      MotionEvent.ACTION_MOVE -> {
        val dx = sx - lastTouchScreenX
        val dy = sy - lastTouchScreenY
        totalDragDistance += hypot(dx, dy)
        lastTouchScreenX = sx
        lastTouchScreenY = sy

        if (hypot(sx - longPressStartX, sy - longPressStartY) > 24f * density) {
          if (!isDraggingCrop && !isSelectingPdfText) {
            pendingLongPressRunnable?.let {
              longPressHandler.removeCallbacks(it)
              pendingLongPressRunnable = null
            }
          }
        }

        // Dragging Text Selection Start Handle (Android-style)
        if (isDraggingStartHandle) {
          val pdfSel = activePdfSelection
          if (pdfSel != null) {
            if (activePdfDoc != null) {
              val pl = pageLayouts.firstOrNull { it.pageIndex == pdfSel.pageIndex }
              val words = pageWordsCache[pdfSel.pageIndex]
              if (pl != null && !words.isNullOrEmpty()) {
                val effectiveSy = sy - 14f * density
                val px = (sx - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
                val py = (effectiveSy - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height
                val closestIdx = words.indices.minByOrNull { i ->
                  val b = words[i].bounds
                  val dy = if (py in b.top..b.bottom) 0f else min(abs(py - b.top), abs(py - b.bottom))
                  val dx = if (px in b.left..b.right) 0f else min(abs(px - b.left), abs(px - b.right))
                  dy * 3.5f + dx
                } ?: pdfSel.startWordIndex
                val newStart = min(closestIdx, pdfSel.endWordIndex)
                updatePdfSelectionByIndex(pl, newStart, pdfSel.endWordIndex)
              }
            } else if (activeStructuredPInfo != null) {
              val pInfo = activeStructuredPInfo!!
              val effectiveSy = sy - 14f * density
              val relY = (effectiveSy - pInfo.topY).coerceIn(0f, pInfo.layout.height.toFloat() - 1f)
              val line = pInfo.layout.getLineForVertical(relY.toInt())
              val relX = (sx - pInfo.paperX).coerceIn(0f, pInfo.width)
              var offset = pInfo.layout.getOffsetForHorizontal(line, relX).coerceIn(0, pInfo.text.length)
              while (offset > 0 && !pInfo.text[offset - 1].isWhitespace()) {
                offset--
              }
              val newStart = min(offset, activeStructuredEndOffset - 1).coerceAtLeast(0)
              updateStructuredSelectionByOffsets(pInfo, newStart, activeStructuredEndOffset)
            }
          }
          invalidate()
          return true
        }

        // Dragging Text Selection End Handle (Android-style)
        if (isDraggingEndHandle) {
          val pdfSel = activePdfSelection
          if (pdfSel != null) {
            if (activePdfDoc != null) {
              val pl = pageLayouts.firstOrNull { it.pageIndex == pdfSel.pageIndex }
              val words = pageWordsCache[pdfSel.pageIndex]
              if (pl != null && !words.isNullOrEmpty()) {
                val effectiveSy = sy - 14f * density
                val px = (sx - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
                val py = (effectiveSy - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height
                val closestIdx = words.indices.minByOrNull { i ->
                  val b = words[i].bounds
                  val dy = if (py in b.top..b.bottom) 0f else min(abs(py - b.top), abs(py - b.bottom))
                  val dx = if (px in b.left..b.right) 0f else min(abs(px - b.left), abs(px - b.right))
                  dy * 3.5f + dx
                } ?: pdfSel.endWordIndex
                val newEnd = max(closestIdx, pdfSel.startWordIndex)
                updatePdfSelectionByIndex(pl, pdfSel.startWordIndex, newEnd)
              }
            } else if (activeStructuredPInfo != null) {
              val pInfo = activeStructuredPInfo!!
              val effectiveSy = sy - 14f * density
              val relY = (effectiveSy - pInfo.topY).coerceIn(0f, pInfo.layout.height.toFloat() - 1f)
              val line = pInfo.layout.getLineForVertical(relY.toInt())
              val relX = (sx - pInfo.paperX).coerceIn(0f, pInfo.width)
              var offset = pInfo.layout.getOffsetForHorizontal(line, relX).coerceIn(0, pInfo.text.length)
              while (offset < pInfo.text.length && !pInfo.text[offset].isWhitespace()) {
                offset++
              }
              val newEnd = max(offset, activeStructuredStartOffset + 1).coerceAtMost(pInfo.text.length)
              updateStructuredSelectionByOffsets(pInfo, activeStructuredStartOffset, newEnd)
            }
          }
          invalidate()
          return true
        }

        // Dragging Divider (100% native smooth 120 FPS tracking, zero bridge re-renders during gesture)
        if (isDraggingDivider) {
          val targetSplitY = sy - dividerDragOffsetY
          val newRatio = (targetSplitY / viewH).coerceIn(0.18f, 0.82f)
          splitRatio = newRatio
          invalidate()
          return true
        }

        // Lifting Excerpt Across Zones
        if (isLiftingExcerpt) {
          liftGhostX = sx
          liftGhostY = sy
          if (sy >= canvasTopY) {
            val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)
            val snap = MagneticStackingEngine.findSnapTarget(wx, wy, cards)
            magneticTargetCardId = snap?.id
          } else {
            magneticTargetCardId = null
          }
          invalidate()
          return true
        }

        // Moving / Repositioning Area Excerpt Box or Pulling across into Workspace Canvas
        if (isMovingCropSelection && activeCropSelection != null) {
          val sel = activeCropSelection!!
          val splitY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio else 0f

          // If dragged across into the workspace canvas: Snap off and lift into workspace!
          if (sy >= splitY - 8f) {
            isMovingCropSelection = false
            isLiftingExcerpt = true
            liftCandidateText = "[Photo Excerpt]"
            liftCandidatePage = sel.pageIndex + 1
            liftCandidateColor = sel.color
            liftCandidateIsImage = true
            val cropBmp = generateCropBitmap(sel.pageIndex, sel.pageBounds)
            val p = if (cropBmp != null) saveCropToFile(cropBmp) else null
            if (cropBmp != null && p != null) {
              cardBitmapCache.put(p, cropBmp)
            }
            liftCandidateImagePath = p
            liftCandidateBitmap = cropBmp
            liftCandidateSourceRects = listOf(RectF(sel.pageBounds.left, sel.pageBounds.top, sel.pageBounds.right, sel.pageBounds.bottom))

            liftAnchorScreenX = sel.screenRect.centerX()
            liftAnchorScreenY = sel.screenRect.centerY()
            liftGhostX = sx
            liftGhostY = sy

            activeCropSelection = null
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
            return true
          }

          // Otherwise, smoothly move/reposition the selection box within the document pane
          val pl = pageLayouts.find { it.pageIndex == sel.pageIndex }
          val leftBound = pl?.boundsOnScreen?.left ?: 8f
          val rightBound = pl?.boundsOnScreen?.right ?: (width - 8f)
          val topBound = pl?.boundsOnScreen?.top ?: subheaderH
          val bottomBound = pl?.boundsOnScreen?.bottom ?: (splitY - 14f)

          val w = sel.screenRect.width()
          val h = sel.screenRect.height()
          val newLeft = (sx - cropDragOffsetDocX).coerceIn(leftBound, (rightBound - w).coerceAtLeast(leftBound))
          val newTop = (sy - cropDragOffsetDocY).coerceIn(topBound, (bottomBound - h).coerceAtLeast(topBound))

          sel.screenRect.set(newLeft, newTop, newLeft + w, newTop + h)

          if (pl != null) {
            val pW = pl.pageSize.width
            val pH = pl.pageSize.height
            val pageLeft = (sel.screenRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageTop = (sel.screenRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            val pageRight = (sel.screenRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageBottom = (sel.screenRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            sel.pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom))
          }
          recomputeCropCalloutRects(sel)
          invalidate()
          return true
        }

        // Dragging Top-Left Handle of Area Excerpt
        if (isDraggingCropTopLeftHandle && activeCropSelection != null) {
          val sel = activeCropSelection!!
          val pl = pageLayouts.find { it.pageIndex == sel.pageIndex }
          val leftBound = pl?.boundsOnScreen?.left ?: 12f
          val rightBound = sel.screenRect.right - 36f * density
          val topBound = pl?.boundsOnScreen?.top ?: subheaderH
          val bottomBound = sel.screenRect.bottom - 28f * density

          val newLeft = sx.coerceIn(leftBound, rightBound)
          val newTop = sy.coerceIn(topBound, bottomBound)
          sel.screenRect.left = newLeft
          sel.screenRect.top = newTop

          if (pl != null) {
            val pW = pl.pageSize.width
            val pH = pl.pageSize.height
            val pageLeft = (sel.screenRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageTop = (sel.screenRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            val pageRight = (sel.screenRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageBottom = (sel.screenRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            sel.pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom))
          }
          recomputeCropCalloutRects(sel)
          invalidate()
          return true
        }

        // Dragging Top-Right Handle of Area Excerpt
        if (isDraggingCropTopRightHandle && activeCropSelection != null) {
          val sel = activeCropSelection!!
          val pl = pageLayouts.find { it.pageIndex == sel.pageIndex }
          val leftBound = sel.screenRect.left + 36f * density
          val rightBound = pl?.boundsOnScreen?.right ?: (width - 12f)
          val topBound = pl?.boundsOnScreen?.top ?: subheaderH
          val bottomBound = sel.screenRect.bottom - 28f * density

          val newRight = sx.coerceIn(leftBound, rightBound)
          val newTop = sy.coerceIn(topBound, bottomBound)
          sel.screenRect.right = newRight
          sel.screenRect.top = newTop

          if (pl != null) {
            val pW = pl.pageSize.width
            val pH = pl.pageSize.height
            val pageLeft = (sel.screenRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageTop = (sel.screenRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            val pageRight = (sel.screenRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageBottom = (sel.screenRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            sel.pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom))
          }
          recomputeCropCalloutRects(sel)
          invalidate()
          return true
        }

        // Dragging Bottom-Left Handle of Area Excerpt
        if (isDraggingCropBottomLeftHandle && activeCropSelection != null) {
          val sel = activeCropSelection!!
          val pl = pageLayouts.find { it.pageIndex == sel.pageIndex }
          val leftBound = pl?.boundsOnScreen?.left ?: 12f
          val rightBound = sel.screenRect.right - 36f * density
          val topBound = sel.screenRect.top + 28f * density
          val bottomBound = pl?.boundsOnScreen?.bottom ?: (splitY - 14f)

          val newLeft = sx.coerceIn(leftBound, rightBound)
          val newBottom = sy.coerceIn(topBound, bottomBound)
          sel.screenRect.left = newLeft
          sel.screenRect.bottom = newBottom

          if (pl != null) {
            val pW = pl.pageSize.width
            val pH = pl.pageSize.height
            val pageLeft = (sel.screenRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageTop = (sel.screenRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            val pageRight = (sel.screenRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageBottom = (sel.screenRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            sel.pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom))
          }
          recomputeCropCalloutRects(sel)
          invalidate()
          return true
        }

        // Dragging Bottom-Right Handle of Area Excerpt
        if (isDraggingCropBottomRightHandle && activeCropSelection != null) {
          val sel = activeCropSelection!!
          val pl = pageLayouts.find { it.pageIndex == sel.pageIndex }
          val leftBound = sel.screenRect.left + 36f * density
          val rightBound = pl?.boundsOnScreen?.right ?: (width - 12f)
          val topBound = sel.screenRect.top + 28f * density
          val bottomBound = pl?.boundsOnScreen?.bottom ?: (splitY - 14f)

          val newRight = sx.coerceIn(leftBound, rightBound)
          val newBottom = sy.coerceIn(topBound, bottomBound)
          sel.screenRect.right = newRight
          sel.screenRect.bottom = newBottom

          if (pl != null) {
            val pW = pl.pageSize.width
            val pH = pl.pageSize.height
            val pageLeft = (sel.screenRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageTop = (sel.screenRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            val pageRight = (sel.screenRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageBottom = (sel.screenRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            sel.pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom))
          }
          recomputeCropCalloutRects(sel)
          invalidate()
          return true
        }

        // Dragging Crop Rectangle dynamically to enclose content
        if (isDraggingCrop) {
          val pl = pageLayouts.find { it.pageIndex == cropPageIndex }
          val leftBound = pl?.boundsOnScreen?.left ?: 16f
          val rightBound = pl?.boundsOnScreen?.right ?: (width - 16f)
          val topBound = pl?.boundsOnScreen?.top ?: 46f
          val bottomBound = pl?.boundsOnScreen?.bottom ?: (splitY - 14f)

          val l = min(cropStartX, sx).coerceIn(leftBound, rightBound)
          val t = min(cropStartY, sy).coerceIn(topBound, bottomBound)
          val r = max(cropStartX, sx).coerceIn(leftBound, rightBound)
          val b = max(cropStartY, sy).coerceIn(topBound, bottomBound)

          val sRect = RectF(l, t, max(l + 6f * density, r), max(t + 6f * density, b))
          val pW = pl?.pageSize?.width ?: 612f
          val pH = pl?.pageSize?.height ?: 792f
          val pageLeft = if (pl != null) (sRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW else 0f
          val pageTop = if (pl != null) (sRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH else 0f
          val pageRight = if (pl != null) (sRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW else pW
          val pageBottom = if (pl != null) (sRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH else pH

          // If user dragged crop box across into the canvas zone, seamlessly convert to lift & excerpt!
          if (sy > splitY + 16f) {
            isDraggingCrop = false
            isLiftingExcerpt = true
            liftCandidateText = "[Photo Excerpt]"
            liftCandidatePage = cropPageIndex + 1
            liftCandidateColor = selectedColor
            liftCandidateIsImage = true
            liftCandidateSourceRects = listOf(RectF(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)))
            val bounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom))
            val bmp = generateCropBitmap(cropPageIndex, bounds)
            if (bmp != null) {
              val p = saveCropToFile(bmp)
              liftCandidateImagePath = p
              liftCandidateBitmap = bmp
              cardBitmapCache.put(p, bmp)
            } else {
              liftCandidateImagePath = null
              liftCandidateBitmap = null
            }
            liftGhostX = sx
            liftGhostY = sy
            liftAnchorScreenX = cropStartX
            liftAnchorScreenY = cropStartY
            activeCropSelection = null
            invalidate()
            return true
          }

          activeCropSelection = createCropSelection(
            pageIndex = cropPageIndex,
            sRect = sRect,
            pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
            color = selectedColor,
            dimensionsText = "Page ${cropPageIndex + 1} (${pW.toInt()}x${pH.toInt()} pt)"
          )
          invalidate()
          return true
        }

        // Dragging Text Selection on PDF
        if (isSelectingPdfText && pdfSelectStartWord != null) {
          val pl = pageLayouts.find { it.pageIndex == pdfSelectPageIndex }
          if (pl != null) {
            val words = pageWordsCache[pl.pageIndex]
            if (words != null) {
              val px = (sx - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
              val py = (sy - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height
              val curWord = words.minByOrNull { hypot(it.bounds.centerX - px, it.bounds.centerY - py) }
              if (curWord != null) {
                updatePdfSelection(pl, pdfSelectStartWord!!, curWord)
              }
            }
          }
          return true
        }

        // Erasing in Document Zone
        if (activeTool == "eraser" && inDocZone) {
          eraseDocStrokesNear(sx, sy)
          eraseAnnotationNear(sx, sy)
          return true
        }

        // Scrolling Document
        if (isScrollingDoc) {
          docScrollX = (docScrollX - dx).coerceIn(0f, maxDocScrollX)
          docScrollY = (docScrollY - dy).coerceIn(0f, maxDocScrollY)
          invalidate()
          return true
        }

        // Canvas 1-finger Pan
        if (isPanningCanvas) {
          panX += dx
          panY += dy
          dispatchTransformEvent()
          invalidate()
          return true
        }

        // Dragging Notebook Page (handled before card drag)
        if (draggingNotebookPage != null) {
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)
          val newX = wx - nbPageDragOffsetWorldX
          val newY = wy - nbPageDragOffsetWorldY
          val totalDx = newX - nbPageDragStartX
          val totalDy = newY - nbPageDragStartY

          draggingNotebookPage!!.x = newX
          draggingNotebookPage!!.y = newY

          for (item in nbPageAttachedCardStarts) {
            val card = item.first
            val orig = item.second
            card.x = orig.first + totalDx
            card.y = orig.second + totalDy
          }

          for (item in nbPageAttachedStrokeStarts) {
            val stroke = item.first
            val origPoints = item.second
            stroke.points = origPoints.map { NativePoint(it.first + totalDx, it.second + totalDy) }
          }

          invalidate()
          return true
        }

        // Resizing Notebook Page
        if (resizingNotebookPage != null) {
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)
          val deltaX = wx - nbPageResizeStartWorldX
          val deltaY = wy - nbPageResizeStartWorldY
          resizingNotebookPage!!.width = (nbPageResizeStartWidth + deltaX).coerceAtLeast(120f)
          resizingNotebookPage!!.height = (nbPageResizeStartHeight + deltaY).coerceAtLeast(160f)
          invalidate()
          return true
        }

        // Dragging Excerpt Card with magnetic proximity check
        if (draggingCard != null) {
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)
          draggingCard!!.x = wx - dragOffsetWorldX
          draggingCard!!.y = wy - dragOffsetWorldY
          val snap = MagneticStackingEngine.findSnapTarget(
            draggingCard!!.x + draggingCard!!.width / 2f,
            draggingCard!!.y + draggingCard!!.getHeight() / 2f,
            cards,
            ignoreCardId = draggingCard!!.id
          )
          magneticTargetCardId = snap?.id
          invalidate()
          return true
        }

        // Cross-zone InkLink drag detection (from Doc across divider to Canvas)
        val curSplitY = if (hasDoc) viewH * effectiveSplitRatio else 0f
        val inCanvas = sy >= curSplitY - 16f || (canvasTopY > 0f && sy >= canvasTopY - 16f) || isDrawingCrossZoneLink
        if (isDrawingOnDoc && inCanvas && activeTool == "pen") {
          if (!isDrawingCrossZoneLink) {
            isDrawingCrossZoneLink = true
            activeDocPoints.clear()
            activeDocPath.reset()
          }
          inkLinkCurrentScreenTouch.set(sx, sy)
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)
          val worldMargin = (36f * density) / scaleFactor
          val hoverCard = cards.find {
            wx >= it.x - worldMargin && wx <= it.x + it.width + worldMargin &&
            wy >= it.y - worldMargin && wy <= it.y + it.getHeight() + worldMargin
          }
          inkLinkTargetCardId = hoverCard?.id
          invalidate()
          return true
        }

        // Document Page Inking (Freehand vs Straight)
        if (isDrawingOnDoc && activeDocPoints.isNotEmpty() && activeDocStrokePageIndex >= 0) {
          val pl = pageLayouts.find { it.pageIndex == activeDocStrokePageIndex }
          if (pl != null) {
            val px = ((sx - pl.boundsOnScreen.left) / pl.boundsOnScreen.width()) * activeDocStrokePageW
            val py = ((sy - pl.boundsOnScreen.top) / pl.boundsOnScreen.height()) * activeDocStrokePageH
            val pressure = event.pressure.takeIf { it > 0f } ?: 1.0f

            if ((activeTool == "pen" || activeTool == "highlighter") && penDrawingMode == "straight") {
              val startPt = activeDocPoints.first()
              activeDocPoints.clear()
              activeDocPoints.add(startPt)
              activeDocPoints.add(NativePoint(px, py, pressure))
              activeDocPath.reset()
              activeDocPath.moveTo(startPt.x, startPt.y)
              activeDocPath.lineTo(px, py)
            } else {
              val lastPt = activeDocPoints.last()
              activeDocPath.quadTo(lastPt.x, lastPt.y, (lastPt.x + px) / 2f, (lastPt.y + py) / 2f)
              activeDocPoints.add(NativePoint(px, py, pressure))
            }
            invalidate()
            return true
          }
        }

        // Canvas Inking (Freehand vs Straight)
        if (activePoints.isNotEmpty()) {
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)
          val pressure = event.pressure.takeIf { it > 0f } ?: 1.0f

          if ((activeTool == "pen" || activeTool == "highlighter") && penDrawingMode == "straight") {
            val startPt = activePoints.first()
            activePoints.clear()
            activePoints.add(startPt)
            activePoints.add(NativePoint(wx, wy, pressure))
            activePath.reset()
            activePath.moveTo(startPt.x, startPt.y)
            activePath.lineTo(wx, wy)
          } else {
            val lastPt = activePoints.last()
            activePath.quadTo(lastPt.x, lastPt.y, (lastPt.x + wx) / 2f, (lastPt.y + wy) / 2f)
            activePoints.add(NativePoint(wx, wy, pressure))
          }
          invalidate()
          return true
        }

        if (activeTool == "eraser" && inCanvasZone) {
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)
          eraseStrokesNear(wx, wy)
          return true
        }
      }

      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        val tapX = downDocX
        val tapY = downDocY
        downDocX = 0f
        downDocY = 0f
        pendingLongPressRunnable?.let {
          longPressHandler.removeCallbacks(it)
          pendingLongPressRunnable = null
        }

        if (isDraggingCrop) {
          isDraggingCrop = false
          val sel = activeCropSelection
          if (sel != null) {
            val pl = pageLayouts.find { it.pageIndex == sel.pageIndex }
            val dragDist = hypot(sx - cropStartX, sy - cropStartY)
            if (dragDist < 12f * density || sel.screenRect.width() < 24f * density || sel.screenRect.height() < 20f * density) {
              // Stationary long-press and release without intentional framing drag:
              // Provide an elegant, clean starter framing box (130dp x 90dp) centered on the touch point
              if (pl != null) {
                val defW = min(pl.boundsOnScreen.width() * 0.70f, 220f * density)
                val defH = min(pl.boundsOnScreen.height() * 0.35f, 150f * density)
                val sRect = RectF(
                  (cropStartX - defW / 2f).coerceIn(pl.boundsOnScreen.left + 8f * density, pl.boundsOnScreen.right - defW - 8f * density),
                  (cropStartY - defH / 2f).coerceIn(pl.boundsOnScreen.top + 8f * density, pl.boundsOnScreen.bottom - defH - 8f * density),
                  (cropStartX + defW / 2f).coerceIn(pl.boundsOnScreen.left + defW + 8f * density, pl.boundsOnScreen.right - 8f * density),
                  (cropStartY + defH / 2f).coerceIn(pl.boundsOnScreen.top + defH + 8f * density, pl.boundsOnScreen.bottom - 8f * density)
                )
                val pW = pl.pageSize.width
                val pH = pl.pageSize.height
                val pageLeft = (sRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
                val pageTop = (sRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
                val pageRight = (sRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
                val pageBottom = (sRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH

                val newCropSel = createCropSelection(
                  pageIndex = pl.pageIndex,
                  sRect = sRect,
                  pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
                  color = selectedColor,
                  dimensionsText = "Page ${pl.pageIndex + 1} (${pW.toInt()}x${pH.toInt()} pt)"
                )
                recomputeCropCalloutRects(newCropSel)
                activeCropSelection = newCropSel
              }
            } else {
              // User dragged out a custom crop region: preserve their framed box!
              recomputeCropCalloutRects(sel)
            }
          }
          invalidate()
        }

        isDraggingStartHandle = false
        isDraggingEndHandle = false
        isDraggingCropTopLeftHandle = false
        isDraggingCropTopRightHandle = false
        isDraggingCropBottomLeftHandle = false
        isDraggingCropBottomRightHandle = false
        isMovingCropSelection = false
        val wasDraggingDivider = isDraggingDivider
        isDraggingDivider = false
        if (wasDraggingDivider) {
          lastUserDividerDragTime = System.currentTimeMillis()
          dispatchSplitRatioEvent(splitRatio)
        }
        val wasPanningCanvas = isPanningCanvas
        isPanningCanvas = false
        isSelectingPdfText = false
        isDraggingCrop = false

        // Finalize notebook page drag (record undo with attached cards & strokes) or resize
        val droppedPage = draggingNotebookPage
        if (droppedPage != null) {
          val dist = hypot(droppedPage.x - nbPageDragStartX, droppedPage.y - nbPageDragStartY)
          if (dist > 2f) {
            val movedCards = nbPageAttachedCards.toList()
            val movedStrokes = nbPageAttachedStrokes.toList()
            val totalDx = droppedPage.x - nbPageDragStartX
            val totalDy = droppedPage.y - nbPageDragStartY

            undoRedoManager.record(MoveNotebookPageAction(
              pageId = droppedPage.id,
              prevX = nbPageDragStartX,
              prevY = nbPageDragStartY,
              newX = droppedPage.x,
              newY = droppedPage.y,
              pagesList = notebookPages,
              cardsList = movedCards,
              strokesList = movedStrokes,
              deltaX = totalDx,
              deltaY = totalDy,
              onPositionChanged = { id, x, y ->
                dispatchNotebookPageMovedEvent(id, x, y)
                persistNotebookPagesLocally()
                invalidate()
              }
            ))
            dispatchNotebookPageMovedEvent(droppedPage.id, droppedPage.x, droppedPage.y)
            persistNotebookPagesLocally()
          }
          draggingNotebookPage = null
          nbPageAttachedCards = emptyList()
          nbPageAttachedStrokes = emptyList()
          nbPageAttachedCardStarts = emptyList()
          nbPageAttachedStrokeStarts = emptyList()
        }

        val resizedPage = resizingNotebookPage
        if (resizedPage != null) {
          val dw = abs(resizedPage.width - nbPageResizeStartWidth)
          val dh = abs(resizedPage.height - nbPageResizeStartHeight)
          if (dw > 2f || dh > 2f) {
            undoRedoManager.record(ResizeNotebookPageAction(
              pageId = resizedPage.id,
              prevWidth = nbPageResizeStartWidth,
              prevHeight = nbPageResizeStartHeight,
              newWidth = resizedPage.width,
              newHeight = resizedPage.height,
              pagesList = notebookPages,
              onSizeChanged = { _, _, _ ->
                persistNotebookPagesLocally()
                invalidate()
              }
            ))
            persistNotebookPagesLocally()
          }
          resizingNotebookPage = null
        }

        // Compute Scroll Fling Inertia for Document
        if (isScrollingDoc) {
          if (totalDragDistance > touchSlop) {
            velocityTracker?.let { vt ->
              vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
              val vy = vt.yVelocity
              val vx = vt.xVelocity
              if (abs(vy) > minFlingVelocity || abs(vx) > minFlingVelocity) {
                val flingVy = -vy * 1.8f
                val flingVx = -vx * 1.8f
                docScroller.fling(
                  docScrollX.toInt(), docScrollY.toInt(),
                  flingVx.toInt(), flingVy.toInt(),
                  0, maxDocScrollX.toInt(),
                  0, maxDocScrollY.toInt(),
                  0, (150f * density).toInt()
                )
                postInvalidateOnAnimation()
              }
            }
          } else {
            handleDocTap(tapX, tapY)
          }
          isScrollingDoc = false
        }
        velocityTracker?.recycle()
        velocityTracker = null

        // Dropping Lifted Excerpt onto Canvas with Stacking & Collision Avoidance matching Video!
        if (isLiftingExcerpt && liftCandidateText != null) {
          if (sy >= canvasTopY) {
            val (dropWx, dropWy) = canvasScreenToWorld(sx, sy, canvasTopY)
            val newId = "card-${System.currentTimeMillis()}"

            var finalImgPath = liftCandidateImagePath
            if (liftCandidateIsImage && liftCandidateBitmap != null) {
              if (finalImgPath.isNullOrEmpty()) {
                finalImgPath = saveCropToFile(liftCandidateBitmap!!)
              }
              cardBitmapCache.put(finalImgPath, liftCandidateBitmap!!)
              cardBitmapCache.put(newId, liftCandidateBitmap!!)
              cardBitmapCache.put("page_${liftCandidatePage}_image", liftCandidateBitmap!!)
            }

            val snapTarget = if (magneticTargetCardId != null) cards.find { it.id == magneticTargetCardId } else null
            if (snapTarget != null) {
              // Magnetically stack into pile!
              val newExcerpt = GroupedExcerpt(
                id = newId,
                text = liftCandidateText!!,
                pageNumber = liftCandidatePage,
                color = liftCandidateColor,
                isImage = liftCandidateIsImage,
                imageUrl = finalImgPath
              )
              MagneticStackingEngine.stackIntoCard(snapTarget, newExcerpt)
              links.add(NativeLink("link-${System.currentTimeMillis()}", snapTarget.id, liftCandidateColor))
              triggerShockwave(snapTarget.x + snapTarget.width / 2f, snapTarget.y + snapTarget.getHeight() / 2f, liftCandidateColor)
              performHapticFeedback(HapticFeedbackConstants.CONFIRM)
              hudToast.show("Magnetically stacked with nearby card!")
            } else {
              // Resolve non-overlapping placement!
              val cardW = if (liftCandidateIsImage) 240f else 220f
              val cardH = if (liftCandidateIsImage) 160f else 115f
              val resolvedPos = CollisionPlacementSolver.findNonOverlappingPosition(
                desiredX = dropWx - cardW / 2f,
                desiredY = dropWy - cardH / 2f,
                cardWidth = cardW,
                cardHeight = cardH,
                existingCards = cards
              )

              val card = NativeCard(
                id = newId,
                x = resolvedPos.x,
                y = resolvedPos.y,
                width = cardW,
                text = liftCandidateText!!,
                color = liftCandidateColor,
                pageNumber = liftCandidatePage,
                comment = null,
                clusterId = null,
                stackCount = 1,
                isImage = liftCandidateIsImage,
                imageUrl = finalImgPath,
                isTable = false,
                tableRows = null,
                sourceRects = liftCandidateSourceRects,
                documentId = activeDocumentId
              )
              cards.add(card)

              val newLink = NativeLink("link-${System.currentTimeMillis()}", newId, liftCandidateColor)
              links.add(newLink)
              undoRedoManager.record(CreateCardAction(
                card = card,
                link = newLink,
                cardsList = cards,
                linksList = links,
                onUndoDispatched = { c ->
                  dispatchCardDeleteEvent(c.id)
                  invalidate()
                },
                onRedoDispatched = { c, _ ->
                  dispatchExtractExcerptEvent(
                    c.text, c.pageNumber, c.color, c.isImage, c.imageUrl, c.x, c.y, c.id, c.sourceRects
                  )
                  invalidate()
                }
              ))
              triggerShockwave(resolvedPos.x + cardW / 2f, resolvedPos.y + cardH / 2f, liftCandidateColor)
              performHapticFeedback(HapticFeedbackConstants.CONFIRM)
              dispatchExtractExcerptEvent(
                card.text,
                card.pageNumber,
                card.color,
                card.isImage,
                card.imageUrl,
                card.x,
                card.y,
                card.id,
                card.sourceRects
              )

              if (liftCandidateIsImage) {
                hudToast.show("Extracted figure placed neatly with live Ink-Link!")
              } else {
                hudToast.show("Excerpt placed without overlap with live Ink-Link!")
              }
            }
          }
          isLiftingExcerpt = false
          liftCandidateText = null
          liftCandidateBitmap = null
          liftCandidateImagePath = null
          liftCandidateSourceRects = emptyList()
          magneticTargetCardId = null
          activePdfSelection = null
          activeCropSelection = null
          docMode = "text"
          invalidate()
          return true
        }

        // Dropping Dragged Card with Snapping & Stacking
        if (draggingCard != null) {
          val card = draggingCard!!
          val snapTarget = if (magneticTargetCardId != null) cards.find { it.id == magneticTargetCardId && it.id != card.id } else null
          if (snapTarget != null) {
            val prevLink = links.find { it.sourceExcerptId == card.id }
            val newExcerpt = GroupedExcerpt(
              id = card.id,
              text = card.text,
              pageNumber = card.pageNumber,
              color = card.color,
              isImage = card.isImage,
              imageUrl = card.imageUrl
            )
            MagneticStackingEngine.stackIntoCard(snapTarget, newExcerpt)
            cards.remove(card)
            links.removeIf { it.sourceExcerptId == card.id }
            val newLink = NativeLink("link-${System.currentTimeMillis()}", snapTarget.id, card.color)
            links.add(newLink)
            undoRedoManager.record(StackCardAction(
              targetCardId = snapTarget.id,
              stackedItem = newExcerpt,
              originalCard = card,
              previousLink = prevLink,
              cardsList = cards,
              linksList = links,
              onUndoDispatched = { restoredCard, restoredLink ->
                dispatchExtractExcerptEvent(
                  restoredCard.text,
                  restoredCard.pageNumber,
                  restoredCard.color,
                  restoredCard.isImage,
                  restoredCard.imageUrl,
                  restoredCard.x,
                  restoredCard.y,
                  restoredCard.id,
                  restoredCard.sourceRects
                )
                invalidate()
              },
              onRedoDispatched = { _, _ ->
                dispatchCardDeleteEvent(card.id)
                invalidate()
              }
            ))
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            hudToast.show("Magnetically stacked with nearby card!")
          } else {
            val dist = hypot(card.x - dragCardStartX, card.y - dragCardStartY)
            if (dist > 2f) {
              val startX = dragCardStartX
              val startY = dragCardStartY
              val endX = card.x
              val endY = card.y
              undoRedoManager.record(MoveCardAction(
                cardId = card.id,
                prevX = startX,
                prevY = startY,
                newX = endX,
                newY = endY,
                cardsList = cards,
                onPositionChanged = { id, x, y ->
                  dispatchExcerptMoveEndEvent(id, x, y)
                  invalidate()
                }
              ))
            }
            dispatchExcerptMoveEndEvent(card.id, card.x, card.y)
          }
          draggingCard = null
          magneticTargetCardId = null
          invalidate()
          return true
        }

        // Finalize Cross-Zone Semantic InkLink
        if (isDrawingCrossZoneLink) {
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)
          val worldMargin = (36f * density) / scaleFactor
          val targetCard = cards.find {
            wx >= it.x - worldMargin && wx <= it.x + it.width + worldMargin &&
            wy >= it.y - worldMargin && wy <= it.y + it.getHeight() + worldMargin
          } ?: (inkLinkTargetCardId?.let { tid -> cards.find { it.id == tid } })
          if (targetCard != null) {
            val cardPointX = (wx - targetCard.x).coerceIn(0f, targetCard.width)
            val cardPointY = (wy - targetCard.y).coerceIn(0f, targetCard.getHeight())
            val normX = (cardPointX / targetCard.width.coerceAtLeast(1f)).coerceIn(0.02f, 0.98f)
            val normY = (cardPointY / targetCard.getHeight().coerceAtLeast(1f)).coerceIn(0.02f, 0.98f)
            val srcDocId = inkLinkSourceDocId.ifEmpty { activeDocumentId.ifEmpty { "default-doc" } }
            val newLink = NativeInkLink(
              id = "inklink-${System.currentTimeMillis()}",
              sourceDocId = srcDocId,
              sourcePageIndex = inkLinkSourcePageIndex,
              sourcePdfRect = inkLinkSourcePdfRect,
              sourcePdfPoint = inkLinkSourcePdfPoint,
              targetCardId = targetCard.id,
              color = penColor,
              strokeWidth = penThickness.coerceAtLeast(3.2f),
              style = "straight",
              createdAt = System.currentTimeMillis(),
              targetCardPoint = NativePoint(cardPointX, cardPointY),
              cardAnchorX = normX,
              cardAnchorY = normY
            )
            semanticInkLinks.add(newLink)
            undoRedoManager.record(CreateSemanticInkLinkAction(
              link = newLink,
              linksList = semanticInkLinks,
              onUndoDispatched = { l ->
                dispatchInkLinkDeleteEvent(l.id)
                persistSemanticInkLinksLocally()
                invalidate()
              },
              onRedoDispatched = { l ->
                dispatchInkLinkCreateEvent(l)
                persistSemanticInkLinksLocally()
                invalidate()
              }
            ))
            dispatchInkLinkCreateEvent(newLink)
            persistSemanticInkLinksLocally()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            hudToast.show("Linked Document p.${inkLinkSourcePageIndex + 1} to Card")
          }
          selectedCardId = null
          draggingCard = null
          isDrawingCrossZoneLink = false
          inkLinkTargetCardId = null
          activeDocPoints.clear()
          activeDocPath.reset()
          isDrawingOnDoc = false
          invalidate()
          return true
        }

        // Finalize Document Inking Stroke
        if (isDrawingOnDoc && activeDocPoints.isNotEmpty() && activeDocStrokePageIndex >= 0) {
          val isHl = activeTool == "highlighter"
          val isStraight = (activeTool == "pen" || activeTool == "highlighter") && penDrawingMode == "straight"
          val newPageStroke = PdfPageStroke(
            id = "doc-stroke-${System.currentTimeMillis()}",
            pageIndex = activeDocStrokePageIndex,
            points = activeDocPoints.toList(),
            color = if (isHl) selectedColor else penColor,
            strokeWidth = if (isHl) max(penThickness, 12f) else penThickness,
            isHighlighter = isHl,
            isStraight = isStraight
          )
          val list = pageStrokes.getOrPut(activeDocStrokePageIndex) { mutableListOf() }
          list.add(newPageStroke)

          undoRedoManager.record(AddPageStrokeAction(
            stroke = newPageStroke,
            pageStrokesMap = pageStrokes,
            onUndoDispatched = { invalidate() },
            onRedoDispatched = { invalidate() }
          ))

          activeDocPoints.clear()
          activeDocPath.reset()
          activeDocStrokePageIndex = -1
          isDrawingOnDoc = false
          invalidate()
          return true
        }

        // Finalize Canvas Inking Stroke
        if (activePoints.isNotEmpty()) {
          val isHl = activeTool == "highlighter"
          val isStraight = (activeTool == "pen" || activeTool == "highlighter") && penDrawingMode == "straight"
          val newStroke = NativeStroke(
            id = "stroke-${System.currentTimeMillis()}",
            points = activePoints.toList(),
            color = if (isHl) selectedColor else penColor,
            strokeWidth = if (isHl) max(penThickness, 12f) else penThickness,
            isHighlighter = isHl,
            isStraight = isStraight
          )
          strokes.add(newStroke)
          undoRedoManager.record(AddStrokeAction(
            stroke = newStroke,
            strokesList = strokes,
            onUndoDispatched = { s ->
              dispatchEraseStrokeEvent(s.id)
              invalidate()
            },
            onRedoDispatched = { s ->
              dispatchAddStrokeEvent(s)
              invalidate()
            }
          ))
          dispatchAddStrokeEvent(newStroke)
          activePoints.clear()
          activePath.reset()
          invalidate()
          return true
        }

        // Clean Canvas Background Tap Detection:
        // If the tap was inside the canvas zone, was not dragged, had no selections/modals open,
        // and no card or page was touched, toggle immersive mode!
        if (sy >= canvasTopY && wasPanningCanvas && totalDragDistance <= touchSlop && !hadSelectionOrModalBeforeTouch && draggingCard == null && resizingNotebookPage == null && draggingNotebookPage == null && !isLiftingExcerpt) {
          toggleImmersiveMode()
          return true
        }
      }
    }

    return true
  }

  // ---------------------------------------------------------------------------
  // Document Tap Selection & Real PDF Word Selection Engine
  // Extracted to ThinkspaceViewSelection.kt as extension functions
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // Excerpt & Annotation Helpers, Erasing & Document Scroll Navigation
  // Extracted to ThinkspaceViewExcerpt.kt as extension functions
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // Native PDF Engine Search & Navigation
  // Extracted to ThinkspaceViewSearch.kt as extension functions
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // Native LiquidText Document Drawer & Folder Management System
  // Extracted to ThinkspaceViewDrawer.kt as extension functions
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // React Native Fabric Event Dispatchers
  // ---------------------------------------------------------------------------
  // ---------------------------------------------------------------------------
  // Notebook Page — Rendering, API, Persistence & Events
  // Extracted to ThinkspaceViewNotebook.kt as extension functions
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // Immersive / Distraction-Free Content Mode API & Event
  // ---------------------------------------------------------------------------
  fun isImmersiveMode(): Boolean = isImmersive

  fun setImmersiveMode(enabled: Boolean) {
    if (isImmersive == enabled) return
    isImmersive = enabled
    setSystemBarsImmersive(enabled)
    dispatchToggleImmersiveEvent(enabled)
    invalidate()
  }

  fun toggleImmersiveMode() {
    setImmersiveMode(!isImmersive)
  }

  fun setSystemBarsImmersive(enabled: Boolean) {
    post {
      val reactContext = UIManagerHelper.getReactContext(this) ?: return@post
      val activity = reactContext.currentActivity ?: return@post
      val window = activity.window ?: return@post
      val controller = WindowCompat.getInsetsController(window, window.decorView)
      if (enabled) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
      } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
      }
    }
  }

  internal fun dispatchToggleImmersiveEvent(enabled: Boolean) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putBoolean("isImmersive", enabled)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topToggleImmersive", data))
  }

  // ---------------------------------------------------------------------------
  // LiquidText Real Pen & Semantic Inking System API & Events
  // Extracted to ThinkspaceViewInking.kt as extension functions
  // ---------------------------------------------------------------------------
}
