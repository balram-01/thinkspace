package com.thinkspace

// ---------------------------------------------------------------------------
// Native PDF Engine Search & Navigation
// Extension functions for ThinkspaceView — extracted from ThinkspaceView.kt
// ---------------------------------------------------------------------------

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.widget.EditText
import com.thinkspace.engine.models.NativeSearchMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun ThinkspaceView.closeSearch() {
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

fun ThinkspaceView.triggerSearchCollapse() {
  if (activePdfDoc == null || searchMatches.isEmpty()) return
  val matchingPages = searchMatches.map { it.pageIndex }.toSet()
  compressionEngine.applySearchMatches(matchingPages, animate = true) {
    invalidate()
  }
}

internal fun ThinkspaceView.removeSearchOverlay() {
  val ov = searchOverlayView ?: return
  (ov.parent as? android.view.ViewGroup)?.removeView(ov)
  // Dismiss keyboard
  val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
  imm?.hideSoftInputFromWindow(windowToken, 0)
  searchOverlayView = null
  searchCounterLabel = null
}

fun ThinkspaceView.goToNextMatch() {
  if (searchMatches.isEmpty()) return
  currentSearchIndex = (currentSearchIndex + 1) % searchMatches.size
  scrollToCurrentMatch()
  updateSearchCounter()
  invalidate()
}

fun ThinkspaceView.goToPreviousMatch() {
  if (searchMatches.isEmpty()) return
  currentSearchIndex = (currentSearchIndex - 1 + searchMatches.size) % searchMatches.size
  scrollToCurrentMatch()
  updateSearchCounter()
  invalidate()
}

internal fun ThinkspaceView.scrollToCurrentMatch() {
  val match = searchMatches.getOrNull(currentSearchIndex) ?: return
  scrollToDocumentPage(match.pageIndex + 1, match.rects)
}

fun ThinkspaceView.performSearch(query: String) {
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
fun ThinkspaceView.promptSearchDialog() {
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
internal fun ThinkspaceView.updateSearchCounter() {
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
internal fun ThinkspaceView.showFallbackDialog() {
  val act = (context as? android.app.Activity)
    ?: ((context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)
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
