package com.thinkspace

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.widget.EditText
import android.widget.FrameLayout
import android.content.ClipboardManager
import android.content.Context
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
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ThinkspaceEvent(
  surfaceId: Int,
  viewTag: Int,
  private val customName: String,
  private val eventData: WritableMap
) : Event<ThinkspaceEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = customName
  override fun getEventData(): WritableMap = eventData
}

data class NativePoint(val x: Float, val y: Float, val pressure: Float = 1.0f)

data class PdfPageStroke(
  val id: String,
  val pageIndex: Int,
  val points: List<NativePoint>,
  val color: Int,
  val strokeWidth: Float,
  val isHighlighter: Boolean,
  val isStraight: Boolean = false
)

data class ParagraphLayoutInfo(
  val secId: String,
  val pIdx: Int,
  val pageNumber: Int,
  val text: String,
  val paperX: Float,
  val topY: Float,
  val width: Float,
  val layout: StaticLayout
)

data class PdfPageLayout(
  val pageIndex: Int,
  val pageNumber: Int,
  val pageSize: PageSize,
  val topY: Float,
  val height: Float,
  val isFolded: Boolean,
  val boundsOnScreen: RectF
)

data class NativeSearchMatch(
  val pageIndex: Int,
  val matchedText: String,
  val rects: List<RectF>,
  val contextSnippet: String = ""
)

data class NativeStroke(
  val id: String,
  var points: List<NativePoint>,
  val color: Int,
  val strokeWidth: Float,
  val isHighlighter: Boolean,
  val isStraight: Boolean = false
)

data class NativeTableRow(val cells: List<String>)
data class NativeTable(val rows: List<NativeTableRow>)

data class NativeSection(
  val id: String,
  val pageNumber: Int,
  val heading: String,
  val paragraphs: List<String>,
  val tables: List<NativeTable>?,
  val imageUrl: String?
)

data class NativeDoc(
  val id: String,
  val title: String,
  val pageCount: Int,
  val sections: List<NativeSection>
)

data class NativeAnnotation(
  val id: String,
  val sectionId: String,
  val paragraphIndex: Int,
  val pageNumber: Int,
  val color: Int,
  val text: String,
  val rects: List<RectF> = emptyList()
)

data class NativeCard(
  val id: String,
  var x: Float,
  var y: Float,
  var width: Float,
  var text: String,
  var color: Int,
  val pageNumber: Int,
  var comment: String?,
  var clusterId: String?,
  var stackCount: Int,
  val isImage: Boolean,
  val imageUrl: String?,
  val isTable: Boolean,
  val tableRows: List<NativeTableRow>?,
  var groupedItems: MutableList<GroupedExcerpt>? = null,
  // Source location: PDF-page-space rects of original selection (for pulse-highlight on navigation)
  val sourceRects: List<RectF> = emptyList(),
  var fontSize: Float = 13f,
  var isBold: Boolean = false,
  var isItalic: Boolean = false,
  var isUnderline: Boolean = false,
  var isStrikethrough: Boolean = false,
  var textColor: Int = Color.parseColor("#1E293B"),
  var textStyleName: String = "Default",
  val undoTextStack: ArrayDeque<String> = ArrayDeque(),
  /**
   * Multi-document: ID of the source document this card was extracted from.
   * Empty string = legacy card (uses the single active document).
   * Used for source badge rendering and source-jump navigation.
   */
  val documentId: String = ""
) {
  fun getHeight(): Float {
    return when {
      !groupedItems.isNullOrEmpty() && groupedItems!!.size > 1 -> {
        val baseH = if (groupedItems!!.any { it.isImage }) 175f else 140f
        baseH + (groupedItems!!.size - 1) * 8f
      }
      isTable -> 170f
      isImage -> 160f
      else -> {
        // Calculate dynamic height based on text layout!
        val tp = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
          textSize = (fontSize * 1.8f).coerceAtLeast(18f)
          if (isBold && isItalic) {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
          } else if (isBold) {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
          } else if (isItalic) {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
          } else {
            typeface = android.graphics.Typeface.DEFAULT
          }
        }
        val textW = Math.max(20f, width - 28f).toInt()
        val textToMeasure = if (text.isEmpty()) " " else text
        val layout = android.text.StaticLayout.Builder
          .obtain(textToMeasure, 0, textToMeasure.length, tp, textW)
          .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
          .build()
        Math.max(105f, layout.height.toFloat() + 56f) // 56f for header/footer padding
      }
    }
  }
}

data class NativeLink(
  val id: String,
  val sourceExcerptId: String,
  val color: Int
)

data class NativeInkLink(
  val id: String,
  val sourceDocId: String,
  val sourcePageIndex: Int,
  val sourcePdfRect: RectF,
  val sourcePdfPoint: NativePoint,
  val targetCardId: String,
  val color: Int,
  val strokeWidth: Float,
  val style: String = "straight",
  val createdAt: Long = System.currentTimeMillis(),
  val targetCardPoint: NativePoint? = null,
  val cardAnchorX: Float = 0.5f,
  val cardAnchorY: Float = 0.5f
)

data class NativeDocumentSelection(
  val text: String,
  val pageNumber: Int,
  val sectionId: String,
  val pIdx: Int,
  val startCharIdx: Int,
  val endCharIdx: Int,
  val highlightRects: List<RectF>,
  val startHandle: RectF,
  val endHandle: RectF,
  val calloutRect: RectF,
  val calloutExcerptBtn: RectF,
  val calloutCopyBtn: RectF,
  val calloutHighlightBtn: RectF,
  val calloutCloseBtn: RectF
)

data class NativePdfSelection(
  val pageIndex: Int,
  val text: String,
  val highlightRects: List<RectF>,
  val pdfRects: List<RectF>,
  val startHandle: RectF,
  val endHandle: RectF,
  val calloutRect: RectF,
  val calloutExcerptBtn: RectF,
  val calloutCopyBtn: RectF,
  val calloutHighlightBtn: RectF,
  val calloutCloseBtn: RectF,
  val startWordIndex: Int = 0,
  val endWordIndex: Int = 0,
  val calloutAddWordLeftBtn: RectF = RectF(),
  val calloutAddWordRightBtn: RectF = RectF(),
  val calloutSelectAllBtn: RectF = RectF(),
  val charCountText: String = "",
  val calloutColorBtns: List<Pair<RectF, Int>> = emptyList(),
  val calloutMoreBtn: RectF = RectF(),
  val calloutTagsBtn: RectF = RectF(),
  val calloutSubCardRect: RectF = RectF(),
  val calloutMainCardRect: RectF = RectF(),
  val calloutRainbowBtn: RectF = RectF()
)

data class CalloutLayoutResult(
  val calloutRect: RectF,
  val closeBtn: RectF,
  val excerptBtn: RectF,
  val copyBtn: RectF,
  val highlightBtn: RectF,
  val addWordLeftBtn: RectF,
  val addWordRightBtn: RectF,
  val selectAllBtn: RectF,
  val colorBtns: List<Pair<RectF, Int>>,
  val moreBtn: RectF = RectF(),
  val tagsBtn: RectF = RectF(),
  val subCardRect: RectF = RectF(),
  val mainCardRect: RectF = RectF(),
  val rainbowBtn: RectF = RectF()
)

data class NativeCropSelection(
  val pageIndex: Int,
  var pageBounds: BoundingBox,
  var screenRect: RectF,
  val calloutRect: RectF = RectF(),
  val calloutHighlightBtn: RectF = RectF(),
  val calloutExcerptBtn: RectF = RectF(),
  val calloutCloseBtn: RectF = RectF(),
  val calloutCommentBtn: RectF = RectF(),
  val calloutBookmarkBtn: RectF = RectF(),
  val calloutTagsBtn: RectF = RectF(),
  var color: Int = Color.parseColor("#3B82F6"),
  val dimensionsText: String = "",
  val holdAndDragRect: RectF = RectF(),
  val calloutColorBtns: MutableList<Pair<RectF, Int>> = mutableListOf()
)

/**
 * A Notebook Page — a movable, resizable paper-like object on the infinite canvas workspace.
 * Like a physical sheet placed on the canvas; cards, strokes, and drawings can sit on top of it.
 */
data class NativeNotebookPage(
  val id: String,
  var x: Float,
  var y: Float,
  var width: Float,
  var height: Float,
  var pageStyle: String = "ruled",       // "blank", "ruled", "grid", "dotted", "sketch"
  var title: String = "Notebook Page",
  var backgroundColor: Int = Color.parseColor("#FFFEF0")
)

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

  private fun isKeyboardActive(): Boolean {
    return editingCardId != null || getKeyboardHeight() > 50f * density
  }

  private fun getKeyboardHeight(): Float {
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
  private var activePdfDoc: PdfDocument? = null
  private val pageLayouts = mutableListOf<PdfPageLayout>()
  // Cache up to 32 rendered pages in memory
  private val pageBitmaps = LruCache<Int, Bitmap>(32)
  private val renderingPages = ConcurrentHashMap.newKeySet<Int>()
  private val pageWordsCache = ConcurrentHashMap<Int, List<TextWord>>()
  private val extractingWords = ConcurrentHashMap.newKeySet<Int>()
  private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  // ── Multi-document workspace registry ───────────────────────────────────────
  /**
   * Registry of all PDF documents currently opened in this workspace.
   * Keys are documentId strings matching WorkspaceDocumentEntry.id.
   * Values are opened PdfDocument handles (shared with PdfEngineModule).
   * Documents are opened lazily when first needed.
   */
  private val documentRegistry = ConcurrentHashMap<String, PdfDocument>()

  /**
   * Ordered list of workspace document entries (metadata only).
   * Kept in insertion order. The PDF renderer uses documentRegistry for actual docs.
   */
  private val workspaceDocumentEntries = mutableListOf<WorkspaceDocumentEntry>()

  /**
   * ID of the document currently visible in the PDF/document viewport.
   * Changing this triggers a viewport switch without touching the workspace canvas.
   */
  private var activeDocumentId: String = ""

  /** Pending page number and source rects to scroll to when switching documents */
  private var pendingScrollToPage: Int? = null
  private var pendingPulseRects: List<RectF>? = null

  /** Color palette for auto-assigning document accent colors. */
  private val docColorPalette = listOf(
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

  // ── Multi-document: simple data holder for workspace entry metadata ──────────
  data class WorkspaceDocumentEntry(
    val id: String,
    val title: String,
    val pageCount: Int,
    val uri: String,
    val colorAccent: String = "#00ADB5"
  )

  // Document & Annotation Models (Fallback/Demo Doc)
  private var activeDocument: NativeDoc? = null
  private val annotations = mutableListOf<NativeAnnotation>()
  private var docScrollY: Float = 0f
  private var maxDocScrollY: Float = 1000f
  private var docScrollX: Float = 0f
  private var maxDocScrollX: Float = 0f
  private var pdfScaleFactor: Float = 1.0f

  // Document interaction mode: "text" or "crop"
  private var docMode: String = "text"

  // Display density & subheader metrics
  private val density: Float get() = context.resources.displayMetrics.density
  private val subheaderH: Float get() = 48f * density

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
  private val strokes = mutableListOf<NativeStroke>()
  private val cards = mutableListOf<NativeCard>()
  private val links = mutableListOf<NativeLink>()

  // ── Notebook Pages ──────────────────────────────────────────────────────────
  /** All notebook page objects currently on the canvas (world-space). */
  private val notebookPages = mutableListOf<NativeNotebookPage>()
  /** ID of the currently-selected notebook page (null = none selected). */
  private var selectedNotebookPageId: String? = null
  /** The page currently being dragged (finger down on page body). */
  private var draggingNotebookPage: NativeNotebookPage? = null
  private var nbPageDragOffsetWorldX: Float = 0f
  private var nbPageDragOffsetWorldY: Float = 0f
  private var nbPageDragStartX: Float = 0f
  private var nbPageDragStartY: Float = 0f
  /** The page currently being resized (finger on bottom-right handle). */
  private var resizingNotebookPage: NativeNotebookPage? = null
  private var nbPageResizeStartWidth: Float = 0f
  private var nbPageResizeStartHeight: Float = 0f
  private var nbPageResizeStartWorldX: Float = 0f
  private var nbPageResizeStartWorldY: Float = 0f
  /** World-space hit rects for per-page controls (keyed by page.id). */
  private val nbPageDeleteRects = HashMap<String, RectF>()
  private val nbPageStyleBtnRects = HashMap<String, RectF>()
  private val nbPageDuplicateRects = HashMap<String, RectF>()
  private val nbPageResizeRects = HashMap<String, RectF>()
  private var nbPageAttachedCards: List<NativeCard> = emptyList()
  private var nbPageAttachedStrokes: List<NativeStroke> = emptyList()
  private var nbPageAttachedCardStarts: List<Pair<NativeCard, Pair<Float, Float>>> = emptyList()
  private var nbPageAttachedStrokeStarts: List<Pair<NativeStroke, List<Pair<Float, Float>>>> = emptyList()
  /** Style picker overlay state. */
  private var isNotebookStylePickerOpen: Boolean = false
  private var stylePickerForPageId: String? = null
  private val nbPageStylePickerRect = RectF()
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
  private var selectedCardId: String? = null
  private var draggingCard: NativeCard? = null
  private var dragOffsetWorldX: Float = 0f
  private var dragOffsetWorldY: Float = 0f
  private var totalDragDistance: Float = 0f
  private var heldCardId: String? = null

  // LiquidText Excerpt Card Toolbar & Typography state
  private var isTypographyBarVisible = false
  private var isStyleSheetOpen = false
  private var isCardColorPaletteOpen = false
  private var isTypoTextColorPaletteOpen = false
  private var editingCardId: String? = null
  private var cursorPosition: Int = 0
  private var isCursorBlinkVisible = true
  private var lastCursorBlinkTime = 0L

  // Action Bar rects (Screen space)
  private val cardActionBarRect = RectF()
  private val btnCardCommentRect = RectF()
  private val btnCardEditRect = RectF()
  private val btnCardCopyRect = RectF()
  private val btnCardDeleteRect = RectF()
  private val btnCardTagsRect = RectF()
  private val btnCardColorWheelRect = RectF()
  private val btnCardTypographyRect = RectF()
  private val cardColorPaletteRects = mutableListOf<Pair<RectF, Int>>()

  // Typography Bar rects (Screen space)
  private val typographyBarRect = RectF()
  private val btnTypoUndoRect = RectF()
  private val btnTypoStyleRect = RectF()
  private val btnTypoBoldRect = RectF()
  private val btnTypoItalicRect = RectF()
  private val btnTypoUnderlineRect = RectF()
  private val btnTypoStrikeRect = RectF()
  private val btnTypoFontSizeRect = RectF()
  private val btnTypoTextColorRect = RectF()
  private val btnTypoMoreRect = RectF()
  private val typoTextColorPaletteRects = mutableListOf<Pair<RectF, Int>>()

  // Style Sheet Popover rects (Screen space)
  private val styleSheetRect = RectF()
  private val styleOptionRects = mutableListOf<Triple<RectF, String, RectF>>()

  // Gesture flags
  private var isPanningCanvas = false
  private var isDraggingDivider = false
  private var isScrollingDoc = false
  private var lastTouchScreenX = 0f
  private var lastTouchScreenY = 0f

  // Immersive / Distraction-Free Content Mode state
  private var isImmersive = false
  private var hadSelectionOrModalBeforeTouch = false

  // Real PDF Selection state
  private var activePdfSelection: NativePdfSelection? = null
  private var isSelectingPdfText = false
  private var pdfSelectStartWord: TextWord? = null
  private var pdfSelectPageIndex = 0

  // Figure Crop state
  private var activeCropSelection: NativeCropSelection? = null
  private var isDraggingCrop = false
  private var isDraggingCropTopLeftHandle = false
  private var isDraggingCropBottomRightHandle = false
  private var isMovingCropSelection = false
  private var cropDragOffsetDocX = 0f
  private var cropDragOffsetDocY = 0f
  private var cropStartX = 0f
  private var cropStartY = 0f
  private var cropPageIndex = 0

  // Text Selection Handle & Word Navigation state
  private val paragraphLayouts = mutableListOf<ParagraphLayoutInfo>()
  private var activeSelection: NativeDocumentSelection? = null
  private var activeStructuredPInfo: ParagraphLayoutInfo? = null
  private var activeStructuredStartOffset = 0
  private var activeStructuredEndOffset = 0
  private var isDraggingStartHandle = false
  private var isDraggingEndHandle = false

  // Cross-Zone Lift-and-Drag state
  private var isLiftingExcerpt = false
  private var liftCandidateText: String? = null
  private var liftCandidatePage: Int = 1
  private var liftCandidateColor: Int = Color.parseColor("#00ADB5")
  private var liftCandidateIsImage = false
  private var liftCandidateImagePath: String? = null
  private var liftCandidateBitmap: Bitmap? = null
  private var liftCandidateSourceRects: List<RectF> = emptyList() // PDF-space rects for source pulse
  private var liftGhostX = 0f
  private var liftGhostY = 0f
  private var liftAnchorScreenX = 0f
  private var liftAnchorScreenY = 0f

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
  private val cardBitmapCache = LruCache<String, Bitmap>(32)

  // Bidirectional Navigation Pulse
  private var pulsePageNumber: Int? = null
  private var pulseAlpha: Int = 0
  // Exact PDF-page-space rects to highlight during pulse (empty → pulse whole page border)
  private var pulseSourceRects: List<RectF> = emptyList()

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
  private var copiedToastText: String? = null
  private val hudToast = HudToastRenderer(density)
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
  private var isSearchActive = false
  private var isSearching = false
  private var currentSearchQuery = ""
  private val searchMatches = mutableListOf<NativeSearchMatch>()
  private var currentSearchIndex = 0
  // Cancels the previous incremental search when a new query starts
  private var searchJob: kotlinx.coroutines.Job? = null
  // Native overlay panel (real Android View, not canvas-drawn)
  private var searchOverlayView: android.view.ViewGroup? = null
  private var searchCounterLabel: android.widget.TextView? = null

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
  private val nbPageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#40000000"); style = Paint.Style.FILL
  }
  private val nbPageSelectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#3B82F6"); strokeWidth = 3f; style = Paint.Style.STROKE
  }
  private val nbPageNormalBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#C8C5A6"); strokeWidth = 1.5f; style = Paint.Style.STROKE
  }
  private val nbPageRuledLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#A8C4E8"); strokeWidth = 0.9f; style = Paint.Style.STROKE
  }
  private val nbPageMarginLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#F0A0A0"); strokeWidth = 1.2f; style = Paint.Style.STROKE
  }
  private val nbPageGridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#C8C8C8"); strokeWidth = 0.7f; style = Paint.Style.STROKE
  }
  private val nbPageDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#B8B8B8"); style = Paint.Style.FILL
  }
  private val nbPageToolbarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B"); style = Paint.Style.FILL
  }
  private val nbPageToolbarBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#334155"); strokeWidth = 1.2f; style = Paint.Style.STROKE
  }
  private val nbPageResizeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
      if (compressionEngine.isManualPinching) {
        return true
      }
      val fy = detector.focusY
      val splitY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio else 0f
      if (fy < splitY) {
        // Document zone zoom (when not two-finger pinch-compressing)
        prevCanvasFocusX = Float.NaN
        prevCanvasFocusY = Float.NaN
        val prevScale = pdfScaleFactor
        pdfScaleFactor *= detector.scaleFactor
        pdfScaleFactor = max(1.0f, min(pdfScaleFactor, 5.0f))
        
        val scaleChange = pdfScaleFactor / prevScale
        val focusDocX = detector.focusX + docScrollX
        val focusDocY = detector.focusY + docScrollY
        
        docScrollX = (focusDocX * scaleChange - detector.focusX).coerceAtLeast(0f)
        docScrollY = (focusDocY * scaleChange - detector.focusY).coerceAtLeast(0f)
        invalidate()
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
  }
  private val scaleGestureDetector = ScaleGestureDetector(context ?: throw IllegalStateException("Context required"), scaleGestureListener)


  // Long-press gesture detector for Android-style Text Selection (handles & copy/paste callout)
  private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onLongPress(e: MotionEvent) {
      triggerLongPressSelect(e.x, e.y)
    }
  })

  private fun createCropSelection(
    pageIndex: Int,
    sRect: RectF,
    pageBounds: BoundingBox,
    color: Int = selectedColor,
    dimensionsText: String = ""
  ): NativeCropSelection {
    val sel = NativeCropSelection(
      pageIndex = pageIndex,
      pageBounds = pageBounds,
      screenRect = sRect,
      color = color,
      dimensionsText = dimensionsText
    )
    recomputeCropCalloutRects(sel)
    return sel
  }

  private fun recomputeCropCalloutRects(sel: NativeCropSelection) {
    val cW = min(width - 24f * density, 340f * density)
    val cH = 64f * density
    val cLeft = (sel.screenRect.centerX() - cW / 2f).coerceIn(12f * density, width - cW - 12f * density)
    val splitY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio else 0f
    val cTop = if (sel.screenRect.top - cH - 12f * density >= subheaderH) {
      sel.screenRect.top - cH - 12f * density
    } else {
      min(sel.screenRect.bottom + 12f * density, (splitY - cH - 14f * density).coerceAtLeast(subheaderH))
    }
    sel.calloutRect.set(cLeft, cTop, cLeft + cW, cTop + cH)

    // Row 1: Actions (Highlight, AutoExcerpt, Comment, Bookmark, Close)
    sel.calloutHighlightBtn.set(cLeft + 8f * density, cTop + 6f * density, cLeft + 72f * density, cTop + 30f * density)
    sel.calloutExcerptBtn.set(cLeft + 76f * density, cTop + 6f * density, cLeft + 164f * density, cTop + 30f * density)
    sel.calloutCommentBtn.set(cLeft + 168f * density, cTop + 6f * density, cLeft + 232f * density, cTop + 30f * density)
    sel.calloutBookmarkBtn.set(cLeft + 236f * density, cTop + 6f * density, cLeft + 304f * density, cTop + 30f * density)
    sel.calloutCloseBtn.set(cLeft + cW - 32f * density, cTop + 6f * density, cLeft + cW - 6f * density, cTop + 30f * density)

    // Row 2: Color swatches & Tags
    sel.calloutColorBtns.clear()
    val palette = listOf(
      Color.parseColor("#EF4444"), // Red
      Color.parseColor("#22C55E"), // Green
      Color.parseColor("#3B82F6"), // Blue
      Color.parseColor("#EAB308"), // Yellow
      Color.parseColor("#EC4899"), // Pink
      Color.parseColor("#FFFFFF")  // White
    )
    val rad = 9f * density
    val spacing = 24f * density
    for (i in palette.indices) {
      val cx = cLeft + 18f * density + i * spacing
      val cy = cTop + 47f * density
      sel.calloutColorBtns.add(Pair(RectF(cx - rad - 4f * density, cy - rad - 4f * density, cx + rad + 4f * density, cy + rad + 4f * density), palette[i]))
    }
    sel.calloutTagsBtn.set(cLeft + 180f * density, cTop + 35f * density, cLeft + 242f * density, cTop + 57f * density)
    sel.holdAndDragRect.set(sel.screenRect.left, sel.screenRect.top - 24f * density, sel.screenRect.left + 115f * density, sel.screenRect.top - 4f * density)
  }

  /**
   * Calculates the exact point on the perimeter/edge of [card] that faces [fromWorldX], [fromWorldY].
   * Ensures the InkLink line attaches cleanly to the card's exterior edge rather than floating or stopping inside.
   */
  private fun getCardEdgeAnchor(card: NativeCard, fromWorldX: Float, fromWorldY: Float): PointF {
    val left = card.x
    val top = card.y
    val right = card.x + card.width
    val bottom = card.y + card.getHeight()
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f

    val dx = cx - fromWorldX
    val dy = cy - fromWorldY

    if (abs(dx) < 0.001f && abs(dy) < 0.001f) {
      return PointF(cx, top)
    }

    // Top edge (y = top): ray traveling downwards toward center
    if (fromWorldY < top && dy > 0f) {
      val t = (top - fromWorldY) / dy
      val x = fromWorldX + t * dx
      if (x in left..right) {
        return PointF(x.coerceIn(left + 10f, right - 10f), top)
      }
    }
    // Bottom edge (y = bottom): ray traveling upwards toward center
    if (fromWorldY > bottom && dy < 0f) {
      val t = (bottom - fromWorldY) / dy
      val x = fromWorldX + t * dx
      if (x in left..right) {
        return PointF(x.coerceIn(left + 10f, right - 10f), bottom)
      }
    }
    // Left edge (x = left): ray traveling rightwards toward center
    if (fromWorldX < left && dx > 0f) {
      val t = (left - fromWorldX) / dx
      val y = fromWorldY + t * dy
      if (y in top..bottom) {
        return PointF(left, y.coerceIn(top + 10f, bottom - 10f))
      }
    }
    // Right edge (x = right): ray traveling leftwards toward center
    if (fromWorldX > right && dx < 0f) {
      val t = (right - fromWorldX) / dx
      val y = fromWorldY + t * dy
      if (y in top..bottom) {
        return PointF(right, y.coerceIn(top + 10f, bottom - 10f))
      }
    }

    // Default fallback to top center
    return PointF(cx, top)
  }

  private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val l2 = dx * dx + dy * dy
    if (l2 < 0.0001f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
    val projX = ax + t * dx
    val projY = ay + t * dy
    return hypot(px - projX, py - projY)
  }

  private fun triggerLongPressSelect(x: Float, y: Float) {
    val splitY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio else 0f
    if (y < splitY - 14f && y >= subheaderH) {
      isScrollingDoc = false

      val sel = activePdfSelection
      if (sel != null) {
        if (sel.calloutRect.contains(x, y)) {
          return // Ignore long presses on the callout menu
        }

        val inSelection = sel.highlightRects.any { it.contains(x, y) }
        if (inSelection) {
          isLiftingExcerpt = true
          liftCandidateText = sel.text
          liftCandidatePage = sel.pageIndex + 1
          liftCandidateColor = android.graphics.Color.parseColor("#3B82F6") // Blue default for text excerpt
          liftCandidateIsImage = false
          liftCandidateImagePath = null
          liftCandidateBitmap = null
          liftCandidateSourceRects = sel.pdfRects
          
          liftAnchorScreenX = x
          liftAnchorScreenY = y
          liftGhostX = x
          liftGhostY = y
          
          activePdfSelection = null
          parent?.requestDisallowInterceptTouchEvent(true)
          performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
          invalidate()
          return
        }
      }

      val crop = activeCropSelection
      if (crop != null) {
        if (crop.calloutRect.contains(x, y)) {
          return
        }
        if (crop.screenRect.contains(x, y)) {
          // Immediately lift excerpt on long press inside selection!
          val cropBmp = generateCropBitmap(crop.pageIndex, crop.pageBounds)
          val p = if (cropBmp != null) saveCropToFile(cropBmp) else null
          if (cropBmp != null && p != null) {
            cardBitmapCache.put(p, cropBmp)
          }
          isLiftingExcerpt = true
          liftCandidateText = "[Photo Excerpt]"
          liftCandidatePage = crop.pageIndex + 1
          liftCandidateColor = crop.color
          liftCandidateIsImage = true
          liftCandidateImagePath = p
          liftCandidateBitmap = cropBmp
          liftCandidateSourceRects = listOf(RectF(crop.pageBounds.left, crop.pageBounds.top, crop.pageBounds.right, crop.pageBounds.bottom))

          liftAnchorScreenX = x
          liftAnchorScreenY = y
          liftGhostX = x
          liftGhostY = y

          activeCropSelection = null
          parent?.requestDisallowInterceptTouchEvent(true)
          performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
          invalidate()
          return
        }
      }

      // 1. Real PDF document - ONLY select text when activeTool is explicitly "select"
      if (activePdfDoc != null) {
        for (pl in pageLayouts) {
          if (!pl.isFolded && pl.boundsOnScreen.contains(x, y)) {
            val words = pageWordsCache[pl.pageIndex]
            if (!words.isNullOrEmpty() && activeTool == "select" && docMode != "crop") {
              val px = (x - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
              val py = (y - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height
              val hitWord = words.find { w ->
                val b = w.bounds
                px >= b.left - 6f && px <= b.right + 6f && py >= b.top - 8f && py <= b.bottom + 8f
              } ?: words.minByOrNull { w ->
                val b = w.bounds
                val cx = (b.left + b.right) / 2f
                val cy = (b.top + b.bottom) / 2f
                (cx - px) * (cx - px) + (cy - py) * (cy - py)
              }?.takeIf { w ->
                val b = w.bounds
                val cx = (b.left + b.right) / 2f
                val cy = (b.top + b.bottom) / 2f
                val distSq = (cx - px) * (cx - px) + (cy - py) * (cy - py)
                distSq < 48f * 48f
              }

              if (hitWord != null) {
                isSelectingPdfText = true
                pdfSelectStartWord = hitWord
                pdfSelectPageIndex = pl.pageIndex
                updatePdfSelection(pl, hitWord, hitWord)
                isScrollingDoc = false
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
                return
              }
            }

            // LiquidText Dynamic Drag-to-Enclose Area Selection:
            // Pin the anchor point and start real-time elastic box sizing!
            isDraggingCrop = true
            cropStartX = x
            cropStartY = y
            cropPageIndex = pl.pageIndex

            val initialW = 32f * density
            val initialH = 24f * density
            val sRect = RectF(
              (x - initialW / 2f).coerceIn(pl.boundsOnScreen.left + 4f * density, pl.boundsOnScreen.right - initialW - 4f * density),
              (y - initialH / 2f).coerceIn(pl.boundsOnScreen.top + 4f * density, pl.boundsOnScreen.bottom - initialH - 4f * density),
              (x + initialW / 2f).coerceIn(pl.boundsOnScreen.left + initialW + 4f * density, pl.boundsOnScreen.right - 4f * density),
              (y + initialH / 2f).coerceIn(pl.boundsOnScreen.top + initialH + 4f * density, pl.boundsOnScreen.bottom - 4f * density)
            )
            val pW = pl.pageSize.width
            val pH = pl.pageSize.height
            val pageLeft = (sRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageTop = (sRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            val pageRight = (sRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageBottom = (sRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH

            activeCropSelection = createCropSelection(
              pageIndex = pl.pageIndex,
              sRect = sRect,
              pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
              color = selectedColor,
              dimensionsText = "Page ${pl.pageIndex + 1} (${pW.toInt()}x${pH.toInt()} pt)"
            )
            activePdfSelection = null
            isScrollingDoc = false
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            return
          }
        }
      }

      // 2. Structured Sections Document - ONLY select text when activeTool is explicitly "select"
      if (activeDocument != null && activeTool == "select") {
        for (pInfo in paragraphLayouts) {
          val paraH = pInfo.layout.height.toFloat() + 16f
          val pRect = RectF(pInfo.paperX, pInfo.topY, pInfo.paperX + pInfo.width + 56f, pInfo.topY + paraH)
          if (pRect.contains(x, y)) {
            updateStructuredSelection(pInfo, x, y)
            isDraggingEndHandle = true
            isDraggingStartHandle = false
            isScrollingDoc = false
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            return
          }
        }
      }
    }
  }

  private fun updateStructuredSelection(pInfo: ParagraphLayoutInfo, touchX: Float, touchY: Float) {
    val relY = (touchY - pInfo.topY).coerceIn(0f, pInfo.layout.height.toFloat() - 1f)
    val line = pInfo.layout.getLineForVertical(relY.toInt())
    val relX = touchX - pInfo.paperX
    val offset = pInfo.layout.getOffsetForHorizontal(line, relX).coerceIn(0, pInfo.text.length)

    var start = offset
    var end = offset
    while (start > 0 && !pInfo.text[start - 1].isWhitespace()) {
      start--
    }
    while (end < pInfo.text.length && !pInfo.text[end].isWhitespace()) {
      end++
    }
    if (start >= end) {
      start = 0
      end = min(pInfo.text.length, 20)
    }

    updateStructuredSelectionByOffsets(pInfo, start, end)
  }

  private fun computeSelectionCalloutLayout(
    rects: List<RectF>,
    customColors: List<Int>? = null
  ): CalloutLayoutResult {
    val d = density
    val colors = customColors ?: listOf(
      Color.parseColor("#EF4444"), // Red
      Color.parseColor("#22C55E"), // Green
      Color.parseColor("#2563EB"), // Blue
      Color.parseColor("#FACC15"), // Yellow
      Color.parseColor("#EC4899"), // Magenta / Pink
      Color.WHITE                  // White
    )

    val maxAvailW = width - 16f * d
    val cW = maxAvailW.coerceIn(340f * d, 420f * d)
    val card1H = 104f * d
    val subCardH = 46f * d
    val subCardGap = 8f * d
    val totalH = card1H + subCardGap + subCardH

    val minX = rects.minOfOrNull { it.left } ?: (width / 2f)
    val maxX = rects.maxOfOrNull { it.right } ?: (width / 2f)
    val firstR = rects.firstOrNull() ?: RectF(minX, 100f * d, maxX, 120f * d)
    val lastR = rects.lastOrNull() ?: firstR

    val idealLeft = (minX + maxX) / 2f - cW / 2f
    val cLeft = idealLeft.coerceIn(8f * d, max(8f * d, width - cW - 8f * d))

    val gapY = 12f * d
    val minTop = subheaderH + 4f * d
    val maxBottom = height * splitRatio - totalH - 8f * d
    val cTop = (if (firstR.top - totalH - gapY > subheaderH) firstR.top - totalH - gapY else lastR.bottom + gapY).coerceIn(minTop, max(minTop, maxBottom))
    
    val mainCardR = RectF(cLeft, cTop, cLeft + cW, cTop + card1H)

    // Row 1: Chevron Down Close Button on top-right
    val closeHitSize = 30f * d
    val closeBtn = RectF(cLeft + cW - closeHitSize - 8f * d, cTop + 8f * d, cLeft + cW - 8f * d, cTop + 8f * d + closeHitSize)

    // Row 2: Actions (+ Excerpt, Copy, Highlight) and Aux controls (< Word, Word >, All, •••)
    val padX = 10f * d
    val row2Top = cTop + 58f * d
    val row2H = 36f * d
    val row2Bottom = row2Top + row2H
    val innerW = cW - 2 * padX

    // Aux group on the right: divider (1dp), < Word, Word >, All, •••
    val auxBtnH = 34f * d
    val auxTop = row2Top + 1f * d
    val auxBottom = auxTop + auxBtnH
    
    val wordBtnW = 40f * d
    val allBtnW = 28f * d
    val moreBtnW = 28f * d
    val btnGap = 4f * d
    val dividerW = 1f * d
    val dividerMargin = 6f * d
    
    val auxTotalW = dividerMargin + dividerW + dividerMargin + wordBtnW + btnGap + wordBtnW + btnGap + allBtnW + btnGap + moreBtnW
    
    // Action group on the left: Excerpt, Copy, Highlight
    val actionGroupW = innerW - auxTotalW - 6f * d
    val actGap = 5f * d
    val remActW = actionGroupW - 2 * actGap
    val excerptW = remActW * 0.38f
    val copyW = remActW * 0.28f
    val hlW = remActW - excerptW - copyW

    val excerptBtn = RectF(cLeft + padX, row2Top, cLeft + padX + excerptW, row2Bottom)
    val copyBtn = RectF(excerptBtn.right + actGap, row2Top, excerptBtn.right + actGap + copyW, row2Bottom)
    val hlBtn = RectF(copyBtn.right + actGap, row2Top, copyBtn.right + actGap + hlW, row2Bottom)

    val auxStartX = hlBtn.right + 6f * d + dividerMargin + dividerW + dividerMargin
    val addWordLeftBtn = RectF(auxStartX, auxTop, auxStartX + wordBtnW, auxBottom)
    val addWordRightBtn = RectF(addWordLeftBtn.right + btnGap, auxTop, addWordLeftBtn.right + btnGap + wordBtnW, auxBottom)
    val selectAllBtn = RectF(addWordRightBtn.right + btnGap, auxTop, addWordRightBtn.right + btnGap + allBtnW, auxBottom)
    val moreBtn = RectF(selectAllBtn.right + btnGap, auxTop, selectAllBtn.right + btnGap + moreBtnW, auxBottom)

    // Card 2: Sub-Card below Highlight
    val subCardTop = mainCardR.bottom + subCardGap
    val subCardBottom = subCardTop + subCardH
    val subCardW = (320f * d).coerceAtMost(cW - 12f * d).coerceAtLeast(290f * d)
    val idealSubLeft = hlBtn.centerX() - subCardW * 0.46f
    val subCardLeft = idealSubLeft.coerceIn(cLeft + 6f * d, cLeft + cW - subCardW - 6f * d)
    val subCardR = RectF(subCardLeft, subCardTop, subCardLeft + subCardW, subCardBottom)

    // Swatches inside sub-card (7 swatches: 6 solid + 1 rainbow)
    val swatchTouchRadius = 14f * d
    val swatchRadius = 11f * d
    val swatchPitch = 28f * d
    val swatchStartX = subCardLeft + 14f * d + swatchRadius
    val swatchCenterY = subCardTop + subCardH / 2f

    val colorBtns = mutableListOf<Pair<RectF, Int>>()
    for (i in colors.indices) {
      val cx = swatchStartX + i * swatchPitch
      val touchRect = RectF(cx - swatchTouchRadius, swatchCenterY - swatchTouchRadius, cx + swatchTouchRadius, swatchCenterY + swatchTouchRadius)
      colorBtns.add(Pair(touchRect, colors[i]))
    }

    // 7th: Rainbow swatch
    val rainbowCx = swatchStartX + colors.size * swatchPitch
    val rainbowBtn = RectF(rainbowCx - swatchTouchRadius, swatchCenterY - swatchTouchRadius, rainbowCx + swatchTouchRadius, swatchCenterY + swatchTouchRadius)

    // Tags button on right side of subCard
    val tagsBtnLeft = rainbowBtn.right + 12f * d
    val tagsBtn = RectF(tagsBtnLeft, subCardTop + 6f * d, subCardR.right - 8f * d, subCardBottom - 6f * d)

    val overallCalloutR = RectF(
      min(mainCardR.left, subCardR.left),
      mainCardR.top,
      max(mainCardR.right, subCardR.right),
      subCardR.bottom + 4f * d
    )

    return CalloutLayoutResult(
      calloutRect = overallCalloutR,
      closeBtn = closeBtn,
      excerptBtn = excerptBtn,
      copyBtn = copyBtn,
      highlightBtn = hlBtn,
      addWordLeftBtn = addWordLeftBtn,
      addWordRightBtn = addWordRightBtn,
      selectAllBtn = selectAllBtn,
      colorBtns = colorBtns,
      moreBtn = moreBtn,
      tagsBtn = tagsBtn,
      subCardRect = subCardR,
      mainCardRect = mainCardR,
      rainbowBtn = rainbowBtn
    )
  }

  private fun updateStructuredSelectionByOffsets(pInfo: ParagraphLayoutInfo, startOffset: Int, endOffset: Int) {
    val clampedStart = startOffset.coerceIn(0, pInfo.text.length)
    val clampedEnd = max(clampedStart + 1, endOffset).coerceIn(clampedStart, pInfo.text.length)
    val rawText = pInfo.text.substring(clampedStart, clampedEnd)
    val selText = if (rawText.trim().isNotEmpty()) rawText.trim() else rawText

    val startLine = pInfo.layout.getLineForOffset(clampedStart)
    val endLine = pInfo.layout.getLineForOffset(clampedEnd)

    val rects = mutableListOf<RectF>()
    for (l in startLine..endLine) {
      val lStart = if (l == startLine) clampedStart else pInfo.layout.getLineStart(l)
      val lEnd = if (l == endLine) clampedEnd else pInfo.layout.getLineEnd(l)
      if (lStart < lEnd) {
        val lX1 = pInfo.paperX + pInfo.layout.getPrimaryHorizontal(lStart)
        val lX2 = pInfo.paperX + pInfo.layout.getPrimaryHorizontal(lEnd)
        val lTop = pInfo.topY + pInfo.layout.getLineTop(l)
        val lBottom = pInfo.topY + pInfo.layout.getLineBottom(l)
        rects.add(RectF(min(lX1, lX2), lTop, max(lX1, lX2), lBottom))
      }
    }
    if (rects.isEmpty()) {
      val lTop = pInfo.topY + pInfo.layout.getLineTop(startLine)
      val lBottom = pInfo.topY + pInfo.layout.getLineBottom(startLine)
      rects.add(RectF(pInfo.paperX, lTop, pInfo.paperX + pInfo.width, lBottom))
    }

    val firstR = rects.first()
    val lastR = rects.last()
    val startHandle = RectF(firstR.left - 14f * density, firstR.bottom - 4f * density, firstR.left + 14f * density, firstR.bottom + 22f * density)
    val endHandle = RectF(lastR.right - 14f * density, lastR.bottom - 4f * density, lastR.right + 14f * density, lastR.bottom + 22f * density)

    val layout = computeSelectionCalloutLayout(rects)

    activeStructuredPInfo = pInfo
    activeStructuredStartOffset = clampedStart
    activeStructuredEndOffset = clampedEnd

    activePdfSelection = NativePdfSelection(
      pageIndex = pInfo.pageNumber - 1,
      text = selText,
      highlightRects = rects,
      pdfRects = emptyList(),
      startHandle = startHandle,
      endHandle = endHandle,
      calloutRect = layout.calloutRect,
      calloutExcerptBtn = layout.excerptBtn,
      calloutCopyBtn = layout.copyBtn,
      calloutHighlightBtn = layout.highlightBtn,
      calloutCloseBtn = layout.closeBtn,
      startWordIndex = 0,
      endWordIndex = 0,
      calloutAddWordLeftBtn = layout.addWordLeftBtn,
      calloutAddWordRightBtn = layout.addWordRightBtn,
      calloutSelectAllBtn = layout.selectAllBtn,
      charCountText = "${selText.length} chars",
      calloutColorBtns = layout.colorBtns,
      calloutMoreBtn = layout.moreBtn,
      calloutTagsBtn = layout.tagsBtn,
      calloutSubCardRect = layout.subCardRect,
      calloutMainCardRect = layout.mainCardRect,
      calloutRainbowBtn = layout.rainbowBtn
    )
    invalidate()
  }

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

  private fun generateCropBitmap(pageIndex: Int, bounds: BoundingBox): Bitmap? {
    if (activePdfDoc != null) {
      var pageBmp = pageBitmaps.get(pageIndex)
      if (pageBmp == null || pageBmp.isRecycled) {
        val wrapper = activePdfDoc as? com.thinkspace.pdfengine.parser.PdfBoxDocumentWrapper
        if (wrapper != null && wrapper.file.exists()) {
          try {
            val pfd = ParcelFileDescriptor.open(wrapper.file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { desc ->
              val nativeRenderer = android.graphics.pdf.PdfRenderer(desc)
              val page = nativeRenderer.openPage(pageIndex)
              val scale = 2.0f
              val targetW = max(1, (page.width * scale).toInt())
              val targetH = max(1, (page.height * scale).toInt())
              val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
              bmp.eraseColor(Color.WHITE)
              page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              page.close()
              nativeRenderer.close()
              pageBmp = bmp
              pageBitmaps.put(pageIndex, bmp)
            }
          } catch (e: Exception) {
            e.printStackTrace()
          }
        }
      }

      // Secondary fallback if nativeRenderer failed or wasn't loaded:
      if (pageBmp == null || pageBmp.isRecycled) {
        try {
          val engine = PdfEngineModule.getOrCreateEngine(context)
          val rendered = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            engine.renderPage(activePdfDoc!!, pageIndex, RenderOptions(scale = 2.0f))
          }
          pageBmp = rendered.bitmap
          if (pageBmp != null) {
            pageBitmaps.put(pageIndex, pageBmp)
          }
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }

      if (pageBmp != null && !pageBmp!!.isRecycled) {
        val bmp = pageBmp!!
        val pl = pageLayouts.find { it.pageIndex == pageIndex }
        val pW = pl?.pageSize?.width ?: (bmp.width / 2.0f)
        val pH = pl?.pageSize?.height ?: (bmp.height / 2.0f)

        val normL = minOf(bounds.left, bounds.right).coerceIn(0f, pW)
        val normR = maxOf(bounds.left, bounds.right).coerceIn(0f, pW)
        val normT = minOf(bounds.top, bounds.bottom).coerceIn(0f, pH)
        val normB = maxOf(bounds.top, bounds.bottom).coerceIn(0f, pH)

        var srcL = ((normL / pW) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        var srcT = ((normT / pH) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        var srcR = ((normR / pW) * bmp.width).toInt().coerceIn(0, bmp.width)
        var srcB = ((normB / pH) * bmp.height).toInt().coerceIn(0, bmp.height)

        var w = srcR - srcL
        var h = srcB - srcT

        if (w < 20) {
          srcL = maxOf(0, srcL - 40)
          srcR = minOf(bmp.width, srcL + 120)
          w = srcR - srcL
        }
        if (h < 20) {
          srcT = maxOf(0, srcT - 40)
          srcB = minOf(bmp.height, srcT + 120)
          h = srcB - srcT
        }

        if (srcL + w > bmp.width) w = bmp.width - srcL
        if (srcT + h > bmp.height) h = bmp.height - srcT

        if (w > 0 && h > 0) {
          return try {
            Bitmap.createBitmap(bmp, srcL, srcT, w, h)
          } catch (e: Exception) {
            null
          }
        }
      }
    }

    if (activeDocument != null) {
      val cropBmp = Bitmap.createBitmap(400, 260, Bitmap.Config.ARGB_8888)
      val cropCanvas = Canvas(cropBmp)
      cropCanvas.drawColor(Color.WHITE)
      val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        strokeWidth = 2f
        style = Paint.Style.STROKE
      }
      cropCanvas.drawRect(0f, 0f, 400f, 260f, borderP)

      val headerP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
      val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        textSize = 14f
      }

      val sec = activeDocument?.sections?.getOrNull(pageIndex) ?: activeDocument?.sections?.firstOrNull()
      val title = sec?.heading ?: activeDocument?.title ?: "Document"
      cropCanvas.drawText("📖 $title", 20f, 36f, headerP)

      val lines = sec?.paragraphs ?: listOf("Historical excerpt and excerpted diagram.")
      var y = 70f
      for (line in lines) {
        val sub = if (line.length > 45) line.substring(0, 42) + "..." else line
        cropCanvas.drawText(sub, 20f, y, bodyP)
        y += 26f
        if (y > 230f) break
      }
      return cropBmp
    }

    return null
  }

  private fun saveCropToFile(bmp: Bitmap): String {
    val file = File(context.cacheDir, "crop_${System.currentTimeMillis()}_${(1000..9999).random()}.png")
    try {
      FileOutputStream(file).use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
    return file.absolutePath
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    renderScope.cancel()
    pageBitmaps.evictAll()
  }

  // ---------------------------------------------------------------------------
  // Document Configuration
  // ---------------------------------------------------------------------------

  fun setDocumentFromJson(json: String?) {
    if (json.isNullOrEmpty()) {
      activePdfDoc = null
      activeDocument = null
      invalidate()
      return
    }
    try {
      val obj = JSONObject(json)
      val id = obj.optString("id", obj.optString("documentId", "default-doc")).ifEmpty { "default-doc" }
      activeDocumentId = id
      val uri = obj.optString("uri", "")
      val title = obj.optString("title", "Document")
      val pageCount = obj.optInt("pageCount", 1)

      // 1. Try to find an already opened PDF in PdfEngineModule
      val existingPdf = PdfEngineModule.openDocuments[id]
      if (existingPdf != null) {
        activePdfDoc = existingPdf
        activeDocument = null
        pageBitmaps.evictAll()
        pageWordsCache.clear()
        docScrollY = 0f
        invalidate()
        return
      }

      // 2. If URI provided, open via DefaultPdfDocumentEngine asynchronously
      if (uri.isNotEmpty()) {
        renderScope.launch(Dispatchers.IO) {
          try {
            val engine = PdfEngineModule.getOrCreateEngine(context)
            val source = PdfEngineModule.resolveSource(context, uri)
            val doc = engine.open(source)
            PdfEngineModule.openDocuments[id] = doc
            withContext(Dispatchers.Main) {
              activePdfDoc = doc
              activeDocument = null
              pageBitmaps.evictAll()
              pageWordsCache.clear()
              docScrollY = 0f
              invalidate()
            }
          } catch (e: Exception) {
            e.printStackTrace()
          }
        }
      }

      // 3. Fallback to structured document sections (for demo/preloaded text)
      val secList = mutableListOf<NativeSection>()
      val secArr = obj.optJSONArray("sections")
      if (secArr != null) {
        for (i in 0 until secArr.length()) {
          val sObj = secArr.getJSONObject(i)
          val sId = sObj.optString("id", "sec-$i")
          val pageNumber = sObj.optInt("pageNumber", i + 1)
          val heading = sObj.optString("heading", "Chapter $i")
          val pArr = sObj.optJSONArray("paragraphs")
          val pList = mutableListOf<String>()
          if (pArr != null) {
            for (j in 0 until pArr.length()) pList.add(pArr.getString(j))
          }
          secList.add(NativeSection(sId, pageNumber, heading, pList, null, null))
        }
      }
      activeDocument = NativeDoc(id, title, pageCount, secList)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

  // ── Multi-Document Engine Methods ────────────────────────────────────────────

  /**
   * Called when the React Native layer passes a new workspace documents list.
   * Registers all document entries in workspaceDocumentEntries.
   * Does NOT immediately open all PDFs — only the active one is opened eagerly;
   * others are opened lazily when switchToDocument() is called.
   *
   * @param json JSON array of WorkspaceDocumentEntry objects.
   */
  fun setWorkspaceDocumentsFromJson(json: String?) {
    if (json.isNullOrEmpty()) return
    try {
      val arr = JSONArray(json)
      val incoming = mutableListOf<WorkspaceDocumentEntry>()
      val palette = docColorPalette

      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "doc-$i")
        val title = obj.optString("title", "Document")
        val pageCount = obj.optInt("pageCount", 1)
        val uri = obj.optString("uri", "")
        // Assign a color from palette if not provided, cycling by index
        val existingAccent = workspaceDocumentEntries.find { it.id == id }?.colorAccent
        val colorHex = obj.optString("colorAccent", "").takeIf { it.isNotEmpty() }
          ?: existingAccent
          ?: "#%06X".format(palette[i % palette.size] and 0xFFFFFF)
        incoming.add(WorkspaceDocumentEntry(id, title, pageCount, uri, colorHex))
      }

      // Preserve ordering: update existing entries, add new ones
      workspaceDocumentEntries.clear()
      workspaceDocumentEntries.addAll(incoming)

      // For each entry, check if already in PdfEngineModule registry
      for (entry in incoming) {
        val existing = PdfEngineModule.openDocuments[entry.id]
        if (existing != null) {
          documentRegistry[entry.id] = existing
        }
      }

      // If no active doc is set yet, activate the first one
      if (activeDocumentId.isEmpty() && incoming.isNotEmpty()) {
        switchToDocument(incoming[0].id)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  /**
   * Switch the active PDF/document viewport to the given document ID.
   * The workspace canvas (cards, strokes, ink, notes) is NOT affected.
   * Only the document pane changes.
   *
   * If the document is already opened in the registry, switching is instant.
   * If not yet opened, opens it asynchronously from the stored URI.
   *
   * @param docId The document ID to activate.
   */
  fun switchToDocument(docId: String) {
    if (docId == activeDocumentId && activePdfDoc != null) return // Already active

    activeDocumentId = docId

    // Check registry first (instant switch)
    val cached = documentRegistry[docId] ?: PdfEngineModule.openDocuments[docId]
    if (cached != null) {
      documentRegistry[docId] = cached
      activePdfDoc = cached
      activeDocument = null
      // Clear per-page caches for the previous document's bitmaps
      pageBitmaps.evictAll()
      pageWordsCache.clear()
      compressionEngine.resetAllToNormal(animate = false) {}
      docScrollY = 0f
      val targetPage = pendingScrollToPage
      val targetRects = pendingPulseRects
      pendingScrollToPage = null
      pendingPulseRects = null
      if (targetPage != null) {
        post { scrollToDocumentPage(targetPage, targetRects ?: emptyList()) }
      }
      invalidate()
      return
    }

    // Not yet open — find the entry and open from URI asynchronously
    val entry = workspaceDocumentEntries.find { it.id == docId }
    if (entry != null && entry.uri.isNotEmpty()) {
      renderScope.launch(Dispatchers.IO) {
        try {
          val engine = PdfEngineModule.getOrCreateEngine(context)
          val source = PdfEngineModule.resolveSource(context, entry.uri)
          val doc = engine.open(source)
          PdfEngineModule.openDocuments[docId] = doc
          documentRegistry[docId] = doc
          withContext(Dispatchers.Main) {
            if (activeDocumentId == docId) {
              activePdfDoc = doc
              activeDocument = null
              pageBitmaps.evictAll()
              pageWordsCache.clear()
              compressionEngine.resetAllToNormal(animate = false) {}
              docScrollY = 0f
              val targetPage = pendingScrollToPage
              val targetRects = pendingPulseRects
              pendingScrollToPage = null
              pendingPulseRects = null
              if (targetPage != null) {
                post { scrollToDocumentPage(targetPage, targetRects ?: emptyList()) }
              }
              invalidate()
            }
          }
        } catch (e: Exception) {
          e.printStackTrace()
          withContext(Dispatchers.Main) {
            hudToast.show("Document could not be opened")
          }
        }
      }
    }
  }

  /**
   * Dispatch a React Native event requesting the RN layer to switch to a specific
   * document and page. Called when the user taps the source badge on a card.
   *
   * @param cardId   The card whose source badge was tapped.
   * @param docId    The source document ID.
   * @param pageNum  The source page number (1-based).
   */
  private fun dispatchRequestDocumentSwitchEvent(cardId: String, docId: String, pageNum: Int) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val dispatcher: EventDispatcher? = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("documentId", docId)
      putInt("sourcePageNumber", pageNum)
      putString("cardId", cardId)
    }
    dispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topRequestDocumentSwitch", data))
  }

  // ── End Multi-Document Engine Methods ────────────────────────────────────────

  fun setAnnotationsFromJson(json: String?) {
    if (json.isNullOrEmpty()) {
      return
    }
    try {
      val arr = JSONArray(json)
      if (arr.length() == 0 && annotations.isNotEmpty()) {
        return
      }
      val incoming = mutableListOf<NativeAnnotation>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "ann-$i")
        val sectionId = obj.optString("sectionId", "")
        val pIdx = obj.optInt("paragraphIndex", 0)
        val pageNumber = obj.optInt("pageNumber", 1)
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.YELLOW }
        val text = obj.optString("text", "")
        val rects = mutableListOf<RectF>()
        val rectsArr = obj.optJSONArray("rects")
        if (rectsArr != null) {
          for (rIdx in 0 until rectsArr.length()) {
            val ro = rectsArr.getJSONObject(rIdx)
            rects.add(RectF(
              ro.optDouble("left", 0.0).toFloat(),
              ro.optDouble("top", 0.0).toFloat(),
              ro.optDouble("right", 0.0).toFloat(),
              ro.optDouble("bottom", 0.0).toFloat()
            ))
          }
        }
        incoming.add(NativeAnnotation(id, sectionId, pIdx, pageNumber, color, text, rects))
      }
      val incomingIds = incoming.map { it.id }.toSet()
      val localOnly = annotations.filter { !incomingIds.contains(it.id) }
      annotations.clear()
      annotations.addAll(incoming)
      annotations.addAll(localOnly)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

  fun setStrokesFromJson(json: String?) {
    strokes.clear()
    if (json.isNullOrEmpty()) {
      invalidate()
      return
    }
    try {
      val arr = JSONArray(json)
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "stroke-$i")
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.WHITE }
        val strokeWidth = obj.optDouble("strokeWidth", 3.5).toFloat()
        val isHighlighter = obj.optBoolean("isHighlighter", false)

        val ptsArr = obj.optJSONArray("points")
        val pts = mutableListOf<NativePoint>()
        if (ptsArr != null) {
          for (j in 0 until ptsArr.length()) {
            val ptObj = ptsArr.getJSONObject(j)
            pts.add(NativePoint(ptObj.getDouble("x").toFloat(), ptObj.getDouble("y").toFloat()))
          }
        }
        strokes.add(NativeStroke(id, pts, color, strokeWidth, isHighlighter))
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

  fun setCardsFromJson(json: String?) {
    if (json.isNullOrEmpty()) {
      return
    }
    try {
      val arr = JSONArray(json)
      if (arr.length() == 0 && cards.isNotEmpty()) {
        return
      }

      val updatedList = mutableListOf<NativeCard>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "card-$i")
        var existing = cards.find { it.id == id }
        val isImage = obj.optBoolean("isImage", existing?.isImage ?: false)
        val pageNumber = obj.optInt("pageNumber", existing?.pageNumber ?: 1)
        if (existing == null && isImage) {
          existing = cards.find { it.isImage && it.pageNumber == pageNumber && !it.imageUrl.isNullOrEmpty() }
        }
        val text = obj.optString("text", existing?.text ?: "")
        if (existing == null && text.isNotEmpty()) {
          existing = cards.find { it.pageNumber == pageNumber && it.text == text }
        }

        val x = if (obj.has("x") && obj.getDouble("x") != 0.0) obj.getDouble("x").toFloat() else (existing?.x ?: 60f)
        val y = if (obj.has("y") && obj.getDouble("y") != 0.0) obj.getDouble("y").toFloat() else (existing?.y ?: 60f)
        val width = obj.optDouble("width", (existing?.width ?: 220f).toDouble()).toFloat()
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { existing?.color ?: Color.WHITE }
        val comment = if (obj.has("comment") && !obj.isNull("comment")) obj.getString("comment") else existing?.comment
        val clusterId = if (obj.has("clusterId") && !obj.isNull("clusterId")) obj.getString("clusterId") else existing?.clusterId
        val stackCount = obj.optInt("stackCount", existing?.stackCount ?: 1)
        val imageUrl = if (obj.has("imageUrl") && !obj.isNull("imageUrl") && obj.getString("imageUrl").isNotEmpty()) {
          obj.getString("imageUrl")
        } else {
          existing?.imageUrl
        }
        val isTable = obj.optBoolean("isTable", existing?.isTable ?: false)

        // Cache association for image card bitmap
        if (isImage) {
          val cachedBmp = (if (!imageUrl.isNullOrEmpty()) cardBitmapCache.get(imageUrl) else null)
            ?: (if (existing != null) cardBitmapCache.get(existing.id) else null)
            ?: cardBitmapCache.get("page_${pageNumber}_image")
          if (cachedBmp != null) {
            cardBitmapCache.put(id, cachedBmp)
            if (!imageUrl.isNullOrEmpty()) {
              cardBitmapCache.put(imageUrl, cachedBmp)
            }
          }
        }

        val parsedSourceRects = mutableListOf<RectF>()
        if (obj.has("sourceRects") && !obj.isNull("sourceRects")) {
          val sArr = obj.getJSONArray("sourceRects")
          for (rIdx in 0 until sArr.length()) {
            val rObj = sArr.getJSONObject(rIdx)
            val l = rObj.optDouble("left", 0.0).toFloat()
            val t = rObj.optDouble("top", 0.0).toFloat()
            val r = rObj.optDouble("right", 0.0).toFloat()
            val b = rObj.optDouble("bottom", 0.0).toFloat()
            parsedSourceRects.add(RectF(l, t, r, b))
          }
        } else if (existing != null && existing.sourceRects.isNotEmpty()) {
          parsedSourceRects.addAll(existing.sourceRects)
        }

        // Multi-document: parse documentId, falling back to existing or activeDocumentId
        val cardDocumentId = obj.optString(
          "documentId",
          existing?.documentId?.takeIf { it.isNotEmpty() } ?: activeDocumentId
        )

        updatedList.add(
          NativeCard(
            id, x, y, width, text, color, pageNumber, comment, clusterId, stackCount,
            isImage, imageUrl, isTable, existing?.tableRows,
            existing?.groupedItems,
            parsedSourceRects,
            documentId = cardDocumentId
          )
        )
      }

      cards.clear()
      cards.addAll(updatedList)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

  fun setLinksFromJson(json: String?) {
    if (json.isNullOrEmpty()) {
      return
    }
    try {
      val arr = JSONArray(json)
      if (arr.length() == 0 && links.isNotEmpty()) {
        return
      }
      links.clear()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "link-$i")
        val sourceExcerptId = obj.optString("sourceExcerptId", "")
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.CYAN }
        links.add(NativeLink(id, sourceExcerptId, color))
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

  private fun triggerShockwave(wx: Float, wy: Float, color: Int) {
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

  private fun canvasScreenToWorld(sx: Float, sy: Float, canvasTopY: Float): Pair<Float, Float> {
    return Pair(camera.screenToWorldX(sx), camera.screenToWorldY(sy, canvasTopY))
  }

  private fun canvasWorldToScreen(wx: Float, wy: Float, canvasTopY: Float): Pair<Float, Float> {
    return Pair(camera.worldToScreenX(wx), camera.worldToScreenY(wy, canvasTopY))
  }

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

      headerRect.set(0f, 0f, viewW, subheaderH)
      canvas.drawRect(headerRect, docSubheaderBgPaint)

      val titleStr = activePdfDoc?.metadata?.title?.takeIf { it.isNotEmpty() }
        ?: activeDocument?.title?.takeIf { it.isNotEmpty() }
        ?: "PDF Document"
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
                    strokeWidth = if (isHl) 18f else penThickness
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

          // Premium Apple-grade Floating Text-Selection Contextual Toolbar matching Reference Screenshot
          if (!isDraggingStartHandle && !isDraggingEndHandle) {
            val mainCard = if (!pdfSel.calloutMainCardRect.isEmpty) pdfSel.calloutMainCardRect else pdfSel.calloutRect
            val subCard = pdfSel.calloutSubCardRect

            // 1. Layered ambient drop shadows for Main Card
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            shadowPaint.color = Color.argb(10, 0, 0, 0)
            canvas.drawRoundRect(mainCard.left - 2f * density, mainCard.top + 2f * density, mainCard.right + 2f * density, mainCard.bottom + 8f * density, 22f * density, 22f * density, shadowPaint)
            shadowPaint.color = Color.argb(22, 0, 0, 0)
            canvas.drawRoundRect(mainCard.left, mainCard.top + 1.5f * density, mainCard.right, mainCard.bottom + 4f * density, 20f * density, 20f * density, shadowPaint)

            // Main Card Background & Crisp Border
            val card1BgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.WHITE
              style = Paint.Style.FILL
            }
            val card1BrdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#E2E8F0")
              strokeWidth = 1f * density
              style = Paint.Style.STROKE
            }
            canvas.drawRoundRect(mainCard, 20f * density, 20f * density, card1BgPaint)
            canvas.drawRoundRect(mainCard, 20f * density, 20f * density, card1BrdPaint)

            // 2. Row 1: Selection Badge & Multi-line Preview Quote + Chevron Close Button
            val charCount = pdfSel.text.length
            val countLabel = "$charCount chars"
            val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#0284C7")
              textSize = 11f * density
              typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val countFm = countPaint.fontMetrics
            val countW = countPaint.measureText(countLabel)
            val badgeW = countW + 16f * density
            val badgeH = 24f * density
            val badgeRect = RectF(mainCard.left + 14f * density, mainCard.top + 12f * density, mainCard.left + 14f * density + badgeW, mainCard.top + 12f * density + badgeH)
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#E0F2FE")
              style = Paint.Style.FILL
            }
            canvas.drawRoundRect(badgeRect, 12f * density, 12f * density, badgeBgPaint)
            canvas.drawText(countLabel, badgeRect.centerX() - countW / 2f, badgeRect.centerY() - (countFm.descent + countFm.ascent) / 2f, countPaint)

            // Selected text preview
            val closeBtn = pdfSel.calloutCloseBtn
            val previewLeft = badgeRect.right + 10f * density
            val previewRight = closeBtn.left - 8f * density
            val maxPreviewW = previewRight - previewLeft
            val cleanSnippet = pdfSel.text.replace('\n', ' ').replace('\r', ' ').trim()
            val previewStr = "\"$cleanSnippet\""
            if (maxPreviewW > 24f * density) {
              val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#334155")
                textSize = 10.5f * density
              }
              val quoteFm = quotePaint.fontMetrics
              val lineH = quoteFm.descent - quoteFm.ascent + 1f * density
              val line1 = android.text.TextUtils.ellipsize(previewStr, quotePaint, maxPreviewW, android.text.TextUtils.TruncateAt.END).toString()
              canvas.drawText(line1, previewLeft, mainCard.top + 20f * density, quotePaint)
              if (previewStr.length > 36 && line1.endsWith("…")) {
                val remText = previewStr.substring(min(previewStr.length, 34)).trim()
                val line2 = android.text.TextUtils.ellipsize(remText, quotePaint, maxPreviewW, android.text.TextUtils.TruncateAt.END).toString()
                canvas.drawText(line2, previewLeft, mainCard.top + 20f * density + lineH, quotePaint)
              }
            }

            // Chevron Down Close Icon
            val chevPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#475569")
              strokeWidth = 2f * density
              strokeCap = Paint.Cap.ROUND
              strokeJoin = Paint.Join.ROUND
              style = Paint.Style.STROKE
            }
            val chevCx = closeBtn.centerX()
            val chevCy = badgeRect.centerY()
            val chevPath = Path().apply {
              moveTo(chevCx - 5f * density, chevCy - 2.5f * density)
              lineTo(chevCx, chevCy + 2.5f * density)
              lineTo(chevCx + 5f * density, chevCy - 2.5f * density)
            }
            canvas.drawPath(chevPath, chevPaint)

            // Horizontal Divider Line between Row 1 and Row 2
            val hDivY = mainCard.top + 50f * density
            val hDivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#F1F5F9")
              strokeWidth = 1f * density
            }
            canvas.drawLine(mainCard.left + 14f * density, hDivY, mainCard.right - 14f * density, hDivY, hDivPaint)

            // 3. Row 2 Actions: [📄 + Excerpt], [📋 Copy], [✏️ Highlight]
            // + Excerpt Button
            val excerptBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#2563EB")
              style = Paint.Style.FILL
            }
            canvas.drawRoundRect(pdfSel.calloutExcerptBtn, 10f * density, 10f * density, excerptBgPaint)
            val exCy = pdfSel.calloutExcerptBtn.centerY()
            val exLeft = pdfSel.calloutExcerptBtn.left + 9f * density
            val docW = 9f * density
            val docH = 12f * density
            val docR = RectF(exLeft, exCy - docH / 2f, exLeft + docW, exCy + docH / 2f)
            val docIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.WHITE
              strokeWidth = 1.3f * density
              style = Paint.Style.STROKE
            }
            canvas.drawRoundRect(docR, 1.5f * density, 1.5f * density, docIconPaint)
            canvas.drawLine(docR.left + 2f * density, docR.top + 3.5f * density, docR.right - 2f * density, docR.top + 3.5f * density, docIconPaint)
            canvas.drawLine(docR.left + 2f * density, docR.top + 6.5f * density, docR.right - 2f * density, docR.top + 6.5f * density, docIconPaint)
            val exTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.WHITE
              textSize = 12f * density
              typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val exFm = exTextPaint.fontMetrics
            canvas.drawText("+ Excerpt", exLeft + docW + 6f * density, exCy - (exFm.descent + exFm.ascent) / 2f, exTextPaint)

            // Copy Button (dynamic state feedback when copied)
            val isCopied = copiedToastText != null
            val copyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = if (isCopied) Color.parseColor("#DCFCE7") else Color.parseColor("#F1F5F9")
              style = Paint.Style.FILL
            }
            canvas.drawRoundRect(pdfSel.calloutCopyBtn, 10f * density, 10f * density, copyBgPaint)
            val cpCy = pdfSel.calloutCopyBtn.centerY()
            val cpLeft = pdfSel.calloutCopyBtn.left + 9f * density
            val cpW = 8f * density
            val cpH = 10f * density
            val copyIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = if (isCopied) Color.parseColor("#15803D") else Color.parseColor("#1E293B")
              strokeWidth = 1.3f * density
              style = Paint.Style.STROKE
            }
            canvas.drawRoundRect(RectF(cpLeft + 3f * density, cpCy - cpH / 2f - 2f * density, cpLeft + 3f * density + cpW, cpCy + cpH / 2f - 2f * density), 1.5f * density, 1.5f * density, copyIconPaint)
            val frontR = RectF(cpLeft, cpCy - cpH / 2f + 1f * density, cpLeft + cpW, cpCy + cpH / 2f + 1f * density)
            canvas.drawRoundRect(frontR, 1.5f * density, 1.5f * density, copyBgPaint)
            canvas.drawRoundRect(frontR, 1.5f * density, 1.5f * density, copyIconPaint)
            val copyTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = if (isCopied) Color.parseColor("#15803D") else Color.parseColor("#1E293B")
              textSize = 12f * density
              typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val cpFm = copyTextPaint.fontMetrics
            canvas.drawText(copiedToastText ?: "Copy", cpLeft + cpW + 6f * density, cpCy - (cpFm.descent + cpFm.ascent) / 2f, copyTextPaint)

            // Highlight Button
            val hlBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#FED7AA") // Warm peach
              style = Paint.Style.FILL
            }
            val hlBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#FB923C") // Orange border
              strokeWidth = 1.2f * density
              style = Paint.Style.STROKE
            }
            canvas.drawRoundRect(pdfSel.calloutHighlightBtn, 10f * density, 10f * density, hlBgPaint)
            canvas.drawRoundRect(pdfSel.calloutHighlightBtn, 10f * density, 10f * density, hlBorderPaint)

            // Pencil / marker icon
            val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#1E293B")
              strokeWidth = 1.4f * density
              style = Paint.Style.STROKE
            }
            val hlCy = pdfSel.calloutHighlightBtn.centerY()
            val hlLeft = pdfSel.calloutHighlightBtn.left + 9f * density
            val penPath = Path().apply {
              moveTo(hlLeft + 2f * density, hlCy + 5f * density)
              lineTo(hlLeft + 9f * density, hlCy - 5f * density)
              lineTo(hlLeft + 12f * density, hlCy - 3f * density)
              lineTo(hlLeft + 4.5f * density, hlCy + 7f * density)
              close()
            }
            canvas.drawPath(penPath, penPaint)

            val hlTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#1E293B")
              textSize = 12f * density
              typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val hlFm = hlTextPaint.fontMetrics
            canvas.drawText("Highlight", hlLeft + 16f * density, hlCy - (hlFm.descent + hlFm.ascent) / 2f, hlTextPaint)

            // Vertical Divider line between Highlight and Aux controls
            val vDivX = pdfSel.calloutHighlightBtn.right + 6f * density
            val vDivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#E2E8F0")
              strokeWidth = 1.2f * density
            }
            canvas.drawLine(vDivX, pdfSel.calloutHighlightBtn.centerY() - 11f * density, vDivX, pdfSel.calloutHighlightBtn.centerY() + 11f * density, vDivPaint)

            // 4. Aux Controls (< Word, Word >, All, •••)
            val auxBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#F8FAFC")
              style = Paint.Style.FILL
            }
            val auxBrdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#E2E8F0")
              strokeWidth = 1f * density
              style = Paint.Style.STROKE
            }
            val auxTxtPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#334155")
              textSize = 11f * density
              textAlign = Paint.Align.CENTER
              typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val auxFm = auxTxtPaint.fontMetrics
            val auxShift = (auxFm.descent + auxFm.ascent) / 2f

            // < Word
            canvas.drawRoundRect(pdfSel.calloutAddWordLeftBtn, 8f * density, 8f * density, auxBgPaint)
            canvas.drawRoundRect(pdfSel.calloutAddWordLeftBtn, 8f * density, 8f * density, auxBrdPaint)
            canvas.drawText("< Word", pdfSel.calloutAddWordLeftBtn.centerX(), pdfSel.calloutAddWordLeftBtn.centerY() - auxShift, auxTxtPaint)

            // Word >
            canvas.drawRoundRect(pdfSel.calloutAddWordRightBtn, 8f * density, 8f * density, auxBgPaint)
            canvas.drawRoundRect(pdfSel.calloutAddWordRightBtn, 8f * density, 8f * density, auxBrdPaint)
            canvas.drawText("Word >", pdfSel.calloutAddWordRightBtn.centerX(), pdfSel.calloutAddWordRightBtn.centerY() - auxShift, auxTxtPaint)

            // All
            canvas.drawRoundRect(pdfSel.calloutSelectAllBtn, 8f * density, 8f * density, auxBgPaint)
            canvas.drawRoundRect(pdfSel.calloutSelectAllBtn, 8f * density, 8f * density, auxBrdPaint)
            canvas.drawText("All", pdfSel.calloutSelectAllBtn.centerX(), pdfSel.calloutSelectAllBtn.centerY() - auxShift, auxTxtPaint)

            // ••• (More)
            canvas.drawRoundRect(pdfSel.calloutMoreBtn, 8f * density, 8f * density, auxBgPaint)
            canvas.drawRoundRect(pdfSel.calloutMoreBtn, 8f * density, 8f * density, auxBrdPaint)
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.parseColor("#475569")
              style = Paint.Style.FILL
            }
            val moreCx = pdfSel.calloutMoreBtn.centerX()
            val moreCy = pdfSel.calloutMoreBtn.centerY()
            val dotR = 1.8f * density
            val dotSpacing = 4.5f * density
            canvas.drawCircle(moreCx - dotSpacing, moreCy, dotR, dotPaint)
            canvas.drawCircle(moreCx, moreCy, dotR, dotPaint)
            canvas.drawCircle(moreCx + dotSpacing, moreCy, dotR, dotPaint)

            // 5. Card 2: Sub-Card Color Tray below Highlight
            if (!subCard.isEmpty) {
              // Triangular Beak connecting Highlight button to Sub-Card
              val beakCx = pdfSel.calloutHighlightBtn.centerX().coerceIn(subCard.left + 16f * density, subCard.right - 16f * density)
              val beakPath = Path().apply {
                moveTo(beakCx - 7f * density, subCard.top)
                lineTo(beakCx, subCard.top - 6f * density)
                lineTo(beakCx + 7f * density, subCard.top)
                close()
              }

              // Sub-card shadow
              shadowPaint.color = Color.argb(10, 0, 0, 0)
              canvas.drawRoundRect(subCard.left - 2f * density, subCard.top + 2f * density, subCard.right + 2f * density, subCard.bottom + 6f * density, 18f * density, 18f * density, shadowPaint)
              shadowPaint.color = Color.argb(20, 0, 0, 0)
              canvas.drawRoundRect(subCard.left, subCard.top + 1f * density, subCard.right, subCard.bottom + 3f * density, 16f * density, 16f * density, shadowPaint)

              // Sub-card background and border
              val subBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
              }
              val subBrdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E2E8F0")
                strokeWidth = 1f * density
                style = Paint.Style.STROKE
              }
              canvas.drawRoundRect(subCard, 16f * density, 16f * density, subBgPaint)
              canvas.drawPath(beakPath, subBgPaint)
              canvas.drawRoundRect(subCard, 16f * density, 16f * density, subBrdPaint)
              canvas.drawLine(beakCx - 7f * density, subCard.top, beakCx, subCard.top - 6f * density, subBrdPaint)
              canvas.drawLine(beakCx, subCard.top - 6f * density, beakCx + 7f * density, subCard.top, subBrdPaint)

              // Swatches inside Sub-Card
              val swatchR = 11f * density
              val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
              val whiteBrdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#CBD5E1")
                strokeWidth = 1.2f * density
                style = Paint.Style.STROKE
              }
              val activeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#38BDF8")
                strokeWidth = 2.2f * density
                style = Paint.Style.STROKE
              }

              for (cb in pdfSel.calloutColorBtns) {
                val cx = cb.first.centerX()
                val cy = cb.first.centerY()
                val col = cb.second
                val isSelected = (col == selectedColor) || (col == Color.WHITE && selectedColor == Color.WHITE)

                if (isSelected) {
                  canvas.drawCircle(cx, cy, swatchR + 3.5f * density, activeHaloPaint)
                }

                circlePaint.color = col
                canvas.drawCircle(cx, cy, swatchR, circlePaint)
                if (col == Color.WHITE) {
                  canvas.drawCircle(cx, cy, swatchR, whiteBrdPaint)
                }
              }

              // 7th: Rainbow Gradient Swatch
              val rainBtn = pdfSel.calloutRainbowBtn
              val rainCx = rainBtn.centerX()
              val rainCy = rainBtn.centerY()
              val rainbowShader = android.graphics.SweepGradient(
                rainCx, rainCy,
                intArrayOf(
                  Color.parseColor("#EF4444"),
                  Color.parseColor("#F59E0B"),
                  Color.parseColor("#10B981"),
                  Color.parseColor("#3B82F6"),
                  Color.parseColor("#8B5CF6"),
                  Color.parseColor("#EC4899"),
                  Color.parseColor("#EF4444")
                ),
                null
              )
              val rainbowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = rainbowShader
                style = Paint.Style.FILL
              }
              canvas.drawCircle(rainCx, rainCy, swatchR, rainbowPaint)

              // Vertical Divider inside Sub-Card
              val subDivX = rainBtn.right + 8f * density
              canvas.drawLine(subDivX, subCard.centerY() - 10f * density, subDivX, subCard.centerY() + 10f * density, vDivPaint)

              // Tags Button: 🏷️ Tags
              val tagsBtn = pdfSel.calloutTagsBtn
              val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#334155")
                strokeWidth = 1.4f * density
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = Paint.Style.STROKE
              }
              val tagCy = tagsBtn.centerY()
              val tagLeft = tagsBtn.left + 4f * density
              val tagPath = Path().apply {
                moveTo(tagLeft + 3f * density, tagCy - 5f * density)
                lineTo(tagLeft + 9f * density, tagCy - 5f * density)
                lineTo(tagLeft + 13f * density, tagCy)
                lineTo(tagLeft + 6.5f * density, tagCy + 6f * density)
                lineTo(tagLeft + 3f * density, tagCy + 2f * density)
                close()
              }
              canvas.drawPath(tagPath, tagPaint)
              val tagHole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#334155")
                style = Paint.Style.FILL
              }
              canvas.drawCircle(tagLeft + 5.5f * density, tagCy - 2f * density, 1f * density, tagHole)

              val tagsTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#334155")
                textSize = 12f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
              }
              val tagFm = tagsTextPaint.fontMetrics
              canvas.drawText("Tags", tagLeft + 17f * density, tagCy - (tagFm.descent + tagFm.ascent) / 2f, tagsTextPaint)
            }
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

        // Diagonal Corner Circular Handles matching Screenshot (Top-Left & Bottom-Right)
        val handleR = 7.5f * density
        val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = cropColor
          style = Paint.Style.FILL
        }
        val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.WHITE
          strokeWidth = 1.8f * density
          style = Paint.Style.STROKE
        }

        // Top-Left Circle Handle
        val tlX = cropSel.screenRect.left
        val tlY = cropSel.screenRect.top
        canvas.drawCircle(tlX, tlY, handleR, handleFill)
        canvas.drawCircle(tlX, tlY, handleR, handleBorder)

        // Bottom-Right Circle Handle
        val brX = cropSel.screenRect.right
        val brY = cropSel.screenRect.bottom
        canvas.drawCircle(brX, brY, handleR, handleFill)
        canvas.drawCircle(brX, brY, handleR, handleBorder)

        // LiquidText Top Contextual Toolbar (hidden while actively dragging/sizing for clean feedback)
        if (!isDraggingCrop && !isDraggingCropTopLeftHandle && !isDraggingCropBottomRightHandle) {
          val holdBadgeRect = cropSel.holdAndDragRect
          val holdBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F172A"); style = Paint.Style.FILL }
          val holdBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cropColor; strokeWidth = 1.2f * density; style = Paint.Style.STROKE }
          canvas.drawRoundRect(holdBadgeRect, 5f * density, 5f * density, holdBg)
          canvas.drawRoundRect(holdBadgeRect, 5f * density, 5f * density, holdBorder)
          val holdTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10.5f * density
            isFakeBoldText = true
          }
          canvas.drawText("✋ Move / Drag to Canvas", holdBadgeRect.left + 8f * density, holdBadgeRect.centerY() + 3.5f * density, holdTextPaint)

          canvas.drawRoundRect(cropSel.calloutRect, 12f * density, 12f * density, calloutBgPaint)
          canvas.drawRoundRect(cropSel.calloutRect, 12f * density, 12f * density, calloutBorderPaint)

          // Row 1: Actions (Highlight, AutoExcerpt, Comment, Bookmark, Close)
          val actionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
            isFakeBoldText = true
          }

          // Highlight button: styled pill with accent color
          val hlPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EAB308")
            style = Paint.Style.FILL
          }
          canvas.drawRoundRect(cropSel.calloutHighlightBtn, 5f * density, 5f * density, hlPillPaint)
          val hlTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 11.5f * density
            isFakeBoldText = true
          }
          canvas.drawText("Highlight", cropSel.calloutHighlightBtn.left + 6f * density, cropSel.calloutHighlightBtn.centerY() + 4f * density, hlTextPaint)

          // AutoExcerpt button: styled pill with accent color
          val excerptPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cropColor
            style = Paint.Style.FILL
          }
          canvas.drawRoundRect(cropSel.calloutExcerptBtn, 5f * density, 5f * density, excerptPillPaint)
          val autoExcerptTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11.5f * density
            isFakeBoldText = true
          }
          canvas.drawText("AutoExcerpt", cropSel.calloutExcerptBtn.left + 6f * density, cropSel.calloutExcerptBtn.centerY() + 4f * density, autoExcerptTextPaint)

          canvas.drawText("Comment", cropSel.calloutCommentBtn.left + 4f * density, cropSel.calloutCommentBtn.centerY() + 4f * density, actionPaint)
          canvas.drawText("Bookmark", cropSel.calloutBookmarkBtn.left + 4f * density, cropSel.calloutBookmarkBtn.centerY() + 4f * density, actionPaint)

          val closePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 13f * density
            isFakeBoldText = true
          }
          canvas.drawText("✕", cropSel.calloutCloseBtn.left + 6f * density, cropSel.calloutCloseBtn.centerY() + 4.5f * density, closePaint)

          // Row 2: Color Swatches & Tags
          for (pair in cropSel.calloutColorBtns) {
            val btnR = pair.first
            val col = pair.second
            val swPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = col
              style = Paint.Style.FILL
            }
            val rad = btnR.width() / 2f - 4f * density
            canvas.drawCircle(btnR.centerX(), btnR.centerY(), rad, swPaint)
            if (col == cropColor) {
              val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 2f * density
                style = Paint.Style.STROKE
              }
              canvas.drawCircle(btnR.centerX(), btnR.centerY(), rad + 2.5f * density, ringPaint)
            }
          }

          // Divider line before Tags
          val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            strokeWidth = 1f * density
          }
          canvas.drawLine(cropSel.calloutTagsBtn.left - 8f * density, cropSel.calloutTagsBtn.top + 2f * density, cropSel.calloutTagsBtn.left - 8f * density, cropSel.calloutTagsBtn.bottom - 2f * density, divPaint)

          val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 12f * density
            isFakeBoldText = true
          }
          canvas.drawText("Tags", cropSel.calloutTagsBtn.left + 4f * density, cropSel.calloutTagsBtn.centerY() + 4f * density, tagPaint)
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
        strokeWidth = if (isHighlighter) 20f else penThickness
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
        val isEditing = editingCardId != null
        if (isEditing) {
          // When actively editing a card: show single docked toolbar above keyboard!
          if (isTypographyBarVisible) {
            drawTypographyBar(canvas, selCard, viewW, viewH)
            if (isStyleSheetOpen) {
              drawStyleSheetPopover(canvas, selCard, viewW, viewH)
            }
            if (isTypoTextColorPaletteOpen) {
              drawTypoTextColorPalette(canvas, selCard)
            }
          } else {
            drawCardActionBar(canvas, selCard, viewW, viewH, canvasTopY)
          }
        } else {
          // Non-edit mode: card selected on canvas
          drawCardActionBar(canvas, selCard, viewW, viewH, canvasTopY)
          if (isTypographyBarVisible) {
            drawTypographyBar(canvas, selCard, viewW, viewH)
            if (isStyleSheetOpen) {
              drawStyleSheetPopover(canvas, selCard, viewW, viewH)
            }
            if (isTypoTextColorPaletteOpen) {
              drawTypoTextColorPalette(canvas, selCard)
            }
          }
        }
        if (isCardColorPaletteOpen) {
          drawCardColorPalette(canvas, selCard)
        }
      }

      // Draw bottom floating toast matching video
      hudToast.draw(canvas, viewW, viewH - 64f * density)
      if (hudToast.isShowing) {
        postInvalidateOnAnimation()
      }
    }

  private fun drawCardActionBar(canvas: Canvas, card: NativeCard, viewW: Float, viewH: Float, canvasTopY: Float) {
    val isDocked = isKeyboardActive()
    val kbH = getKeyboardHeight()
    val isEditing = editingCardId != null

    val abW = min(viewW - 16f * density, 390f * density)
    val abH = 48f * density
    val typoH = 46f * density
    val spacingBetweenBars = 8f * density
    val totalStackH = if (isTypographyBarVisible) abH + typoH + spacingBetweenBars else abH

    val abLeft = (viewW - abW) / 2f
    val abTop = if (isDocked) {
      viewH - kbH - abH - 8f * density
    } else {
      val (scLeft, scTop) = canvasWorldToScreen(card.x, card.y, canvasTopY)
      val (scRight, scBottom) = canvasWorldToScreen(card.x + card.width, card.y + card.getHeight(), canvasTopY)
      val spaceAbove = scTop - (canvasTopY + 8f * density)
      if (spaceAbove >= totalStackH + 12f * density) {
        scTop - totalStackH - 12f * density
      } else {
        (scBottom + 12f * density).coerceAtMost(viewH - totalStackH - 48f * density)
      }
    }
    cardActionBarRect.set(abLeft, abTop, abLeft + abW, abTop + abH)

    // Elevation Drop Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#4D000000")
      style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
      RectF(abLeft, abTop + 3f * density, abLeft + abW, abTop + abH + 3f * density),
      24f * density, 24f * density, shadowPaint
    )

    val abBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#0F172A") // Deep Slate-900
      style = Paint.Style.FILL
    }
    val abBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#334155") // Slate-700 outline
      strokeWidth = 1.2f * density
      style = Paint.Style.STROKE
    }
    canvas.drawRoundRect(cardActionBarRect, 24f * density, 24f * density, abBgPaint)
    canvas.drawRoundRect(cardActionBarRect, 24f * density, 24f * density, abBorderPaint)

    val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#F8FAFC")
      textSize = 13.5f * density
      isFakeBoldText = true
    }

    val deleteLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#FCA5A5")
      textSize = 13.5f * density
      isFakeBoldText = true
    }

    val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#1E293B")
      style = Paint.Style.FILL
    }
    val buttonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#334155")
      strokeWidth = 1f * density
      style = Paint.Style.STROKE
    }

    val innerLeft = abLeft + 8f * density
    val innerRight = abLeft + abW - 8f * density
    val innerW = innerRight - innerLeft

    val btnH = abH - 12f * density
    val btnTop = abTop + 6f * density
    val btnBottom = btnTop + btnH
    var curX = innerLeft

    if (isEditing) {
      // LiquidText Edit Mode Bar (Matching Screenshot 2): Comment, Copy, Delete, Tags, Color, TT
      val wComment = 66f * density
      val wCopy = 50f * density
      val wDelete = 58f * density
      val wTags = 50f * density
      val wColor = 34f * density
      val wDiv = 6f * density
      val wTypo = 48f * density
      val fixedTotal = wComment + wCopy + wDelete + wTags + wColor + wDiv + wTypo
      val gap = ((innerW - fixedTotal) / 6f).coerceAtLeast(3f * density)

      // 1. Comment
      btnCardCommentRect.set(curX, btnTop, curX + wComment, btnBottom)
      canvas.drawRoundRect(btnCardCommentRect, 10f * density, 10f * density, buttonBgPaint)
      canvas.drawRoundRect(btnCardCommentRect, 10f * density, 10f * density, buttonStrokePaint)
      canvas.drawText("Comment", btnCardCommentRect.centerX() - labelPaint.measureText("Comment") / 2f, btnCardCommentRect.centerY() + 5f * density, labelPaint)
      curX += wComment + gap

      // 2. Copy
      btnCardCopyRect.set(curX, btnTop, curX + wCopy, btnBottom)
      canvas.drawRoundRect(btnCardCopyRect, 10f * density, 10f * density, buttonBgPaint)
      canvas.drawRoundRect(btnCardCopyRect, 10f * density, 10f * density, buttonStrokePaint)
      canvas.drawText("Copy", btnCardCopyRect.centerX() - labelPaint.measureText("Copy") / 2f, btnCardCopyRect.centerY() + 5f * density, labelPaint)
      curX += wCopy + gap

      // 3. Delete
      btnCardDeleteRect.set(curX, btnTop, curX + wDelete, btnBottom)
      val deleteBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3F1418")
        style = Paint.Style.FILL
      }
      val deleteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7F1D1D")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
      }
      canvas.drawRoundRect(btnCardDeleteRect, 10f * density, 10f * density, deleteBgPaint)
      canvas.drawRoundRect(btnCardDeleteRect, 10f * density, 10f * density, deleteBorderPaint)
      canvas.drawText("Delete", btnCardDeleteRect.centerX() - deleteLabelPaint.measureText("Delete") / 2f, btnCardDeleteRect.centerY() + 5f * density, deleteLabelPaint)
      curX += wDelete + gap

      // 4. Tags
      btnCardTagsRect.set(curX, btnTop, curX + wTags, btnBottom)
      canvas.drawRoundRect(btnCardTagsRect, 10f * density, 10f * density, buttonBgPaint)
      canvas.drawRoundRect(btnCardTagsRect, 10f * density, 10f * density, buttonStrokePaint)
      canvas.drawText("Tags", btnCardTagsRect.centerX() - labelPaint.measureText("Tags") / 2f, btnCardTagsRect.centerY() + 5f * density, labelPaint)
      curX += wTags + gap

      // 5. Color Wheel / Swatch
      btnCardColorWheelRect.set(curX, btnTop, curX + wColor, btnBottom)
      val cwCenter = btnCardColorWheelRect.centerX()
      val cwY = btnCardColorWheelRect.centerY()
      val cwRad = 12f * density
      val cwPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card.color; style = Paint.Style.FILL }
      canvas.drawCircle(cwCenter, cwY, cwRad, cwPaint)
      val cwRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
      }
      canvas.drawCircle(cwCenter, cwY, cwRad, cwRing)
      curX += wColor + gap

      // 6. Divider |
      val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        strokeWidth = 1.2f * density
      }
      val divX = curX + 2f * density
      canvas.drawLine(divX, abTop + 10f * density, divX, abTop + abH - 10f * density, divPaint)
      curX += wDiv + gap

      // 7. Typography Button [TT]
      btnCardTypographyRect.set(curX, btnTop, innerRight, btnBottom)
      canvas.drawRoundRect(btnCardTypographyRect, 10f * density, 10f * density, buttonBgPaint)
      canvas.drawRoundRect(btnCardTypographyRect, 10f * density, 10f * density, buttonStrokePaint)
      val typoTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        isFakeBoldText = true
      }
      canvas.drawText("TT", btnCardTypographyRect.centerX() - typoTextPaint.measureText("TT") / 2f, btnCardTypographyRect.centerY() + 5f * density, typoTextPaint)

      btnCardEditRect.setEmpty()
    } else {
      // Normal Non-Edit Card Selection Bar: Comment, Edit, Copy, Delete, Tags, Color, TT
      val wComment = 64f * density
      val wEdit = 46f * density
      val wCopy = 46f * density
      val wDelete = 52f * density
      val wTags = 44f * density
      val wColor = 30f * density
      val wDiv = 6f * density
      val wTypo = 44f * density
      val fixedTotal = wComment + wEdit + wCopy + wDelete + wTags + wColor + wDiv + wTypo
      val gap = ((innerW - fixedTotal) / 7f).coerceAtLeast(2f * density)

      // 1. Comment
      btnCardCommentRect.set(curX, btnTop, curX + wComment, btnBottom)
      canvas.drawRoundRect(btnCardCommentRect, 10f * density, 10f * density, buttonBgPaint)
      canvas.drawRoundRect(btnCardCommentRect, 10f * density, 10f * density, buttonStrokePaint)
      canvas.drawText("Comment", btnCardCommentRect.centerX() - labelPaint.measureText("Comment") / 2f, btnCardCommentRect.centerY() + 5f * density, labelPaint)
      curX += wComment + gap

      // 2. Edit
      btnCardEditRect.set(curX, btnTop, curX + wEdit, btnBottom)
      canvas.drawRoundRect(btnCardEditRect, 10f * density, 10f * density, buttonBgPaint)
      canvas.drawRoundRect(btnCardEditRect, 10f * density, 10f * density, buttonStrokePaint)
      canvas.drawText("Edit", btnCardEditRect.centerX() - labelPaint.measureText("Edit") / 2f, btnCardEditRect.centerY() + 5f * density, labelPaint)
      curX += wEdit + gap

      // 3. Copy
      btnCardCopyRect.set(curX, btnTop, curX + wCopy, btnBottom)
      canvas.drawRoundRect(btnCardCopyRect, 10f * density, 10f * density, buttonBgPaint)
      canvas.drawRoundRect(btnCardCopyRect, 10f * density, 10f * density, buttonStrokePaint)
      canvas.drawText("Copy", btnCardCopyRect.centerX() - labelPaint.measureText("Copy") / 2f, btnCardCopyRect.centerY() + 5f * density, labelPaint)
      curX += wCopy + gap

      // 4. Delete
      btnCardDeleteRect.set(curX, btnTop, curX + wDelete, btnBottom)
      val deleteBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3F1418")
        style = Paint.Style.FILL
      }
      val deleteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7F1D1D")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
      }
      canvas.drawRoundRect(btnCardDeleteRect, 10f * density, 10f * density, deleteBgPaint)
      canvas.drawRoundRect(btnCardDeleteRect, 10f * density, 10f * density, deleteBorderPaint)
      canvas.drawText("Delete", btnCardDeleteRect.centerX() - deleteLabelPaint.measureText("Delete") / 2f, btnCardDeleteRect.centerY() + 5f * density, deleteLabelPaint)
      curX += wDelete + gap

      // 5. Tags
      btnCardTagsRect.set(curX, btnTop, curX + wTags, btnBottom)
      canvas.drawRoundRect(btnCardTagsRect, 10f * density, 10f * density, buttonBgPaint)
      canvas.drawRoundRect(btnCardTagsRect, 10f * density, 10f * density, buttonStrokePaint)
      canvas.drawText("Tags", btnCardTagsRect.centerX() - labelPaint.measureText("Tags") / 2f, btnCardTagsRect.centerY() + 5f * density, labelPaint)
      curX += wTags + gap

      // 6. Color Wheel / Swatch
      btnCardColorWheelRect.set(curX, btnTop, curX + wColor, btnBottom)
      val cwCenter = btnCardColorWheelRect.centerX()
      val cwY = btnCardColorWheelRect.centerY()
      val cwRad = 12f * density
      val cwPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card.color; style = Paint.Style.FILL }
      canvas.drawCircle(cwCenter, cwY, cwRad, cwPaint)
      val cwRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
      }
      canvas.drawCircle(cwCenter, cwY, cwRad, cwRing)
      curX += wColor + gap

      // 7. Divider |
      val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        strokeWidth = 1.2f * density
      }
      val divX = curX + 2f * density
      canvas.drawLine(divX, abTop + 10f * density, divX, abTop + abH - 10f * density, divPaint)
      curX += wDiv + gap

      // 8. Typography Button [TT]
      btnCardTypographyRect.set(curX, btnTop, innerRight, btnBottom)
      if (isTypographyBarVisible) {
        val typoBtnBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2563EB"); style = Paint.Style.FILL }
        canvas.drawRoundRect(btnCardTypographyRect, 10f * density, 10f * density, typoBtnBg)
      } else {
        canvas.drawRoundRect(btnCardTypographyRect, 10f * density, 10f * density, buttonBgPaint)
        canvas.drawRoundRect(btnCardTypographyRect, 10f * density, 10f * density, buttonStrokePaint)
      }
      val typoTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        isFakeBoldText = true
      }
      canvas.drawText("TT", btnCardTypographyRect.centerX() - typoTextPaint.measureText("TT") / 2f, btnCardTypographyRect.centerY() + 5f * density, typoTextPaint)
    }
  }

  private fun drawTypographyBar(canvas: Canvas, card: NativeCard, viewW: Float, viewH: Float) {
    val isDocked = isKeyboardActive()
    val kbH = getKeyboardHeight()

    val typoW = min(viewW - 16f * density, 400f * density)
    val typoH = 46f * density
    val typoLeft = (viewW - typoW) / 2f
    val typoTop = if (isDocked) {
      viewH - kbH - typoH - 8f * density
    } else {
      cardActionBarRect.bottom + 8f * density
    }
    typographyBarRect.set(typoLeft, typoTop, typoLeft + typoW, typoTop + typoH)

    // Elevation Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#4D000000")
      style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
      RectF(typoLeft, typoTop + 3f * density, typoLeft + typoW, typoTop + typoH + 3f * density),
      23f * density, 23f * density, shadowPaint
    )

    val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#0F172A") // Deep Slate-900
      style = Paint.Style.FILL
    }
    val barBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#334155")
      strokeWidth = 1.2f * density
      style = Paint.Style.STROKE
    }
    canvas.drawRoundRect(typographyBarRect, 23f * density, 23f * density, barBg)
    canvas.drawRoundRect(typographyBarRect, 23f * density, 23f * density, barBorder)

    val itemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#F8FAFC")
      textSize = 14f * density
      isFakeBoldText = true
    }
    val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#334155")
      strokeWidth = 1.2f * density
    }
    val activeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#2563EB")
      style = Paint.Style.FILL
    }
    val itemBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#1E293B")
      style = Paint.Style.FILL
    }
    val itemStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#334155")
      strokeWidth = 1f * density
      style = Paint.Style.STROKE
    }

    val innerLeft = typoLeft + 8f * density
    val innerRight = typoLeft + typoW - 8f * density
    val btnH = typoH - 12f * density
    val btnTop = typoTop + 6f * density
    val btnBottom = btnTop + btnH

    val wUndo = 34f * density
    val wStyle = 72f * density
    val wBold = 28f * density
    val wItalic = 28f * density
    val wUnderline = 28f * density
    val wStrike = 28f * density
    val wSize = 34f * density
    val wColor = 32f * density
    val wMore = 34f * density
    val fixedTotal = wUndo + wStyle + wBold + wItalic + wUnderline + wStrike + wSize + wColor + wMore + 18f * density
    val gap = ((innerRight - innerLeft - fixedTotal) / 8f).coerceAtLeast(2f * density)

    var curX = innerLeft

    // 1. Undo
    btnTypoUndoRect.set(curX, btnTop, curX + wUndo, btnBottom)
    canvas.drawRoundRect(btnTypoUndoRect, 8f * density, 8f * density, itemBg)
    canvas.drawRoundRect(btnTypoUndoRect, 8f * density, 8f * density, itemStroke)
    val undoPaint = TextPaint(itemPaint).apply { textSize = 16f * density }
    canvas.drawText("↩", btnTypoUndoRect.centerX() - undoPaint.measureText("↩") / 2f, btnTypoUndoRect.centerY() + 5.5f * density, undoPaint)
    curX += wUndo + gap

    // Divider
    val div1X = curX + 2f * density
    canvas.drawLine(div1X, typoTop + 10f * density, div1X, typoTop + typoH - 10f * density, divPaint)
    curX += 5f * density + gap

    // 2. Style
    btnTypoStyleRect.set(curX, btnTop, curX + wStyle, btnBottom)
    if (isStyleSheetOpen) {
      canvas.drawRoundRect(btnTypoStyleRect, 8f * density, 8f * density, activeBg)
    } else {
      canvas.drawRoundRect(btnTypoStyleRect, 8f * density, 8f * density, itemBg)
      canvas.drawRoundRect(btnTypoStyleRect, 8f * density, 8f * density, itemStroke)
    }
    val styleLabel = if (card.textStyleName != "Default") card.textStyleName else "Style"
    val styleText = "$styleLabel ▾"
    val stylePaint = TextPaint(itemPaint).apply { textSize = 12.5f * density }
    canvas.drawText(styleText, btnTypoStyleRect.centerX() - stylePaint.measureText(styleText) / 2f, btnTypoStyleRect.centerY() + 4.5f * density, stylePaint)
    curX += wStyle + gap

    // Divider
    val div2X = curX + 2f * density
    canvas.drawLine(div2X, typoTop + 10f * density, div2X, typoTop + typoH - 10f * density, divPaint)
    curX += 5f * density + gap

    // 3. Bold 'B'
    btnTypoBoldRect.set(curX, btnTop, curX + wBold, btnBottom)
    if (card.isBold) {
      canvas.drawRoundRect(btnTypoBoldRect, 8f * density, 8f * density, activeBg)
    } else {
      canvas.drawRoundRect(btnTypoBoldRect, 8f * density, 8f * density, itemBg)
      canvas.drawRoundRect(btnTypoBoldRect, 8f * density, 8f * density, itemStroke)
    }
    val boldPaint = TextPaint(itemPaint).apply { isFakeBoldText = true }
    canvas.drawText("B", btnTypoBoldRect.centerX() - boldPaint.measureText("B") / 2f, btnTypoBoldRect.centerY() + 5f * density, boldPaint)
    curX += wBold + gap

    // 4. Italic 'I'
    btnTypoItalicRect.set(curX, btnTop, curX + wItalic, btnBottom)
    if (card.isItalic) {
      canvas.drawRoundRect(btnTypoItalicRect, 8f * density, 8f * density, activeBg)
    } else {
      canvas.drawRoundRect(btnTypoItalicRect, 8f * density, 8f * density, itemBg)
      canvas.drawRoundRect(btnTypoItalicRect, 8f * density, 8f * density, itemStroke)
    }
    val italicPaint = TextPaint(itemPaint).apply { textSkewX = -0.25f }
    canvas.drawText("I", btnTypoItalicRect.centerX() - italicPaint.measureText("I") / 2f, btnTypoItalicRect.centerY() + 5f * density, italicPaint)
    curX += wItalic + gap

    // 5. Underline 'U'
    btnTypoUnderlineRect.set(curX, btnTop, curX + wUnderline, btnBottom)
    if (card.isUnderline) {
      canvas.drawRoundRect(btnTypoUnderlineRect, 8f * density, 8f * density, activeBg)
    } else {
      canvas.drawRoundRect(btnTypoUnderlineRect, 8f * density, 8f * density, itemBg)
      canvas.drawRoundRect(btnTypoUnderlineRect, 8f * density, 8f * density, itemStroke)
    }
    val ulPaint = TextPaint(itemPaint).apply { isUnderlineText = true }
    canvas.drawText("U", btnTypoUnderlineRect.centerX() - ulPaint.measureText("U") / 2f, btnTypoUnderlineRect.centerY() + 5f * density, ulPaint)
    curX += wUnderline + gap

    // 6. Strikethrough 'S'
    btnTypoStrikeRect.set(curX, btnTop, curX + wStrike, btnBottom)
    if (card.isStrikethrough) {
      canvas.drawRoundRect(btnTypoStrikeRect, 8f * density, 8f * density, activeBg)
    } else {
      canvas.drawRoundRect(btnTypoStrikeRect, 8f * density, 8f * density, itemBg)
      canvas.drawRoundRect(btnTypoStrikeRect, 8f * density, 8f * density, itemStroke)
    }
    val strikePaint = TextPaint(itemPaint).apply { isStrikeThruText = true }
    canvas.drawText("S", btnTypoStrikeRect.centerX() - strikePaint.measureText("S") / 2f, btnTypoStrikeRect.centerY() + 5f * density, strikePaint)
    curX += wStrike + gap

    // Divider
    val div3X = curX + 2f * density
    canvas.drawLine(div3X, typoTop + 10f * density, div3X, typoTop + typoH - 10f * density, divPaint)
    curX += 5f * density + gap

    // 7. Font size indicator
    btnTypoFontSizeRect.set(curX, btnTop, curX + wSize, btnBottom)
    canvas.drawRoundRect(btnTypoFontSizeRect, 8f * density, 8f * density, itemBg)
    canvas.drawRoundRect(btnTypoFontSizeRect, 8f * density, 8f * density, itemStroke)
    val sizeText = "${card.fontSize.toInt()}"
    canvas.drawText(sizeText, btnTypoFontSizeRect.centerX() - itemPaint.measureText(sizeText) / 2f, btnTypoFontSizeRect.centerY() + 5f * density, itemPaint)
    curX += wSize + gap

    // 8. Text Color A_ (dash a)
    btnTypoTextColorRect.set(curX, btnTop, curX + wColor, btnBottom)
    if (isTypoTextColorPaletteOpen) {
      canvas.drawRoundRect(btnTypoTextColorRect, 8f * density, 8f * density, activeBg)
    } else {
      canvas.drawRoundRect(btnTypoTextColorRect, 8f * density, 8f * density, itemBg)
      canvas.drawRoundRect(btnTypoTextColorRect, 8f * density, 8f * density, itemStroke)
    }
    canvas.drawText("A", btnTypoTextColorRect.centerX() - itemPaint.measureText("A") / 2f, btnTypoTextColorRect.centerY() + 3.5f * density, itemPaint)
    val colorBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = card.textColor
      strokeWidth = 3f * density
      strokeCap = Paint.Cap.ROUND
    }
    val barY = btnTypoTextColorRect.bottom - 5f * density
    canvas.drawLine(btnTypoTextColorRect.left + 7f * density, barY, btnTypoTextColorRect.right - 7f * density, barY, colorBarPaint)
    curX += wColor + gap

    // 9. TT Toggle / Back Button
    btnTypoMoreRect.set(curX, btnTop, innerRight, btnBottom)
    if (editingCardId != null) {
      val ttActiveBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2563EB"); style = Paint.Style.FILL }
      canvas.drawRoundRect(btnTypoMoreRect, 8f * density, 8f * density, ttActiveBg)
      val ttPaint = TextPaint(itemPaint).apply { textSize = 13.5f * density }
      canvas.drawText("TT", btnTypoMoreRect.centerX() - ttPaint.measureText("TT") / 2f, btnTypoMoreRect.centerY() + 4.5f * density, ttPaint)
    } else {
      canvas.drawRoundRect(btnTypoMoreRect, 8f * density, 8f * density, itemBg)
      canvas.drawRoundRect(btnTypoMoreRect, 8f * density, 8f * density, itemStroke)
      val morePaint = TextPaint(itemPaint).apply { textSize = 15f * density }
      canvas.drawText("···", btnTypoMoreRect.centerX() - morePaint.measureText("···") / 2f, btnTypoMoreRect.centerY() + 4f * density, morePaint)
    }
  }

  private fun drawStyleSheetPopover(canvas: Canvas, card: NativeCard, viewW: Float, viewH: Float) {
    val popW = min(viewW - 24f * density, 300f * density)
    val rowH = 44f * density
    val options = listOf(
      Pair("Title", 20f),
      Pair("Subtitle", 16f),
      Pair("Heading 1", 18f),
      Pair("Heading 2", 16f),
      Pair("Heading 3", 14f),
      Pair("Default Style for New Excerpts", 13f)
    )
    val popH = options.size * rowH

    val popLeft = (typographyBarRect.left + 10f * density).coerceIn(10f * density, viewW - popW - 10f * density)
    val isDocked = isKeyboardActive()
    val popTop = if (isDocked) {
      typographyBarRect.top - popH - 8f * density
    } else {
      (typographyBarRect.bottom + 6f * density).coerceAtMost(viewH - popH - 12f * density)
    }
    styleSheetRect.set(popLeft, popTop, popLeft + popW, popTop + popH)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#4D000000")
      style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
      RectF(popLeft, popTop + 4f * density, popLeft + popW, popTop + popH + 4f * density),
      14f * density, 14f * density, shadowPaint
    )

    val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.FILL
    }
    val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#E2E8F0")
      strokeWidth = 1.2f * density
      style = Paint.Style.STROKE
    }
    canvas.drawRoundRect(styleSheetRect, 14f * density, 14f * density, cardBg)
    canvas.drawRoundRect(styleSheetRect, 14f * density, 14f * density, cardBorder)

    styleOptionRects.clear()
    val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#F1F5F9")
      strokeWidth = 1f * density
    }
    val dotsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#94A3B8")
      textSize = 14f * density
      isFakeBoldText = true
    }

    for (i in options.indices) {
      val name = options[i].first
      val rTop = popTop + i * rowH
      val rowRect = RectF(popLeft, rTop, popLeft + popW, rTop + rowH)
      val menuRect = RectF(rowRect.right - 28f * density, rTop, rowRect.right, rTop + rowH)
      styleOptionRects.add(Triple(rowRect, name, menuRect))

      val isSelected = card.textStyleName == name || (name == "Default Style for New Excerpts" && card.textStyleName == "Default")
      if (isSelected) {
        val selRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#EFF6FF")
          style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rowRect, 8f * density, 8f * density, selRowPaint)
      }

      val rowTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelected) Color.parseColor("#2563EB") else Color.parseColor("#0F172A")
        textSize = when (name) {
          "Title" -> 18f * density
          "Subtitle" -> 14.5f * density
          "Heading 1" -> 16.5f * density
          "Heading 2" -> 15f * density
          "Heading 3" -> 13.5f * density
          else -> 12.5f * density
        }
        isFakeBoldText = name != "Subtitle" && name != "Default Style for New Excerpts"
        if (name == "Subtitle") textSkewX = -0.15f
      }

      canvas.drawText(name, rowRect.left + 14f * density, rowRect.centerY() + 4.5f * density, rowTextPaint)
      canvas.drawText("⋮", rowRect.right - 18f * density, rowRect.centerY() + 4.5f * density, dotsPaint)

      if (i < options.size - 1) {
        canvas.drawLine(popLeft + 10f * density, rowRect.bottom, popLeft + popW - 10f * density, rowRect.bottom, divPaint)
      }
    }
  }

  private fun drawCardColorPalette(canvas: Canvas, card: NativeCard) {
    val paletteColors = listOf(
      Color.parseColor("#EAB308"), // Yellow
      Color.parseColor("#3B82F6"), // Blue
      Color.parseColor("#22C55E"), // Green
      Color.parseColor("#8B5CF6"), // Purple
      Color.parseColor("#EC4899"), // Pink
      Color.parseColor("#EF4444")  // Red
    )
    val swatchSize = 24f * density
    val spacing = 8f * density
    val pW = paletteColors.size * (swatchSize + spacing) + 14f * density
    val pH = swatchSize + 14f * density
    val pLeft = (btnCardColorWheelRect.centerX() - pW / 2f).coerceIn(10f * density, width - pW - 10f * density)
    val isDocked = isKeyboardActive()
    val anchorTop = if (isTypographyBarVisible) typographyBarRect.top else cardActionBarRect.top
    val anchorBottom = if (isTypographyBarVisible) typographyBarRect.bottom else cardActionBarRect.bottom
    val pTop = if (isDocked) {
      anchorTop - pH - 8f * density
    } else {
      (anchorBottom + 6f * density).coerceAtMost(height - pH - 12f * density)
    }
    val pRect = RectF(pLeft, pTop, pLeft + pW, pTop + pH)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#4D000000")
      style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
      RectF(pLeft, pTop + 3f * density, pLeft + pW, pTop + pH + 3f * density),
      12f * density, 12f * density, shadowPaint
    )

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F172A"); style = Paint.Style.FILL }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#334155"); strokeWidth = 1.2f * density; style = Paint.Style.STROKE }
    canvas.drawRoundRect(pRect, 12f * density, 12f * density, bg)
    canvas.drawRoundRect(pRect, 12f * density, 12f * density, border)

    cardColorPaletteRects.clear()
    for (i in paletteColors.indices) {
      val col = paletteColors[i]
      val sLeft = pLeft + 7f * density + i * (swatchSize + spacing)
      val sTop = pTop + 7f * density
      val sRect = RectF(sLeft, sTop, sLeft + swatchSize, sTop + swatchSize)
      cardColorPaletteRects.add(Pair(sRect, col))

      val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }
      canvas.drawCircle(sRect.centerX(), sRect.centerY(), swatchSize / 2f, sp)
      if (col == card.color) {
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.5f * density; style = Paint.Style.STROKE }
        canvas.drawCircle(sRect.centerX(), sRect.centerY(), swatchSize / 2f + 2f * density, ring)
      }
    }
  }

  private fun drawTypoTextColorPalette(canvas: Canvas, card: NativeCard) {
    val textColors = listOf(
      Color.parseColor("#FFFFFF"), // White
      Color.parseColor("#94A3B8"), // Slate
      Color.parseColor("#000000"), // Black
      Color.parseColor("#2563EB"), // Blue
      Color.parseColor("#16A34A"), // Green
      Color.parseColor("#DC2626"), // Red
      Color.parseColor("#D97706")  // Amber
    )
    val swatchSize = 24f * density
    val spacing = 8f * density
    val pW = textColors.size * (swatchSize + spacing) + 14f * density
    val pH = swatchSize + 14f * density
    val pLeft = (btnTypoTextColorRect.centerX() - pW / 2f).coerceIn(10f * density, width - pW - 10f * density)
    val isDocked = isKeyboardActive()
    val pTop = if (isDocked) {
      typographyBarRect.top - pH - 8f * density
    } else {
      (typographyBarRect.bottom + 6f * density).coerceAtMost(height - pH - 12f * density)
    }
    val pRect = RectF(pLeft, pTop, pLeft + pW, pTop + pH)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#4D000000")
      style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
      RectF(pLeft, pTop + 3f * density, pLeft + pW, pTop + pH + 3f * density),
      12f * density, 12f * density, shadowPaint
    )

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F172A"); style = Paint.Style.FILL }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#334155"); strokeWidth = 1.2f * density; style = Paint.Style.STROKE }
    canvas.drawRoundRect(pRect, 12f * density, 12f * density, bg)
    canvas.drawRoundRect(pRect, 12f * density, 12f * density, border)

    typoTextColorPaletteRects.clear()
    for (i in textColors.indices) {
      val col = textColors[i]
      val sLeft = pLeft + 7f * density + i * (swatchSize + spacing)
      val sTop = pTop + 7f * density
      val sRect = RectF(sLeft, sTop, sLeft + swatchSize, sTop + swatchSize)
      typoTextColorPaletteRects.add(Pair(sRect, col))

      val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }
      canvas.drawCircle(sRect.centerX(), sRect.centerY(), swatchSize / 2f, sp)
      if (col == card.textColor) {
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.5f * density; style = Paint.Style.STROKE }
        canvas.drawCircle(sRect.centerX(), sRect.centerY(), swatchSize / 2f + 2f * density, ring)
      }
    }
  }

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

    val inDivider = hasDoc && (sy in (splitY - 30f)..(splitY + 30f))
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

    // Only pass events to scaleGestureDetector when NOT performing document compression
    val shouldRouteToScaleDetector = if (event.pointerCount >= 2 && inDocZoneMulti) {
      pdfScaleFactor > 1.25f && !compressionEngine.isManualPinching
    } else {
      !compressionEngine.isManualPinching
    }
    if (shouldRouteToScaleDetector) {
      scaleGestureDetector.onTouchEvent(event)
    }

    // 1. Two or more fingers -> Canvas Pan & Zoom or Document Pinch Compression / Pan
    if (event.pointerCount >= 2) {
      if (inDocZoneMulti) {
        when (event.actionMasked) {
          MotionEvent.ACTION_POINTER_DOWN -> {
            if (event.pointerCount == 2) {
              val y0 = event.getY(0)
              val y1 = event.getY(1)
              val x0 = event.getX(0)
              val x1 = event.getX(1)
              docPinchInitialSpan = hypot(x0 - x1, y0 - y1)
              docPinchInitialDistY = abs(y0 - y1)
              lastTouchScreenX = (x0 + x1) / 2f
              lastTouchScreenY = (y0 + y1) / 2f

              if (pdfScaleFactor <= 1.15f) {
                val pageBounds = pageLayouts.map { it.boundsOnScreen }
                val pageIndices = pageLayouts.map { it.pageIndex }
                val annotatedPages = (annotations.map { it.pageNumber - 1 } + cards.map { it.pageNumber - 1 }).toSet()
                compressionEngine.onManualPinchBegin(y0, y1, pageBounds, pageIndices, annotatedPages)
              }
            }
          }
          MotionEvent.ACTION_MOVE -> {
            val y0 = event.getY(0)
            val y1 = event.getY(1)
            val x0 = event.getX(0)
            val x1 = event.getX(1)
            val currentDistY = abs(y0 - y1)
            val currentSpan = hypot(x0 - x1, y0 - y1)

            if (!compressionEngine.isManualPinching && pdfScaleFactor <= 1.15f) {
              val spanDelta = docPinchInitialSpan - currentSpan
              val distYDelta = docPinchInitialDistY - currentDistY
              val isPinchingInward = spanDelta > 8f * density || distYDelta > 8f * density
              val isVerticalPinch = currentDistY > 24f * density

              if (isPinchingInward || isVerticalPinch) {
                val pageBounds = pageLayouts.map { it.boundsOnScreen }
                val pageIndices = pageLayouts.map { it.pageIndex }
                val annotatedPages = (annotations.map { it.pageNumber - 1 } + cards.map { it.pageNumber - 1 }).toSet()
                compressionEngine.onManualPinchBegin(y0, y1, pageBounds, pageIndices, annotatedPages)
              }
            }

            if (compressionEngine.isManualPinching) {
              val handled = compressionEngine.onManualPinchMove(y0, y1)
              if (handled) {
                invalidate()
                return true
              }
            } else {
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
            docPinchInitialSpan = 0f
            docPinchInitialDistY = 0f
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
        if (isTypographyBarVisible && selectedCardId != null && typographyBarRect.contains(sx, sy)) {
          val selCard = cards.find { it.id == selectedCardId }
          if (selCard != null) {
            if (btnTypoUndoRect.contains(sx, sy)) {
              if (selCard.undoTextStack.isNotEmpty()) {
                selCard.text = selCard.undoTextStack.removeLast()
                cursorPosition = selCard.text.length
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                hudToast.show("↩ Undone")
                invalidate()
                return true
              } else {
                undo()
                return true
              }
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
              if (editingCardId != null) {
                isTypographyBarVisible = false
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
              }
              hudToast.show("Typography: ${selCard.textStyleName} (${selCard.fontSize.toInt()}pt)")
              performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
              invalidate()
              return true
            }
          }
          return true
        }

        // 0D. Check Primary Excerpt Action Bar Clicks (Screenshot 1)
        if (selectedCardId != null && cardActionBarRect.contains(sx, sy)) {
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
        if (inDocZone && sy < subheaderH) {
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

          // 2. Check Callout Clicks matching Video (+Word, Word+, All, Excerpt, Copy, Highlight, Close)
          if (pdfSel != null && pdfSel.calloutRect.contains(sx, sy)) {
            if (pdfSel.calloutExcerptBtn.contains(sx, sy)) {
              extractExcerptToCanvas(pdfSel.text, pdfSel.pageIndex + 1, selectedColor, pdfSel.pdfRects)
              activePdfSelection = null
              activeStructuredPInfo = null
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              invalidate()
              return true
            }
            if (pdfSel.calloutCopyBtn.contains(sx, sy)) {
              copyToClipboard(pdfSel.text)
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              return true
            }
            if (pdfSel.calloutHighlightBtn.contains(sx, sy)) {
              addAnnotation(pdfSel.text, pdfSel.pageIndex + 1, selectedColor, pdfSel.pdfRects)
              activePdfSelection = null
              activeStructuredPInfo = null
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              invalidate()
              return true
            }
            for (cb in pdfSel.calloutColorBtns) {
              if (cb.first.contains(sx, sy)) {
                val colorToUse = if (cb.second == android.graphics.Color.WHITE) android.graphics.Color.parseColor("#F59E0B") else cb.second
                selectedColor = colorToUse
                addAnnotation(pdfSel.text, pdfSel.pageIndex + 1, colorToUse, pdfSel.pdfRects)
                activePdfSelection = null
                activeStructuredPInfo = null
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
              }
            }
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
            if (pdfSel.calloutTagsBtn.contains(sx, sy)) {
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("Tags: #keypoint #research")
              return true
            }
            if (pdfSel.calloutMoreBtn.contains(sx, sy)) {
              performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("More options")
              return true
            }
            if (pdfSel.calloutAddWordLeftBtn.contains(sx, sy)) {
              if (activePdfDoc != null) {
                val pl = pageLayouts.firstOrNull { it.pageIndex == pdfSel.pageIndex }
                if (pl != null && pdfSel.startWordIndex > 0) {
                  updatePdfSelectionByIndex(pl, pdfSel.startWordIndex - 1, pdfSel.endWordIndex)
                  performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
              } else if (activeStructuredPInfo != null) {
                val pInfo = activeStructuredPInfo!!
                var newStart = (activeStructuredStartOffset - 1).coerceAtLeast(0)
                while (newStart > 0 && pInfo.text[newStart].isWhitespace()) {
                  newStart--
                }
                while (newStart > 0 && !pInfo.text[newStart - 1].isWhitespace()) {
                  newStart--
                }
                updateStructuredSelectionByOffsets(pInfo, newStart, activeStructuredEndOffset)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              }
              return true
            }
            if (pdfSel.calloutAddWordRightBtn.contains(sx, sy)) {
              if (activePdfDoc != null) {
                val pl = pageLayouts.firstOrNull { it.pageIndex == pdfSel.pageIndex }
                val words = pageWordsCache[pdfSel.pageIndex]
                if (pl != null && words != null && pdfSel.endWordIndex < words.size - 1) {
                  updatePdfSelectionByIndex(pl, pdfSel.startWordIndex, pdfSel.endWordIndex + 1)
                  performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
              } else if (activeStructuredPInfo != null) {
                val pInfo = activeStructuredPInfo!!
                var newEnd = (activeStructuredEndOffset + 1).coerceAtMost(pInfo.text.length)
                while (newEnd < pInfo.text.length && pInfo.text[newEnd].isWhitespace()) {
                  newEnd++
                }
                while (newEnd < pInfo.text.length && !pInfo.text[newEnd].isWhitespace()) {
                  newEnd++
                }
                updateStructuredSelectionByOffsets(pInfo, activeStructuredStartOffset, newEnd)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              }
              return true
            }
            if (pdfSel.calloutSelectAllBtn.contains(sx, sy)) {
              if (activePdfDoc != null) {
                val pl = pageLayouts.firstOrNull { it.pageIndex == pdfSel.pageIndex }
                val words = pageWordsCache[pdfSel.pageIndex]
                if (pl != null && !words.isNullOrEmpty()) {
                  updatePdfSelectionByIndex(pl, 0, words.size - 1)
                  performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
              } else if (activeStructuredPInfo != null) {
                val pInfo = activeStructuredPInfo!!
                updateStructuredSelectionByOffsets(pInfo, 0, pInfo.text.length)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
              }
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
              if (cropSel.calloutHighlightBtn.contains(sx, sy)) {
                val colorToUse = if (cropSel.color == Color.WHITE) Color.parseColor("#EAB308") else cropSel.color
                val r = RectF(cropSel.pageBounds.left, cropSel.pageBounds.top, cropSel.pageBounds.right, cropSel.pageBounds.bottom)
                addAnnotation(cropSel.dimensionsText.ifEmpty { "Highlighted Region" }, cropSel.pageIndex + 1, colorToUse, listOf(r))
                hudToast.show("Highlighted on Page ${cropSel.pageIndex + 1}")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                activeCropSelection = null
                invalidate()
                return true
              }
              if (cropSel.calloutExcerptBtn.contains(sx, sy)) {
                extractCropToCanvas(cropSel)
                activeCropSelection = null
                docMode = "text"
                invalidate()
                return true
              }
              if (cropSel.calloutCloseBtn.contains(sx, sy)) {
                activeCropSelection = null
                docMode = "text"
                invalidate()
                return true
              }
              if (cropSel.calloutCommentBtn.contains(sx, sy)) {
                hudToast.show("Comment added to excerpt")
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
              }
              if (cropSel.calloutBookmarkBtn.contains(sx, sy)) {
                hudToast.show("Bookmark saved on page ${cropSel.pageIndex + 1}")
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
              }
              val clickedColorPair = cropSel.calloutColorBtns.find { it.first.contains(sx, sy) }
              if (clickedColorPair != null) {
                val colorToUse = if (clickedColorPair.second == Color.WHITE) Color.parseColor("#EAB308") else clickedColorPair.second
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
              return true
            }

            // 2. Check Diagonal Corner Handles (Top-Left & Bottom-Right)
            val distTL = hypot(sx - cropSel.screenRect.left, sy - cropSel.screenRect.top)
            val distBR = hypot(sx - cropSel.screenRect.right, sy - cropSel.screenRect.bottom)
            val handleHitRadius = 34f * density

            if (distTL <= handleHitRadius) {
              isDraggingCropTopLeftHandle = true
              isDraggingCropBottomRightHandle = false
              isScrollingDoc = false
              parent?.requestDisallowInterceptTouchEvent(true)
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              return true
            } else if (distBR <= handleHitRadius) {
              isDraggingCropBottomRightHandle = true
              isDraggingCropTopLeftHandle = false
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

          // Start 350ms Long-Press Timer for Android-style Text Selection (handles & callout)
          // ONLY when activeTool is explicitly "select"
          if (activeTool == "select") {
            longPressStartX = sx
            longPressStartY = sy
            pendingLongPressRunnable?.let { longPressHandler.removeCallbacks(it) }
            pendingLongPressRunnable = Runnable {
              triggerLongPressSelect(longPressStartX, longPressStartY)
            }
            longPressHandler.postDelayed(pendingLongPressRunnable!!, 350)
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

        if (hypot(sx - longPressStartX, sy - longPressStartY) > 18f * density) {
          pendingLongPressRunnable?.let {
            longPressHandler.removeCallbacks(it)
            pendingLongPressRunnable = null
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

        // Dragging Divider
        if (isDraggingDivider) {
          val newRatio = (sy / viewH).coerceIn(0.18f, 0.82f)
          splitRatio = newRatio
          dispatchSplitRatioEvent(newRatio)
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
          val rightBound = sel.screenRect.right - 48f * density
          val topBound = pl?.boundsOnScreen?.top ?: subheaderH
          val bottomBound = sel.screenRect.bottom - 48f * density

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

        // Dragging Bottom-Right Handle of Area Excerpt
        if (isDraggingCropBottomRightHandle && activeCropSelection != null) {
          val sel = activeCropSelection!!
          val pl = pageLayouts.find { it.pageIndex == sel.pageIndex }
          val leftBound = sel.screenRect.left + 48f * density
          val rightBound = pl?.boundsOnScreen?.right ?: (width - 12f)
          val topBound = sel.screenRect.top + 48f * density
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

            if (activeTool == "pen" && penDrawingMode == "straight") {
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

          if (activeTool == "pen" && penDrawingMode == "straight") {
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
          if (sel != null && (sel.screenRect.width() < 24f * density || sel.screenRect.height() < 24f * density)) {
            val pl = pageLayouts.find { it.pageIndex == sel.pageIndex }
            if (pl != null) {
              val defW = min(pl.boundsOnScreen.width() * 0.70f, 240f * density)
              val defH = min(pl.boundsOnScreen.height() * 0.35f, 160f * density)
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

              activeCropSelection = createCropSelection(
                pageIndex = pl.pageIndex,
                sRect = sRect,
                pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
                color = selectedColor,
                dimensionsText = "Page ${pl.pageIndex + 1} (${pW.toInt()}x${pH.toInt()} pt)"
              )
            }
          }
          invalidate()
        }

        isDraggingStartHandle = false
        isDraggingEndHandle = false
        isDraggingCropTopLeftHandle = false
        isDraggingCropBottomRightHandle = false
        isMovingCropSelection = false
        isDraggingDivider = false
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
          val isStraight = activeTool == "pen" && penDrawingMode == "straight"
          val newPageStroke = PdfPageStroke(
            id = "doc-stroke-${System.currentTimeMillis()}",
            pageIndex = activeDocStrokePageIndex,
            points = activeDocPoints.toList(),
            color = if (isHl) selectedColor else penColor,
            strokeWidth = if (isHl) 18f else penThickness,
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
          val isStraight = activeTool == "pen" && penDrawingMode == "straight"
          val newStroke = NativeStroke(
            id = "stroke-${System.currentTimeMillis()}",
            points = activePoints.toList(),
            color = if (isHl) selectedColor else penColor,
            strokeWidth = if (isHl) 20f else penThickness,
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
  // Document Tap Selection Handling
  // ---------------------------------------------------------------------------
  private fun handleDocTap(tapX: Float, tapY: Float) {
    // 0. If user tapped outside an active selection, dismiss it and return!
    if (activeCropSelection != null || activePdfSelection != null) {
      activeCropSelection = null
      activePdfSelection = null
      docMode = "text"
      invalidate()
      return
    }

    // 1. Real PDF Word Selection or InkLink Tap
    if (activePdfDoc != null) {
      for (pl in pageLayouts) {
        if (pl.boundsOnScreen.contains(tapX, tapY)) {
          val px = (tapX - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
          val py = (tapY - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height

          // Check if tapped on or near an InkLink anchor pin in the PDF
          val matchingLink = semanticInkLinks.find {
            it.sourceDocId == activeDocumentId &&
            it.sourcePageIndex == pl.pageIndex &&
            hypot(it.sourcePdfPoint.x - px, it.sourcePdfPoint.y - py) < 28f
          }
          if (matchingLink != null) {
            val targetCard = cards.find { it.id == matchingLink.targetCardId }
            if (targetCard != null) {
              val targetCx = targetCard.x + targetCard.width / 2f
              val targetCy = targetCard.y + targetCard.getHeight() / 2f
              val hasDoc = activePdfDoc != null || activeDocument != null
              val curCanvasTopY = if (hasDoc && effectiveSplitRatio > 0f) height * effectiveSplitRatio + 14f else 0f
              val viewW = width.toFloat()
              val viewH = height - curCanvasTopY
              camera.panX = -targetCx + (viewW / 2f) / camera.scaleFactor
              camera.panY = -targetCy + (viewH / 2f) / camera.scaleFactor
              selectedCardId = targetCard.id
              performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              hudToast.show("Jumped to Linked Card in Workspace")
              invalidate()
              return
            }
          }

          if (pl.isFolded) {
            compressionEngine.expandPage(pl.pageIndex, animate = true) { invalidate() }
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
          }
          val words = pageWordsCache[pl.pageIndex]
          if (words != null && words.isNotEmpty() && activeTool == "select") {
            val px = (tapX - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
            val py = (tapY - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height
            val hitWord = words.find { w ->
              val b = w.bounds
              px >= b.left - 6f && px <= b.right + 6f && py >= b.top - 8f && py <= b.bottom + 8f
            } ?: words.minByOrNull { w ->
              val b = w.bounds
              val cx = (b.left + b.right) / 2f
              val cy = (b.top + b.bottom) / 2f
              (cx - px) * (cx - px) + (cy - py) * (cy - py)
            }?.takeIf { w ->
              val b = w.bounds
              val cx = (b.left + b.right) / 2f
              val cy = (b.top + b.bottom) / 2f
              val distSq = (cx - px) * (cx - px) + (cy - py) * (cy - py)
              distSq < 36f * 36f
            }

            if (hitWord != null) {
              updatePdfSelection(pl, hitWord, hitWord)
              invalidate()
              return
            }
          }

          // If no text word was hit:
          // ONLY create a Figure Crop selection if the user explicitly switched to "crop" mode!
          if (docMode == "crop") {
            val cropW = min(pl.boundsOnScreen.width() * 0.85f, 320f * density)
            val cropH = min(pl.boundsOnScreen.height() * 0.45f, 220f * density)
            val sRect = RectF(
              (tapX - cropW / 2f).coerceIn(pl.boundsOnScreen.left + 8f * density, pl.boundsOnScreen.right - cropW - 8f * density),
              (tapY - cropH / 2f).coerceIn(pl.boundsOnScreen.top + 8f * density, pl.boundsOnScreen.bottom - cropH - 8f * density),
              (tapX + cropW / 2f).coerceIn(pl.boundsOnScreen.left + cropW + 8f * density, pl.boundsOnScreen.right - 8f * density),
              (tapY + cropH / 2f).coerceIn(pl.boundsOnScreen.top + cropH + 8f * density, pl.boundsOnScreen.bottom - 8f * density)
            )

            val pW = pl.pageSize.width
            val pH = pl.pageSize.height
            val pageLeft = (sRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageTop = (sRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
            val pageRight = (sRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
            val pageBottom = (sRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH

            val cW = 200f * density
            val cH = 44f * density
            val cLeft = sRect.centerX() - cW / 2f
            val cTop = if (sRect.top - cH - 12f * density > subheaderH) sRect.top - cH - 12f * density else sRect.bottom + 12f * density
            val calloutR = RectF(cLeft, cTop, cLeft + cW, cTop + cH)
            val excerptBtn = RectF(cLeft + 8f * density, cTop + 4f * density, cLeft + cW - 44f * density, cTop + cH - 4f * density)
            val closeBtn = RectF(cLeft + cW - 40f * density, cTop + 4f * density, cLeft + cW - 4f * density, cTop + cH - 4f * density)

            activeCropSelection = createCropSelection(
              pageIndex = pl.pageIndex,
              sRect = sRect,
              pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
              color = selectedColor,
              dimensionsText = "Page ${pl.pageIndex + 1} (${pW.toInt()}x${pH.toInt()} pt)"
            )
            activePdfSelection = null
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
            return
          } else {
            // Normal tap on blank whitespace in text mode: toggle immersive mode!
            activeCropSelection = null
            activePdfSelection = null
            toggleImmersiveMode()
            invalidate()
            return
          }
        }
      }
    }

    // 2. Structured Sections Document
    if (activeDocument != null && activePdfDoc == null && activeTool == "select") {
      for (pInfo in paragraphLayouts) {
        val paraH = pInfo.layout.height.toFloat() + 16f
        val pRect = RectF(pInfo.paperX, pInfo.topY, pInfo.paperX + pInfo.width + 56f, pInfo.topY + paraH)
        if (pRect.contains(tapX, tapY)) {
          val hlLeft = pInfo.paperX + 24f
          val hlTop = pInfo.topY - 4f
          val hlRight = pInfo.paperX + pInfo.width + 32f
          val hlBottom = pInfo.topY + pInfo.layout.height + 4f
          val highlightR = RectF(hlLeft, hlTop, hlRight, hlBottom)

          val layout = computeSelectionCalloutLayout(listOf(highlightR))

          activePdfSelection = NativePdfSelection(
            pageIndex = pInfo.pageNumber - 1,
            text = pInfo.text,
            highlightRects = listOf(highlightR),
            pdfRects = emptyList(),
            startHandle = RectF(hlLeft - 12f * density, hlTop - 20f * density, hlLeft + 12f * density, hlBottom),
            endHandle = RectF(hlRight - 12f * density, hlTop, hlRight + 12f * density, hlBottom + 20f * density),
            calloutRect = layout.calloutRect,
            calloutExcerptBtn = layout.excerptBtn,
            calloutCopyBtn = layout.copyBtn,
            calloutHighlightBtn = layout.highlightBtn,
            calloutCloseBtn = layout.closeBtn,
            startWordIndex = 0,
            endWordIndex = 0,
            calloutAddWordLeftBtn = layout.addWordLeftBtn,
            calloutAddWordRightBtn = layout.addWordRightBtn,
            calloutSelectAllBtn = layout.selectAllBtn,
            charCountText = "${pInfo.text.length} chars",
            calloutColorBtns = layout.colorBtns,
            calloutMoreBtn = layout.moreBtn,
            calloutTagsBtn = layout.tagsBtn,
            calloutSubCardRect = layout.subCardRect,
            calloutMainCardRect = layout.mainCardRect,
            calloutRainbowBtn = layout.rainbowBtn
          )
          invalidate()
          return
        }
      }
    }

    // 3. Tapped outside -> Dismiss selection or toggle immersive mode
    val hadSelection = activePdfSelection != null || activeCropSelection != null
    activePdfSelection = null
    activeCropSelection = null
    if (!hadSelection) {
      toggleImmersiveMode()
    }
    invalidate()
  }

  // ---------------------------------------------------------------------------
  // Real PDF Word Selection Engine matching LiquidText
  // ---------------------------------------------------------------------------

  /**
   * LiquidText-Style Line Clustering:
   * Groups selected consecutive words belonging to the same horizontal text line and
   * merges them into single continuous bounding rectangles covering all spaces between words.
   */
  private fun clusterWordsIntoLineRects(
    words: List<TextWord>,
    pl: PdfPageLayout
  ): Pair<List<RectF>, List<RectF>> {
    if (words.isEmpty()) return Pair(emptyList(), emptyList())

    val lineGroups = mutableListOf<MutableList<TextWord>>()
    for (word in words) {
      if (lineGroups.isEmpty()) {
        lineGroups.add(mutableListOf(word))
      } else {
        val currentGroup = lineGroups.last()
        val prev = currentGroup.last()

        val overlapTop = max(prev.bounds.top, word.bounds.top)
        val overlapBottom = min(prev.bounds.bottom, word.bounds.bottom)
        val overlapH = overlapBottom - overlapTop
        val minH = min(prev.bounds.height, word.bounds.height)

        val baselineMatch = if (prev.baseline > 0f && word.baseline > 0f) {
          abs(prev.baseline - word.baseline) <= max(prev.fontSize, word.fontSize) * 0.45f
        } else false

        val isSameLine = baselineMatch ||
          (minH > 0f && overlapH >= minH * 0.45f) ||
          (abs(prev.bounds.top - word.bounds.top) <= max(prev.bounds.height, word.bounds.height) * 0.35f)

        if (isSameLine) {
          currentGroup.add(word)
        } else {
          lineGroups.add(mutableListOf(word))
        }
      }
    }

    val screenRects = mutableListOf<RectF>()
    val pdfRects = mutableListOf<RectF>()

    for (group in lineGroups) {
      if (group.isEmpty()) continue
      val sorted = group.sortedBy { it.bounds.left }
      val lineLeft = sorted.minOf { it.bounds.left }
      val lineRight = sorted.maxOf { it.bounds.right }
      val lineTop = sorted.minOf { it.bounds.top }
      val lineBottom = sorted.maxOf { it.bounds.bottom }

      val pRect = RectF(lineLeft, lineTop, lineRight, lineBottom)
      pdfRects.add(pRect)

      val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull() ?: pl.pageSize
      val pW = pSize.width
      val pH = pSize.height
      val l = pl.boundsOnScreen.left + (lineLeft / pW) * pl.boundsOnScreen.width()
      val t = pl.boundsOnScreen.top + (lineTop / pH) * pl.boundsOnScreen.height()
      val r = pl.boundsOnScreen.left + (lineRight / pW) * pl.boundsOnScreen.width()
      val b = pl.boundsOnScreen.top + (lineBottom / pH) * pl.boundsOnScreen.height()
      screenRects.add(RectF(l, t, r, b))
    }

    return Pair(screenRects, pdfRects)
  }

  private fun buildPdfSelectionForLayout(
    pl: PdfPageLayout,
    startIdx: Int,
    endIdx: Int
  ): NativePdfSelection? {
    val words = pageWordsCache[pl.pageIndex] ?: return null
    if (words.isEmpty()) return null
    val clampedStart = startIdx.coerceIn(0, words.size - 1)
    val clampedEnd = endIdx.coerceIn(clampedStart, words.size - 1)
    val selWords = words.subList(clampedStart, clampedEnd + 1)
    val combinedText = selWords.joinToString(" ") { it.text }

    val (rects, pRects) = clusterWordsIntoLineRects(selWords, pl)
    if (rects.isEmpty()) return null

    val firstR = rects.first()
    val lastR = rects.last()
    val startHandle = RectF(firstR.left - 14f * density, firstR.bottom - 4f * density, firstR.left + 14f * density, firstR.bottom + 22f * density)
    val endHandle = RectF(lastR.right - 14f * density, lastR.bottom - 4f * density, lastR.right + 14f * density, lastR.bottom + 22f * density)

    val charCount = combinedText.length
    val previewSnippet = if (combinedText.length > 22) combinedText.substring(0, 20) + "..." else combinedText
    val charCountText = "$charCount chars selected \"$previewSnippet\""

    val layout = computeSelectionCalloutLayout(rects)

    return NativePdfSelection(
      pageIndex = pl.pageIndex,
      text = combinedText,
      highlightRects = rects,
      pdfRects = pRects,
      startHandle = startHandle,
      endHandle = endHandle,
      calloutRect = layout.calloutRect,
      calloutExcerptBtn = layout.excerptBtn,
      calloutCopyBtn = layout.copyBtn,
      calloutHighlightBtn = layout.highlightBtn,
      calloutCloseBtn = layout.closeBtn,
      startWordIndex = clampedStart,
      endWordIndex = clampedEnd,
      calloutAddWordLeftBtn = layout.addWordLeftBtn,
      calloutAddWordRightBtn = layout.addWordRightBtn,
      calloutSelectAllBtn = layout.selectAllBtn,
      charCountText = "${combinedText.length} chars",
      calloutColorBtns = layout.colorBtns,
      calloutMoreBtn = layout.moreBtn,
      calloutTagsBtn = layout.tagsBtn,
      calloutSubCardRect = layout.subCardRect,
      calloutMainCardRect = layout.mainCardRect,
      calloutRainbowBtn = layout.rainbowBtn
    )
  }

  private fun syncPdfSelectionWithLayout(
    sel: NativePdfSelection,
    pl: PdfPageLayout
  ): NativePdfSelection {
    val words = pageWordsCache[pl.pageIndex]
    if (!words.isNullOrEmpty() && sel.startWordIndex >= 0 && sel.endWordIndex < words.size) {
      val built = buildPdfSelectionForLayout(pl, sel.startWordIndex, sel.endWordIndex)
      if (built != null) return built
    }

    // Fallback projection using sel.pdfRects directly
    val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull() ?: pl.pageSize
    val pW = pSize.width
    val pH = pSize.height
    val screenRects = sel.pdfRects.map { pRect ->
      val l = pl.boundsOnScreen.left + (pRect.left / pW) * pl.boundsOnScreen.width()
      val t = pl.boundsOnScreen.top + (pRect.top / pH) * pl.boundsOnScreen.height()
      val r = pl.boundsOnScreen.left + (pRect.right / pW) * pl.boundsOnScreen.width()
      val b = pl.boundsOnScreen.top + (pRect.bottom / pH) * pl.boundsOnScreen.height()
      RectF(l, t, r, b)
    }

    if (screenRects.isEmpty()) return sel

    val firstR = screenRects.first()
    val lastR = screenRects.last()
    val startHandle = RectF(firstR.left - 14f * density, firstR.bottom - 4f * density, firstR.left + 14f * density, firstR.bottom + 22f * density)
    val endHandle = RectF(lastR.right - 14f * density, lastR.bottom - 4f * density, lastR.right + 14f * density, lastR.bottom + 22f * density)

    val layout = computeSelectionCalloutLayout(screenRects, sel.calloutColorBtns.map { it.second })

    return sel.copy(
      highlightRects = screenRects,
      startHandle = startHandle,
      endHandle = endHandle,
      calloutRect = layout.calloutRect,
      calloutExcerptBtn = layout.excerptBtn,
      calloutCopyBtn = layout.copyBtn,
      calloutHighlightBtn = layout.highlightBtn,
      calloutCloseBtn = layout.closeBtn,
      calloutAddWordLeftBtn = layout.addWordLeftBtn,
      calloutAddWordRightBtn = layout.addWordRightBtn,
      calloutSelectAllBtn = layout.selectAllBtn,
      calloutColorBtns = layout.colorBtns,
      calloutMoreBtn = layout.moreBtn,
      calloutTagsBtn = layout.tagsBtn,
      calloutSubCardRect = layout.subCardRect,
      calloutMainCardRect = layout.mainCardRect,
      calloutRainbowBtn = layout.rainbowBtn
    )
  }

  private fun updatePdfSelectionByIndex(pl: PdfPageLayout, startIdx: Int, endIdx: Int) {
    val sel = buildPdfSelectionForLayout(pl, startIdx, endIdx) ?: return
    activePdfSelection = sel
    invalidate()
  }

  private fun updatePdfSelection(pl: PdfPageLayout, w1: TextWord, w2: TextWord) {
    val words = pageWordsCache[pl.pageIndex] ?: return
    val idx1 = words.indexOf(w1)
    val idx2 = words.indexOf(w2)
    if (idx1 == -1 || idx2 == -1) return
    val startIdx = min(idx1, idx2)
    val endIdx = max(idx1, idx2)
    updatePdfSelectionByIndex(pl, startIdx, endIdx)
  }

  // ---------------------------------------------------------------------------
  // Excerpt & Annotation Helpers with Collision-Free Drop & Toast Feedback
  // ---------------------------------------------------------------------------
  private fun extractExcerptToCanvas(text: String, pageNumber: Int, color: Int, pdfRects: List<RectF> = emptyList()) {
    val newId = "card-${System.currentTimeMillis()}"
    val (dropWx, dropWy) = canvasScreenToWorld(width / 2f, height * splitRatio + 80f, height * splitRatio + 14f)

    val cardW = 220f
    val cardH = if (text.length > 70) 130f else 105f
    val resolved = CollisionPlacementSolver.findNonOverlappingPosition(
      desiredX = dropWx - cardW / 2f,
      desiredY = dropWy - cardH / 2f,
      cardWidth = cardW,
      cardHeight = cardH,
      existingCards = cards
    )

    val card = NativeCard(
      id = newId,
      x = resolved.x,
      y = resolved.y,
      width = cardW,
      text = text,
      color = color,
      pageNumber = pageNumber,
      comment = null,
      clusterId = null,
      stackCount = 1,
      isImage = false,
      imageUrl = null,
      isTable = false,
      tableRows = null,
      groupedItems = null,
      sourceRects = pdfRects,
      documentId = activeDocumentId
    )
    cards.add(card)

    val newLink = NativeLink("link-${System.currentTimeMillis()}", newId, color)
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
    triggerShockwave(resolved.x + cardW / 2f, resolved.y + cardH / 2f, color)
    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    dispatchExtractExcerptEvent(
      text = text,
      pageNumber = pageNumber,
      color = color,
      isImg = false,
      x = resolved.x,
      y = resolved.y,
      cardId = newId,
      sourceRects = pdfRects
    )
    hudToast.show("Excerpt placed without overlap with live Ink-Link!")
    invalidate()
  }

  private fun extractCropToCanvas(cropSel: NativeCropSelection) {
    val newId = "card-${System.currentTimeMillis()}"
    val (dropWx, dropWy) = canvasScreenToWorld(width / 2f, height * splitRatio + 80f, height * splitRatio + 14f)

    val bmp = generateCropBitmap(cropSel.pageIndex, cropSel.pageBounds)
    val imgPath = if (bmp != null) {
      val p = saveCropToFile(bmp)
      cardBitmapCache.put(p, bmp)
      cardBitmapCache.put(newId, bmp)
      cardBitmapCache.put("page_${cropSel.pageIndex + 1}_image", bmp)
      p
    } else null

    val cardW = 240f
    val cardH = 160f
    val resolved = CollisionPlacementSolver.findNonOverlappingPosition(
      desiredX = dropWx - cardW / 2f,
      desiredY = dropWy - cardH / 2f,
      cardWidth = cardW,
      cardHeight = cardH,
      existingCards = cards
    )

    val cropSourceRects = listOf(RectF(cropSel.pageBounds.left, cropSel.pageBounds.top, cropSel.pageBounds.right, cropSel.pageBounds.bottom))
    val card = NativeCard(
      id = newId,
      x = resolved.x,
      y = resolved.y,
      width = cardW,
      text = "[Photo Excerpt]",
      color = cropSel.color,
      pageNumber = cropSel.pageIndex + 1,
      comment = null,
      clusterId = null,
      stackCount = 1,
      isImage = true,
      imageUrl = imgPath,
      isTable = false,
      tableRows = null,
      groupedItems = null,
      sourceRects = cropSourceRects,
      documentId = activeDocumentId
    )
    cards.add(card)

    // Reset docMode and dismiss crop selector so user can scroll immediately
    activeCropSelection = null
    docMode = "text"

    val newLink = NativeLink("link-${System.currentTimeMillis()}", newId, cropSel.color)
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
    triggerShockwave(resolved.x + cardW / 2f, resolved.y + cardH / 2f, cropSel.color)
    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    dispatchExtractExcerptEvent(
      text = card.text,
      pageNumber = card.pageNumber,
      color = card.color,
      isImg = card.isImage,
      imageUrl = card.imageUrl,
      x = card.x,
      y = card.y,
      cardId = card.id,
      sourceRects = cropSourceRects
    )
    hudToast.show("Extracted figure placed neatly with live Ink-Link!")
    invalidate()
  }

  private fun tidyCards() {
    if (cards.isEmpty()) {
      hudToast.show("No cards to tidy")
      invalidate()
      return
    }
    var curX = 60f
    var curY = 40f
    for (c in cards) {
      c.x = curX
      c.y = curY
      curX += c.width + 24f
      if (curX > 600f) {
        curX = 60f
        curY += c.getHeight() + 24f
      }
    }
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    hudToast.show("✨ Workspace tidied neatly!")
    invalidate()
  }

  private fun addAnnotation(text: String, pageNumber: Int, color: Int, rects: List<RectF> = emptyList()) {
    val annId = "ann-${System.currentTimeMillis()}"
    val ann = NativeAnnotation(annId, "page-$pageNumber", 0, pageNumber, color, text, rects)
    annotations.add(ann)
    undoRedoManager.record(AddAnnotationAction(
      annotation = ann,
      annotationsList = annotations,
      onUndoDispatched = { invalidate() },
      onRedoDispatched = { invalidate() }
    ))
    invalidate()
  }

  private fun eraseAnnotationNear(sx: Float, sy: Float): Boolean {
    val threshold = 28f * density
    val targetAnn = annotations.findLast { ann ->
      val pl = pageLayouts.find { it.pageNumber == ann.pageNumber }
      if (pl != null && !pl.isFolded) {
        val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
          ?: com.thinkspace.pdfengine.model.PageSize.LETTER
        val pW = pSize.width
        val pH = pSize.height
        ann.rects.any { r ->
          val l = pl.boundsOnScreen.left + (r.left / pW) * pl.boundsOnScreen.width()
          val t = pl.boundsOnScreen.top + (r.top / pH) * pl.boundsOnScreen.height()
          val right = pl.boundsOnScreen.left + (r.right / pW) * pl.boundsOnScreen.width()
          val b = pl.boundsOnScreen.top + (r.bottom / pH) * pl.boundsOnScreen.height()
          val hRect = RectF(l - threshold, t - threshold, right + threshold, b + threshold)
          hRect.contains(sx, sy)
        }
      } else false
    }

    if (targetAnn != null) {
      annotations.remove(targetAnn)
      undoRedoManager.record(DeleteAnnotationAction(
        annotation = targetAnn,
        annotationsList = annotations,
        onUndoDispatched = { invalidate() },
        onRedoDispatched = { invalidate() }
      ))
      hudToast.show("🗑️ Highlight erased")
      performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
      invalidate()
      return true
    }
    return false
  }

  private fun copyToClipboard(text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Excerpt", text)
    clipboard?.setPrimaryClip(clip)
    copiedToastText = "✓ Copied!"
    postDelayed({
      copiedToastText = null
      invalidate()
    }, 1500)
    invalidate()
  }

  private fun eraseStrokesNear(wx: Float, wy: Float) {
    val threshold = 28f
    val toRemove = strokes.filter { s ->
      s.points.any { hypot(it.x - wx, it.y - wy) <= threshold }
    }
    if (toRemove.isNotEmpty()) {
      strokes.removeAll(toRemove)
      undoRedoManager.record(EraseStrokesAction(
        erasedStrokes = toRemove,
        strokesList = strokes,
        onUndoDispatched = { restored ->
          for (s in restored) {
            dispatchAddStrokeEvent(s)
          }
          invalidate()
        },
        onRedoDispatched = { removed ->
          for (s in removed) {
            dispatchEraseStrokeEvent(s.id)
          }
          invalidate()
        }
      ))
      for (s in toRemove) {
        dispatchEraseStrokeEvent(s.id)
      }
      invalidate()
    }

    val curCanvasTopY = if ((activePdfDoc != null || activeDocument != null) && effectiveSplitRatio > 0f) height * effectiveSplitRatio + 14f else 0f
    val erasedLinks = semanticInkLinks.filter { link ->
      val card = cards.find { it.id == link.targetCardId }
      if (card == null) false
      else {
        val srcWorldX: Float
        val srcWorldY: Float
        val pl = pageLayouts.find { it.pageIndex == link.sourcePageIndex }
        if (pl != null && link.sourceDocId == activeDocumentId) {
          val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
            ?: com.thinkspace.pdfengine.model.PageSize.LETTER
          val rawSx = pl.boundsOnScreen.left + (link.sourcePdfPoint.x / pSize.width) * pl.boundsOnScreen.width()
          val rawSy = pl.boundsOnScreen.top + (link.sourcePdfPoint.y / pSize.height) * pl.boundsOnScreen.height()
          srcWorldX = camera.screenToWorldX(rawSx)
          srcWorldY = camera.screenToWorldY(rawSy, curCanvasTopY)
        } else {
          srcWorldX = card.x
          srcWorldY = card.y - 120f
        }
        val edge = getCardEdgeAnchor(card, srcWorldX, srcWorldY)
        val d = distToSegment(wx, wy, srcWorldX, srcWorldY, edge.x, edge.y)
        d <= threshold || hypot(edge.x - wx, edge.y - wy) <= threshold * 1.4f
      }
    }
    if (erasedLinks.isNotEmpty()) {
      semanticInkLinks.removeAll(erasedLinks)
      for (el in erasedLinks) {
        undoRedoManager.record(DeleteSemanticInkLinkAction(
          link = el,
          linksList = semanticInkLinks,
          onUndoDispatched = { l ->
            dispatchInkLinkCreateEvent(l)
            persistSemanticInkLinksLocally()
            invalidate()
          },
          onRedoDispatched = { l ->
            dispatchInkLinkDeleteEvent(l.id)
            persistSemanticInkLinksLocally()
            invalidate()
          }
        ))
        dispatchInkLinkDeleteEvent(el.id)
      }
      persistSemanticInkLinksLocally()
      invalidate()
    }
  }

  private fun eraseDocStrokesNear(sx: Float, sy: Float): Boolean {
    var erasedAny = false
    for (pl in pageLayouts) {
      if (!pl.boundsOnScreen.contains(sx, sy)) continue
      val pSize = runCatching { activePdfDoc?.getPage(pl.pageIndex)?.size }.getOrNull()
        ?: com.thinkspace.pdfengine.model.PageSize.LETTER
      val pW = pSize.width
      val pH = pSize.height
      val px = ((sx - pl.boundsOnScreen.left) / pl.boundsOnScreen.width()) * pW
      val py = ((sy - pl.boundsOnScreen.top) / pl.boundsOnScreen.height()) * pH

      val list = pageStrokes[pl.pageIndex] ?: continue
      val threshold = 28f * (pW / pl.boundsOnScreen.width().coerceAtLeast(1f))
      val toRemove = list.filter { s ->
        s.points.any { hypot(it.x - px, it.y - py) <= threshold }
      }
      if (toRemove.isNotEmpty()) {
        list.removeAll(toRemove)
        undoRedoManager.record(ErasePageStrokesAction(
          erasedStrokes = toRemove,
          pageStrokesMap = pageStrokes,
          onUndoDispatched = { invalidate() },
          onRedoDispatched = { invalidate() }
        ))
        erasedAny = true
        invalidate()
      }

      val erasedDocLinks = semanticInkLinks.filter { link ->
        link.sourceDocId == activeDocumentId &&
        link.sourcePageIndex == pl.pageIndex &&
        hypot(link.sourcePdfPoint.x - px, link.sourcePdfPoint.y - py) <= threshold
      }
      if (erasedDocLinks.isNotEmpty()) {
        semanticInkLinks.removeAll(erasedDocLinks)
        for (el in erasedDocLinks) {
          undoRedoManager.record(DeleteSemanticInkLinkAction(
            link = el,
            linksList = semanticInkLinks,
            onUndoDispatched = { l ->
              dispatchInkLinkCreateEvent(l)
              persistSemanticInkLinksLocally()
              invalidate()
            },
            onRedoDispatched = { l ->
              dispatchInkLinkDeleteEvent(l.id)
              persistSemanticInkLinksLocally()
              invalidate()
            }
          ))
          dispatchInkLinkDeleteEvent(el.id)
        }
        persistSemanticInkLinksLocally()
        erasedAny = true
        invalidate()
      }
    }
    return erasedAny
  }

  private var docScrollAnimator: ValueAnimator? = null
  private var pulseAnimator: ValueAnimator? = null
  private var canvasAnimator: ValueAnimator? = null

  private fun scrollToDocumentPage(targetPageNum: Int, sourceRects: List<RectF> = emptyList()) {
    val viewW = width.toFloat().coerceAtLeast(100f)
    val viewH = height.toFloat().coerceAtLeast(100f)
    val docBottomY = viewH * splitRatio
    val viewportH = max(100f, docBottomY - subheaderH)

    val targetScrollY: Float
    val safePageNum: Int

    if (activePdfDoc != null) {
      val pCount = activePdfDoc?.pageCount ?: 1
      safePageNum = targetPageNum.coerceIn(1, max(1, pCount))
      val pageIdx = safePageNum - 1

      val paperMargin = 10f * density
      val basePaperW = viewW - paperMargin * 2f
      val paperW = basePaperW * pdfScaleFactor
      val pSize = runCatching { activePdfDoc?.getPage(pageIdx)?.size }.getOrNull() ?: com.thinkspace.pdfengine.model.PageSize.LETTER
      val docAspectRatio = if (pSize.width > 0f) pSize.height / pSize.width else 1.294f
      val standardPageH = paperW * docAspectRatio
      val standardGap = 16f * pdfScaleFactor
      val totalDocH = compressionEngine.getTotalDocHeight(standardPageH, standardGap)
      val maxScroll = max(0f, totalDocH - (docBottomY - subheaderH) + 60f)

      val pageTopDocY = compressionEngine.getPageTopDocY(pageIdx, standardPageH, standardGap)
      val displayedH = compressionEngine.getDisplayedPageHeight(pageIdx, standardPageH)

      targetScrollY = if (sourceRects.isNotEmpty()) {
        val minY = sourceRects.minOf { it.top }
        val maxY = sourceRects.maxOf { it.bottom }
        val centerPdfY = (minY + maxY) / 2f
        val pdfH = pSize.height
        val scale = if (pdfH > 0f) displayedH / pdfH else 1f
        val sourceOffset = centerPdfY * scale
        val sourceDocY = pageTopDocY + sourceOffset
        (sourceDocY - viewportH / 2f + 16f).coerceIn(0f, maxScroll)
      } else {
        pageTopDocY.coerceIn(0f, maxScroll)
      }
    } else {
      // Structured document mode
      safePageNum = targetPageNum
      val targetP = paragraphLayouts.find { it.pageNumber == targetPageNum }
      targetScrollY = if (targetP != null) {
        (docScrollY + targetP.topY - subheaderH - 16f).coerceIn(0f, maxDocScrollY)
      } else {
        0f
      }
    }

    docScrollAnimator?.cancel()
    val animator = ValueAnimator.ofFloat(docScrollY, targetScrollY).apply {
      duration = 420
      interpolator = DecelerateInterpolator()
      addUpdateListener {
        docScrollY = it.animatedValue as Float
        invalidate()
      }
      start()
    }
    docScrollAnimator = animator

    pulsePageNumber = safePageNum
    pulseSourceRects = sourceRects
    pulseAlpha = 240

    pulseAnimator?.cancel()
    val pulseAnim = ValueAnimator.ofInt(240, 0).apply {
      duration = 1400
      addUpdateListener {
        pulseAlpha = it.animatedValue as Int
        invalidate()
      }
      start()
    }
    pulseAnimator = pulseAnim

    // Center horizontally if zoomed in
    if (activePdfDoc != null && sourceRects.isNotEmpty() && maxDocScrollX > 0f) {
      val minX = sourceRects.minOf { it.left }
      val maxX = sourceRects.maxOf { it.right }
      val centerPdfX = (minX + maxX) / 2f
      val pSize = runCatching { activePdfDoc?.getPage(safePageNum - 1)?.size }.getOrNull() ?: com.thinkspace.pdfengine.model.PageSize.LETTER
      val pdfW = pSize.width
      val paperMargin = 10f * density
      val basePaperW = viewW - paperMargin * 2f
      val paperW = basePaperW * pdfScaleFactor
      val scaleX = if (pdfW > 0f) paperW / pdfW else 1f
      val sourceX = centerPdfX * scaleX
      docScrollX = (sourceX - basePaperW / 2f).coerceIn(0f, maxDocScrollX)
    }

    invalidate()
  }

  // ---------------------------------------------------------------------------
  // Native PDF Engine Search & Navigation
  // ---------------------------------------------------------------------------

  fun closeSearch() {
    // Cancel any running search coroutine
    searchJob?.cancel()
    searchJob = null
    isSearchActive = false
    isSearching = false
    searchMatches.clear()
    currentSearchIndex = 0
    compressionEngine.resetAllToNormal(animate = true) { invalidate() }
    // Remove native overlay panel if visible
    removeSearchOverlay()
    invalidate()
  }

  fun triggerSearchCollapse() {
    if (activePdfDoc == null || searchMatches.isEmpty()) return
    val matchingPages = searchMatches.map { it.pageIndex }.toSet()
    compressionEngine.applySearchMatches(matchingPages, animate = true) {
      invalidate()
    }
  }

  private fun removeSearchOverlay() {
    val ov = searchOverlayView ?: return
    (ov.parent as? android.view.ViewGroup)?.removeView(ov)
    // Dismiss keyboard
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    imm?.hideSoftInputFromWindow(windowToken, 0)
    searchOverlayView = null
    searchCounterLabel = null
  }

  fun goToNextMatch() {
    if (searchMatches.isEmpty()) return
    currentSearchIndex = (currentSearchIndex + 1) % searchMatches.size
    scrollToCurrentMatch()
    updateSearchCounter()
    invalidate()
  }

  fun goToPreviousMatch() {
    if (searchMatches.isEmpty()) return
    currentSearchIndex = (currentSearchIndex - 1 + searchMatches.size) % searchMatches.size
    scrollToCurrentMatch()
    updateSearchCounter()
    invalidate()
  }

  private fun scrollToCurrentMatch() {
    val match = searchMatches.getOrNull(currentSearchIndex) ?: return
    scrollToDocumentPage(match.pageIndex + 1, match.rects)
  }

  fun performSearch(query: String) {
    val q = query.trim()
    if (q.isEmpty()) {
      closeSearch()
      return
    }
    // Cancel previous search immediately
    searchJob?.cancel()
    searchJob = null

    currentSearchQuery = q
    isSearchActive = true
    isSearching = true
    searchMatches.clear()
    currentSearchIndex = 0
    invalidate()

    // Update counter label while searching
    updateSearchCounter()

    searchJob = renderScope.launch {
      val pdf = activePdfDoc
      if (pdf != null) {
        // ── Incremental path: results stream in page-by-page ──────────────────
        val engine = PdfEngineModule.getOrCreateEngine(context)
        val docEngine = engine as? com.thinkspace.pdfengine.core.DefaultPdfDocumentEngine

        if (docEngine != null) {
          var navigatedToFirst = false
          try {
            docEngine.searchIncremental(
              document = pdf,
              query = q,
              onMatchFound = { pageResults, _ ->
                val newMatches = pageResults.map { sr ->
                  val rects = if (sr.quads.isNotEmpty()) {
                    sr.quads.map { qd ->
                      RectF(
                        minOf(qd.topLeft.x, qd.bottomLeft.x),
                        minOf(qd.topLeft.y, qd.topRight.y),
                        maxOf(qd.topRight.x, qd.bottomRight.x),
                        maxOf(qd.bottomLeft.y, qd.bottomRight.y)
                      )
                    }
                  } else {
                    listOf(RectF(sr.bounds.left, sr.bounds.top, sr.bounds.right, sr.bounds.bottom))
                  }
                  NativeSearchMatch(
                    pageIndex = sr.pageIndex,
                    matchedText = sr.matchedText,
                    rects = rects,
                    contextSnippet = sr.context
                  )
                }

                withContext(Dispatchers.Main) {
                  // Maintain sorted order as matches arrive
                  searchMatches.addAll(newMatches)
                  searchMatches.sortWith(compareBy({ it.pageIndex }, { it.rects.firstOrNull()?.top ?: 0f }, { it.rects.firstOrNull()?.left ?: 0f }))

                  // Navigate to the FIRST match immediately (only once)
                  if (!navigatedToFirst && searchMatches.isNotEmpty()) {
                    navigatedToFirst = true
                    currentSearchIndex = 0
                    scrollToCurrentMatch()
                  } else if (navigatedToFirst) {
                    // Keep currentSearchIndex pointing at the same logical match
                    // (its position in the sorted list may have shifted if earlier-page results arrived late)
                    // Nothing to do: index is still valid, new matches are appended after
                  }
                  updateSearchCounter()
                  invalidate()
                }
                true // continue searching
              }
            )
          } catch (e: kotlinx.coroutines.CancellationException) {
            // Normal — new query cancelled this job
            return@launch
          } catch (e: Exception) {
            e.printStackTrace()
          }

          withContext(Dispatchers.Main) {
            isSearching = false
            updateSearchCounter()
            if (searchMatches.isEmpty()) {
              hudToast.show("No matches for \"$q\"")
            } else {
              val matchingPages = searchMatches.map { it.pageIndex }.toSet()
              compressionEngine.applySearchMatches(matchingPages, animate = true) {
                invalidate()
              }
            }
            invalidate()
          }
        } else {
          // Fallback: old blocking search via engine interface
          try {
            val searchResults = engine.search(pdf, q)
            val results = searchResults.map { sr ->
              val rects = if (sr.quads.isNotEmpty()) {
                sr.quads.map { qd ->
                  RectF(
                    minOf(qd.topLeft.x, qd.bottomLeft.x),
                    minOf(qd.topLeft.y, qd.topRight.y),
                    maxOf(qd.topRight.x, qd.bottomRight.x),
                    maxOf(qd.bottomLeft.y, qd.bottomRight.y)
                  )
                }
              } else {
                listOf(RectF(sr.bounds.left, sr.bounds.top, sr.bounds.right, sr.bounds.bottom))
              }
              NativeSearchMatch(
                pageIndex = sr.pageIndex,
                matchedText = sr.matchedText,
                rects = rects,
                contextSnippet = sr.context
              )
            }
            withContext(Dispatchers.Main) {
              isSearching = false
              searchMatches.clear()
              searchMatches.addAll(results)
              currentSearchIndex = 0
              updateSearchCounter()
              if (searchMatches.isNotEmpty()) {
                val matchingPages = searchMatches.map { it.pageIndex }.toSet()
                compressionEngine.applySearchMatches(matchingPages, animate = true) {
                  invalidate()
                }
                scrollToCurrentMatch()
              } else {
                hudToast.show("No matches for \"$q\"")
              }
              invalidate()
            }
          } catch (e: Exception) {
            e.printStackTrace()
          }
        }
      } else if (activeDocument != null) {
        // Structured document (NativeDoc) search — keep original behavior
        val doc = activeDocument!!
        val results = mutableListOf<NativeSearchMatch>()
        for (sec in doc.sections) {
          for ((pIdx, pText) in sec.paragraphs.withIndex()) {
            var idx = 0
            val lowerP = pText.lowercase()
            val lowerQ = q.lowercase()
            while (idx < lowerP.length) {
              val found = lowerP.indexOf(lowerQ, idx)
              if (found == -1) break
              val pLayout = paragraphLayouts.find { it.secId == sec.id && it.pIdx == pIdx }
              val rects = if (pLayout != null && pLayout.layout.lineCount > 0) {
                val line = pLayout.layout.getLineForOffset(found)
                val lineTop = pLayout.topY + pLayout.layout.getLineTop(line).toFloat()
                val lineBottom = pLayout.topY + pLayout.layout.getLineBottom(line).toFloat()
                val startX = pLayout.paperX + pLayout.layout.getPrimaryHorizontal(found)
                val endX = pLayout.paperX + pLayout.layout.getPrimaryHorizontal(minOf(found + q.length, pText.length))
                listOf(RectF(minOf(startX, endX), lineTop, maxOf(startX, endX), lineBottom))
              } else {
                listOf(RectF(20f, 20f, 200f, 40f))
              }
              results.add(
                NativeSearchMatch(
                  pageIndex = sec.pageNumber - 1,
                  matchedText = pText.substring(found, minOf(found + q.length, pText.length)),
                  rects = rects,
                  contextSnippet = pText
                )
              )
              idx = found + maxOf(1, lowerQ.length)
            }
          }
        }
        results.sortWith(compareBy({ it.pageIndex }, { it.rects.firstOrNull()?.top ?: 0f }, { it.rects.firstOrNull()?.left ?: 0f }))
        withContext(Dispatchers.Main) {
          isSearching = false
          searchMatches.clear()
          searchMatches.addAll(results)
          currentSearchIndex = 0
          updateSearchCounter()
          if (searchMatches.isNotEmpty()) {
            scrollToCurrentMatch()
          } else {
            hudToast.show("No matches for \"$q\"")
          }
          invalidate()
        }
      }
    }
  }

  /**
   * Opens the native search panel — a real Android overlay ViewGroup containing
   * an EditText (with real keyboard) placed at the top of the document area.
   * The existing canvas-drawn HUD (‹ Prev | X / N | Next › | ✕) handles navigation.
   * This replaces the old AlertDialog approach.
   */
  fun promptSearchDialog() {
    // If panel already open, just focus the input
    val existing = searchOverlayView
    if (existing != null) {
      existing.findViewWithTag<EditText>("search_input")?.requestFocus()
      return
    }

    // Find activity root to attach to
    val activity = generateSequence(context) {
      (it as? android.content.ContextWrapper)?.baseContext
    }.filterIsInstance<Activity>().firstOrNull()

    val rootView = activity?.window?.decorView
      ?.findViewById<android.widget.FrameLayout>(android.R.id.content)
      ?: run { showFallbackDialog(); return }

    val d = density

    // ── Panel container ────────────────────────────────────────────────────
    val panel = android.widget.LinearLayout(context).apply {
      orientation = android.widget.LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_VERTICAL
      setBackgroundColor(Color.parseColor("#0F172A"))
      elevation = 20f * d
      // Round bottom corners only (top is flush with subheader)
      outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
          outline.setRoundRect(0, 0, view.width, view.height, 12f * d)
        }
      }
      clipToOutline = true
      setPadding((10f * d).toInt(), (6f * d).toInt(), (6f * d).toInt(), (6f * d).toInt())
    }

    // Lens icon
    val lensLabel = android.widget.TextView(context).apply {
      text = "🔍"
      textSize = 15f
      setPadding(0, 0, (6f * d).toInt(), 0)
    }

    // Text input
    val input = EditText(context).apply {
      tag = "search_input"
      hint = "Search in document…"
      setText(currentSearchQuery)
      setSelection(text.length)
      setSingleLine(true)
      setTextColor(Color.WHITE)
      setHintTextColor(Color.parseColor("#64748B"))
      background = null
      textSize = 14f
      imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
      inputType = android.text.InputType.TYPE_CLASS_TEXT
      layoutParams = android.widget.LinearLayout.LayoutParams(
        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
      )
    }

    // Counter label (e.g. "3 / 12" or "Searching…")
    val counter = android.widget.TextView(context).apply {
      text = if (isSearching) "Searching…" else if (searchMatches.isNotEmpty()) "${currentSearchIndex + 1} / ${searchMatches.size}" else ""
      setTextColor(Color.parseColor("#00ADB5"))
      textSize = 12f
      android.text.TextUtils.TruncateAt.END
      setPadding((6f * d).toInt(), 0, (6f * d).toInt(), 0)
    }
    searchCounterLabel = counter

    // Previous button [ ‹ ]
    val prevBtn = android.widget.TextView(context).apply {
      text = "‹"
      textSize = 18f
      setTextColor(Color.parseColor("#00ADB5"))
      gravity = android.view.Gravity.CENTER
      setBackgroundColor(Color.parseColor("#1E293B"))
      val btnPadH = (8f * d).toInt()
      val btnPadV = (2f * d).toInt()
      setPadding(btnPadH, btnPadV, btnPadH, btnPadV)
      val marginParams = android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        leftMargin = (2f * d).toInt()
        rightMargin = (2f * d).toInt()
      }
      layoutParams = marginParams
      setOnClickListener { goToPreviousMatch() }
    }

    // Next button [ › ]
    val nextBtn = android.widget.TextView(context).apply {
      text = "›"
      textSize = 18f
      setTextColor(Color.parseColor("#00ADB5"))
      gravity = android.view.Gravity.CENTER
      setBackgroundColor(Color.parseColor("#1E293B"))
      val btnPadH = (8f * d).toInt()
      val btnPadV = (2f * d).toInt()
      setPadding(btnPadH, btnPadV, btnPadH, btnPadV)
      val marginParams = android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        leftMargin = (2f * d).toInt()
        rightMargin = (4f * d).toInt()
      }
      layoutParams = marginParams
      setOnClickListener { goToNextMatch() }
    }

    // Close button
    val closeBtn = android.widget.TextView(context).apply {
      text = "✕"
      textSize = 14f
      setTextColor(Color.parseColor("#94A3B8"))
      setPadding((8f * d).toInt(), (6f * d).toInt(), (8f * d).toInt(), (6f * d).toInt())
      setOnClickListener { closeSearch() }
    }

    panel.addView(lensLabel)
    panel.addView(input)
    panel.addView(prevBtn)
    panel.addView(counter)
    panel.addView(nextBtn)
    panel.addView(closeBtn)

    // ── Position the panel at the top of the document area ─────────────────
    val viewLocation = IntArray(2)
    getLocationInWindow(viewLocation)
    val rootLocation = IntArray(2)
    rootView.getLocationInWindow(rootLocation)

    val panelTop = viewLocation[1] - rootLocation[1] + (4f * d).toInt()
    val panelLeft = viewLocation[0] - rootLocation[0] + (10f * d).toInt()
    val panelWidth = width - (20f * d).toInt()
    val panelHeight = (44f * d).toInt()

    val params = android.widget.FrameLayout.LayoutParams(panelWidth, panelHeight).apply {
      topMargin = panelTop
      leftMargin = panelLeft
    }
    rootView.addView(panel, params)
    searchOverlayView = panel

    // ── Wire up real-time search as user types ─────────────────────────────
    input.addTextChangedListener(object : android.text.TextWatcher {
      private var debounceJob: kotlinx.coroutines.Job? = null
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun afterTextChanged(s: android.text.Editable?) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        val q = s?.toString()?.trim() ?: ""
        debounceJob?.cancel()
        if (q.isEmpty()) {
          searchJob?.cancel()
          searchJob = null
          isSearchActive = false
          isSearching = false
          searchMatches.clear()
          currentSearchIndex = 0
          currentSearchQuery = ""
          updateSearchCounter()
          invalidate()
          return
        }
        // Debounce 250 ms so we don't search every keystroke
        debounceJob = renderScope.launch {
          kotlinx.coroutines.delay(250)
          performSearch(q)
        }
      }
    })

    input.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
        val q = input.text.toString().trim()
        if (q.isNotEmpty()) performSearch(q)
        true
      } else false
    }

    // Show keyboard
    input.requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

    // If we already have results (re-opening), show them immediately
    if (searchMatches.isNotEmpty()) {
      isSearchActive = true
      invalidate()
    }
  }

  /** Updates the counter label in the search overlay panel (e.g. "3 / 12"). */
  private fun updateSearchCounter() {
    val lbl = searchCounterLabel ?: return
    lbl.post {
      lbl.text = when {
        isSearching && searchMatches.isEmpty() -> "Searching…"
        isSearching -> "${currentSearchIndex + 1} / ${searchMatches.size}+"
        searchMatches.isEmpty() && currentSearchQuery.isNotEmpty() -> "No results"
        searchMatches.isEmpty() -> ""
        else -> "${currentSearchIndex + 1} / ${searchMatches.size}"
      }
    }
  }

  /** Fallback for when we can't find the activity root view. */
  private fun showFallbackDialog() {
    val act = (context as? Activity)
      ?: ((context as? android.content.ContextWrapper)?.baseContext as? Activity)
    val ctx = act ?: context
    val input = EditText(ctx).apply {
      hint = "Search in document..."
      setText(currentSearchQuery)
      selectAll()
      setSingleLine(true)
      setTextColor(Color.WHITE)
      setHintTextColor(Color.parseColor("#64748B"))
      setBackgroundColor(Color.parseColor("#1E293B"))
      setPadding((16f * density).toInt(), (12f * density).toInt(), (16f * density).toInt(), (12f * density).toInt())
      imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    }
    val container = android.widget.FrameLayout(ctx).apply {
      setPadding((18f * density).toInt(), (10f * density).toInt(), (18f * density).toInt(), (6f * density).toInt())
      addView(input)
    }
    val dialog = android.app.AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle("Search Document")
      .setView(container)
      .setPositiveButton("Search") { _, _ ->
        val q = input.text.toString().trim()
        if (q.isNotEmpty()) performSearch(q)
      }
      .setNegativeButton("Cancel", null)
      .create()
    input.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
        val q = input.text.toString().trim()
        if (q.isNotEmpty()) performSearch(q)
        dialog.dismiss()
        true
      } else false
    }
    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
    input.requestFocus()
  }

  // ---------------------------------------------------------------------------
  // React Native Fabric Event Dispatchers
  // ---------------------------------------------------------------------------
  // ---------------------------------------------------------------------------
  // Notebook Page \u2014 Rendering
  // ---------------------------------------------------------------------------
  /**
   * Draws all notebook pages on the canvas (called inside the world-transform save/restore block).
   * Pages are drawn in list order (first = bottom), with the selected page always highlighted.
   */
  private fun drawNotebookPages(canvas: Canvas) {
    nbPageDeleteRects.clear()
    nbPageStyleBtnRects.clear()
    nbPageResizeRects.clear()

    for (page in notebookPages) {
      val isSelected = page.id == selectedNotebookPageId
      val r = RectF(page.x, page.y, page.x + page.width, page.y + page.height)

      // 1. Drop shadow
      val shadowR = RectF(r.left + 5f, r.top + 5f, r.right + 5f, r.bottom + 5f)
      canvas.drawRoundRect(shadowR, 10f, 10f, nbPageShadowPaint)

      // 2. Paper background
      val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.backgroundColor; style = Paint.Style.FILL
      }
      canvas.drawRoundRect(r, 10f, 10f, bgPaint)

      // 3. Paper pattern (clipped inside page bounds)
      canvas.save()
      canvas.clipRect(r)
      drawNotebookPagePattern(canvas, page, r)
      canvas.restore()

      // 4. Title (only when there is one and page is large enough)
      if (page.title.isNotEmpty() && page.height > 60f) {
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#374151")
          textSize = (page.height * 0.055f).coerceIn(14f, 22f)
          typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        canvas.drawText(page.title, r.left + 18f, r.top + 28f, titlePaint)
      }

      // 5. Page border
      canvas.drawRoundRect(r, 10f, 10f,
        if (isSelected) nbPageSelectedBorderPaint else nbPageNormalBorderPaint)

      // 6. Toolbar + controls when selected
      if (isSelected) {
        val tbH = 28f
        val tbW = page.width.coerceAtMost(260f)
        val tbLeft = r.left + (page.width - tbW) / 2f
        val tbTop = r.top - tbH - 6f
        val tbRect = RectF(tbLeft, tbTop, tbLeft + tbW, tbTop + tbH)

        // Toolbar shadow
        val toolShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#30000000"); style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(tbLeft, tbTop + 2f, tbLeft + tbW, tbTop + tbH + 2f),
          12f, 12f, toolShadow)
        // Toolbar bg + border
        canvas.drawRoundRect(tbRect, 12f, 12f, nbPageToolbarBgPaint)
        canvas.drawRoundRect(tbRect, 12f, 12f, nbPageToolbarBorderPaint)

        val iconPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#CBD5E1"); textSize = 11.5f
        }

        val btnW = tbW / 4f
        val midY = tbRect.centerY() + 4f

        // [✥ Move] btn (visual header drag indicator)
        val moveRect = RectF(tbLeft, tbTop, tbLeft + btnW, tbTop + tbH)
        val moveLabel = "✥ Move"
        canvas.drawText(moveLabel, moveRect.centerX() - iconPaint.measureText(moveLabel) / 2f, midY, iconPaint)

        // [≡ Style] btn
        val styleRect = RectF(tbLeft + btnW, tbTop, tbLeft + btnW * 2f, tbTop + tbH)
        nbPageStyleBtnRects[page.id] = styleRect
        val styleLabel = when (page.pageStyle) {
          "blank" -> "Blank"
          "ruled" -> "Ruled"
          "grid"  -> "Grid"
          "dotted"-> "Dots"
          "sketch"-> "Sketch"
          "cornell"-> "Cornell"
          "squared"-> "Squared"
          else    -> "Custom"
        }
        val styleFull = "≡ $styleLabel"
        canvas.drawText(styleFull, styleRect.centerX() - iconPaint.measureText(styleFull) / 2f, midY, iconPaint)

        // [⧉ Copy] btn
        val dupRect = RectF(tbLeft + btnW * 2f, tbTop, tbLeft + btnW * 3f, tbTop + tbH)
        nbPageDuplicateRects[page.id] = dupRect
        val dupLabel = "⧉ Copy"
        canvas.drawText(dupLabel, dupRect.centerX() - iconPaint.measureText(dupLabel) / 2f, midY, iconPaint)

        // [🗑 Delete] btn
        val deleteRect = RectF(tbLeft + btnW * 3f, tbTop, tbLeft + tbW, tbTop + tbH)
        nbPageDeleteRects[page.id] = deleteRect
        val delPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#FCA5A5"); textSize = 11.5f
        }
        val delLabel = "🗑 Delete"
        canvas.drawText(delLabel, deleteRect.centerX() - delPaint.measureText(delLabel) / 2f, midY, delPaint)

        // Dividers
        val divPaint = Paint().apply { color = Color.parseColor("#334155"); strokeWidth = 1f }
        canvas.drawLine(tbLeft + btnW, tbTop + 5f, tbLeft + btnW, tbTop + tbH - 5f, divPaint)
        canvas.drawLine(tbLeft + btnW * 2f, tbTop + 5f, tbLeft + btnW * 2f, tbTop + tbH - 5f, divPaint)
        canvas.drawLine(tbLeft + btnW * 3f, tbTop + 5f, tbLeft + btnW * 3f, tbTop + tbH - 5f, divPaint)

        // 7. Four circular selection handles on corners (matching design reference)
        val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#3B82F6"); style = Paint.Style.FILL
        }
        val cornerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f
        }
        val corners = listOf(
          Pair(r.left, r.top),
          Pair(r.right, r.top),
          Pair(r.left, r.bottom),
          Pair(r.right, r.bottom)
        )
        for (pt in corners) {
          canvas.drawCircle(pt.first, pt.second, 5.5f, cornerPaint)
          canvas.drawCircle(pt.first, pt.second, 5.5f, cornerStroke)
        }

        // Bottom-right corner resize hit area
        val handleSz = 22f
        val handleRect = RectF(r.right - handleSz, r.bottom - handleSz, r.right, r.bottom)
        nbPageResizeRects[page.id] = handleRect

        // 8. Style picker overlay (if open for this page)
        if (isNotebookStylePickerOpen && stylePickerForPageId == page.id) {
          val styles = listOf(
            "blank" to "Blank",
            "ruled" to "Ruled",
            "grid" to "Grid",
            "dotted" to "Dotted",
            "sketch" to "Sketch",
            "cornell" to "Cornell",
            "squared" to "Squared",
            "custom" to "Custom"
          )
          val rowH = 34f
          val popW = 125f
          val popLeft = (nbPageStyleBtnRects[page.id]?.left ?: r.left)
          val popTop = tbTop + tbH + 4f
          val popRect = RectF(popLeft, popTop, popLeft + popW, popTop + styles.size * rowH)
          nbPageStylePickerRect.set(popRect)

          // Picker shadow + bg
          canvas.drawRoundRect(RectF(popLeft, popTop + 2f, popLeft + popW, popTop + styles.size * rowH + 2f),
            8f, 8f, toolShadow)
          val popBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A"); style = Paint.Style.FILL
          }
          canvas.drawRoundRect(popRect, 8f, 8f, popBg)
          canvas.drawRoundRect(popRect, 8f, 8f, nbPageToolbarBorderPaint)

          val rowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
          }
          for (i in styles.indices) {
            val (styleKey, styleLabel2) = styles[i]
            val rowTop = popTop + i * rowH
            val isActive = page.pageStyle == styleKey
            if (isActive) {
              val activeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1E3A5F"); style = Paint.Style.FILL
              }
              canvas.drawRect(popLeft, rowTop, popLeft + popW, rowTop + rowH, activeBg)
            }
            rowPaint.color = if (isActive) Color.parseColor("#3B82F6") else Color.parseColor("#CBD5E1")
            canvas.drawText(styleLabel2, popLeft + 12f, rowTop + rowH * 0.65f, rowPaint)
          }
        }
      }
    }
  }

  /** Draws the paper texture/pattern lines inside the notebook page's clipped region. */
  private fun drawNotebookPagePattern(canvas: Canvas, page: NativeNotebookPage, r: RectF) {
    val contentTop = r.top + 40f  // leave room for title
    when (page.pageStyle) {
      "ruled" -> {
        val lineSpacing = (page.height * 0.065f).coerceIn(22f, 36f)
        var y = contentTop + lineSpacing
        while (y < r.bottom - 10f) {
          canvas.drawLine(r.left + 10f, y, r.right - 10f, y, nbPageRuledLinePaint)
          y += lineSpacing
        }
        // Pink margin line ~15% from left
        val marginX = r.left + page.width * 0.15f
        canvas.drawLine(marginX, contentTop, marginX, r.bottom - 10f, nbPageMarginLinePaint)
      }
      "grid" -> {
        val step = (page.width * 0.08f).coerceIn(20f, 40f)
        var x = r.left + step
        while (x < r.right - 5f) {
          canvas.drawLine(x, contentTop, x, r.bottom - 5f, nbPageGridLinePaint)
          x += step
        }
        var y = contentTop + step
        while (y < r.bottom - 5f) {
          canvas.drawLine(r.left + 5f, y, r.right - 5f, y, nbPageGridLinePaint)
          y += step
        }
      }
      "dotted" -> {
        val stepX = (page.width * 0.1f).coerceIn(20f, 38f)
        val stepY = (page.height * 0.065f).coerceIn(20f, 36f)
        var x = r.left + stepX
        while (x < r.right - 5f) {
          var y = contentTop + stepY
          while (y < r.bottom - 5f) {
            canvas.drawCircle(x, y, 1.8f, nbPageDotPaint)
            y += stepY
          }
          x += stepX
        }
      }
      "sketch" -> {
        val step = (page.width * 0.12f).coerceIn(24f, 46f)
        val sketchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#D8D5BE"); strokeWidth = 0.6f; style = Paint.Style.STROKE
        }
        var startX = r.left - page.height
        while (startX < r.right + page.height) {
          canvas.drawLine(startX, contentTop, startX + page.height, r.bottom, sketchPaint)
          startX += step
        }
      }
      "cornell" -> {
        val cueX = r.left + page.width * 0.28f
        val summaryY = r.bottom - page.height * 0.22f
        val cornellSepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#EF4444"); strokeWidth = 1.2f
        }
        // Vertical cue column
        canvas.drawLine(cueX, contentTop, cueX, summaryY, cornellSepPaint)
        // Horizontal summary line
        canvas.drawLine(r.left + 10f, summaryY, r.right - 10f, summaryY, cornellSepPaint)
        // Ruled lines in main notes section
        val lineSpacing = (page.height * 0.065f).coerceIn(22f, 36f)
        var y = contentTop + lineSpacing
        while (y < summaryY - 8f) {
          canvas.drawLine(cueX + 4f, y, r.right - 10f, y, nbPageRuledLinePaint)
          y += lineSpacing
        }
      }
      "squared" -> {
        val step = (page.width * 0.05f).coerceIn(14f, 26f)
        val sqMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#94A3B8"); strokeWidth = 0.8f
        }
        val sqMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#CBD5E1"); strokeWidth = 0.4f
        }
        var count = 0
        var x = r.left + step
        while (x < r.right - 5f) {
          canvas.drawLine(x, contentTop, x, r.bottom - 5f, if (count % 5 == 0) sqMajorPaint else sqMinorPaint)
          x += step
          count++
        }
        count = 0
        var y = contentTop + step
        while (y < r.bottom - 5f) {
          canvas.drawLine(r.left + 5f, y, r.right - 5f, y, if (count % 5 == 0) sqMajorPaint else sqMinorPaint)
          y += step
          count++
        }
      }
      "custom" -> {
        val lineSpacing = (page.height * 0.07f).coerceIn(24f, 38f)
        val customLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#E0E7FF"); strokeWidth = 0.8f
        }
        var y = contentTop + lineSpacing
        while (y < r.bottom - 10f) {
          canvas.drawLine(r.left + 15f, y, r.right - 15f, y, customLinePaint)
          y += lineSpacing
        }
      }
      // "blank" -> clean paper background without guide lines
    }
  }

  // ---------------------------------------------------------------------------
  // Notebook Page — Public API & Persistence
  // ---------------------------------------------------------------------------
  /**
   * Creates a new notebook page centered in the current viewport and adds it to the canvas.
   * Called from React Native via the 'addNotebookPage' command, or directly from Kotlin.
   */
  fun addNotebookPage(style: String = "ruled", title: String = "") {
    val viewW = width.toFloat()
    val viewH = height.toFloat()
    val hasDoc = activePdfDoc != null || activeDocument != null
    val splitY = if (hasDoc) viewH * effectiveSplitRatio else 0f
    val canvasTopY = if (hasDoc && effectiveSplitRatio > 0f) splitY + 14f else 0f

    // Center in the visible canvas viewport
    val centerScreenX = viewW / 2f
    val centerScreenY = (canvasTopY + viewH) / 2f
    val (centerWx, centerWy) = canvasScreenToWorld(centerScreenX, centerScreenY, canvasTopY)

    val pageW = (280f / scaleFactor).coerceIn(200f, 600f)
    val pageH = (380f / scaleFactor).coerceIn(260f, 800f)

    val newPage = NativeNotebookPage(
      id = "nbpage-${System.currentTimeMillis()}",
      x = centerWx - pageW / 2f,
      y = centerWy - pageH / 2f,
      width = pageW,
      height = pageH,
      pageStyle = style,
      title = if (title.isEmpty()) "Notebook Page" else title
    )

    notebookPages.add(newPage)
    selectedNotebookPageId = newPage.id

    undoRedoManager.record(CreateNotebookPageAction(
      page = newPage,
      pagesList = notebookPages,
      onUndoDispatched = { invalidate() },
      onRedoDispatched = { invalidate() }
    ))

    dispatchNotebookPageAddedEvent(newPage)
    persistNotebookPagesLocally()
    hudToast.show("📄 Notebook page added — drag header to move")
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    invalidate()
  }

  /**
   * Duplicates an existing notebook page, offsetting it slightly and adding it to the canvas.
   */
  fun duplicateNotebookPage(pageId: String) {
    val orig = notebookPages.find { it.id == pageId } ?: return
    val copy = NativeNotebookPage(
      id = "nbpage-${System.currentTimeMillis()}",
      x = orig.x + 30f,
      y = orig.y + 30f,
      width = orig.width,
      height = orig.height,
      pageStyle = orig.pageStyle,
      title = if (orig.title.endsWith("(Copy)")) orig.title else "${orig.title} (Copy)",
      backgroundColor = orig.backgroundColor
    )
    notebookPages.add(copy)
    selectedNotebookPageId = copy.id
    undoRedoManager.record(CreateNotebookPageAction(
      page = copy,
      pagesList = notebookPages,
      onUndoDispatched = { invalidate() },
      onRedoDispatched = { invalidate() }
    ))
    dispatchNotebookPageAddedEvent(copy)
    persistNotebookPagesLocally()
    hudToast.show("📋 Page duplicated")
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    invalidate()
  }

  /**
   * Persists the notebook pages to a local app-private JSON file so they survive restarts.
   */
  fun persistNotebookPagesLocally() {
    try {
      val file = java.io.File(context.filesDir, "thinkspace_notebook_pages.json")
      val arr = org.json.JSONArray()
      for (page in notebookPages) {
        val obj = org.json.JSONObject().apply {
          put("id", page.id)
          put("x", page.x.toDouble())
          put("y", page.y.toDouble())
          put("width", page.width.toDouble())
          put("height", page.height.toDouble())
          put("pageStyle", page.pageStyle)
          put("title", page.title)
          put("backgroundColor", String.format("#%06X", 0xFFFFFF and page.backgroundColor))
        }
        arr.put(obj)
      }
      file.writeText(arr.toString())
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  /**
   * Restores notebook pages from the local app-private JSON file if currently empty.
   */
  fun loadPersistedNotebookPages() {
    try {
      val file = java.io.File(context.filesDir, "thinkspace_notebook_pages.json")
      if (file.exists() && notebookPages.isEmpty()) {
        val json = file.readText()
        setNotebookPagesFromJson(json)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  /**
   * Loads notebook pages from a JSON array string (used by the 'notebookPagesJson' ReactProp).
   */
  fun setNotebookPagesFromJson(json: String?) {
    if (json.isNullOrBlank()) return
    try {
      val arr = org.json.JSONArray(json)
      notebookPages.clear()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        notebookPages.add(NativeNotebookPage(
          id = obj.optString("id", "nbpage-$i"),
          x = obj.optDouble("x", 0.0).toFloat(),
          y = obj.optDouble("y", 0.0).toFloat(),
          width = obj.optDouble("width", 280.0).toFloat(),
          height = obj.optDouble("height", 380.0).toFloat(),
          pageStyle = obj.optString("pageStyle", "ruled"),
          title = obj.optString("title", "Notebook Page"),
          backgroundColor = Color.parseColor(obj.optString("backgroundColor", "#FFFEF0")
            .let { if (it.startsWith("#")) it else "#FFFEF0" })
        ))
      }
      persistNotebookPagesLocally()
      invalidate()
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  // ---------------------------------------------------------------------------
  // Notebook Page \u2014 React Native Event Dispatchers
  // ---------------------------------------------------------------------------
  private fun getEventDispatcher(): EventDispatcher? {
    val reactContext = UIManagerHelper.getReactContext(this) ?: return null
    return UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)
  }

  private fun dispatchNotebookPageAddedEvent(page: NativeNotebookPage) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("id", page.id)
      putDouble("x", page.x.toDouble())
      putDouble("y", page.y.toDouble())
      putDouble("width", page.width.toDouble())
      putDouble("height", page.height.toDouble())
      putString("pageStyle", page.pageStyle)
      putString("title", page.title)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topNotebookPageAdded", data))
  }

  private fun dispatchNotebookPageMovedEvent(pageId: String, x: Float, y: Float) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("id", pageId)
      putDouble("x", x.toDouble())
      putDouble("y", y.toDouble())
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topNotebookPageMoved", data))
  }

  private fun dispatchNotebookPageDeletedEvent(pageId: String) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply { putString("id", pageId) }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topNotebookPageDeleted", data))
  }


  private fun dispatchTransformEvent() {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putDouble("panX", panX.toDouble())
      putDouble("panY", panY.toDouble())
      putDouble("scale", scaleFactor.toDouble())
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topTransformChange", data))
  }

  private fun dispatchSplitRatioEvent(ratio: Float) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putDouble("ratio", ratio.toDouble())
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topSplitRatioChange", data))
  }

  private fun dispatchToggleSqueezeEvent(squeezed: Boolean) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putBoolean("isSqueezed", squeezed)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topToggleSqueeze", data))
  }

  private fun dispatchExtractExcerptEvent(
    text: String,
    pageNumber: Int,
    color: Int,
    isImg: Boolean,
    imageUrl: String? = null,
    x: Float = 0f,
    y: Float = 0f,
    cardId: String? = null,
    sourceRects: List<RectF> = emptyList(),
    documentId: String = activeDocumentId
  ) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("text", text)
      putDouble("pageNumber", pageNumber.toDouble())
      putString("color", String.format("#%06X", 0xFFFFFF and color))
      putBoolean("isTable", false)
      putBoolean("isImage", isImg)
      if (!imageUrl.isNullOrEmpty()) {
        putString("imageUrl", imageUrl)
      }
      if (!cardId.isNullOrEmpty()) {
        putString("id", cardId)
      }
      putDouble("x", x.toDouble())
      putDouble("y", y.toDouble())
      // Multi-document: always include the source document ID
      putString("documentId", documentId)
      if (sourceRects.isNotEmpty()) {
        val arr = Arguments.createArray()
        for (r in sourceRects) {
          val m = Arguments.createMap().apply {
            putDouble("left", r.left.toDouble())
            putDouble("top", r.top.toDouble())
            putDouble("right", r.right.toDouble())
            putDouble("bottom", r.bottom.toDouble())
          }
          arr.pushMap(m)
        }
        putArray("sourceRects", arr)
      }
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topExtractExcerpt", data))
  }

  private fun dispatchExcerptPressEvent(cardId: String) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("id", cardId)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topExcerptPress", data))
  }

  private fun dispatchExcerptMoveEndEvent(cardId: String, x: Float, y: Float) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("id", cardId)
      putDouble("x", x.toDouble())
      putDouble("y", y.toDouble())
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topExcerptMoveEnd", data))
  }

  private fun dispatchAddStrokeEvent(stroke: NativeStroke) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val json = JSONObject().apply {
      put("id", stroke.id)
      put("color", String.format("#%06X", 0xFFFFFF and stroke.color))
      put("strokeWidth", stroke.strokeWidth.toDouble())
      put("isHighlighter", stroke.isHighlighter)
      val ptsArr = JSONArray()
      for (p in stroke.points) {
        ptsArr.put(JSONObject().apply {
          put("x", p.x.toDouble())
          put("y", p.y.toDouble())
        })
      }
      put("points", ptsArr)
    }
    val data = Arguments.createMap().apply {
      putString("strokeJson", json.toString())
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topAddStroke", data))
  }

  private fun dispatchEraseStrokeEvent(strokeId: String) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("id", strokeId)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topEraseStroke", data))
  }

  fun zoomToFitCards() {
    if (width <= 0 || height <= 0) {
      post { zoomToFitCards() }
      return
    }

    val viewW = width.toFloat()
    val viewH = height.toFloat()
    val hasDoc = activePdfDoc != null || activeDocument != null
    val splitY = if (hasDoc) viewH * effectiveSplitRatio else 0f
    val canvasTopY = if (hasDoc && effectiveSplitRatio > 0f) splitY + 14f else 0f
    val viewportH = (viewH - canvasTopY).coerceAtLeast(100f)
    val viewportW = viewW.coerceAtLeast(100f)

    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var hasContent = false

    for (c in cards) {
      minX = minOf(minX, c.x)
      maxX = maxOf(maxX, c.x + c.width)
      minY = minOf(minY, c.y)
      maxY = maxOf(maxY, c.y + c.getHeight())
      hasContent = true
    }

    for (s in strokes) {
      for (p in s.points) {
        minX = minOf(minX, p.x)
        maxX = maxOf(maxX, p.x)
        minY = minOf(minY, p.y)
        maxY = maxOf(maxY, p.y)
        hasContent = true
      }
    }

    for (pg in notebookPages) {
      minX = minOf(minX, pg.x)
      maxX = maxOf(maxX, pg.x + pg.width)
      minY = minOf(minY, pg.y)
      maxY = maxOf(maxY, pg.y + pg.height)
      hasContent = true
    }

    val targetPanX: Float
    val targetPanY: Float
    val targetScale: Float

    if (!hasContent) {
      targetPanX = 0f
      targetPanY = 0f
      targetScale = 1.0f
    } else {
      val contentW = (maxX - minX).coerceAtLeast(80f)
      val contentH = (maxY - minY).coerceAtLeast(80f)
      val contentCenterX = (minX + maxX) / 2f
      val contentCenterY = (minY + maxY) / 2f

      val padding = 56f * density
      val availW = (viewportW - 2 * padding).coerceAtLeast(80f)
      val availH = (viewportH - 2 * padding).coerceAtLeast(80f)

      val fitScale = minOf(availW / contentW, availH / contentH)
      targetScale = fitScale.coerceIn(camera.minScale, 1.05f)

      targetPanX = (viewportW / 2f) - (contentCenterX * targetScale)
      targetPanY = (viewportH / 2f) - (contentCenterY * targetScale)
    }

    val startPanX = panX
    val startPanY = panY
    val startScale = scaleFactor

    canvasAnimator?.cancel()
    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 380
      interpolator = DecelerateInterpolator()
      addUpdateListener { animator ->
        val fraction = animator.animatedFraction
        panX = startPanX + (targetPanX - startPanX) * fraction
        panY = startPanY + (targetPanY - startPanY) * fraction
        scaleFactor = startScale + (targetScale - startScale) * fraction
        dispatchTransformEvent()
        invalidate()
      }
    }
    canvasAnimator = anim
    anim.start()

    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    if (hasContent) {
      hudToast.show("Zoomed to Cards")
    } else {
      hudToast.show("Workspace Centered")
    }
  }

  fun undo(): Boolean {
    val action = undoRedoManager.undo()
    if (action != null) {
      hudToast.show("↩ Undone: ${action.description}")
      performHapticFeedback(HapticFeedbackConstants.CONFIRM)
      invalidate()
      return true
    } else {
      hudToast.show("Nothing to undo")
      return false
    }
  }

  fun redo(): Boolean {
    val action = undoRedoManager.redo()
    if (action != null) {
      hudToast.show("↪ Redone: ${action.description}")
      performHapticFeedback(HapticFeedbackConstants.CONFIRM)
      invalidate()
      return true
    } else {
      hudToast.show("Nothing to redo")
      return false
    }
  }

  fun canUndo(): Boolean = undoRedoManager.canUndo
  fun canRedo(): Boolean = undoRedoManager.canRedo

  private fun dispatchUndoStateChangeEvent(canUndo: Boolean, canRedo: Boolean) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putBoolean("canUndo", canUndo)
      putBoolean("canRedo", canRedo)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topUndoStateChange", data))
  }

  private fun dispatchCardDeleteEvent(cardId: String) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("id", cardId)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topCardDelete", data))
  }

  private fun dispatchChangeCardColorEvent(cardId: String, color: Int) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("id", cardId)
      putString("color", String.format("#%06X", 0xFFFFFF and color))
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topChangeCardColor", data))
  }

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

  private fun dispatchToggleImmersiveEvent(enabled: Boolean) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putBoolean("isImmersive", enabled)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topToggleImmersive", data))
  }

  // ---------------------------------------------------------------------------
  // LiquidText Real Pen & Semantic Inking System API & Events
  // ---------------------------------------------------------------------------
  fun setPenFavoritesFromJson(json: String?) {
    if (json.isNullOrEmpty()) return
    try {
      val arr = JSONArray(json)
      val list = mutableListOf<String>()
      for (i in 0 until arr.length()) {
        list.add(arr.getString(i))
      }
      if (list.isNotEmpty()) {
        penFavoriteColors.clear()
        penFavoriteColors.addAll(list)
        persistPenSettingsLocally()
        dispatchPenStateChangeEvent()
      }
    } catch (_: Exception) {}
  }

  fun setSemanticInkLinksFromJson(json: String?) {
    if (json.isNullOrEmpty()) return
    try {
      val arr = JSONArray(json)
      val parsedLinks = mutableListOf<NativeInkLink>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "inklink-${System.currentTimeMillis()}-$i")
        val srcEnd = obj.optJSONObject("sourceEndpoint")
        val tgtEnd = obj.optJSONObject("targetEndpoint")

        val srcDoc = if (obj.has("sourceDocId") && obj.optString("sourceDocId").isNotEmpty()) {
          obj.optString("sourceDocId")
        } else {
          srcEnd?.optString("documentId", activeDocumentId.ifEmpty { "default-doc" }) ?: activeDocumentId.ifEmpty { "default-doc" }
        }

        val srcPage = if (obj.has("sourcePageIndex")) {
          obj.optInt("sourcePageIndex", 0)
        } else {
          srcEnd?.optInt("pageIndex", 0) ?: 0
        }

        val rectObj = obj.optJSONObject("sourcePdfRect") ?: srcEnd?.optJSONObject("sourceRect")
        val rect = if (rectObj != null) {
          RectF(
            rectObj.optDouble("left", 0.0).toFloat(),
            rectObj.optDouble("top", 0.0).toFloat(),
            rectObj.optDouble("right", 0.0).toFloat(),
            rectObj.optDouble("bottom", 0.0).toFloat()
          )
        } else RectF(0f, 0f, 100f, 20f)

        val ptObj = obj.optJSONObject("sourcePdfPoint") ?: srcEnd?.optJSONObject("anchorPoint")
        val pt = if (ptObj != null) {
          NativePoint(ptObj.optDouble("x", 0.0).toFloat(), ptObj.optDouble("y", 0.0).toFloat())
        } else NativePoint(rect.centerX(), rect.centerY())

        val targetCard = if (obj.has("targetCardId") && obj.optString("targetCardId").isNotEmpty()) {
          obj.optString("targetCardId")
        } else {
          tgtEnd?.optString("cardId", "") ?: ""
        }
        if (targetCard.isEmpty()) continue

        val anchorX = if (obj.has("cardAnchorX")) {
          obj.optDouble("cardAnchorX", 0.5).toFloat()
        } else if (tgtEnd != null && tgtEnd.has("cardAnchorX")) {
          tgtEnd.optDouble("cardAnchorX", 0.5).toFloat()
        } else if (tgtEnd?.optJSONObject("anchorPoint") != null) {
          tgtEnd.optJSONObject("anchorPoint")?.optDouble("x", 0.5)?.toFloat() ?: 0.5f
        } else 0.5f

        val anchorY = if (obj.has("cardAnchorY")) {
          obj.optDouble("cardAnchorY", 0.5).toFloat()
        } else if (tgtEnd != null && tgtEnd.has("cardAnchorY")) {
          tgtEnd.optDouble("cardAnchorY", 0.5).toFloat()
        } else if (tgtEnd?.optJSONObject("anchorPoint") != null) {
          tgtEnd.optJSONObject("anchorPoint")?.optDouble("y", 0.5)?.toFloat() ?: 0.5f
        } else 0.5f

        val targetCardPtObj = obj.optJSONObject("targetCardPoint") ?: tgtEnd?.optJSONObject("anchorPoint")
        val targetCardPt = if (targetCardPtObj != null) {
          NativePoint(targetCardPtObj.optDouble("x", 0.0).toFloat(), targetCardPtObj.optDouble("y", 0.0).toFloat())
        } else null

        val color = try {
          val colStr = obj.optString("color", "")
          if (colStr.isNotEmpty()) Color.parseColor(colStr) else penColor
        } catch (_: Exception) {
          penColor
        }

        val strokeW = obj.optDouble("strokeWidth", obj.optDouble("thickness", 3.5)).toFloat()
        val style = obj.optString("style", "straight")
        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())

        parsedLinks.add(NativeInkLink(
          id = id,
          sourceDocId = srcDoc,
          sourcePageIndex = srcPage,
          sourcePdfRect = rect,
          sourcePdfPoint = pt,
          targetCardId = targetCard,
          targetCardPoint = targetCardPt,
          color = color,
          strokeWidth = strokeW,
          style = style,
          createdAt = createdAt,
          cardAnchorX = anchorX,
          cardAnchorY = anchorY
        ))
      }

      if (parsedLinks.isNotEmpty() || arr.length() == 0) {
        semanticInkLinks.clear()
        semanticInkLinks.addAll(parsedLinks)
        invalidate()
      }
    } catch (_: Exception) {}
  }

  fun togglePenSettings() {
    isPenSettingsOpen = !isPenSettingsOpen
    dispatchPenStateChangeEvent()
    invalidate()
  }

  fun persistPenSettingsLocally() {
    try {
      val file = File(context.filesDir, "thinkspace_pen_settings.json")
      val obj = JSONObject().apply {
        put("mode", penDrawingMode)
        put("color", String.format("#%06X", (0xFFFFFF and penColor)))
        put("thickness", penThickness.toDouble())
        val arr = JSONArray()
        for (c in penFavoriteColors) arr.put(c)
        put("favorites", arr)
      }
      file.writeText(obj.toString())
    } catch (_: Exception) {}
  }

  fun loadPersistedPenSettings() {
    try {
      val file = File(context.filesDir, "thinkspace_pen_settings.json")
      if (file.exists()) {
        val obj = JSONObject(file.readText())
        if (obj.has("mode")) penDrawingMode = obj.getString("mode")
        if (obj.has("color")) {
          try {
            penColor = Color.parseColor(obj.getString("color"))
            selectedColor = penColor
          } catch (_: Exception) {}
        }
        if (obj.has("thickness")) penThickness = obj.getDouble("thickness").toFloat()
        if (obj.has("favorites")) {
          val arr = obj.getJSONArray("favorites")
          penFavoriteColors.clear()
          for (i in 0 until arr.length()) {
            penFavoriteColors.add(arr.getString(i))
          }
        }
      }
    } catch (_: Exception) {}
  }

  fun persistSemanticInkLinksLocally() {
    try {
      val file = File(context.filesDir, "thinkspace_ink_links.json")
      val arr = JSONArray()
      for (link in semanticInkLinks) {
        val obj = JSONObject().apply {
          put("id", link.id)
          put("sourceDocId", link.sourceDocId)
          put("sourcePageIndex", link.sourcePageIndex)
          put("sourcePdfRect", JSONObject().apply {
            put("left", link.sourcePdfRect.left.toDouble())
            put("top", link.sourcePdfRect.top.toDouble())
            put("right", link.sourcePdfRect.right.toDouble())
            put("bottom", link.sourcePdfRect.bottom.toDouble())
          })
          put("sourcePdfPoint", JSONObject().apply {
            put("x", link.sourcePdfPoint.x.toDouble())
            put("y", link.sourcePdfPoint.y.toDouble())
          })
          put("targetCardId", link.targetCardId)
          put("cardAnchorX", link.cardAnchorX.toDouble())
          put("cardAnchorY", link.cardAnchorY.toDouble())
          if (link.targetCardPoint != null) {
            put("targetCardPoint", JSONObject().apply {
              put("x", link.targetCardPoint.x.toDouble())
              put("y", link.targetCardPoint.y.toDouble())
            })
          }
          put("color", String.format("#%06X", (0xFFFFFF and link.color)))
          put("strokeWidth", link.strokeWidth.toDouble())
          put("style", link.style)
          put("createdAt", link.createdAt)
        }
        arr.put(obj)
      }
      file.writeText(arr.toString())
    } catch (_: Exception) {}
  }

  fun loadPersistedSemanticInkLinks() {
    try {
      val file = File(context.filesDir, "thinkspace_ink_links.json")
      if (file.exists() && semanticInkLinks.isEmpty()) {
        setSemanticInkLinksFromJson(file.readText())
      }
    } catch (_: Exception) {}
  }

  private fun dispatchPenStateChangeEvent() {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val hexColor = String.format("#%06X", (0xFFFFFF and penColor))
    val data = Arguments.createMap().apply {
      putString("mode", penDrawingMode)
      putString("color", hexColor)
      putDouble("thickness", penThickness.toDouble())
      putBoolean("isSettingsOpen", isPenSettingsOpen)
      val favs = Arguments.createArray()
      for (f in penFavoriteColors) {
        favs.pushString(f)
      }
      putArray("favoriteColors", favs)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topPenStateChange", data))
  }

  private fun dispatchInkLinkCreateEvent(link: NativeInkLink) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val hexColor = String.format("#%06X", (0xFFFFFF and link.color))

    val fullJson = JSONObject().apply {
      put("id", link.id)
      put("sourceDocId", link.sourceDocId)
      put("sourcePageIndex", link.sourcePageIndex)
      put("targetCardId", link.targetCardId)
      put("cardAnchorX", link.cardAnchorX.toDouble())
      put("cardAnchorY", link.cardAnchorY.toDouble())
      put("sourceEndpoint", JSONObject().apply {
        put("type", "pdf")
        put("documentId", link.sourceDocId)
        put("pageIndex", link.sourcePageIndex)
        put("sourceRect", JSONObject().apply {
          put("left", link.sourcePdfRect.left.toDouble())
          put("top", link.sourcePdfRect.top.toDouble())
          put("right", link.sourcePdfRect.right.toDouble())
          put("bottom", link.sourcePdfRect.bottom.toDouble())
        })
        put("anchorPoint", JSONObject().apply {
          put("x", link.sourcePdfPoint.x.toDouble())
          put("y", link.sourcePdfPoint.y.toDouble())
        })
      })
      put("targetEndpoint", JSONObject().apply {
        put("type", "card")
        put("cardId", link.targetCardId)
        put("cardAnchorX", link.cardAnchorX.toDouble())
        put("cardAnchorY", link.cardAnchorY.toDouble())
        put("anchorPoint", JSONObject().apply {
          put("x", link.cardAnchorX.toDouble())
          put("y", link.cardAnchorY.toDouble())
        })
      })
      put("color", hexColor)
      put("thickness", link.strokeWidth.toDouble())
      put("style", link.style)
      put("createdAt", link.createdAt.toString())
    }.toString()

    val data = Arguments.createMap().apply {
      putString("linkJson", fullJson)
      putString("id", link.id)
      putString("sourceDocId", link.sourceDocId)
      putInt("sourcePageIndex", link.sourcePageIndex)
      putDouble("sourceX", link.sourcePdfPoint.x.toDouble())
      putDouble("sourceY", link.sourcePdfPoint.y.toDouble())
      putString("targetCardId", link.targetCardId)
      putDouble("cardAnchorX", link.cardAnchorX.toDouble())
      putDouble("cardAnchorY", link.cardAnchorY.toDouble())
      putString("color", hexColor)
      putDouble("strokeWidth", link.strokeWidth.toDouble())
      putString("style", link.style)
      putDouble("createdAt", link.createdAt.toDouble())
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topInkLinkCreate", data))
  }

  private fun dispatchInkLinkDeleteEvent(linkId: String) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("id", linkId)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topInkLinkDelete", data))
  }
}
