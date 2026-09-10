package com.thinkspace

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import org.json.JSONArray
import org.json.JSONObject
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
  val calloutPrevWordBtn: RectF,
  val calloutNextWordBtn: RectF,
  val calloutCloseBtn: RectF
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
  val tableRows: List<NativeTableRow>?
) {
  fun getHeight(): Float {
    return when {
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
  var activeTool: String = "select"
  var selectedColor: Int = Color.parseColor("#00ADB5")
  var pattern: String = "looseleaf"
  var panX: Float = 0f
  var panY: Float = 0f
  var scaleFactor: Float = 1f

  // Document & Annotation Models
  private var activeDocument: NativeDoc? = null
  private val annotations = mutableListOf<NativeAnnotation>()
  private var docScrollY: Float = 0f
  private var maxDocScrollY: Float = 1000f

  // Canvas Collections
  private val strokes = mutableListOf<NativeStroke>()
  private val cards = mutableListOf<NativeCard>()
  private val links = mutableListOf<NativeLink>()

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

  // 1-finger canvas panning
  private var isPanningCanvas = false
  private var isDraggingDivider = false
  private var isScrollingDoc = false
  private var lastTouchScreenX = 0f
  private var lastTouchScreenY = 0f

  // LiquidText Cross-Zone Lift-and-Drag state
  private var isLiftingExcerpt = false
  private var liftCandidateText: String? = null
  private var liftCandidatePage: Int = 1
  private var liftCandidateColor: Int = Color.parseColor("#00ADB5")
  private var liftGhostX: Float = 0f
  private var liftGhostY: Float = 0f
  private var liftAnchorScreenX: Float = 0f
  private var liftAnchorScreenY: Float = 0f

  // Document Text Selection Engine state
  private val paragraphLayouts = mutableListOf<ParagraphLayoutInfo>()
  private var activeSelection: NativeDocumentSelection? = null
  private var isDraggingStartHandle = false
  private var isDraggingEndHandle = false
  private var isDirectDraggingSelection = false
  private var copiedToastText: String? = null

  // Drop shockwave ripple animation
  private var rippleOriginX = 0f
  private var rippleOriginY = 0f
  private var rippleColor = Color.parseColor("#00ADB5")
  private var rippleProgress = 1f
  private var rippleAnimator: ValueAnimator? = null

  // Context colors
  private val contextColors = intArrayOf(
    Color.parseColor("#00ADB5"),
    Color.parseColor("#F59E0B"),
    Color.parseColor("#EF4444"),
    Color.parseColor("#3B82F6"),
    Color.parseColor("#8B5CF6")
  )

  // Paints
  private val docBgPaint = Paint().apply { color = Color.parseColor("#0F172A") }
  private val docPageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
  private val docPageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#CBD5E1")
    strokeWidth = 1f
    style = Paint.Style.STROKE
  }
  private val canvasBgPaint = Paint().apply { color = Color.parseColor("#131922") }
  private val dividerLinePaint = Paint().apply {
    color = Color.parseColor("#334155")
    strokeWidth = 2f
  }
  private val dividerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1A202C")
    style = Paint.Style.FILL
  }
  private val dividerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }
  private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#2D3748")
    style = Paint.Style.FILL
  }
  private val gridPaint = Paint().apply {
    color = Color.parseColor("#1E293B")
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }
  private val looseleafBluePaint = Paint().apply {
    color = Color.parseColor("#1E293B")
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }
  private val looseleafRedPaint = Paint().apply {
    color = Color.parseColor("#4B1D24")
    strokeWidth = 2.5f
    style = Paint.Style.STROKE
  }
  private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val highlighterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
  }
  private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.FILL
  }
  private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#E2E8F0")
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }
  private val cardActiveRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    strokeWidth = 2.5f
    style = Paint.Style.STROKE
  }
  private val cardAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }
  private val stackUnderlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    alpha = 60
  }
  private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#E0F7FA")
    style = Paint.Style.FILL
  }
  private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#80DEEA")
    strokeWidth = 1f
    style = Paint.Style.STROKE
  }
  private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    textSize = 19f
    isFakeBoldText = true
  }
  private val docSubheaderBgPaint = Paint().apply {
    color = Color.parseColor("#131922")
    style = Paint.Style.FILL
  }
  private val docTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#F8FAFC")
    textSize = 22f
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
  private val squeezeBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B")
    style = Paint.Style.FILL
  }
  private val squeezeBtnBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#334155")
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }
  private val squeezeBtnTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00ADB5")
    textSize = 24f
    isFakeBoldText = true
  }
  private val highlightBgPaint = Paint().apply {
    style = Paint.Style.FILL
  }
  private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B")
    textSize = 23f
    textSkewX = -0.15f
  }
  private val commentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#64748B")
    textSize = 20f
    textSkewX = -0.2f
  }
  private val closeBtnTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#94A3B8")
    textSize = 20f
    isFakeBoldText = true
  }
  private val toolbarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#0F172A")
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

  // Selection & Floating Callout Menu Paints
  private val selectionFillPaint = Paint().apply {
    color = Color.parseColor("#5900ADB5") // rgba(0, 173, 181, 0.35)
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
  private val calloutBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1E293B")
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
    color = Color.parseColor("#0F172A")
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
  private val calloutBadgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#94A3B8")
    textSize = 15f
    isFakeBoldText = true
  }

  // Word and Selection Helpers
  private fun expandToWord(text: String, charIdx: Int): Pair<Int, Int> {
    if (text.isEmpty()) return Pair(0, 0)
    var s = charIdx.coerceIn(0, text.length)
    var e = s
    while (s > 0 && !Character.isWhitespace(text[s - 1])) {
      s--
    }
    while (e < text.length && !Character.isWhitespace(text[e])) {
      e++
    }
    if (s == e && s < text.length) e = min(text.length, s + 1)
    return Pair(s, e)
  }

  private fun expandStartWord(text: String, startIdx: Int): Int {
    var s = startIdx
    while (s > 0 && Character.isWhitespace(text[s - 1])) s--
    while (s > 0 && !Character.isWhitespace(text[s - 1])) s--
    return s
  }

  private fun expandEndWord(text: String, endIdx: Int): Int {
    var e = endIdx
    while (e < text.length && Character.isWhitespace(text[e])) e++
    while (e < text.length && !Character.isWhitespace(text[e])) e++
    return e
  }

  private fun buildSelection(
    info: ParagraphLayoutInfo,
    rawStart: Int,
    rawEnd: Int
  ): NativeDocumentSelection {
    val startChar = min(rawStart, rawEnd).coerceIn(0, info.text.length)
    val endChar = max(rawStart, rawEnd).coerceIn(0, info.text.length)
    val selectedText = if (startChar < endChar) info.text.substring(startChar, endChar) else ""
    val layout = info.layout

    val startLine = layout.getLineForOffset(startChar)
    val endLine = layout.getLineForOffset(max(startChar, endChar))

    val rects = mutableListOf<RectF>()
    for (line in startLine..endLine) {
      val lineStart = if (line == startLine) startChar else layout.getLineStart(line)
      val lineEnd = if (line == endLine) endChar else layout.getLineEnd(line)
      if (lineEnd <= lineStart) continue

      val x1 = layout.getPrimaryHorizontal(lineStart)
      val x2 = layout.getPrimaryHorizontal(lineEnd)
      val left = info.paperX + 28f + min(x1, x2)
      val right = info.paperX + 28f + max(x1, x2)
      val top = info.topY + layout.getLineTop(line).toFloat()
      val bottom = info.topY + layout.getLineBottom(line).toFloat()
      rects.add(RectF(left, top, right, bottom))
    }

    if (rects.isEmpty()) {
      val top = info.topY + layout.getLineTop(startLine).toFloat()
      val bottom = info.topY + layout.getLineBottom(startLine).toFloat()
      val left = info.paperX + 28f + layout.getPrimaryHorizontal(startChar)
      rects.add(RectF(left, top, left + 20f, bottom))
    }

    val first = rects.first()
    val last = rects.last()
    val startHandle = RectF(first.left - 24f, first.top - 24f, first.left + 24f, first.bottom + 12f)
    val endHandle = RectF(last.right - 24f, last.top - 12f, last.right + 24f, last.bottom + 24f)

    val calloutW = 390f
    val calloutH = 38f
    var calloutTop = first.top - calloutH - 14f
    if (calloutTop < 44f) {
      calloutTop = last.bottom + 14f
    }
    val midX = (first.left + last.right) / 2f
    val calloutLeft = (midX - calloutW / 2f).coerceIn(10f, max(10f, width.toFloat() - calloutW - 10f))
    val calloutRect = RectF(calloutLeft, calloutTop, calloutLeft + calloutW, calloutTop + calloutH)

    val excerptBtn = RectF(calloutLeft + 8f, calloutTop + 4f, calloutLeft + 106f, calloutTop + calloutH - 4f)
    val copyBtn = RectF(calloutLeft + 112f, calloutTop + 4f, calloutLeft + 188f, calloutTop + calloutH - 4f)
    val hlBtn = RectF(calloutLeft + 194f, calloutTop + 4f, calloutLeft + 282f, calloutTop + calloutH - 4f)
    val prevWBtn = RectF(calloutLeft + 288f, calloutTop + 4f, calloutLeft + 318f, calloutTop + calloutH - 4f)
    val nextWBtn = RectF(calloutLeft + 322f, calloutTop + 4f, calloutLeft + 352f, calloutTop + calloutH - 4f)
    val closeBtn = RectF(calloutLeft + 356f, calloutTop + 4f, calloutLeft + 384f, calloutTop + calloutH - 4f)

    return NativeDocumentSelection(
      text = selectedText,
      pageNumber = info.pageNumber,
      sectionId = info.secId,
      pIdx = info.pIdx,
      startCharIdx = startChar,
      endCharIdx = endChar,
      highlightRects = rects,
      startHandle = startHandle,
      endHandle = endHandle,
      calloutRect = calloutRect,
      calloutExcerptBtn = excerptBtn,
      calloutCopyBtn = copyBtn,
      calloutHighlightBtn = hlBtn,
      calloutPrevWordBtn = prevWBtn,
      calloutNextWordBtn = nextWBtn,
      calloutCloseBtn = closeBtn
    )
  }

  private fun copyToClipboard(text: String) {
    try {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      val clip = ClipData.newPlainText("ThinkSpace Excerpt", text)
      clipboard?.setPrimaryClip(clip)
      copiedToastText = "✓ Copied!"
      postDelayed({
        copiedToastText = null
        invalidate()
      }, 1500)
      invalidate()
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  // Scale gesture detector for canvas pinch-to-zoom
  private val scaleGestureDetector = ScaleGestureDetector(
    context ?: throw IllegalStateException(),
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScale(detector: ScaleGestureDetector): Boolean {
        val prevScale = scaleFactor
        scaleFactor = max(0.12f, min(scaleFactor * detector.scaleFactor, 5.0f))
        val scaleRatio = scaleFactor / prevScale

        val focusX = detector.focusX
        val focusY = detector.focusY - (height * splitRatio)

        panX = focusX - (focusX - panX) * scaleRatio
        panY = focusY - (focusY - panY) * scaleRatio

        dispatchTransformEvent()
        invalidate()
        return true
      }
    }
  )

  init {
    setLayerType(LAYER_TYPE_HARDWARE, null)
  }

  // Chaikin smoothing
  private fun chaikinSmooth(pts: List<NativePoint>, iterations: Int = 1): List<NativePoint> {
    if (pts.size <= 2) return pts
    var current = pts
    for (it in 0 until iterations) {
      val next = mutableListOf<NativePoint>()
      next.add(current.first())
      for (i in 0 until current.size - 1) {
        val p0 = current[i]
        val p1 = current[i + 1]
        next.add(NativePoint(0.75f * p0.x + 0.25f * p1.x, 0.75f * p0.y + 0.25f * p1.y))
        next.add(NativePoint(0.25f * p0.x + 0.75f * p1.x, 0.25f * p0.y + 0.75f * p1.y))
      }
      next.add(current.last())
      current = next
    }
    return current
  }

  private fun pointsToSmoothPath(rawPoints: List<NativePoint>, smooth: Boolean = true): Path {
    val path = Path()
    if (rawPoints.isEmpty()) return path
    val pts = if (smooth && rawPoints.size > 2) chaikinSmooth(rawPoints, 1) else rawPoints
    val p0 = pts[0]
    path.moveTo(p0.x, p0.y)

    for (i in 1 until pts.size - 1) {
      val xc = (pts[i].x + pts[i + 1].x) / 2f
      val yc = (pts[i].y + pts[i + 1].y) / 2f
      path.quadTo(pts[i].x, pts[i].y, xc, yc)
    }
    if (pts.size > 1) {
      val last = pts.last()
      path.lineTo(last.x, last.y)
    }
    return path
  }

  // Setters for Document, Annotations, Cards, Strokes, Links
  fun setDocumentFromJson(json: String?) {
    if (json.isNullOrEmpty()) {
      activeDocument = null
      invalidate()
      return
    }
    try {
      val obj = JSONObject(json)
      val id = obj.optString("id", "doc-1")
      val title = obj.optString("title", "Document")
      val pageCount = obj.optInt("pageCount", 1)

      val sectionsArr = obj.optJSONArray("sections")
      val secList = mutableListOf<NativeSection>()
      if (sectionsArr != null) {
        for (i in 0 until sectionsArr.length()) {
          val sObj = sectionsArr.getJSONObject(i)
          val sId = sObj.optString("id", "sec-$i")
          val pageNumber = sObj.optInt("pageNumber", i + 1)
          val heading = sObj.optString("heading", "")

          val pArr = sObj.optJSONArray("paragraphs")
          val pList = mutableListOf<String>()
          if (pArr != null) {
            for (j in 0 until pArr.length()) {
              pList.add(pArr.getString(j))
            }
          }

          val tArr = sObj.optJSONArray("tables")
          val tList = mutableListOf<NativeTable>()
          if (tArr != null) {
            for (t in 0 until tArr.length()) {
              val tableObj = tArr.getJSONObject(t)
              val rowsArr = tableObj.optJSONArray("rows")
              val rowsList = mutableListOf<NativeTableRow>()
              if (rowsArr != null) {
                for (r in 0 until rowsArr.length()) {
                  val rowObj = rowsArr.getJSONObject(r)
                  val cellsArr = rowObj.optJSONArray("cells")
                  val cellsList = mutableListOf<String>()
                  if (cellsArr != null) {
                    for (c in 0 until cellsArr.length()) {
                      cellsList.add(cellsArr.getString(c))
                    }
                  }
                  rowsList.add(NativeTableRow(cellsList))
                }
              }
              tList.add(NativeTable(rowsList))
            }
          }

          val imageUrl = if (sObj.has("imageUrl") && !sObj.isNull("imageUrl")) sObj.getString("imageUrl") else null
          secList.add(NativeSection(sId, pageNumber, heading, pList, if (tList.isNotEmpty()) tList else null, imageUrl))
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
    cards.clear()
    if (json.isNullOrEmpty()) {
      invalidate()
      return
    }
    try {
      val arr = JSONArray(json)
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "card-$i")
        val x = obj.optDouble("x", 0.0).toFloat()
        val y = obj.optDouble("y", 0.0).toFloat()
        val width = obj.optDouble("width", 220.0).toFloat()
        val text = obj.optString("text", "")
        val colorHex = obj.optString("color", "#00ADB5")
        val color = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.WHITE }
        val pageNumber = obj.optInt("pageNumber", 1)
        val comment = if (obj.has("comment") && !obj.isNull("comment")) obj.getString("comment") else null
        val clusterId = if (obj.has("clusterId") && !obj.isNull("clusterId")) obj.getString("clusterId") else null
        val stackCount = obj.optInt("stackCount", 1)
        val isImage = obj.optBoolean("isImage", false)
        val imageUrl = if (obj.has("imageUrl") && !obj.isNull("imageUrl")) obj.getString("imageUrl") else null
        val isTable = obj.optBoolean("isTable", false)

        val tableRows = mutableListOf<NativeTableRow>()
        val tableDataObj = obj.optJSONObject("tableData")
        if (tableDataObj != null) {
          val rowsArr = tableDataObj.optJSONArray("rows")
          if (rowsArr != null) {
            for (r in 0 until rowsArr.length()) {
              val rowObj = rowsArr.getJSONObject(r)
              val cellsArr = rowObj.optJSONArray("cells")
              val cellList = mutableListOf<String>()
              if (cellsArr != null) {
                for (c in 0 until cellsArr.length()) {
                  cellList.add(cellsArr.getString(c))
                }
              }
              tableRows.add(NativeTableRow(cellList))
            }
          }
        }

        cards.add(NativeCard(
          id, x, y, width, text, color, pageNumber, comment, clusterId, stackCount,
          isImage, imageUrl, isTable, if (tableRows.isNotEmpty()) tableRows else null
        ))
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
    invalidate()
  }

  fun setLinksFromJson(json: String?) {
    links.clear()
    if (json.isNullOrEmpty()) {
      invalidate()
      return
    }
    try {
      val arr = JSONArray(json)
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

  // ---------------------------------------------------------------------------
  // Master OnDraw
  // ---------------------------------------------------------------------------
  @SuppressLint("DrawAllocation")
  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    val viewW = width.toFloat()
    val viewH = height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return

    val hasDoc = activeDocument != null
    val splitY = if (hasDoc) viewH * splitRatio else 0f
    val docBottomY = max(0f, splitY - 12f)
    val canvasTopY = if (hasDoc) splitY + 12f else 0f

    // =========================================================================
    // 1. TOP ZONE: Native Document Viewer
    // =========================================================================
    if (hasDoc && docBottomY > 10f) {
      canvas.save()
      canvas.clipRect(0f, 0f, viewW, docBottomY)
      canvas.drawRect(0f, 0f, viewW, docBottomY, docBgPaint)

      val doc = activeDocument!!

      // Top Document Subheader Bar (LiquidText Document Bar)
      val subheaderH = 42f
      val subheaderRect = RectF(0f, 0f, viewW, subheaderH)
      canvas.drawRect(subheaderRect, docSubheaderBgPaint)

      // Left: Document Dropdown Pill
      val docTitlePill = if (doc.title.length > 24) doc.title.substring(0, 22) + "... ▾" else "${doc.title} ▾"
      val titlePillW = badgeTextPaint.measureText(docTitlePill) + 24f
      val titlePillRect = RectF(14f, 7f, 14f + titlePillW, 35f)
      canvas.drawRoundRect(titlePillRect, 14f, 14f, dividerHandlePaint)
      canvas.drawRoundRect(titlePillRect, 14f, 14f, dividerBorderPaint)
      canvas.drawText(docTitlePill, 24f, 26f, badgeTextPaint)

      // Center/Right: Page Indicator
      val pageInd = "p. 7/${doc.pageCount}"
      canvas.drawText(pageInd, 14f + titlePillW + 16f, 26f, docTitlePaint)

      // Crop Button
      val cropRect = RectF(viewW - 140f, 7f, viewW - 68f, 35f)
      canvas.drawRoundRect(cropRect, 14f, 14f, dividerHandlePaint)
      canvas.drawRoundRect(cropRect, 14f, 14f, dividerBorderPaint)
      canvas.drawText("✂️ Crop", viewW - 132f, 26f, badgeTextPaint)

      // Zoom Buttons
      canvas.drawText("1:1", viewW - 48f, 26f, commentPaint)

      // Document Content Container (Centered White Paper Book Page)
      val paperMargin = 16f
      val paperW = min(viewW - paperMargin * 2, 640f)
      val paperX = (viewW - paperW) / 2f
      var curY = subheaderH + 16f - docScrollY
      paragraphLayouts.clear()

      for (sec in doc.sections) {
        val secAnns = annotations.filter { it.sectionId == sec.id }
        val hasAnn = secAnns.isNotEmpty()

        if (isSqueezed && !hasAnn && sec.tables == null && sec.imageUrl == null) {
          // Accordion Folded ribbon
          val foldRect = RectF(paperX, curY, paperX + paperW, curY + 28f)
          canvas.drawRoundRect(foldRect, 6f, 6f, dividerHandlePaint)
          canvas.drawText("── Page ${sec.pageNumber}: ${sec.heading} (Folded) ──", paperX + 16f, curY + 18f, commentPaint)
          curY += 34f
          continue
        }

        // Paper Page Background
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
        canvas.drawRoundRect(pageCardRect, 10f, 10f, docPageBgPaint)
        canvas.drawRoundRect(pageCardRect, 10f, 10f, docPageBorderPaint)

        // Book Chapter / Heading (e.g. "PREFACE")
        val headingText = sec.heading.uppercase()
        val headingW = docHeadingPaint.measureText(headingText)
        val headingX = paperX + (paperW - headingW) / 2f
        canvas.drawText(headingText, headingX, curY + 36f, docHeadingPaint)
        curY += 56f

        // Paragraphs
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

            highlightBgPaint.alpha = 255
            val leftBar = RectF(paperX + 16f, curY - 4f, paperX + 21f, curY + paraH)
            canvas.drawRoundRect(leftBar, 2f, 2f, highlightBgPaint)
          }

          canvas.save()
          canvas.translate(paperX + 28f, curY)
          staticLayout.draw(canvas)
          canvas.restore()

          curY += paraH + 12f
        }

        curY += 24f
      }

      // Draw Active Text Selection (Teal highlights, handle pins, callout menu)
      if (activeSelection != null) {
        val sel = activeSelection!!

        // 1. Teal highlight rectangles
        for (r in sel.highlightRects) {
          canvas.drawRoundRect(r, 4f, 4f, selectionFillPaint)
          canvas.drawLine(r.left, r.bottom, r.right, r.bottom, selectionBorderPaint)
        }

        // 2. Start Handle Pin (Vertical bar + top teardrop pin)
        if (sel.highlightRects.isNotEmpty()) {
          val firstR = sel.highlightRects.first()
          canvas.drawLine(firstR.left, firstR.top - 6f, firstR.left, firstR.bottom, selectionHandleBarPaint)
          canvas.drawCircle(firstR.left, firstR.top - 8f, 7.5f, selectionHandlePinPaint)
        }

        // 3. End Handle Pin (Vertical bar + bottom teardrop pin)
        if (sel.highlightRects.isNotEmpty()) {
          val lastR = sel.highlightRects.last()
          canvas.drawLine(lastR.right, lastR.top, lastR.right, lastR.bottom + 6f, selectionHandleBarPaint)
          canvas.drawCircle(lastR.right, lastR.bottom + 8f, 7.5f, selectionHandlePinPaint)
        }

        // 4. Floating Selection Callout Menu Dock
        val cRect = sel.calloutRect
        canvas.drawRoundRect(cRect, 10f, 10f, calloutBgPaint)
        canvas.drawRoundRect(cRect, 10f, 10f, calloutBorderPaint)

        // [+ Excerpt] Button
        canvas.drawRoundRect(sel.calloutExcerptBtn, 6f, 6f, calloutPrimaryBtnPaint)
        canvas.drawText("+ Excerpt", sel.calloutExcerptBtn.left + 14f, sel.calloutExcerptBtn.centerY() + 6f, calloutTextPaint)

        // [📋 Copy] Button
        canvas.drawRoundRect(sel.calloutCopyBtn, 6f, 6f, calloutSecondaryBtnPaint)
        val copyLabel = copiedToastText ?: "📋 Copy"
        canvas.drawText(copyLabel, sel.calloutCopyBtn.left + 10f, sel.calloutCopyBtn.centerY() + 6f, calloutSecondaryTextPaint)

        // [🖍️ Highlight] Button
        canvas.drawRoundRect(sel.calloutHighlightBtn, 6f, 6f, calloutHighlightBtnPaint)
        canvas.drawText("🖍️ Highlight", sel.calloutHighlightBtn.left + 8f, sel.calloutHighlightBtn.centerY() + 6f, calloutTextPaint)

        // [◀] & [▶] Word Expander Buttons
        canvas.drawRoundRect(sel.calloutPrevWordBtn, 5f, 5f, calloutSecondaryBtnPaint)
        canvas.drawText("◀", sel.calloutPrevWordBtn.left + 8f, sel.calloutPrevWordBtn.centerY() + 6f, calloutTextPaint)

        canvas.drawRoundRect(sel.calloutNextWordBtn, 5f, 5f, calloutSecondaryBtnPaint)
        canvas.drawText("▶", sel.calloutNextWordBtn.left + 8f, sel.calloutNextWordBtn.centerY() + 6f, calloutTextPaint)

        // [✕] Dismiss Button
        canvas.drawText("✕", sel.calloutCloseBtn.left + 8f, sel.calloutCloseBtn.centerY() + 6f, closeBtnTextPaint)
      }

      // Floating Accordion Squeeze Button on right margin
      val sqW = 34f
      val sqH = 46f
      val sqY = max(subheaderH + 10f, min(docBottomY - sqH - 10f, docBottomY / 2f - sqH / 2f))
      val sqRect = RectF(viewW - sqW - 8f, sqY, viewW - 8f, sqY + sqH)
      canvas.drawRoundRect(sqRect, 16f, 16f, squeezeBtnBgPaint)
      canvas.drawRoundRect(sqRect, 16f, 16f, if (isSqueezed) dividerBorderPaint else squeezeBtnBorderPaint)
      canvas.drawText("≈", viewW - sqW + 2f, sqY + 31f, squeezeBtnTextPaint)

      canvas.restore()
    }

    // =========================================================================
    // 2. MIDDLE ZONE: Draggable Split Divider
    // =========================================================================
    if (hasDoc) {
      canvas.drawLine(0f, splitY, viewW, splitY, dividerLinePaint)

      // Centered Rounded Grip Handle
      val handleW = 76f
      val handleH = 20f
      val handleRect = RectF(
        (viewW - handleW) / 2f,
        splitY - handleH / 2f,
        (viewW + handleW) / 2f,
        splitY + handleH / 2f
      )
      canvas.drawRoundRect(handleRect, 10f, 10f, dividerHandlePaint)
      canvas.drawRoundRect(handleRect, 10f, 10f, dividerBorderPaint)

      // 3 Horizontal Grip lines
      val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00ADB5")
        strokeWidth = 2f
      }
      val hMidX = viewW / 2f
      canvas.drawLine(hMidX - 16f, splitY - 4f, hMidX + 16f, splitY - 4f, gripPaint)
      canvas.drawLine(hMidX - 16f, splitY, hMidX + 16f, splitY, gripPaint)
      canvas.drawLine(hMidX - 16f, splitY + 4f, hMidX + 16f, splitY + 4f, gripPaint)
    }

    // =========================================================================
    // 3. BOTTOM ZONE: Infinite Thinking Canvas
    // =========================================================================
    canvas.save()
    canvas.clipRect(0f, canvasTopY, viewW, viewH)
    canvas.drawRect(0f, canvasTopY, viewW, viewH, canvasBgPaint)

    // Canvas Background Pattern
    when (pattern) {
      "looseleaf" -> {
        val lineSpacing = 38f * scaleFactor
        val startY = canvasTopY + (((panY % lineSpacing) + lineSpacing) % lineSpacing)
        var y = startY
        while (y < viewH) {
          canvas.drawLine(0f, y, viewW, y, looseleafBluePaint)
          y += lineSpacing
        }
        val redLineX = 70f * scaleFactor + panX
        if (redLineX in 0f..viewW) {
          canvas.drawLine(redLineX, canvasTopY, redLineX, viewH, looseleafRedPaint)
        }
      }
      "dots" -> {
        val spacing = 36f * scaleFactor
        val startX = ((panX % spacing) + spacing) % spacing
        val startY = canvasTopY + (((panY % spacing) + spacing) % spacing)
        var x = startX
        while (x < viewW) {
          var y = startY
          while (y < viewH) {
            canvas.drawCircle(x, y, 2.2f, dotPaint)
            y += spacing
          }
          x += spacing
        }
      }
      "grid" -> {
        val spacing = 48f * scaleFactor
        val startX = ((panX % spacing) + spacing) % spacing
        val startY = canvasTopY + (((panY % spacing) + spacing) % spacing)
        var x = startX
        while (x < viewW) {
          canvas.drawLine(x, canvasTopY, x, viewH, gridPaint)
          x += spacing
        }
        var y = startY
        while (y < viewH) {
          canvas.drawLine(0f, y, viewW, y, gridPaint)
          y += spacing
        }
      }
    }

    // Canvas matrix transformation
    canvas.save()
    canvas.translate(panX, canvasTopY + panY)
    canvas.scale(scaleFactor, scaleFactor)

    // Tether Cords (Dashed lines connecting cards to document margin)
    for (link in links) {
      val card = cards.find { it.id == link.sourceExcerptId } ?: continue
      val isHeld = heldCardId == card.id
      val cardAnchorX = card.x + 14f
      val cardAnchorY = card.y + 20f

      val leftWorldX = (-panX - 40f) / scaleFactor
      val startX = min(leftWorldX, cardAnchorX - 80f)
      val startY = cardAnchorY - 40f

      val linkPath = Path()
      linkPath.moveTo(startX, startY)
      val spanX = max(30f, cardAnchorX - startX)
      linkPath.cubicTo(
        startX + spanX * 0.4f, startY + 20f,
        startX + spanX * 0.7f, cardAnchorY - 15f,
        cardAnchorX, cardAnchorY
      )

      linkGlowPaint.color = link.color
      linkGlowPaint.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
      canvas.drawPath(linkPath, linkGlowPaint)

      linkPaint.color = link.color
      linkPaint.strokeWidth = if (isHeld) 3.5f else 2.5f
      linkPaint.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
      canvas.drawPath(linkPath, linkPaint)

      pinPaint.color = link.color
      canvas.drawCircle(cardAnchorX, cardAnchorY, if (isHeld) 7f else 5.5f, pinPaint)
      pinPaint.color = Color.WHITE
      canvas.drawCircle(cardAnchorX, cardAnchorY, if (isHeld) 3f else 2f, pinPaint)
    }

    // Stored Inking Strokes
    for (stroke in strokes) {
      if (stroke.points.size < 2) continue
      val path = pointsToSmoothPath(stroke.points, true)

      if (stroke.isHighlighter) {
        highlighterPaint.color = stroke.color
        highlighterPaint.alpha = 95
        highlighterPaint.strokeWidth = stroke.strokeWidth
        canvas.drawPath(path, highlighterPaint)
      } else {
        strokePaint.color = stroke.color
        strokePaint.alpha = 245
        strokePaint.strokeWidth = stroke.strokeWidth
        canvas.drawPath(path, strokePaint)
      }
    }

    // Active Live Drawing Stroke
    if (activePoints.size > 1) {
      val isHighlighter = activeTool == "highlighter"
      if (isHighlighter) {
        highlighterPaint.color = selectedColor
        highlighterPaint.alpha = 95
        highlighterPaint.strokeWidth = 14f
        canvas.drawPath(activePath, highlighterPaint)
      } else {
        strokePaint.color = selectedColor
        strokePaint.alpha = 245
        strokePaint.strokeWidth = 3.5f
        canvas.drawPath(activePath, strokePaint)
      }
    }

    // Landing Shockwave Ripple
    if (rippleProgress < 1f) {
      val outerR = 10f + rippleProgress * 150f
      val alpha = ((1f - rippleProgress) * 240).toInt().coerceIn(0, 255)
      ripplePaint.color = rippleColor
      ripplePaint.alpha = alpha
      ripplePaint.strokeWidth = 3.5f
      canvas.drawCircle(rippleOriginX, rippleOriginY, outerR, ripplePaint)

      ripplePaint.alpha = (alpha * 0.5f).toInt()
      ripplePaint.strokeWidth = 6f
      canvas.drawCircle(rippleOriginX, rippleOriginY, outerR * 0.65f, ripplePaint)
    }

    // Excerpt Cards (White background matching ThinkSpace ExcerptCard)
    for (card in cards) {
      val cardW = card.width
      val cardH = card.getHeight()
      val isSelected = selectedCardId == card.id
      val isStacked = card.stackCount > 1

      // Stack underlay
      if (isStacked) {
        stackUnderlayPaint.color = card.color
        val underlayRect = RectF(card.x + 6f, card.y + 6f, card.x + cardW + 6f, card.y + cardH + 6f)
        canvas.drawRoundRect(underlayRect, 14f, 14f, stackUnderlayPaint)
      }

      // Card body
      val cardRect = RectF(card.x, card.y, card.x + cardW, card.y + cardH)
      canvas.drawRoundRect(cardRect, 14f, 14f, cardBgPaint)
      canvas.drawRoundRect(cardRect, 14f, 14f, if (isSelected) cardActiveRingPaint else cardBorderPaint)

      // Left Accent Pill
      cardAccentPaint.color = card.color
      val leftAccentRect = RectF(card.x, card.y + 8f, card.x + 4.5f, card.y + cardH - 8f)
      canvas.drawRoundRect(leftAccentRect, 2f, 2f, cardAccentPaint)

      // Accent color dot
      canvas.drawCircle(card.x + 16f, card.y + 19f, 4.5f, cardAccentPaint)

      // Header Page Badge Pill (Light cyan capsule)
      val badgeIcon = "🔗 p. ${card.pageNumber}"
      val badgeW = badgeTextPaint.measureText(badgeIcon) + 16f
      val badgeRect = RectF(card.x + 25f, card.y + 8f, card.x + 25f + badgeW, card.y + 30f)
      canvas.drawRoundRect(badgeRect, 6f, 6f, badgeBgPaint)
      canvas.drawRoundRect(badgeRect, 6f, 6f, badgeBorderPaint)
      canvas.drawText(badgeIcon, card.x + 33f, card.y + 24f, badgeTextPaint)

      // Close Button
      canvas.drawText("✕", card.x + cardW - 22f, card.y + 24f, closeBtnTextPaint)

      // Body text in double quotes
      canvas.save()
      canvas.translate(card.x + 16f, card.y + 42f)
      val textWidth = max(20, (cardW - 32f).toInt())
      val previewText = "\"${card.text}\""
      val staticLayout = StaticLayout.Builder
        .obtain(previewText, 0, previewText.length, textPaint, textWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1.15f)
        .setMaxLines(4)
        .build()
      staticLayout.draw(canvas)
      canvas.restore()

      // Context Toolbar
      if (isSelected) {
        val tbW = 210f
        val tbH = 36f
        val tbX = card.x + (cardW - tbW) / 2f
        val tbY = card.y - 44f
        val tbRect = RectF(tbX, tbY, tbX + tbW, tbY + tbH)

        canvas.drawRoundRect(tbRect, 18f, 18f, toolbarBgPaint)
        canvas.drawRoundRect(tbRect, 18f, 18f, toolbarBorderPaint)

        var dotX = tbX + 16f
        for (c in contextColors) {
          cardAccentPaint.color = c
          canvas.drawCircle(dotX, tbY + 18f, 9f, cardAccentPaint)
          if (card.color == c) {
            canvas.drawCircle(dotX, tbY + 18f, 3.5f, canvasBgPaint)
          }
          dotX += 38f
        }
        canvas.drawText("✕", tbX + tbW - 24f, tbY + 25f, closeBtnTextPaint)
      }
    }

    canvas.restore()
    canvas.restore()

    // =========================================================================
    // 4. FLOATING 3D CROSS-ZONE LIFT-AND-DRAG CARD (LiquidText interaction)
    // =========================================================================
    if (isLiftingExcerpt && liftCandidateText != null) {
      val isOverCanvas = liftGhostY >= canvasTopY - 20f
      val themeColor = if (isOverCanvas) Color.parseColor("#10B981") else Color.parseColor("#00ADB5")

      // 1. Live Elastic Spring Tether Cord
      val tetherPath = Path()
      val startX = if (liftAnchorScreenX > 0f) liftAnchorScreenX else 40f
      tetherPath.moveTo(startX, liftAnchorScreenY)
      val midX = (startX + liftGhostX) / 2f
      val midY = (liftAnchorScreenY + liftGhostY) / 2f + 35f
      tetherPath.quadTo(midX, midY, liftGhostX, liftGhostY)

      // Outer glow halo
      linkGlowPaint.color = themeColor
      linkGlowPaint.strokeWidth = 9f
      linkGlowPaint.alpha = 65
      canvas.drawPath(tetherPath, linkGlowPaint)

      // Dashed main cord
      linkPaint.color = themeColor
      linkPaint.strokeWidth = 3.5f
      canvas.drawPath(tetherPath, linkPaint)

      // 2. 3D Floating Lifted Excerpt Card Tile
      val ghostW = 230f
      val ghostH = 92f
      val ghostRect = RectF(-ghostW / 2f, -ghostH / 2f, ghostW / 2f, ghostH / 2f)

      canvas.save()
      canvas.translate(liftGhostX, liftGhostY)
      canvas.rotate(-2.5f)
      canvas.scale(1.08f, 1.08f)

      // Elevated shadow and card background
      canvas.drawRoundRect(ghostRect, 12f, 12f, cardBgPaint)
      cardBorderPaint.color = themeColor
      cardBorderPaint.strokeWidth = 2.5f
      canvas.drawRoundRect(ghostRect, 12f, 12f, cardBorderPaint)

      // Left Accent Pill
      cardAccentPaint.color = themeColor
      val leftBar = RectF(ghostRect.left, ghostRect.top + 8f, ghostRect.left + 5f, ghostRect.bottom - 8f)
      canvas.drawRoundRect(leftBar, 2f, 2f, cardAccentPaint)

      // Status Indicator Dot
      val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        style = Paint.Style.FILL
      }
      canvas.drawCircle(ghostRect.left + 18f, ghostRect.top + 20f, 5f, statusDotPaint)

      // Status Header Banner
      val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        textSize = 15f
        isFakeBoldText = true
      }
      val headerText = if (isOverCanvas) "🎯 RELEASE TO DROP ON CANVAS" else "✨ DRAGGING EXCERPT"
      canvas.drawText(headerText, ghostRect.left + 28f, ghostRect.top + 25f, headerPaint)

      // Excerpt preview quote
      val previewPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        textSize = 18f
        typeface = Typeface.SERIF
        textSkewX = -0.15f
      }
      val preview = if (liftCandidateText!!.length > 34) liftCandidateText!!.substring(0, 31) + "..." else liftCandidateText!!
      canvas.drawText("\"$preview\"", ghostRect.left + 16f, ghostRect.top + 52f, previewPaint)

      // Page Pill Badge
      val badgeRect = RectF(ghostRect.left + 16f, ghostRect.top + 64f, ghostRect.left + 100f, ghostRect.top + 84f)
      canvas.drawRoundRect(badgeRect, 6f, 6f, badgeBgPaint)
      canvas.drawRoundRect(badgeRect, 6f, 6f, badgeBorderPaint)
      canvas.drawText("🔗 Page $liftCandidatePage", ghostRect.left + 22f, ghostRect.top + 78f, badgeTextPaint)

      canvas.restore()
    }
  }

  // ---------------------------------------------------------------------------
  // Touch Handling & Unified Gesture Arbitration
  // ---------------------------------------------------------------------------
  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    val viewH = height.toFloat()
    val hasDoc = activeDocument != null
    val splitY = if (hasDoc) viewH * splitRatio else 0f
    val canvasTopY = if (hasDoc) splitY + 12f else 0f

    val sx = event.x
    val sy = event.y

    val inDivider = hasDoc && (sy in (splitY - 20f)..(splitY + 20f))
    val inDocZone = hasDoc && (sy < splitY - 12f)
    val inCanvasZone = sy >= canvasTopY

    // 1. Two or more fingers in Canvas Zone -> Canvas Pan & Zoom
    if (inCanvasZone && event.pointerCount >= 2) {
      scaleGestureDetector.onTouchEvent(event)
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
      return true
    }

    // 2. Single Touch Gestures
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastTouchScreenX = sx
        lastTouchScreenY = sy
        totalDragDistance = 0f

        if (inDivider) {
          isDraggingDivider = true
          return true
        }

        if (inDocZone) {
          // 1. Check if user clicked inside activeSelection's floating callout buttons
          val sel = activeSelection
          if (sel != null && sel.calloutRect.contains(sx, sy)) {
            if (sel.calloutExcerptBtn.contains(sx, sy)) {
              // Immediately extract to canvas workspace
              val newId = "card-${System.currentTimeMillis()}"
              val splitMidY = splitY + 70f
              val (worldX, worldY) = canvasScreenToWorld(width / 2f, splitMidY, canvasTopY)
              val newCard = NativeCard(
                newId,
                max(20f, worldX - 110f),
                max(20f, worldY - 50f),
                220f,
                sel.text,
                Color.parseColor("#00ADB5"),
                sel.pageNumber,
                "Excerpt from p. ${sel.pageNumber}",
                null,
                1,
                false,
                null,
                false,
                null
              )
              cards.add(newCard)
              links.add(NativeLink("link-${System.currentTimeMillis()}", newId, Color.parseColor("#00ADB5")))
              triggerShockwave(newCard.x + newCard.width / 2f, newCard.y + 50f, newCard.color)
              dispatchExtractExcerptEvent(sel.text, sel.pageNumber, "#00ADB5", false, false)
              activeSelection = null
              invalidate()
              return true
            }

            if (sel.calloutCopyBtn.contains(sx, sy)) {
              copyToClipboard(sel.text)
              return true
            }

            if (sel.calloutHighlightBtn.contains(sx, sy)) {
              annotations.add(
                NativeAnnotation(
                  id = "ann-${System.currentTimeMillis()}",
                  sectionId = sel.sectionId,
                  paragraphIndex = sel.pIdx,
                  pageNumber = sel.pageNumber,
                  color = Color.parseColor("#F59E0B"),
                  text = sel.text
                )
              )
              activeSelection = null
              invalidate()
              return true
            }

            if (sel.calloutPrevWordBtn.contains(sx, sy)) {
              val info = paragraphLayouts.find { it.secId == sel.sectionId && it.pIdx == sel.pIdx }
              if (info != null) {
                val newStart = expandStartWord(info.text, sel.startCharIdx)
                activeSelection = buildSelection(info, newStart, sel.endCharIdx)
                invalidate()
              }
              return true
            }

            if (sel.calloutNextWordBtn.contains(sx, sy)) {
              val info = paragraphLayouts.find { it.secId == sel.sectionId && it.pIdx == sel.pIdx }
              if (info != null) {
                val newEnd = expandEndWord(info.text, sel.endCharIdx)
                activeSelection = buildSelection(info, sel.startCharIdx, newEnd)
                invalidate()
              }
              return true
            }

            if (sel.calloutCloseBtn.contains(sx, sy)) {
              activeSelection = null
              invalidate()
              return true
            }
            return true
          }

          // 2. Check if user grabbed the Start Handle pin
          if (sel != null && sel.startHandle.contains(sx, sy)) {
            isDraggingStartHandle = true
            return true
          }

          // 3. Check if user grabbed the End Handle pin
          if (sel != null && sel.endHandle.contains(sx, sy)) {
            isDraggingEndHandle = true
            return true
          }

          // 4. Check if user touched directly ON the active selection highlight rects
          // (Triggers direct 3D Lift & Drag to canvas workspace!)
          if (sel != null) {
            val hitHighlight = sel.highlightRects.any { r ->
              sx >= r.left - 6f && sx <= r.right + 6f && sy >= r.top - 4f && sy <= r.bottom + 4f
            }
            if (hitHighlight) {
              isDirectDraggingSelection = true
              liftCandidateText = sel.text
              liftCandidatePage = sel.pageNumber
              liftCandidateColor = Color.parseColor("#00ADB5")
              liftAnchorScreenX = if (sel.highlightRects.isNotEmpty()) sel.highlightRects.first().left else sx
              liftAnchorScreenY = if (sel.highlightRects.isNotEmpty()) sel.highlightRects.first().centerY() else sy
              liftGhostX = sx
              liftGhostY = sy
              return true
            }
          }

          // 5. User tapped elsewhere in document: Select word at tapped location!
          for (info in paragraphLayouts) {
            val pTop = info.topY
            val pBottom = info.topY + info.layout.height
            if (sy in (pTop - 6f)..(pBottom + 6f)) {
              val relX = sx - (info.paperX + 28f)
              val relY = (sy - info.topY).coerceIn(0f, info.layout.height.toFloat() - 1f)
              val line = info.layout.getLineForVertical(relY.toInt())
              val charIdx = info.layout.getOffsetForHorizontal(line, relX)
              val (wStart, wEnd) = expandToWord(info.text, charIdx)
              activeSelection = buildSelection(info, wStart, wEnd)
              isScrollingDoc = false
              invalidate()
              return true
            }
          }

          // If tapped outside text paragraphs, scroll document
          isScrollingDoc = true
          return true
        }

        if (inCanvasZone) {
          val (worldX, worldY) = canvasScreenToWorld(sx, sy, canvasTopY)

          if (activeTool == "select") {
            // Check context toolbar of selected card
            if (selectedCardId != null) {
              val selCard = cards.find { it.id == selectedCardId }
              if (selCard != null) {
                val tbW = 210f
                val tbH = 36f
                val tbX = selCard.x + (selCard.width - tbW) / 2f
                val tbY = selCard.y - 44f
                if (worldX in tbX..(tbX + tbW) && worldY in tbY..(tbY + tbH)) {
                  var dotX = tbX + 16f
                  for (c in contextColors) {
                    if (hypot(worldX - dotX, worldY - (tbY + 18f)) < 14f) {
                      selCard.color = c
                      dispatchCardColorChangeEvent(selCard.id, String.format("#%06X", 0xFFFFFF and c))
                      invalidate()
                      return true
                    }
                    dotX += 26f
                  }
                  if (worldX >= tbX + tbW - 32f) {
                    deleteCard(selCard.id)
                    selectedCardId = null
                    return true
                  }
                  return true
                }
              }
            }

            // Hit-test cards
            draggingCard = null
            for (i in cards.size - 1 downTo 0) {
              val c = cards[i]
              val cardW = c.width
              val cardH = c.getHeight()
              if (worldX >= c.x && worldX <= c.x + cardW && worldY >= c.y && worldY <= c.y + cardH) {
                if (worldX >= c.x + cardW - 36f && worldY <= c.y + 36f) {
                  deleteCard(c.id)
                  return true
                }
                draggingCard = c
                selectedCardId = c.id
                heldCardId = c.id
                dragOffsetWorldX = worldX - c.x
                dragOffsetWorldY = worldY - c.y
                isPanningCanvas = false
                invalidate()
                return true
              }
            }

            // Canvas panning
            isPanningCanvas = true
            selectedCardId = null
            invalidate()
            return true

          } else if (activeTool == "pen" || activeTool == "highlighter") {
            activePoints.clear()
            activePath.reset()
            activePoints.add(NativePoint(worldX, worldY))
            activePath.moveTo(worldX, worldY)
            invalidate()
            return true
          } else if (activeTool == "eraser") {
            eraseStrokeAt(worldX, worldY)
            return true
          }
        }
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        val dx = sx - lastTouchScreenX
        val dy = sy - lastTouchScreenY
        totalDragDistance += hypot(dx, dy)
        lastTouchScreenX = sx
        lastTouchScreenY = sy

        if (isDraggingDivider) {
          val newRatio = (sy / viewH).coerceIn(0.15f, 0.85f)
          splitRatio = newRatio
          dispatchSplitRatioEvent(newRatio)
          invalidate()
          return true
        }

        // Dragging Start Handle Pin
        if (isDraggingStartHandle && activeSelection != null) {
          val sel = activeSelection!!
          val info = paragraphLayouts.find { it.secId == sel.sectionId && it.pIdx == sel.pIdx }
          if (info != null) {
            val relX = sx - (info.paperX + 28f)
            val relY = (sy - info.topY).coerceIn(0f, info.layout.height.toFloat() - 1f)
            val line = info.layout.getLineForVertical(relY.toInt())
            val newChar = info.layout.getOffsetForHorizontal(line, relX)
            activeSelection = buildSelection(info, newChar, sel.endCharIdx)
            invalidate()
          }
          return true
        }

        // Dragging End Handle Pin
        if (isDraggingEndHandle && activeSelection != null) {
          val sel = activeSelection!!
          val info = paragraphLayouts.find { it.secId == sel.sectionId && it.pIdx == sel.pIdx }
          if (info != null) {
            val relX = sx - (info.paperX + 28f)
            val relY = (sy - info.topY).coerceIn(0f, info.layout.height.toFloat() - 1f)
            val line = info.layout.getLineForVertical(relY.toInt())
            val newChar = info.layout.getOffsetForHorizontal(line, relX)
            activeSelection = buildSelection(info, sel.startCharIdx, newChar)
            invalidate()
          }
          return true
        }

        // Direct Touch & Drag on Active Selection
        if (isDirectDraggingSelection && liftCandidateText != null) {
          if (totalDragDistance > 6f || sy > splitY - 10f) {
            isLiftingExcerpt = true
            liftGhostX = sx
            liftGhostY = sy
            invalidate()
            return true
          }
        }

        // Cross-zone Lift-and-Drag in progress
        if (isLiftingExcerpt && liftCandidateText != null) {
          liftGhostX = sx
          liftGhostY = sy
          invalidate()
          return true
        }

        if (isScrollingDoc) {
          docScrollY = (docScrollY - dy).coerceIn(0f, maxDocScrollY)
          invalidate()
          return true
        }

        if (inCanvasZone) {
          val (worldX, worldY) = canvasScreenToWorld(sx, sy, canvasTopY)

          if (draggingCard != null) {
            val card = draggingCard!!
            card.x = worldX - dragOffsetWorldX
            card.y = worldY - dragOffsetWorldY
            invalidate()
          } else if (isPanningCanvas && activeTool == "select") {
            panX += dx
            panY += dy
            dispatchTransformEvent()
            invalidate()
          } else if (activeTool == "pen" || activeTool == "highlighter") {
            if (activePoints.isNotEmpty()) {
              val prev = activePoints.last()
              val midX = (prev.x + worldX) / 2f
              val midY = (prev.y + worldY) / 2f
              activePath.quadTo(prev.x, prev.y, midX, midY)
              activePoints.add(NativePoint(worldX, worldY))
              invalidate()
            }
          } else if (activeTool == "eraser") {
            eraseStrokeAt(worldX, worldY)
          }
        }
        return true
      }

      MotionEvent.ACTION_UP -> {
        // Complete Cross-Zone Lift-and-Drag: drop into canvas!
        if (isLiftingExcerpt && liftCandidateText != null) {
          if (sy >= canvasTopY) {
            val (worldX, worldY) = canvasScreenToWorld(sx, sy, canvasTopY)
            val newId = "card-${System.currentTimeMillis()}"
            val newCard = NativeCard(
              newId,
              max(20f, worldX - 110f),
              max(20f, worldY - 50f),
              220f,
              liftCandidateText!!,
              liftCandidateColor,
              liftCandidatePage,
              "Excerpt from p. $liftCandidatePage",
              null,
              1,
              false,
              null,
              false,
              null
            )
            cards.add(newCard)
            links.add(NativeLink("link-${System.currentTimeMillis()}", newId, liftCandidateColor))
            triggerShockwave(newCard.x + newCard.width / 2f, newCard.y + 50f, newCard.color)
            dispatchExtractExcerptEvent(liftCandidateText!!, liftCandidatePage, String.format("#%06X", 0xFFFFFF and liftCandidateColor), false, false)
            activeSelection = null
          }
          isLiftingExcerpt = false
          isDirectDraggingSelection = false
          liftCandidateText = null
          invalidate()
          return true
        }

        isDraggingStartHandle = false
        isDraggingEndHandle = false
        isDirectDraggingSelection = false

        if (draggingCard != null) {
          val card = draggingCard!!
          val wasDragged = totalDragDistance > 8f
          if (wasDragged) {
            // Magnetic stack snapping (42px)
            val stackRadius = 42f
            var targetStackCard: NativeCard? = null
            for (other in cards) {
              if (other.id == card.id) continue
              if (hypot(card.x - other.x, card.y - other.y) < stackRadius) {
                targetStackCard = other
                break
              }
            }

            if (targetStackCard != null) {
              card.x = targetStackCard.x + 6f
              card.y = targetStackCard.y + 6f
              val clusterId = targetStackCard.clusterId ?: "cluster-${targetStackCard.id}"
              val nextStack = max(2, targetStackCard.stackCount + 1)
              card.clusterId = clusterId
              card.stackCount = nextStack
              targetStackCard.clusterId = clusterId
              targetStackCard.stackCount = nextStack
              triggerShockwave(card.x + card.width / 2f, card.y + 60f, card.color)
              dispatchExcerptMoveEndEvent(card.id, card.x, card.y, clusterId, nextStack)
            } else {
              triggerShockwave(card.x + card.width / 2f, card.y + 60f, card.color)
              dispatchExcerptMoveEndEvent(card.id, card.x, card.y, card.clusterId, card.stackCount)
            }
          } else {
            // Card tapped -> jump document to citation page
            if (activeDocument != null && card.pageNumber > 0) {
              docScrollY = max(0f, (card.pageNumber - 1) * 260f)
            }
            dispatchExcerptPressEvent(card.id)
          }

          draggingCard = null
          heldCardId = null
          invalidate()
        } else if (activePoints.size > 1) {
          val strokeId = "stroke-${System.currentTimeMillis()}"
          val isHighlighter = activeTool == "highlighter"
          val smoothed = chaikinSmooth(activePoints.toList(), 1)
          val newStroke = NativeStroke(strokeId, smoothed, selectedColor, if (isHighlighter) 14f else 3.5f, isHighlighter)
          strokes.add(newStroke)
          dispatchAddStrokeEvent(newStroke)
          activePoints.clear()
          activePath.reset()
          invalidate()
        } else {
          activePoints.clear()
          activePath.reset()
        }

        isDraggingDivider = false
        isScrollingDoc = false
        isPanningCanvas = false
        isLiftingExcerpt = false
        liftCandidateText = null
        lastTouchScreenX = 0f
        lastTouchScreenY = 0f
        return true
      }

      MotionEvent.ACTION_CANCEL -> {
        isDraggingDivider = false
        isScrollingDoc = false
        isPanningCanvas = false
        isLiftingExcerpt = false
        liftCandidateText = null
        draggingCard = null
        heldCardId = null
        activePoints.clear()
        activePath.reset()
        lastTouchScreenX = 0f
        lastTouchScreenY = 0f
        invalidate()
        return true
      }
    }

    return super.onTouchEvent(event)
  }

  private fun deleteCard(cardId: String) {
    cards.removeAll { it.id == cardId }
    links.removeAll { it.sourceExcerptId == cardId }
    dispatchCardDeleteEvent(cardId)
    invalidate()
  }

  private fun eraseStrokeAt(worldX: Float, worldY: Float) {
    val threshold = 28f
    var erasedId: String? = null
    for (i in strokes.size - 1 downTo 0) {
      val s = strokes[i]
      for (pt in s.points) {
        if (hypot(pt.x - worldX, pt.y - worldY) < threshold) {
          erasedId = s.id
          strokes.removeAt(i)
          break
        }
      }
      if (erasedId != null) break
    }
    if (erasedId != null) {
      dispatchEraseStrokeEvent(erasedId)
      invalidate()
    }
  }

  // Event dispatchers
  private fun dispatchAddStrokeEvent(stroke: NativeStroke) {
    try {
      val json = JSONObject()
      json.put("id", stroke.id)
      json.put("color", String.format("#%06X", 0xFFFFFF and stroke.color))
      json.put("strokeWidth", stroke.strokeWidth.toDouble())
      json.put("isHighlighter", stroke.isHighlighter)
      val ptsArr = JSONArray()
      for (pt in stroke.points) {
        val ptObj = JSONObject()
        ptObj.put("x", pt.x.toDouble())
        ptObj.put("y", pt.y.toDouble())
        ptsArr.put(ptObj)
      }
      json.put("points", ptsArr)
      val map = Arguments.createMap()
      map.putString("strokeJson", json.toString())
      dispatchEvent("topAddStroke", map)
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  private fun dispatchEraseStrokeEvent(strokeId: String) {
    val map = Arguments.createMap()
    map.putString("id", strokeId)
    dispatchEvent("topEraseStroke", map)
  }

  private fun dispatchExcerptMoveEndEvent(id: String, x: Float, y: Float, clusterId: String?, stackCount: Int?) {
    val map = Arguments.createMap()
    map.putString("id", id)
    map.putDouble("x", x.toDouble())
    map.putDouble("y", y.toDouble())
    if (clusterId != null) map.putString("clusterId", clusterId)
    if (stackCount != null) map.putDouble("stackCount", stackCount.toDouble())
    dispatchEvent("topExcerptMoveEnd", map)
  }

  private fun dispatchExcerptPressEvent(id: String) {
    val map = Arguments.createMap()
    map.putString("id", id)
    dispatchEvent("topExcerptPress", map)
  }

  private fun dispatchCardDeleteEvent(id: String) {
    val map = Arguments.createMap()
    map.putString("id", id)
    dispatchEvent("topCardDelete", map)
  }

  private fun dispatchCardColorChangeEvent(id: String, colorHex: String) {
    val map = Arguments.createMap()
    map.putString("id", id)
    map.putString("color", colorHex)
    dispatchEvent("topChangeCardColor", map)
  }

  private fun dispatchSplitRatioEvent(ratio: Float) {
    val map = Arguments.createMap()
    map.putDouble("ratio", ratio.toDouble())
    dispatchEvent("topSplitRatioChange", map)
  }

  private fun dispatchExtractExcerptEvent(text: String, pageNumber: Int, colorHex: String, isTable: Boolean, isImage: Boolean) {
    val map = Arguments.createMap()
    map.putString("text", text)
    map.putDouble("pageNumber", pageNumber.toDouble())
    map.putString("color", colorHex)
    map.putBoolean("isTable", isTable)
    map.putBoolean("isImage", isImage)
    dispatchEvent("topExtractExcerpt", map)
  }

  private fun dispatchTransformEvent() {
    val map = Arguments.createMap()
    map.putDouble("panX", panX.toDouble())
    map.putDouble("panY", panY.toDouble())
    map.putDouble("scale", scaleFactor.toDouble())
    dispatchEvent("topTransformChange", map)
  }

  private fun dispatchEvent(eventName: String, data: WritableMap) {
    try {
      val reactContext = UIManagerHelper.getReactContext(this)
      val surfaceId = UIManagerHelper.getSurfaceId(this)
      val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)
      dispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, eventName, data))
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }
}
