package com.thinkspace

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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

data class NativePoint(val x: Float, val y: Float)

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

data class NativeStroke(
  val id: String,
  val points: List<NativePoint>,
  val color: Int,
  val strokeWidth: Float,
  val isHighlighter: Boolean
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
  val text: String
)

data class NativeCard(
  val id: String,
  var x: Float,
  var y: Float,
  var width: Float,
  val text: String,
  var color: Int,
  val pageNumber: Int,
  var comment: String?,
  var clusterId: String?,
  var stackCount: Int,
  val isImage: Boolean,
  val imageUrl: String?,
  val isTable: Boolean,
  val tableRows: List<NativeTableRow>?,
  var groupedItems: MutableList<GroupedExcerpt>? = null
) {
  fun getHeight(): Float {
    return when {
      !groupedItems.isNullOrEmpty() && groupedItems!!.size > 1 -> {
        val baseH = if (groupedItems!!.any { it.isImage }) 175f else 140f
        baseH + (groupedItems!!.size - 1) * 8f
      }
      isTable -> 170f
      isImage -> 160f
      else -> if (text.length > 70) 130f else 105f
    }
  }
}

data class NativeLink(
  val id: String,
  val sourceExcerptId: String,
  val color: Int
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
  val charCountText: String = ""
)

data class NativeCropSelection(
  val pageIndex: Int,
  val pageBounds: BoundingBox,
  val screenRect: RectF,
  val calloutRect: RectF,
  val calloutExcerptBtn: RectF,
  val calloutCloseBtn: RectF,
  val dimensionsText: String = "",
  val holdAndDragRect: RectF = RectF()
)

class ThinkspaceView : View {
  constructor(context: Context?) : super(context)
  constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
  constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
    context,
    attrs,
    defStyleAttr
  )

  // LiquidText Workspace State Props
  var splitRatio: Float = 0.44f
  var isSqueezed: Boolean = false
  var activeTool: String = "select" // "select", "pan", "pen", "highlighter", "eraser"
  var selectedColor: Int = Color.parseColor("#00ADB5")
  var pattern: String = "looseleaf"
  var panX: Float = 0f
  var panY: Float = 0f
  var scaleFactor: Float = 1f

  // Real PDF Engine State
  private var activePdfDoc: PdfDocument? = null
  private val pageLayouts = mutableListOf<PdfPageLayout>()
  // Cache up to 32 rendered pages in memory
  private val pageBitmaps = LruCache<Int, Bitmap>(32)
  private val renderingPages = ConcurrentHashMap.newKeySet<Int>()
  private val pageWordsCache = ConcurrentHashMap<Int, List<TextWord>>()
  private val extractingWords = ConcurrentHashMap.newKeySet<Int>()
  private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  // Document & Annotation Models (Fallback/Demo Doc)
  private var activeDocument: NativeDoc? = null
  private val annotations = mutableListOf<NativeAnnotation>()
  private var docScrollY: Float = 0f
  private var maxDocScrollY: Float = 1000f

  // Document interaction mode: "text" or "crop"
  private var docMode: String = "text"

  // Display density & subheader metrics
  private val density: Float get() = context.resources.displayMetrics.density
  private val subheaderH: Float get() = 48f * density

  // Dedicated LiquidText Long-Press Lift Engine
  private val longPressHandler = Handler(Looper.getMainLooper())
  private var pendingLongPressRunnable: Runnable? = null
  private var longPressStartX = 0f
  private var longPressStartY = 0f

  // Canvas Collections
  private val strokes = mutableListOf<NativeStroke>()
  private val cards = mutableListOf<NativeCard>().apply {
    add(
      NativeCard(
        id = "card-1",
        x = 60f,
        y = 35f,
        width = 225f,
        text = "that essay. What was my philosophy of life? I did not know. Some years earlier I would not have been so hesitant. There was a definite-ness...",
        color = Color.parseColor("#3B82F6"),
        pageNumber = 23,
        comment = null,
        clusterId = null,
        stackCount = 1,
        isImage = false,
        imageUrl = null,
        isTable = false,
        tableRows = null
      )
    )
    add(
      NativeCard(
        id = "card-2",
        x = 320f,
        y = 55f,
        width = 235f,
        text = "successively different ages and periods and had for companions men and women who had lived long ago. I had leisure in jail there was no sens...",
        color = Color.parseColor("#00ADB5"),
        pageNumber = 22,
        comment = null,
        clusterId = null,
        stackCount = 1,
        isImage = false,
        imageUrl = null,
        isTable = false,
        tableRows = null
      )
    )
    add(
      NativeCard(
        id = "card-3",
        x = 75f,
        y = 195f,
        width = 225f,
        text = "of life have always a way out of it, if they so choose. That is always in our power to achieve...",
        color = Color.parseColor("#F59E0B"),
        pageNumber = 22,
        comment = null,
        clusterId = null,
        stackCount = 1,
        isImage = false,
        imageUrl = null,
        isTable = false,
        tableRows = null
      )
    )
  }
  private val links = mutableListOf<NativeLink>().apply {
    add(NativeLink("link-1", "card-1", Color.parseColor("#3B82F6")))
    add(NativeLink("link-2", "card-2", Color.parseColor("#00ADB5")))
    add(NativeLink("link-3", "card-3", Color.parseColor("#F59E0B")))
  }

  // Active inking state
  private val activePoints = mutableListOf<NativePoint>()
  private val activePath = Path()

  // Active card interaction state
  private var selectedCardId: String? = null
  private var draggingCard: NativeCard? = null
  private var dragOffsetWorldX: Float = 0f
  private var dragOffsetWorldY: Float = 0f
  private var totalDragDistance: Float = 0f
  private var heldCardId: String? = null

  // Gesture flags
  private var isPanningCanvas = false
  private var isDraggingDivider = false
  private var isScrollingDoc = false
  private var lastTouchScreenX = 0f
  private var lastTouchScreenY = 0f

  // Real PDF Selection state
  private var activePdfSelection: NativePdfSelection? = null
  private var isSelectingPdfText = false
  private var pdfSelectStartWord: TextWord? = null
  private var pdfSelectPageIndex = 0

  // Figure Crop state
  private var activeCropSelection: NativeCropSelection? = null
  private var isDraggingCrop = false
  private var cropStartX = 0f
  private var cropStartY = 0f
  private var cropPageIndex = 0

  // Fallback Text Selection state
  private val paragraphLayouts = mutableListOf<ParagraphLayoutInfo>()
  private var activeSelection: NativeDocumentSelection? = null

  // Cross-Zone Lift-and-Drag state
  private var isLiftingExcerpt = false
  private var liftCandidateText: String? = null
  private var liftCandidatePage: Int = 1
  private var liftCandidateColor: Int = Color.parseColor("#00ADB5")
  private var liftCandidateIsImage = false
  private var liftCandidateImagePath: String? = null
  private var liftCandidateBitmap: Bitmap? = null
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

  // Drop shockwave ripple animation
  private var rippleOriginX = 0f
  private var rippleOriginY = 0f
  private var rippleColor = Color.parseColor("#00ADB5")
  private var rippleProgress = 1f
  private var rippleAnimator: ValueAnimator? = null

  // Toast feedback & HUD Notification Engine
  private var copiedToastText: String? = null
  private val hudToast = HudToastRenderer(density)
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
  private val headerSearchRect = RectF()
  private val headerZoomOutRect = RectF()
  private val headerZoomResetRect = RectF()
  private val headerZoomInRect = RectF()

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

  // Scale gesture detector for 2-finger zoom and pinch accordion squeeze
  private val scaleGestureListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScale(detector: ScaleGestureDetector): Boolean {
      val fy = detector.focusY
      val splitY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio else 0f
      if (fy < splitY) {
        // Pinching inside document zone -> LiquidText accordion squeeze
        if (detector.scaleFactor < 0.88f && !isSqueezed) {
          isSqueezed = true
          performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
          dispatchToggleSqueezeEvent(true)
          invalidate()
        } else if (detector.scaleFactor > 1.12f && isSqueezed) {
          isSqueezed = false
          performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
          dispatchToggleSqueezeEvent(false)
          invalidate()
        }
        return true
      }

      val oldScale = scaleFactor
      scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.4f, 3.5f)
      val fx = detector.focusX
      panX = fx - (fx - panX) * (scaleFactor / oldScale)
      panY = fy - (fy - panY) * (scaleFactor / oldScale)
      dispatchTransformEvent()
      invalidate()
      return true
    }
  }
  private val scaleGestureDetector = ScaleGestureDetector(context ?: throw IllegalStateException("Context required"), scaleGestureListener)

  // Long-press gesture detector for instant LiquidText-style Figure / Excerpt lifting
  private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onLongPress(e: MotionEvent) {
      triggerLongPressLift(e.x, e.y)
    }
  })

  private fun triggerLongPressLift(x: Float, y: Float) {
    val splitY = if (activePdfDoc != null || activeDocument != null) height.toFloat() * splitRatio else 0f
    if (y < splitY - 14f && y >= subheaderH) {
      performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
      isScrollingDoc = false

      // 1. Check if there is a real PDF page under touch
      for (pl in pageLayouts) {
        if (!pl.isFolded && pl.boundsOnScreen.contains(x, y)) {
          // Check if there is text directly under touch
          val words = pageWordsCache[pl.pageIndex]
          val px = (x - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pl.pageSize.width
          val py = (y - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pl.pageSize.height
          val hitWord = words?.find { w ->
            px >= w.bounds.left - 6f && px <= w.bounds.right + 6f && py >= w.bounds.top - 8f && py <= w.bounds.bottom + 8f
          }

          if (hitWord != null) {
            isLiftingExcerpt = true
            liftCandidateText = hitWord.text
            liftCandidatePage = pl.pageIndex + 1
            liftCandidateColor = selectedColor
            liftCandidateIsImage = false
            liftCandidateImagePath = null
            liftCandidateBitmap = null
            liftGhostX = x
            liftGhostY = y
            liftAnchorScreenX = x
            liftAnchorScreenY = y
            activeCropSelection = null
            activePdfSelection = null
            invalidate()
            return
          }

          val cropW = min(pl.boundsOnScreen.width() * 0.85f, 320f * density)
          val cropH = min(pl.boundsOnScreen.height() * 0.45f, 200f * density)
          val sRect = RectF(
            (x - cropW / 2f).coerceIn(pl.boundsOnScreen.left + 8f * density, pl.boundsOnScreen.right - cropW - 8f * density),
            (y - cropH / 2f).coerceIn(pl.boundsOnScreen.top + 8f * density, pl.boundsOnScreen.bottom - cropH - 8f * density),
            (x + cropW / 2f).coerceIn(pl.boundsOnScreen.left + cropW + 8f * density, pl.boundsOnScreen.right - 8f * density),
            (y + cropH / 2f).coerceIn(pl.boundsOnScreen.top + cropH + 8f * density, pl.boundsOnScreen.bottom - 8f * density)
          )
          val pW = pl.pageSize.width
          val pH = pl.pageSize.height
          val pageLeft = (sRect.left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
          val pageTop = (sRect.top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH
          val pageRight = (sRect.right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW
          val pageBottom = (sRect.bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH

          val bounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom))
          val bmp = generateCropBitmap(pl.pageIndex, bounds)

          isLiftingExcerpt = true
          liftCandidateText = "[Figure Crop]"
          liftCandidatePage = pl.pageIndex + 1
          liftCandidateColor = selectedColor
          liftCandidateIsImage = true
          if (bmp != null) {
            val p = saveCropToFile(bmp)
            liftCandidateImagePath = p
            liftCandidateBitmap = bmp
            cardBitmapCache.put(p, bmp)
          } else {
            liftCandidateImagePath = null
            liftCandidateBitmap = null
          }
          liftGhostX = x
          liftGhostY = y
          liftAnchorScreenX = x
          liftAnchorScreenY = y
          activeCropSelection = null
          activePdfSelection = null
          invalidate()
          return
        }
      }

      // 2. Fallback structured demo document
      if (activeDocument != null) {
        val bmp = generateCropBitmap(0, BoundingBox(0f, 0f, 400f, 260f))
        isLiftingExcerpt = true
        liftCandidateText = "Chapter Excerpt"
        liftCandidatePage = 1
        liftCandidateColor = selectedColor
        liftCandidateIsImage = true
        if (bmp != null) {
          val p = saveCropToFile(bmp)
          liftCandidateImagePath = p
          liftCandidateBitmap = bmp
          cardBitmapCache.put(p, bmp)
        }
        liftGhostX = x
        liftGhostY = y
        liftAnchorScreenX = x
        liftAnchorScreenY = y
        activeCropSelection = null
        activePdfSelection = null
        invalidate()
      }
    }
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
      val title = sec?.heading ?: "Discovery of India"
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
      val id = obj.optString("id", obj.optString("documentId", ""))
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

  fun setAnnotationsFromJson(json: String?) {
    annotations.clear()
    if (json.isNullOrEmpty()) {
      invalidate()
      return
    }
    try {
      val arr = JSONArray(json)
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "ann-$i")
        val sectionId = obj.optString("sectionId", "")
        val pIdx = obj.optInt("paragraphIndex", 0)
        val pageNumber = obj.optInt("pageNumber", 1)
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.YELLOW }
        val text = obj.optString("text", "")
        annotations.add(NativeAnnotation(id, sectionId, pIdx, pageNumber, color, text))
      }
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

        val x = if (obj.has("x") && obj.getDouble("x") != 0.0) obj.getDouble("x").toFloat() else (existing?.x ?: 60f)
        val y = if (obj.has("y") && obj.getDouble("y") != 0.0) obj.getDouble("y").toFloat() else (existing?.y ?: 60f)
        val width = obj.optDouble("width", (existing?.width ?: 220f).toDouble()).toFloat()
        val text = obj.optString("text", existing?.text ?: "")
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

        updatedList.add(
          NativeCard(
            id, x, y, width, text, color, pageNumber, comment, clusterId, stackCount,
            isImage, imageUrl, isTable, existing?.tableRows
          )
        )
      }

      // Preserve any locally dropped cards not yet present in React Native state
      for (localCard in cards) {
        if (updatedList.none { it.id == localCard.id || (it.isImage && localCard.isImage && it.pageNumber == localCard.pageNumber) }) {
          updatedList.add(localCard)
        }
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
    return Pair((sx - panX) / scaleFactor, (sy - canvasTopY - panY) / scaleFactor)
  }

  private fun canvasWorldToScreen(wx: Float, wy: Float, canvasTopY: Float): Pair<Float, Float> {
    return Pair(wx * scaleFactor + panX, wy * scaleFactor + panY + canvasTopY)
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
    val splitY = if (hasDoc) viewH * splitRatio else 0f
    val docBottomY = max(0f, splitY - 14f)
    val canvasTopY = if (hasDoc) splitY + 14f else 0f

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

      // 1. Document Pill
      val displayTitle = if (titleStr.length > 18) titleStr.substring(0, 16) + "... ▾" else "$titleStr ▾"
      val pillW = badgeTextPaint.measureText(displayTitle) + 24f * density
      val btnTop = (subheaderH - 34f * density) / 2f
      val btnBottom = btnTop + 34f * density

      headerDocPillRect.set(12f * density, btnTop, 12f * density + pillW, btnBottom)
      canvas.drawRoundRect(headerDocPillRect, 14f * density, 14f * density, dividerHandlePaint)
      canvas.drawRoundRect(headerDocPillRect, 14f * density, 14f * density, dividerBorderPaint)
      canvas.drawText(displayTitle, 20f * density, btnTop + 22f * density, badgeTextPaint)

      // Page Pill
      val curPageNum = (pageLayouts.firstOrNull { it.boundsOnScreen.bottom > subheaderH + 20f }?.pageNumber ?: 1).coerceIn(1, pageTotal)
      val pageInd = "p. $curPageNum / $pageTotal"
      canvas.drawText(pageInd, 12f * density + pillW + 12f * density, btnTop + 22f * density, docTitlePaint)

      // Right Side Controls matching Video: Crop toggle [X Crop], Zoom Pill [- 1:1 +], and Accordion Squeeze [🪗]
      val cropBtnW = 82f * density
      val cropBtnLeft = viewW - 12f * density - cropBtnW

      val zoomPillW = 86f * density
      val zoomPillLeft = cropBtnLeft - 8f * density - zoomPillW

      val squeezeBtnW = 38f * density
      val squeezeBtnLeft = zoomPillLeft - 8f * density - squeezeBtnW

      // Squeeze Button [🪗]
      headerSqueezeRect.set(squeezeBtnLeft, btnTop, squeezeBtnLeft + squeezeBtnW, btnBottom)
      val sqBg = if (isSqueezed) Color.parseColor("#00ADB5") else Color.parseColor("#1E293B")
      canvas.drawRoundRect(headerSqueezeRect, 10f * density, 10f * density, Paint().apply { color = sqBg })
      canvas.drawText("🪗", squeezeBtnLeft + 10f * density, btnTop + 23f * density, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f * density })

      // Zoom Pill [- 1:1 +]
      val zoomBgPaint = Paint().apply { color = Color.parseColor("#1E293B") }
      val zoomBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
      }
      val zoomFullRect = RectF(zoomPillLeft, btnTop, zoomPillLeft + zoomPillW, btnBottom)
      canvas.drawRoundRect(zoomFullRect, 10f * density, 10f * density, zoomBgPaint)
      canvas.drawRoundRect(zoomFullRect, 10f * density, 10f * density, zoomBorderPaint)

      val zoomSegW = zoomPillW / 3f
      headerZoomOutRect.set(zoomPillLeft, btnTop, zoomPillLeft + zoomSegW, btnBottom)
      headerZoomResetRect.set(zoomPillLeft + zoomSegW, btnTop, zoomPillLeft + zoomSegW * 2f, btnBottom)
      headerZoomInRect.set(zoomPillLeft + zoomSegW * 2f, btnTop, zoomPillLeft + zoomPillW, btnBottom)

      val zoomTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        isFakeBoldText = true
      }
      canvas.drawText("-", headerZoomOutRect.centerX() - 3f * density, btnTop + 21f * density, zoomTextPaint)
      canvas.drawText("1:1", headerZoomResetRect.centerX() - 9f * density, btnTop + 21f * density, zoomTextPaint)
      canvas.drawText("+", headerZoomInRect.centerX() - 4f * density, btnTop + 21f * density, zoomTextPaint)

      // Crop Mode Toggle [✕ Crop] or [✂️ Crop]
      headerModeCropRect.set(cropBtnLeft, btnTop, cropBtnLeft + cropBtnW, btnBottom)
      val cropBg = if (docMode == "crop") Color.parseColor("#00ADB5") else Color.parseColor("#1E293B")
      val cropText = if (docMode == "crop") "✕ Crop" else "✂️ Crop"
      canvas.drawRoundRect(headerModeCropRect, 10f * density, 10f * density, Paint().apply { color = cropBg })
      canvas.drawText(cropText, cropBtnLeft + 14f * density, btnTop + 22f * density, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        isFakeBoldText = true
      })

      // -----------------------------------------------------------------------
      // Render Document Pages (Real PDF Document or Fallback Structured Sections)
      // -----------------------------------------------------------------------
      val paperMargin = 16f
      val paperW = min(viewW - paperMargin * 2f, 680f)
      val paperX = (viewW - paperW) / 2f

      pageLayouts.clear()

      if (activePdfDoc != null) {
        val pdf = activePdfDoc!!
        val pCount = pdf.pageCount
        val standardPageH = paperW * 1.294f
        val pageStride = if (isSqueezed) (32f + 6f) else (standardPageH + 18f)
        val totalDocH = pCount * pageStride
        maxDocScrollY = max(0f, totalDocH - (docBottomY - subheaderH) + 60f)

        val firstIdx = max(0, ((docScrollY - 400f) / pageStride).toInt())
        val lastIdx = min(pCount - 1, ((docScrollY + (docBottomY - subheaderH) + 400f) / pageStride).toInt() + 1)

        for (pageIdx in firstIdx..lastIdx) {
          val pageNum = pageIdx + 1
          val hasAnnotation = annotations.any { it.pageNumber == pageNum } || cards.any { it.pageNumber == pageNum }
          val isFolded = isSqueezed && !hasAnnotation

          val pageH = if (isFolded) 32f else standardPageH
          val pageTopY = subheaderH + 16f - docScrollY + pageIdx * pageStride
          val screenRect = RectF(paperX, pageTopY, paperX + paperW, pageTopY + pageH)

          pageLayouts.add(
            PdfPageLayout(
              pageIndex = pageIdx,
              pageNumber = pageNum,
              pageSize = PageSize.LETTER,
              topY = pageTopY,
              height = pageH,
              isFolded = isFolded,
              boundsOnScreen = screenRect
            )
          )

          // Viewport culling: only draw pages touching the visible area
          if (screenRect.bottom >= subheaderH && screenRect.top <= docBottomY) {
            if (isFolded) {
              // Accordion folded ribbon
              canvas.drawRoundRect(screenRect, 8f, 8f, dividerHandlePaint)
              canvas.drawRoundRect(screenRect, 8f, 8f, dividerBorderPaint)
              canvas.drawText(
                "─── Page $pageNum (Folded) • Tap to expand ───",
                paperX + 24f,
                screenRect.top + 21f,
                commentPaint
              )
            } else {
              // Standard PDF Page Paper Background
              canvas.drawRoundRect(screenRect, 6f, 6f, docPageBgPaint)
              canvas.drawRoundRect(screenRect, 6f, 6f, docPageBorderPaint)

              // Check if page bitmap is cached
              val bmp = pageBitmaps.get(pageIdx)
              if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, screenRect, null)
              } else {
                // Page loading placeholder
                val skeletonPaint = Paint().apply { color = Color.parseColor("#F1F5F9") }
                canvas.drawRoundRect(screenRect, 6f, 6f, skeletonPaint)
                canvas.drawText(
                  "Rendering Page $pageNum...",
                  screenRect.centerX() - 80f,
                  screenRect.centerY(),
                  commentPaint
                )

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
                            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            nativeRenderer.close()
                            pageBmp = bmp
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
                        postInvalidateOnAnimation()
                      }
                    } catch (e: Exception) {
                      e.printStackTrace()
                    } finally {
                      renderingPages.remove(pageIdx)
                    }
                  }
                }
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
              for (ann in pageAnns) {
                highlightBgPaint.color = ann.color
                highlightBgPaint.alpha = 50
                canvas.drawRect(
                  screenRect.left + 20f,
                  screenRect.top + 20f,
                  screenRect.right - 20f,
                  screenRect.top + 50f,
                  highlightBgPaint
                )
              }

              // Flash bidirectional navigation pulse if active
              if (pulsePageNumber == pageNum && pulseAlpha > 0) {
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
        // Fallback Structured Text Sections (e.g. Discovery of India Demo)
        val doc = activeDocument!!
        paragraphLayouts.clear()
        var curY = subheaderH + 16f - docScrollY

        for (sec in doc.sections) {
          val secAnns = annotations.filter { it.sectionId == sec.id }
          val hasAnn = secAnns.isNotEmpty()

          if (isSqueezed && !hasAnn) {
            val foldRect = RectF(paperX, curY, paperX + paperW, curY + 28f)
            canvas.drawRoundRect(foldRect, 6f, 6f, dividerHandlePaint)
            canvas.drawText("── Page ${sec.pageNumber}: ${sec.heading} (Folded) ──", paperX + 16f, curY + 18f, commentPaint)
            curY += 34f
            continue
          }

          var sectionContentH = 60f
          for (para in sec.paragraphs) {
            val textW = max(20, (paperW - 56f).toInt())
            val layout = StaticLayout.Builder
              .obtain(para, 0, para.length, docParagraphPaint, textW)
              .setAlignment(Layout.Alignment.ALIGN_NORMAL)
              .setLineSpacing(0f, 1.25f)
              .build()
            sectionContentH += layout.height + 24f
          }

          val pageCardRect = RectF(paperX, curY, paperX + paperW, curY + sectionContentH)
          canvas.drawRoundRect(pageCardRect, 8f, 8f, docPageBgPaint)
          canvas.drawRoundRect(pageCardRect, 8f, 8f, docPageBorderPaint)

          val headingText = sec.heading.uppercase()
          val headingW = docHeadingPaint.measureText(headingText)
          val headingX = paperX + (paperW - headingW) / 2f
          canvas.drawText(headingText, headingX, curY + 36f, docHeadingPaint)
          curY += 56f

          for ((pIdx, para) in sec.paragraphs.withIndex()) {
            val ann = secAnns.find { it.paragraphIndex == pIdx }
            val isHighlighted = ann != null

            val textW = max(20, (paperW - 56f).toInt())
            val staticLayout = StaticLayout.Builder
              .obtain(para, 0, para.length, docParagraphPaint, textW)
              .setAlignment(Layout.Alignment.ALIGN_NORMAL)
              .setLineSpacing(0f, 1.25f)
              .build()

            paragraphLayouts.add(
              ParagraphLayoutInfo(
                secId = sec.id,
                pIdx = pIdx,
                pageNumber = sec.pageNumber,
                text = para,
                paperX = paperX,
                topY = curY,
                width = (paperW - 56f),
                layout = staticLayout
              )
            )

            val paraH = staticLayout.height.toFloat() + 16f
            if (isHighlighted) {
              val hlRect = RectF(paperX + 16f, curY - 4f, paperX + paperW - 16f, curY + paraH)
              highlightBgPaint.color = ann.color
              highlightBgPaint.alpha = 40
              canvas.drawRoundRect(hlRect, 6f, 6f, highlightBgPaint)
            }

            canvas.save()
            canvas.translate(paperX + 28f, curY)
            staticLayout.draw(canvas)
            canvas.restore()

            curY += paraH + 12f
          }
          curY += 24f
        }
        maxDocScrollY = max(0f, curY + docScrollY - docBottomY + 40f)
      }

      // -----------------------------------------------------------------------
      // Draw Active Text Selection (Real PDF or Fallback) matching Video
      // -----------------------------------------------------------------------
      val pdfSel = activePdfSelection
      if (pdfSel != null) {
        for (r in pdfSel.highlightRects) {
          canvas.drawRoundRect(r, 4f, 4f, selectionFillPaint)
          canvas.drawLine(r.left, r.bottom, r.right, r.bottom, selectionBorderPaint)
        }

        // Handles
        if (pdfSel.highlightRects.isNotEmpty()) {
          val firstR = pdfSel.highlightRects.first()
          canvas.drawLine(firstR.left, firstR.top - 6f, firstR.left, firstR.bottom, selectionHandleBarPaint)
          canvas.drawCircle(firstR.left, firstR.top - 8f, 7.5f, selectionHandlePinPaint)

          val lastR = pdfSel.highlightRects.last()
          canvas.drawLine(lastR.right, lastR.top, lastR.right, lastR.bottom + 6f, selectionHandleBarPaint)
          canvas.drawCircle(lastR.right, lastR.bottom + 8f, 7.5f, selectionHandlePinPaint)
        }

        // Sleek 2-Tier Callout Dock
        val cRect = pdfSel.calloutRect
        canvas.drawRoundRect(cRect, 12f * density, 12f * density, calloutBgPaint)
        canvas.drawRoundRect(cRect, 12f * density, 12f * density, calloutBorderPaint)

        // Tier 1 Header: Chars Count & Snippet Preview + Close Button
        val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#38BDF8")
          textSize = 12f * density
          isFakeBoldText = true
        }
        val headerDisplay = if (pdfSel.charCountText.isNotEmpty()) pdfSel.charCountText else "${pdfSel.text.length} chars selected"
        canvas.drawText(headerDisplay, cRect.left + 12f * density, cRect.top + 20f * density, countPaint)
        canvas.drawText("✕", pdfSel.calloutCloseBtn.left + 8f * density, cRect.top + 20f * density, closeBtnTextPaint)

        // Tier 2 Actions: [+ Excerpt] [Copy] [Highlight] [+Word] [Word+] [All]
        canvas.drawRoundRect(pdfSel.calloutExcerptBtn, 6f * density, 6f * density, calloutPrimaryBtnPaint)
        canvas.drawText("+ Excerpt", pdfSel.calloutExcerptBtn.left + 10f * density, pdfSel.calloutExcerptBtn.centerY() + 5f * density, calloutTextPaint)

        canvas.drawRoundRect(pdfSel.calloutCopyBtn, 6f * density, 6f * density, calloutSecondaryBtnPaint)
        canvas.drawText(copiedToastText ?: "Copy", pdfSel.calloutCopyBtn.left + 10f * density, pdfSel.calloutCopyBtn.centerY() + 5f * density, calloutSecondaryTextPaint)

        canvas.drawRoundRect(pdfSel.calloutHighlightBtn, 6f * density, 6f * density, calloutHighlightBtnPaint)
        canvas.drawText("Highlight", pdfSel.calloutHighlightBtn.left + 8f * density, pdfSel.calloutHighlightBtn.centerY() + 5f * density, calloutTextPaint)

        // Dynamic Word Boundaries: +Word, Word+, All
        val auxBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#1E293B")
          style = Paint.Style.FILL
        }
        val auxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#334155")
          strokeWidth = 1f * density
          style = Paint.Style.STROKE
        }
        val auxTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#E2E8F0")
          textSize = 11f * density
          isFakeBoldText = true
        }

        canvas.drawRoundRect(pdfSel.calloutAddWordLeftBtn, 5f * density, 5f * density, auxBtnPaint)
        canvas.drawRoundRect(pdfSel.calloutAddWordLeftBtn, 5f * density, 5f * density, auxBorderPaint)
        canvas.drawText("+Word", pdfSel.calloutAddWordLeftBtn.left + 4f * density, pdfSel.calloutAddWordLeftBtn.centerY() + 4f * density, auxTextPaint)

        canvas.drawRoundRect(pdfSel.calloutAddWordRightBtn, 5f * density, 5f * density, auxBtnPaint)
        canvas.drawRoundRect(pdfSel.calloutAddWordRightBtn, 5f * density, 5f * density, auxBorderPaint)
        canvas.drawText("Word+", pdfSel.calloutAddWordRightBtn.left + 4f * density, pdfSel.calloutAddWordRightBtn.centerY() + 4f * density, auxTextPaint)

        canvas.drawRoundRect(pdfSel.calloutSelectAllBtn, 5f * density, 5f * density, auxBtnPaint)
        canvas.drawRoundRect(pdfSel.calloutSelectAllBtn, 5f * density, 5f * density, auxBorderPaint)
        canvas.drawText("All", pdfSel.calloutSelectAllBtn.left + 8f * density, pdfSel.calloutSelectAllBtn.centerY() + 4f * density, auxTextPaint)
      }

      // Draw Figure Crop Selection matching Video
      val cropSel = activeCropSelection
      if (cropSel != null) {
        canvas.drawRect(cropSel.screenRect, cropFillPaint)
        canvas.drawRect(cropSel.screenRect, cropDashPaint)

        // Corner Grips
        val gSize = 14f * density
        val r = cropSel.screenRect
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00ADB5"); style = Paint.Style.FILL }
        canvas.drawRect(r.left - 2f, r.top - 2f, r.left + gSize, r.top + 3f * density, gp)
        canvas.drawRect(r.left - 2f, r.top - 2f, r.left + 3f * density, r.top + gSize, gp)
        canvas.drawRect(r.right - gSize, r.top - 2f, r.right + 2f, r.top + 3f * density, gp)
        canvas.drawRect(r.right - 3f * density, r.top - 2f, r.right + 2f, r.top + gSize, gp)
        canvas.drawRect(r.left - 2f, r.bottom - 3f * density, r.left + gSize, r.bottom + 2f, gp)
        canvas.drawRect(r.left - 2f, r.bottom - gSize, r.left + 3f * density, r.bottom + 2f, gp)
        canvas.drawRect(r.right - gSize, r.bottom - 3f * density, r.right + 2f, r.bottom + 2f, gp)
        canvas.drawRect(r.right - 3f * density, r.bottom - gSize, r.right + 2f, r.bottom + 2f, gp)

        // "Hold & Drag" top badge
        val holdBadgeRect = RectF(r.left, r.top - 26f * density, r.left + 96f * density, r.top - 4f * density)
        val holdBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F172A"); style = Paint.Style.FILL }
        val holdBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00ADB5"); strokeWidth = 1.2f * density; style = Paint.Style.STROKE }
        canvas.drawRoundRect(holdBadgeRect, 5f * density, 5f * density, holdBg)
        canvas.drawRoundRect(holdBadgeRect, 5f * density, 5f * density, holdBorder)
        val holdTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.WHITE
          textSize = 11f * density
          isFakeBoldText = true
        }
        canvas.drawText("✋ Hold & Drag", holdBadgeRect.left + 8f * density, holdBadgeRect.centerY() + 4f * density, holdTextPaint)

        // Crop Callout Pill: [Image / Graphic] [Page X (WxH pt)] [+ Excerpt] [✕ Crop]
        canvas.drawRoundRect(cropSel.calloutRect, 10f * density, 10f * density, calloutBgPaint)
        canvas.drawRoundRect(cropSel.calloutRect, 10f * density, 10f * density, calloutBorderPaint)

        // Dimension text
        val dimText = if (cropSel.dimensionsText.isNotEmpty()) cropSel.dimensionsText else "Page ${cropSel.pageIndex + 1} (${cropSel.pageBounds.width.toInt()}x${cropSel.pageBounds.height.toInt()} pt)"
        val dimPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#94A3B8")
          textSize = 12f * density
          isFakeBoldText = true
        }
        canvas.drawText(dimText, cropSel.calloutRect.left + 12f * density, cropSel.calloutRect.centerY() + 4f * density, dimPaint)

        canvas.drawRoundRect(cropSel.calloutExcerptBtn, 6f * density, 6f * density, calloutPrimaryBtnPaint)
        canvas.drawText("+ Excerpt", cropSel.calloutExcerptBtn.left + 10f * density, cropSel.calloutExcerptBtn.centerY() + 4f * density, calloutTextPaint)

        canvas.drawText("✕", cropSel.calloutCloseBtn.left + 8f * density, cropSel.calloutCloseBtn.centerY() + 5f * density, closeBtnTextPaint)
      }

      canvas.restore()
    }

    // =========================================================================
    // 2. RESIZABLE SPLIT DIVIDER
    // =========================================================================
    if (hasDoc && docBottomY > 10f) {
      canvas.drawLine(0f, splitY, viewW, splitY, dividerLinePaint)

      val pillW = 160f
      val pillH = 26f
      val pillX = (viewW - pillW) / 2f
      val pillY = splitY - pillH / 2f
      val handleRect = RectF(pillX, pillY, pillX + pillW, pillY + pillH)
      canvas.drawRoundRect(handleRect, 13f, 13f, dividerHandlePaint)
      canvas.drawRoundRect(handleRect, 13f, 13f, dividerBorderPaint)

      // Live Split Ratio Percentage Badge
      val pctDoc = (splitRatio * 100).toInt()
      val pctCanvas = 100 - pctDoc
      val ratioText = "↕ $pctDoc% Doc | $pctCanvas% Canvas"
      val textX = pillX + (pillW - badgeTextPaint.measureText(ratioText)) / 2f
      canvas.drawText(ratioText, textX, pillY + 18f, badgeTextPaint)
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

    // Shockwave drop ripple
    if (rippleProgress < 1f) {
      val maxRadius = 140f
      val currentRadius = maxRadius * rippleProgress
      ripplePaint.color = rippleColor
      ripplePaint.alpha = ((1f - rippleProgress) * 255).toInt()
      ripplePaint.strokeWidth = (1f - rippleProgress) * 4f + 1f
      canvas.drawCircle(rippleOriginX, rippleOriginY, currentRadius, ripplePaint)
    }

    // Dynamic Bezier Ink Links (LiquidText connector cords)
    for (link in links) {
      val card = cards.find { it.id == link.sourceExcerptId } ?: continue
      val cardTargetX = card.x + card.width / 2f
      val cardTargetY = card.y

      val docAnchorScreenX = viewW / 2f
      val (worldDocAnchorX, worldDocAnchorY) = canvasScreenToWorld(docAnchorScreenX, splitY, canvasTopY)

      val linkPath = Path().apply {
        moveTo(worldDocAnchorX, worldDocAnchorY)
        val midY = (worldDocAnchorY + cardTargetY) / 2f
        cubicTo(
          worldDocAnchorX, midY - 20f,
          cardTargetX, midY + 20f,
          cardTargetX, cardTargetY
        )
      }

      linkGlowPaint.color = link.color
      linkGlowPaint.alpha = 50
      canvas.drawPath(linkPath, linkGlowPaint)

      linkPaint.color = link.color
      canvas.drawPath(linkPath, linkPaint)

      pinPaint.color = link.color
      canvas.drawCircle(worldDocAnchorX, worldDocAnchorY, 4.5f, pinPaint)
      canvas.drawCircle(cardTargetX, cardTargetY, 4.5f, pinPaint)
    }

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
        color = selectedColor
        strokeWidth = if (isHighlighter) 20f else 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (isHighlighter) alpha = 115
      }
      canvas.drawPath(activePath, curPaint)
    }

    // Excerpt Cards
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
      canvas.drawRoundRect(cardRect, 10f, 10f, cardBgPaint)
      cardBorderPaint.color = if (card.id == selectedCardId || isSnapTarget) card.color else Color.parseColor("#CBD5E1")
      canvas.drawRoundRect(cardRect, 10f, 10f, cardBorderPaint)

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

      // Delete Button if selected
      if (card.id == selectedCardId) {
        canvas.drawText("✕", cardRect.right - 22f, cardRect.top + 25f, closeBtnTextPaint)
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
          val preview = if (card.text.length > 80) card.text.substring(0, 77) + "..." else card.text
          val textW = max(20, (card.width - 28f).toInt())
          val layout = StaticLayout.Builder
            .obtain(preview, 0, preview.length, textPaint, textW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

          canvas.save()
          canvas.translate(card.x + 14f, card.y + 36f)
          layout.draw(canvas)
          canvas.restore()
        }
      }

      canvas.restore() // Undo world transform

      // -------------------------------------------------------------------------
      // 4. FLOATING NATIVE CANVAS TOOLBAR (Bottom of Canvas Zone)
      // -------------------------------------------------------------------------
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

      canvas.restore() // Clip canvas rect

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

        // Live Cubic Bezier Spline matching Video
        val tetherPath = Path()
        val startX = if (liftAnchorScreenX > 0f) liftAnchorScreenX else 40f
        val startY = liftAnchorScreenY
        tetherPath.moveTo(startX, startY)
        val dy = liftGhostY - startY
        val cp1Y = startY + dy * 0.45f
        val cp2Y = liftGhostY - dy * 0.45f
        tetherPath.cubicTo(startX, cp1Y, liftGhostX, cp2Y, liftGhostX, liftGhostY)

        linkGlowPaint.color = themeColor
        linkGlowPaint.strokeWidth = 10f * density
        linkGlowPaint.alpha = 75
        canvas.drawPath(tetherPath, linkGlowPaint)

        linkPaint.color = themeColor
        linkPaint.strokeWidth = 3.5f * density
        canvas.drawPath(tetherPath, linkPaint)

        // Ghost Card
        val ghostW = if (liftCandidateIsImage) 250f else 235f
        val ghostH = if (liftCandidateIsImage) 155f else 96f
        val ghostRect = RectF(-ghostW / 2f, -ghostH / 2f, ghostW / 2f, ghostH / 2f)

        canvas.save()
        canvas.translate(liftGhostX, liftGhostY)
        canvas.rotate(-2f)
        canvas.scale(1.06f, 1.06f)

        canvas.drawRoundRect(ghostRect, 12f, 12f, cardBgPaint)
        cardBorderPaint.color = themeColor
        cardBorderPaint.strokeWidth = 2.5f * density
        canvas.drawRoundRect(ghostRect, 12f, 12f, cardBorderPaint)

        cardAccentPaint.color = themeColor
        val leftBar = RectF(ghostRect.left, ghostRect.top + 8f, ghostRect.left + 5f, ghostRect.bottom - 8f)
        canvas.drawRoundRect(leftBar, 2f, 2f, cardAccentPaint)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = themeColor
          textSize = 14f
          isFakeBoldText = true
        }
        val subheaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.parseColor("#64748B")
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
          val imgR = RectF(ghostRect.left + 12f, ghostRect.top + 42f, ghostRect.right - 12f, ghostRect.bottom - 26f)
          canvas.drawBitmap(liftCandidateBitmap!!, null, imgR, null)

          val badgeRect = RectF(ghostRect.left + 14f, ghostRect.bottom - 24f, ghostRect.left + 94f, ghostRect.bottom - 6f)
          canvas.drawRoundRect(badgeRect, 4f, 4f, badgeBgPaint)
          canvas.drawRoundRect(badgeRect, 4f, 4f, badgeBorderPaint)
          canvas.drawText("🔗 Page $liftCandidatePage", ghostRect.left + 20f, ghostRect.bottom - 10f, badgeTextPaint)
        } else {
          val previewPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 15f
            typeface = Typeface.SERIF
          }
          val preview = if (liftCandidateText!!.length > 32) liftCandidateText!!.substring(0, 30) + "..." else liftCandidateText!!
          canvas.drawText("\"$preview\"", ghostRect.left + 16f, ghostRect.top + 58f, previewPaint)

          val badgeRect = RectF(ghostRect.left + 16f, ghostRect.top + 68f, ghostRect.left + 94f, ghostRect.top + 88f)
          canvas.drawRoundRect(badgeRect, 5f, 5f, badgeBgPaint)
          canvas.drawRoundRect(badgeRect, 5f, 5f, badgeBorderPaint)
          canvas.drawText("🔗 Page $liftCandidatePage", ghostRect.left + 22f, ghostRect.top + 82f, badgeTextPaint)
        }

        canvas.restore()
      }

      // Draw bottom floating toast matching video
      hudToast.draw(canvas, viewW, viewH - 64f * density)
      if (hudToast.isShowing) {
        postInvalidateOnAnimation()
      }
    }

  // ---------------------------------------------------------------------------
  // Touch Handling & Unified Gesture Arbitration
  // ---------------------------------------------------------------------------
  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    val viewH = height.toFloat()
    val hasDoc = activePdfDoc != null || activeDocument != null
    val splitY = if (hasDoc) viewH * splitRatio else 0f
    val canvasTopY = if (hasDoc) splitY + 14f else 0f

    val sx = event.x
    val sy = event.y

    val inDivider = hasDoc && (sy in (splitY - 30f)..(splitY + 30f))
    val inDocZone = hasDoc && (sy < splitY - 14f)
    val inCanvasZone = sy >= canvasTopY

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

    gestureDetector.onTouchEvent(event)

    // 1. Two or more fingers -> Canvas Pan & Zoom or Document Accordion Squeeze
    if (event.pointerCount >= 2) {
      scaleGestureDetector.onTouchEvent(event)
      if (inCanvasZone) {
        when (event.actionMasked) {
          MotionEvent.ACTION_MOVE -> {
            val midX = (event.getX(0) + event.getX(1)) / 2f
            val midY = (event.getY(0) + event.getY(1)) / 2f - canvasTopY
            if (lastTouchScreenX != 0f && lastTouchScreenY != 0f) {
              panX += (midX - lastTouchScreenX)
              panY += (midY - lastTouchScreenY)
              dispatchTransformEvent()
              invalidate()
            }
            lastTouchScreenX = midX
            lastTouchScreenY = midY
          }
          MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
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

        // Check Top Subheader UI Clicks matching Video
        if (inDocZone && sy < subheaderH) {
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

                activeCropSelection = NativeCropSelection(
                  pageIndex = curPl.pageIndex,
                  pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
                  screenRect = sRect,
                  calloutRect = calloutR,
                  calloutExcerptBtn = excerptBtn,
                  calloutCloseBtn = closeBtn,
                  dimensionsText = "Page ${curPl.pageIndex + 1} (${pW.toInt()}x${pH.toInt()} pt)",
                  holdAndDragRect = RectF(sRect.left, sRect.top - 26f * density, sRect.left + 96f * density, sRect.top - 4f * density)
                )
              }
            } else {
              activeCropSelection = null
            }
            invalidate()
            return true
          }
          if (headerSqueezeRect.contains(sx, sy)) {
            isSqueezed = !isSqueezed
            dispatchToggleSqueezeEvent(isSqueezed)
            hudToast.show(if (isSqueezed) "🪗 Document Squeezed" else "Document Expanded")
            invalidate()
            return true
          }
          return true
        }

        // Check Floating Canvas Bottom Toolbar & HUD Clicks
        if (inCanvasZone && canvasToolbarRect.contains(sx, sy)) {
          for ((toolId, rect) in toolBtnRects) {
            if (rect.contains(sx, sy)) {
              activeTool = toolId
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
            panX = 0f
            panY = 0f
            scaleFactor = 1f
            dispatchTransformEvent()
            hudToast.show("Canvas Centered")
            invalidate()
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
          // Check Callout Clicks matching Video (+Word, Word+, All, Excerpt, Copy, Highlight, Close)
          val pdfSel = activePdfSelection
          if (pdfSel != null && pdfSel.calloutRect.contains(sx, sy)) {
            if (pdfSel.calloutExcerptBtn.contains(sx, sy)) {
              extractExcerptToCanvas(pdfSel.text, pdfSel.pageIndex + 1, selectedColor)
              activePdfSelection = null
              invalidate()
              return true
            }
            if (pdfSel.calloutCopyBtn.contains(sx, sy)) {
              copyToClipboard(pdfSel.text)
              return true
            }
            if (pdfSel.calloutHighlightBtn.contains(sx, sy)) {
              addAnnotation(pdfSel.text, pdfSel.pageIndex + 1, selectedColor)
              activePdfSelection = null
              invalidate()
              return true
            }
            if (pdfSel.calloutAddWordLeftBtn.contains(sx, sy)) {
              val pl = pageLayouts.firstOrNull { it.pageIndex == pdfSel.pageIndex }
              if (pl != null && pdfSel.startWordIndex > 0) {
                updatePdfSelectionByIndex(pl, pdfSel.startWordIndex - 1, pdfSel.endWordIndex)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              }
              return true
            }
            if (pdfSel.calloutAddWordRightBtn.contains(sx, sy)) {
              val pl = pageLayouts.firstOrNull { it.pageIndex == pdfSel.pageIndex }
              val words = pageWordsCache[pdfSel.pageIndex]
              if (pl != null && words != null && pdfSel.endWordIndex < words.size - 1) {
                updatePdfSelectionByIndex(pl, pdfSel.startWordIndex, pdfSel.endWordIndex + 1)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              }
              return true
            }
            if (pdfSel.calloutSelectAllBtn.contains(sx, sy)) {
              val pl = pageLayouts.firstOrNull { it.pageIndex == pdfSel.pageIndex }
              val words = pageWordsCache[pdfSel.pageIndex]
              if (pl != null && !words.isNullOrEmpty()) {
                updatePdfSelectionByIndex(pl, 0, words.size - 1)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
              }
              return true
            }
            if (pdfSel.calloutCloseBtn.contains(sx, sy)) {
              activePdfSelection = null
              invalidate()
              return true
            }
          }

          // Check Crop Callout Clicks
          val cropSel = activeCropSelection
          if (cropSel != null && cropSel.calloutRect.contains(sx, sy)) {
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
          }

          // Check Folded Accordion Pleat Tap -> Expand
          for (pl in pageLayouts) {
            if (pl.isFolded && pl.boundsOnScreen.contains(sx, sy)) {
              // Unfold this page or disable squeeze
              isSqueezed = false
              dispatchToggleSqueezeEvent(false)
              invalidate()
              return true
            }
          }

          // Check Lift from Active Selection
          if (pdfSel != null && pdfSel.highlightRects.any { it.contains(sx, sy) }) {
            isLiftingExcerpt = true
            liftCandidateText = pdfSel.text
            liftCandidatePage = pdfSel.pageIndex + 1
            liftCandidateColor = selectedColor
            liftCandidateIsImage = false
            liftGhostX = sx
            liftGhostY = sy
            liftAnchorScreenX = sx
            liftAnchorScreenY = sy
            return true
          }

          if (cropSel != null && cropSel.screenRect.contains(sx, sy)) {
            isLiftingExcerpt = true
            liftCandidateText = "[Figure Crop]"
            liftCandidatePage = cropSel.pageIndex + 1
            liftCandidateColor = selectedColor
            liftCandidateIsImage = true
            val bmp = generateCropBitmap(cropSel.pageIndex, cropSel.pageBounds)
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
            liftAnchorScreenX = sx
            liftAnchorScreenY = sy
            return true
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

          // Start 260ms Long-Press Timer for effortless LiquidText-style Figure / Excerpt Lift
          longPressStartX = sx
          longPressStartY = sy
          pendingLongPressRunnable?.let { longPressHandler.removeCallbacks(it) }
          pendingLongPressRunnable = Runnable {
            triggerLongPressLift(longPressStartX, longPressStartY)
          }
          longPressHandler.postDelayed(pendingLongPressRunnable!!, 260)
          return true
        }

        // Canvas Zone Gestures
        if (inCanvasZone) {
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)

          // 1. Check Excerpt Card Clicks
          val clickedCard = cards.findLast { c ->
            val ch = c.getHeight()
            wx >= c.x && wx <= c.x + c.width && wy >= c.y && wy <= c.y + ch
          }

          if (clickedCard != null) {
            selectedCardId = clickedCard.id
            draggingCard = clickedCard
            dragOffsetWorldX = wx - clickedCard.x
            dragOffsetWorldY = wy - clickedCard.y
            dispatchExcerptPressEvent(clickedCard.id)

            // Bidirectional Navigation: Scroll document to card's page!
            scrollToDocumentPage(clickedCard.pageNumber)
            invalidate()
            return true
          }

          selectedCardId = null

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

        // Dragging Crop Rectangle
        if (isDraggingCrop) {
          val pl = pageLayouts.find { it.pageIndex == cropPageIndex }
          val leftBound = pl?.boundsOnScreen?.left ?: 16f
          val rightBound = pl?.boundsOnScreen?.right ?: (width - 16f)
          val topBound = pl?.boundsOnScreen?.top ?: 46f
          val bottomBound = pl?.boundsOnScreen?.bottom ?: (splitY - 14f)

          val left = min(cropStartX, sx).coerceIn(leftBound, rightBound)
          val top = min(cropStartY, sy).coerceIn(topBound, bottomBound)
          val right = max(cropStartX, sx).coerceIn(leftBound, rightBound)
          val bottom = max(cropStartY, sy).coerceIn(topBound, bottomBound)

          val sRect = RectF(left, top, right, bottom)
          val pW = pl?.pageSize?.width ?: 612f
          val pH = pl?.pageSize?.height ?: 792f
          val pageLeft = if (pl != null) (left - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW else 0f
          val pageTop = if (pl != null) (top - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH else 0f
          val pageRight = if (pl != null) (right - pl.boundsOnScreen.left) / pl.boundsOnScreen.width() * pW else pW
          val pageBottom = if (pl != null) (bottom - pl.boundsOnScreen.top) / pl.boundsOnScreen.height() * pH else pH

          // If user dragged crop box across into the canvas zone, seamlessly convert to lift & excerpt!
          if (sy > splitY + 16f) {
            isDraggingCrop = false
            isLiftingExcerpt = true
            liftCandidateText = "[Figure Crop]"
            liftCandidatePage = cropPageIndex + 1
            liftCandidateColor = selectedColor
            liftCandidateIsImage = true
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

          val cW = 160f
          val cH = 38f
          val cLeft = sRect.centerX() - cW / 2f
          val cTop = if (sRect.top - cH - 10f > 50f) sRect.top - cH - 10f else sRect.bottom + 10f
          val calloutR = RectF(cLeft, cTop, cLeft + cW, cTop + cH)
          val excerptBtn = RectF(cLeft + 6f, cTop + 4f, cLeft + 124f, cTop + cH - 4f)
          val closeBtn = RectF(cLeft + 128f, cTop + 4f, cLeft + cW - 6f, cTop + cH - 4f)

          activeCropSelection = NativeCropSelection(
            pageIndex = cropPageIndex,
            pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
            screenRect = sRect,
            calloutRect = calloutR,
            calloutExcerptBtn = excerptBtn,
            calloutCloseBtn = closeBtn
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

        // Scrolling Document
        if (isScrollingDoc) {
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

        // Inking
        if (activePoints.isNotEmpty()) {
          val (wx, wy) = canvasScreenToWorld(sx, sy, canvasTopY)
          val lastPt = activePoints.last()
          activePath.quadTo(lastPt.x, lastPt.y, (lastPt.x + wx) / 2f, (lastPt.y + wy) / 2f)
          activePoints.add(NativePoint(wx, wy))
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
        downDocX = 0f
        downDocY = 0f
        pendingLongPressRunnable?.let {
          longPressHandler.removeCallbacks(it)
          pendingLongPressRunnable = null
        }

        isDraggingDivider = false
        isPanningCanvas = false
        isSelectingPdfText = false
        isDraggingCrop = false

        // Compute Scroll Fling Inertia for Document
        if (isScrollingDoc) {
          if (totalDragDistance > touchSlop) {
            velocityTracker?.let { vt ->
              vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
              val vy = vt.yVelocity
              if (abs(vy) > minFlingVelocity) {
                val flingVy = -vy * 1.8f
                docScroller.fling(
                  0, docScrollY.toInt(),
                  0, flingVy.toInt(),
                  0, 0,
                  0, maxDocScrollY.toInt(),
                  0, (150f * density).toInt()
                )
                postInvalidateOnAnimation()
              }
            }
          } else {
            handleDocTap(downDocX, downDocY)
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
                tableRows = null
              )
              cards.add(card)

              links.add(NativeLink("link-${System.currentTimeMillis()}", newId, liftCandidateColor))
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
                card.id
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
            links.add(NativeLink("link-${System.currentTimeMillis()}", snapTarget.id, card.color))
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            hudToast.show("Magnetically stacked with nearby card!")
          } else {
            dispatchExcerptMoveEndEvent(card.id, card.x, card.y)
          }
          draggingCard = null
          magneticTargetCardId = null
          invalidate()
          return true
        }

        // Finalize Inking Stroke
        if (activePoints.isNotEmpty()) {
          val newStroke = NativeStroke(
            id = "stroke-${System.currentTimeMillis()}",
            points = activePoints.toList(),
            color = selectedColor,
            strokeWidth = if (activeTool == "highlighter") 20f else 3.5f,
            isHighlighter = activeTool == "highlighter"
          )
          strokes.add(newStroke)
          dispatchAddStrokeEvent(newStroke)
          activePoints.clear()
          activePath.reset()
          invalidate()
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

    // 1. Real PDF Word Selection
    if (activePdfDoc != null) {
      for (pl in pageLayouts) {
        if (!pl.isFolded && pl.boundsOnScreen.contains(tapX, tapY)) {
          val words = pageWordsCache[pl.pageIndex]
          if (words != null && words.isNotEmpty()) {
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

            activeCropSelection = NativeCropSelection(
              pageIndex = pl.pageIndex,
              pageBounds = BoundingBox(pageLeft, pageTop, max(pageLeft + 1f, pageRight), max(pageTop + 1f, pageBottom)),
              screenRect = sRect,
              calloutRect = calloutR,
              calloutExcerptBtn = excerptBtn,
              calloutCloseBtn = closeBtn
            )
            activePdfSelection = null
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
            return
          } else {
            // Normal tap on blank whitespace in text mode: dismiss selections
            activeCropSelection = null
            activePdfSelection = null
            invalidate()
            return
          }
        }
      }
    }

    // 2. Structured Sections Demo (The Discovery of India)
    if (activeDocument != null && activePdfDoc == null) {
      for (pInfo in paragraphLayouts) {
        val paraH = pInfo.layout.height.toFloat() + 16f
        val pRect = RectF(pInfo.paperX, pInfo.topY, pInfo.paperX + pInfo.width + 56f, pInfo.topY + paraH)
        if (pRect.contains(tapX, tapY)) {
          val hlLeft = pInfo.paperX + 24f
          val hlTop = pInfo.topY - 4f
          val hlRight = pInfo.paperX + pInfo.width + 32f
          val hlBottom = pInfo.topY + pInfo.layout.height + 4f
          val highlightR = RectF(hlLeft, hlTop, hlRight, hlBottom)

          val cW = 280f
          val cH = 40f
          val cLeft = (hlLeft + hlRight) / 2f - cW / 2f
          val cTop = if (hlTop - cH - 12f > 50f) hlTop - cH - 12f else hlBottom + 12f
          val calloutR = RectF(cLeft, cTop, cLeft + cW, cTop + cH)

          val excerptBtn = RectF(cLeft + 6f, cTop + 4f, cLeft + 90f, cTop + cH - 4f)
          val copyBtn = RectF(cLeft + 94f, cTop + 4f, cLeft + 168f, cTop + cH - 4f)
          val hlBtn = RectF(cLeft + 172f, cTop + 4f, cLeft + 250f, cTop + cH - 4f)
          val closeBtn = RectF(cLeft + 254f, cTop + 4f, cLeft + cW - 4f, cTop + cH - 4f)

          activePdfSelection = NativePdfSelection(
            pageIndex = pInfo.pageNumber - 1,
            text = pInfo.text,
            highlightRects = listOf(highlightR),
            startHandle = RectF(hlLeft - 12f, hlTop - 20f, hlLeft + 12f, hlBottom),
            endHandle = RectF(hlRight - 12f, hlTop, hlRight + 12f, hlBottom + 20f),
            calloutRect = calloutR,
            calloutExcerptBtn = excerptBtn,
            calloutCopyBtn = copyBtn,
            calloutHighlightBtn = hlBtn,
            calloutCloseBtn = closeBtn
          )
          invalidate()
          return
        }
      }
    }

    // 3. Tapped outside -> Dismiss selection
    activePdfSelection = null
    activeCropSelection = null
    invalidate()
  }

  // ---------------------------------------------------------------------------
  // Real PDF Word Selection Engine matching Video
  // ---------------------------------------------------------------------------
  private fun updatePdfSelectionByIndex(pl: PdfPageLayout, startIdx: Int, endIdx: Int) {
    val words = pageWordsCache[pl.pageIndex] ?: return
    if (words.isEmpty()) return
    val clampedStart = startIdx.coerceIn(0, words.size - 1)
    val clampedEnd = endIdx.coerceIn(clampedStart, words.size - 1)
    val selWords = words.subList(clampedStart, clampedEnd + 1)
    val combinedText = selWords.joinToString(" ") { it.text }

    val rects = selWords.map { w ->
      val l = pl.boundsOnScreen.left + (w.bounds.left / pl.pageSize.width) * pl.boundsOnScreen.width()
      val t = pl.boundsOnScreen.top + (w.bounds.top / pl.pageSize.height) * pl.boundsOnScreen.height()
      val r = pl.boundsOnScreen.left + (w.bounds.right / pl.pageSize.width) * pl.boundsOnScreen.width()
      val b = pl.boundsOnScreen.top + (w.bounds.bottom / pl.pageSize.height) * pl.boundsOnScreen.height()
      RectF(l, t, r, b)
    }

    val firstR = rects.first()
    val lastR = rects.last()
    val startHandle = RectF(firstR.left - 12f * density, firstR.top - 20f * density, firstR.left + 12f * density, firstR.bottom)
    val endHandle = RectF(lastR.right - 12f * density, lastR.top, lastR.right + 12f * density, lastR.bottom + 20f * density)

    val charCount = combinedText.length
    val previewSnippet = if (combinedText.length > 22) combinedText.substring(0, 20) + "..." else combinedText
    val charCountText = "$charCount chars selected \"$previewSnippet\""

    val cW = 340f * density
    val cH = 74f * density
    val cLeft = ((rects.minOf { it.left } + rects.maxOf { it.right }) / 2f - cW / 2f).coerceIn(10f * density, width - cW - 10f * density)
    val cTop = if (firstR.top - cH - 16f * density > subheaderH) firstR.top - cH - 16f * density else (lastR.bottom + 16f * density).coerceAtMost(height * splitRatio - cH - 10f * density)
    val calloutR = RectF(cLeft, cTop, cLeft + cW, cTop + cH)

    val closeBtn = RectF(cLeft + cW - 34f * density, cTop + 4f * density, cLeft + cW - 6f * density, cTop + 30f * density)

    val row2Top = cTop + 34f * density
    val row2Bottom = cTop + cH - 6f * density
    val excerptBtn = RectF(cLeft + 8f * density, row2Top, cLeft + 86f * density, row2Bottom)
    val copyBtn = RectF(cLeft + 90f * density, row2Top, cLeft + 144f * density, row2Bottom)
    val hlBtn = RectF(cLeft + 148f * density, row2Top, cLeft + 224f * density, row2Bottom)
    val addWordLeftBtn = RectF(cLeft + 228f * density, row2Top, cLeft + 268f * density, row2Bottom)
    val addWordRightBtn = RectF(cLeft + 272f * density, row2Top, cLeft + 312f * density, row2Bottom)
    val selectAllBtn = RectF(cLeft + 316f * density, row2Top, cLeft + cW - 8f * density, row2Bottom)

    activePdfSelection = NativePdfSelection(
      pageIndex = pl.pageIndex,
      text = combinedText,
      highlightRects = rects,
      startHandle = startHandle,
      endHandle = endHandle,
      calloutRect = calloutR,
      calloutExcerptBtn = excerptBtn,
      calloutCopyBtn = copyBtn,
      calloutHighlightBtn = hlBtn,
      calloutCloseBtn = closeBtn,
      startWordIndex = clampedStart,
      endWordIndex = clampedEnd,
      calloutAddWordLeftBtn = addWordLeftBtn,
      calloutAddWordRightBtn = addWordRightBtn,
      calloutSelectAllBtn = selectAllBtn,
      charCountText = charCountText
    )
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
  private fun extractExcerptToCanvas(text: String, pageNumber: Int, color: Int) {
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

    cards.add(
      NativeCard(
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
        tableRows = null
      )
    )

    links.add(NativeLink("link-${System.currentTimeMillis()}", newId, color))
    triggerShockwave(resolved.x + cardW / 2f, resolved.y + cardH / 2f, color)
    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    dispatchExtractExcerptEvent(text, pageNumber, color, false)
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

    val card = NativeCard(
      id = newId,
      x = resolved.x,
      y = resolved.y,
      width = cardW,
      text = "[Figure Excerpt]",
      color = selectedColor,
      pageNumber = cropSel.pageIndex + 1,
      comment = null,
      clusterId = null,
      stackCount = 1,
      isImage = true,
      imageUrl = imgPath,
      isTable = false,
      tableRows = null
    )
    cards.add(card)

    // Reset docMode and dismiss crop selector so user can scroll immediately
    activeCropSelection = null
    docMode = "text"

    links.add(NativeLink("link-${System.currentTimeMillis()}", newId, selectedColor))
    triggerShockwave(resolved.x + cardW / 2f, resolved.y + cardH / 2f, selectedColor)
    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    dispatchExtractExcerptEvent(
      card.text,
      card.pageNumber,
      card.color,
      card.isImage,
      card.imageUrl,
      card.x,
      card.y,
      card.id
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

  private fun addAnnotation(text: String, pageNumber: Int, color: Int) {
    val annId = "ann-${System.currentTimeMillis()}"
    annotations.add(NativeAnnotation(annId, "page-$pageNumber", 0, pageNumber, color, text))
    invalidate()
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
      for (s in toRemove) {
        dispatchEraseStrokeEvent(s.id)
      }
      invalidate()
    }
  }

  private fun scrollToDocumentPage(targetPageNum: Int) {
    val pl = pageLayouts.find { it.pageNumber == targetPageNum } ?: return
    val targetScrollY = (docScrollY + pl.topY - 56f).coerceIn(0f, maxDocScrollY)

    val animator = ValueAnimator.ofFloat(docScrollY, targetScrollY).apply {
      duration = 380
      interpolator = DecelerateInterpolator()
      addUpdateListener {
        docScrollY = it.animatedValue as Float
        invalidate()
      }
      start()
    }

    pulsePageNumber = targetPageNum
    pulseAlpha = 220
    val pulseAnim = ValueAnimator.ofInt(220, 0).apply {
      duration = 1200
      addUpdateListener {
        pulseAlpha = it.animatedValue as Int
        invalidate()
      }
      start()
    }
  }

  // ---------------------------------------------------------------------------
  // React Native Fabric Event Dispatchers
  // ---------------------------------------------------------------------------
  private fun getEventDispatcher() = (context as? com.facebook.react.bridge.ReactContext)?.let {
    UIManagerHelper.getEventDispatcherForReactTag(it, id)
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
    cardId: String? = null
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
}
