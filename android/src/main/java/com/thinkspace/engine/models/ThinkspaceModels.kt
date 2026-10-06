package com.thinkspace.engine.models

import android.graphics.Color
import android.graphics.RectF
import android.text.StaticLayout
import com.thinkspace.engine.GroupedExcerpt
import com.thinkspace.pdfengine.model.BoundingBox
import com.thinkspace.pdfengine.model.PageSize
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.Event

// ---------------------------------------------------------------------------
// ThinkSpace Native Data Models
// Extracted from ThinkspaceView.kt — pure data structures with no view state.
// ---------------------------------------------------------------------------

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
  val calloutRainbowBtn: RectF = RectF(),
  val calloutCommentBtn: RectF = RectF(),
  val calloutBookmarkBtn: RectF = RectF(),
  val calloutClearBtn: RectF = RectF()
)

data class CalloutLayoutResult(
  val calloutRect: RectF,
  val closeBtn: RectF = RectF(),
  val excerptBtn: RectF,
  val copyBtn: RectF = RectF(),
  val highlightBtn: RectF = RectF(),
  val addWordLeftBtn: RectF = RectF(),
  val addWordRightBtn: RectF = RectF(),
  val selectAllBtn: RectF = RectF(),
  val colorBtns: List<Pair<RectF, Int>>,
  val moreBtn: RectF = RectF(),
  val tagsBtn: RectF = RectF(),
  val subCardRect: RectF = RectF(),
  val mainCardRect: RectF = RectF(),
  val rainbowBtn: RectF = RectF(),
  val commentBtn: RectF = RectF(),
  val bookmarkBtn: RectF = RectF(),
  val clearBtn: RectF = RectF()
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
  val calloutColorBtns: MutableList<Pair<RectF, Int>> = mutableListOf(),
  val calloutMoreBtn: RectF = RectF(),
  val calloutRainbowBtn: RectF = RectF(),
  val calloutClearBtn: RectF = RectF()
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

// ── Multi-document & Hierarchical Folder Management ──────────────────────────

data class WorkspaceFolder(
  val id: String,
  var name: String,
  var parentId: String? = null,
  val createdAt: Long = System.currentTimeMillis()
)

data class WorkspaceDocumentEntry(
  val id: String,
  var title: String,
  val pageCount: Int,
  val uri: String,
  val colorAccent: String = "#00ADB5",
  var folderId: String? = null
)
