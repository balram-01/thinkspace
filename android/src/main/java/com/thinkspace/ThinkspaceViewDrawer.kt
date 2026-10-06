package com.thinkspace

// ---------------------------------------------------------------------------
// Native LiquidText Document Drawer & Folder Management System
// Extension functions for ThinkspaceView — extracted from ThinkspaceView.kt
// ---------------------------------------------------------------------------

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.UIManagerHelper
import com.thinkspace.engine.models.ThinkspaceEvent
import com.thinkspace.engine.models.WorkspaceDocumentEntry
import com.thinkspace.engine.models.WorkspaceFolder
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

fun ThinkspaceView.closeDocumentsSheet() {
  post {
    documentsSheetDialog?.dismiss()
    documentsSheetDialog = null
  }
}

fun ThinkspaceView.openDocumentsSheet() {
  post {
    val activity = generateSequence(context) {
      (it as? android.content.ContextWrapper)?.baseContext
    }.filterIsInstance<Activity>().firstOrNull() ?: return@post

    documentsSheetDialog?.dismiss()
    val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    documentsSheetDialog = dialog

    val d = density
    val dm = activity.resources.displayMetrics
    val cardW = min(450f * d, dm.widthPixels * 0.92f).toInt()
    val cardH = min(620f * d, dm.heightPixels * 0.85f).toInt()

    val rootLayout = FrameLayout(activity).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.parseColor("#B3050C16"))
      setOnClickListener { dialog.dismiss() }
    }

    val card = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = FrameLayout.LayoutParams(cardW, cardH).apply {
        gravity = Gravity.CENTER
      }
      background = GradientDrawable().apply {
        setColor(Color.parseColor("#141D2B"))
        cornerRadius = 18f * d
        setStroke((1.5f * d).toInt(), Color.parseColor("#2C3A4E"))
      }
      elevation = 28f * d
      setPadding(0, (14f * d).toInt(), 0, (14f * d).toInt())
      setOnClickListener { /* consume click so sheet does not dismiss */ }
    }

    // Top Header
    val headerLayout = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding((16f * d).toInt(), 0, (16f * d).toInt(), (10f * d).toInt())
    }

    val titleView = TextView(activity).apply {
      text = "📄 Documents"
      setTextColor(Color.WHITE)
      textSize = 17f
      typeface = Typeface.DEFAULT_BOLD
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerLayout.addView(titleView)

    // + Doc button
    val addDocBtn = TextView(activity).apply {
      text = "+ Doc"
      setTextColor(Color.parseColor("#00ADB5"))
      textSize = 12f
      typeface = Typeface.DEFAULT_BOLD
      background = GradientDrawable().apply {
        setColor(Color.parseColor("#0A2B35"))
        cornerRadius = 8f * d
        setStroke((1.2f * d).toInt(), Color.parseColor("#00ADB5"))
      }
      setPadding((12f * d).toInt(), (6f * d).toInt(), (12f * d).toInt(), (6f * d).toInt())
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply { rightMargin = (8f * d).toInt() }
      setOnClickListener {
        dispatchRequestAddDocumentEvent()
        hudToast.show("Select a PDF to import")
      }
    }
    headerLayout.addView(addDocBtn)

    // + Folder button
    val addFolderBtn = TextView(activity).apply {
      text = "📁+ Folder"
      setTextColor(Color.parseColor("#E2E8F0"))
      textSize = 12f
      typeface = Typeface.DEFAULT_BOLD
      background = GradientDrawable().apply {
        setColor(Color.parseColor("#1E293B"))
        cornerRadius = 8f * d
        setStroke((1.2f * d).toInt(), Color.parseColor("#334155"))
      }
      setPadding((10f * d).toInt(), (6f * d).toInt(), (10f * d).toInt(), (6f * d).toInt())
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply { rightMargin = (8f * d).toInt() }
    }
    headerLayout.addView(addFolderBtn)

    // Close button
    val closeBtn = TextView(activity).apply {
      text = "✕"
      setTextColor(Color.parseColor("#94A3B8"))
      textSize = 13f
      gravity = Gravity.CENTER
      background = GradientDrawable().apply {
        setColor(Color.parseColor("#1E293B"))
        cornerRadius = 14f * d
      }
      layoutParams = LinearLayout.LayoutParams((28f * d).toInt(), (28f * d).toInt())
      setOnClickListener { dialog.dismiss() }
    }
    headerLayout.addView(closeBtn)
    card.addView(headerLayout)

    // Search bar
    var filterQuery = ""
    val searchContainer = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply {
        setMargins((16f * d).toInt(), 0, (16f * d).toInt(), (12f * d).toInt())
      }
      background = GradientDrawable().apply {
        setColor(Color.parseColor("#0B1320"))
        cornerRadius = 10f * d
        setStroke((1f * d).toInt(), Color.parseColor("#243142"))
      }
      setPadding((10f * d).toInt(), (4f * d).toInt(), (10f * d).toInt(), (4f * d).toInt())
    }

    val searchIcon = TextView(activity).apply {
      text = "🔍"
      textSize = 13f
      setPadding(0, 0, (6f * d).toInt(), 0)
    }
    searchContainer.addView(searchIcon)

    val searchInput = EditText(activity).apply {
      hint = "Search documents & folders…"
      setHintTextColor(Color.parseColor("#64748B"))
      setTextColor(Color.WHITE)
      textSize = 13.5f
      background = null
      setSingleLine(true)
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    searchContainer.addView(searchInput)
    card.addView(searchContainer)

    // Scrollable Tree Layout
    val scrollView = ScrollView(activity).apply {
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f
      )
    }
    val treeLayout = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, 0, 0, (12f * d).toInt())
    }
    scrollView.addView(treeLayout)
    card.addView(scrollView)
    rootLayout.addView(card)

    // Recursive tree builder
    lateinit var renderTree: (String) -> Unit
    renderTree = { query ->
      treeLayout.removeAllViews()
      val q = query.trim().lowercase()

      if (workspaceDocumentEntries.isEmpty() && workspaceFolders.isEmpty()) {
        val emptyTv = TextView(activity).apply {
          text = "No documents in workspace.\nTap + Doc to add a PDF."
          gravity = Gravity.CENTER
          setTextColor(Color.parseColor("#64748B"))
          textSize = 13.5f
          setPadding((24f * d).toInt(), (48f * d).toInt(), (24f * d).toInt(), (48f * d).toInt())
        }
        treeLayout.addView(emptyTv)
      } else {
        lateinit var renderFolderItem: (WorkspaceFolder, Int) -> Unit
        lateinit var renderDocumentItem: (WorkspaceDocumentEntry, Int) -> Unit

        renderFolderItem = { folder, depth ->
          val childFolders = workspaceFolders.filter { it.parentId == folder.id }
          val childDocs = workspaceDocumentEntries.filter { it.folderId == folder.id }

          val folderMatches = q.isEmpty() || folder.name.lowercase().contains(q)
          val anyChildMatches = q.isNotEmpty() && (
            childFolders.any { it.name.lowercase().contains(q) } ||
            childDocs.any { it.title.lowercase().contains(q) }
          )

          if (!(q.isNotEmpty() && !folderMatches && !anyChildMatches)) {
            val isExpanded = expandedFolderIds.contains(folder.id) || q.isNotEmpty()

            val folderRow = LinearLayout(activity).apply {
              orientation = LinearLayout.HORIZONTAL
              gravity = Gravity.CENTER_VERTICAL
              layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
              ).apply {
                setMargins((12f * d).toInt(), (2f * d).toInt(), (12f * d).toInt(), (2f * d).toInt())
              }
              background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B2536"))
                cornerRadius = 8f * d
              }
              setPadding(
                ((14f + depth * 18f) * d).toInt(),
                (8f * d).toInt(),
                (10f * d).toInt(),
                (8f * d).toInt()
              )
              setOnClickListener {
                if (expandedFolderIds.contains(folder.id)) {
                  expandedFolderIds.remove(folder.id)
                } else {
                  expandedFolderIds.add(folder.id)
                }
                renderTree(filterQuery)
              }
            }

            val chevronIcon = TextView(activity).apply {
              text = if (isExpanded) "▾  📂 " else "▸  📁 "
              textSize = 14f
              setTextColor(Color.parseColor("#94A3B8"))
            }
            folderRow.addView(chevronIcon)

            val folderTitle = TextView(activity).apply {
              text = folder.name
              setTextColor(Color.parseColor("#F1F5F9"))
              textSize = 13.5f
              typeface = Typeface.DEFAULT_BOLD
              maxLines = 1
              ellipsize = android.text.TextUtils.TruncateAt.END
              layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            folderRow.addView(folderTitle)

            val countBadge = TextView(activity).apply {
              text = "(${childDocs.size + childFolders.size})"
              setTextColor(Color.parseColor("#64748B"))
              textSize = 11.5f
              setPadding((6f * d).toInt(), 0, (8f * d).toInt(), 0)
            }
            folderRow.addView(countBadge)

            val folderDotsBtn = TextView(activity).apply {
              text = "⋮"
              setTextColor(Color.parseColor("#94A3B8"))
              textSize = 16f
              gravity = Gravity.CENTER
              setPadding((6f * d).toInt(), (2f * d).toInt(), (6f * d).toInt(), (2f * d).toInt())
              setOnClickListener {
                promptFolderActions(activity, folder) { renderTree(filterQuery) }
              }
            }
            folderRow.addView(folderDotsBtn)
            treeLayout.addView(folderRow)

            if (isExpanded) {
              for (sub in childFolders) {
                renderFolderItem(sub, depth + 1)
              }
              for (doc in childDocs) {
                renderDocumentItem(doc, depth + 1)
              }
            }
          }
        }

        renderDocumentItem = { doc, depth ->
          if (q.isEmpty() || doc.title.lowercase().contains(q)) {
            val isActive = doc.id == activeDocumentId

            val docRow = LinearLayout(activity).apply {
              orientation = LinearLayout.HORIZONTAL
              gravity = Gravity.CENTER_VERTICAL
              layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
              ).apply {
                setMargins((12f * d).toInt(), (2f * d).toInt(), (12f * d).toInt(), (2f * d).toInt())
              }
              background = GradientDrawable().apply {
                if (isActive) {
                  setColor(Color.parseColor("#092833"))
                  cornerRadius = 8f * d
                  setStroke((1.5f * d).toInt(), Color.parseColor("#00ADB5"))
                } else {
                  setColor(Color.parseColor("#151E2C"))
                  cornerRadius = 8f * d
                }
              }
              setPadding(
                ((14f + depth * 18f) * d).toInt(),
                (8f * d).toInt(),
                (10f * d).toInt(),
                (8f * d).toInt()
              )
              setOnClickListener {
                switchToDocument(doc.id)
                dispatchDocumentChangeEvent(doc)
                dialog.dismiss()
              }
            }

            val accentBar = View(activity).apply {
              layoutParams = LinearLayout.LayoutParams((3f * d).toInt(), (16f * d).toInt()).apply {
                rightMargin = (6f * d).toInt()
              }
              background = GradientDrawable().apply {
                setColor(getDocumentAccentColor(doc.id))
                cornerRadius = 1.5f * d
              }
            }
            docRow.addView(accentBar)

            val docIcon = TextView(activity).apply {
              text = "📄 "
              textSize = 13.5f
            }
            docRow.addView(docIcon)

            val docTitle = TextView(activity).apply {
              text = doc.title
              setTextColor(if (isActive) Color.parseColor("#00ADB5") else Color.parseColor("#F8FAFC"))
              textSize = 13f
              typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
              maxLines = 1
              ellipsize = android.text.TextUtils.TruncateAt.END
              layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            docRow.addView(docTitle)

            val pageBadge = TextView(activity).apply {
              text = "${doc.pageCount} pgs"
              setTextColor(Color.parseColor("#64748B"))
              textSize = 11f
              setPadding((4f * d).toInt(), 0, (6f * d).toInt(), 0)
            }
            docRow.addView(pageBadge)

            if (isActive) {
              val checkBadge = TextView(activity).apply {
                text = "✓ "
                setTextColor(Color.parseColor("#00ADB5"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
              }
              docRow.addView(checkBadge)
            }

            val docDotsBtn = TextView(activity).apply {
              text = "⋮"
              setTextColor(Color.parseColor("#94A3B8"))
              textSize = 16f
              gravity = Gravity.CENTER
              setPadding((6f * d).toInt(), (2f * d).toInt(), (6f * d).toInt(), (2f * d).toInt())
              setOnClickListener {
                promptDocumentActions(activity, doc) { renderTree(filterQuery) }
              }
            }
            docRow.addView(docDotsBtn)
            treeLayout.addView(docRow)
          }
        }

        val rootFolders = workspaceFolders.filter { it.parentId.isNullOrEmpty() }
        for (f in rootFolders) {
          renderFolderItem(f, 0)
        }

        val rootDocs = workspaceDocumentEntries.filter { it.folderId.isNullOrEmpty() }
        for (docEntry in rootDocs) {
          renderDocumentItem(docEntry, 0)
        }
      }
    }

    addFolderBtn.setOnClickListener {
      promptCreateFolder(activity) { renderTree(filterQuery) }
    }

    searchInput.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        filterQuery = s?.toString() ?: ""
        renderTree(filterQuery)
      }
      override fun afterTextChanged(s: Editable?) {}
    })

    renderTree("")
    dialog.setContentView(rootLayout)
    dialog.show()
  }
}

internal fun ThinkspaceView.promptCreateFolder(activity: Activity, onDone: () -> Unit) {
  val d = density
  val input = EditText(activity).apply {
    hint = "Folder name"
    setHintTextColor(Color.parseColor("#64748B"))
    setTextColor(Color.WHITE)
    setBackgroundColor(Color.parseColor("#1E293B"))
    setPadding((16f * d).toInt(), (12f * d).toInt(), (16f * d).toInt(), (12f * d).toInt())
    setSingleLine(true)
  }
  val container = FrameLayout(activity).apply {
    setPadding((18f * d).toInt(), (10f * d).toInt(), (18f * d).toInt(), (6f * d).toInt())
    addView(input)
  }
  AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    .setTitle("New Folder")
    .setView(container)
    .setPositiveButton("Create") { _, _ ->
      val name = input.text.toString().trim().ifEmpty { "New Folder" }
      val newFolder = WorkspaceFolder(id = UUID.randomUUID().toString(), name = name)
      workspaceFolders.add(newFolder)
      expandedFolderIds.add(newFolder.id)
      dispatchDocumentsUpdatedEvent()
      onDone()
    }
    .setNegativeButton("Cancel", null)
    .show()
}

internal fun ThinkspaceView.promptFolderActions(activity: Activity, folder: WorkspaceFolder, onDone: () -> Unit) {
  val options = arrayOf("Rename Folder", "Delete Folder")
  AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    .setTitle("Folder: ${folder.name}")
    .setItems(options) { _, which ->
      when (which) {
        0 -> {
          val d = density
          val input = EditText(activity).apply {
            setText(folder.name)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding((16f * d).toInt(), (12f * d).toInt(), (16f * d).toInt(), (12f * d).toInt())
            setSingleLine(true)
          }
          val container = FrameLayout(activity).apply {
            setPadding((18f * d).toInt(), (10f * d).toInt(), (18f * d).toInt(), (6f * d).toInt())
            addView(input)
          }
          AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename Folder")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
              val newName = input.text.toString().trim()
              if (newName.isNotEmpty()) {
                folder.name = newName
                dispatchDocumentsUpdatedEvent()
                onDone()
              }
            }
            .setNegativeButton("Cancel", null)
            .show()
        }
        1 -> {
          AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete Folder")
            .setMessage("Delete folder \"${folder.name}\"? Contained documents will be moved to root.")
            .setPositiveButton("Delete") { _, _ ->
              for (doc in workspaceDocumentEntries) {
                if (doc.folderId == folder.id) {
                  doc.folderId = folder.parentId
                }
              }
              for (sub in workspaceFolders) {
                if (sub.parentId == folder.id) {
                  sub.parentId = folder.parentId
                }
              }
              workspaceFolders.removeAll { it.id == folder.id }
              expandedFolderIds.remove(folder.id)
              dispatchDocumentsUpdatedEvent()
              onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
        }
      }
    }
    .show()
}

internal fun ThinkspaceView.promptDocumentActions(activity: Activity, doc: WorkspaceDocumentEntry, onDone: () -> Unit) {
  val options = arrayOf("Rename", "Add to New Folder", "Move to Folder…", "Delete")
  AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    .setTitle("Document: ${doc.title}")
    .setItems(options) { _, which ->
      when (which) {
        0 -> {
          val d = density
          val input = EditText(activity).apply {
            setText(doc.title)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding((16f * d).toInt(), (12f * d).toInt(), (16f * d).toInt(), (12f * d).toInt())
            setSingleLine(true)
          }
          val container = FrameLayout(activity).apply {
            setPadding((18f * d).toInt(), (10f * d).toInt(), (18f * d).toInt(), (6f * d).toInt())
            addView(input)
          }
          AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename Document")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
              val newTitle = input.text.toString().trim()
              if (newTitle.isNotEmpty()) {
                doc.title = newTitle
                invalidate()
                dispatchDocumentsUpdatedEvent()
                onDone()
              }
            }
            .setNegativeButton("Cancel", null)
            .show()
        }
        1 -> {
          val d = density
          val input = EditText(activity).apply {
            hint = "Enter name for new folder"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding((16f * d).toInt(), (12f * d).toInt(), (16f * d).toInt(), (12f * d).toInt())
            setSingleLine(true)
          }
          val container = FrameLayout(activity).apply {
            setPadding((18f * d).toInt(), (10f * d).toInt(), (18f * d).toInt(), (6f * d).toInt())
            addView(input)
          }
          AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("New Folder")
            .setView(container)
            .setPositiveButton("Okay") { _, _ ->
              val folderName = input.text.toString().trim().ifEmpty { "New Folder" }
              val newFolder = WorkspaceFolder(
                id = UUID.randomUUID().toString(),
                name = folderName,
                parentId = doc.folderId
              )
              workspaceFolders.add(newFolder)
              doc.folderId = newFolder.id
              expandedFolderIds.add(newFolder.id)
              dispatchDocumentsUpdatedEvent()
              onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
        }
        2 -> {
          val destinationList = mutableListOf<Pair<String?, String>>()
          if (!doc.folderId.isNullOrEmpty()) {
            destinationList.add(null to "Root Level (Remove from folder)")
          }
          for (f in workspaceFolders) {
            if (f.id != doc.folderId) {
              destinationList.add(f.id to "📁  " + f.name)
            }
          }
          if (destinationList.isEmpty()) {
            hudToast.show("No other folders available")
            return@setItems
          }
          val labels = destinationList.map { it.second }.toTypedArray()
          AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Move \"${doc.title}\"")
            .setItems(labels) { _, destIdx ->
              val chosenFolderId = destinationList[destIdx].first
              doc.folderId = chosenFolderId
              if (chosenFolderId != null) {
                expandedFolderIds.add(chosenFolderId)
              }
              dispatchDocumentsUpdatedEvent()
              onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
        }
        3 -> {
          AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete Document")
            .setMessage("Remove \"${doc.title}\" from the workspace?")
            .setPositiveButton("Remove") { _, _ ->
              workspaceDocumentEntries.removeAll { it.id == doc.id }
              if (activeDocumentId == doc.id) {
                val nextDoc = workspaceDocumentEntries.firstOrNull()
                if (nextDoc != null) {
                  switchToDocument(nextDoc.id)
                  dispatchDocumentChangeEvent(nextDoc)
                }
              }
              dispatchDocumentsUpdatedEvent()
              invalidate()
              onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
        }
      }
    }
    .show()
}

fun ThinkspaceView.dispatchDocumentChangeEvent(entry: WorkspaceDocumentEntry) {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap().apply {
    putString("documentId", entry.id)
    putString("title", entry.title)
    putString("uri", entry.uri)
    putInt("pageCount", entry.pageCount)
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topDocumentChange", data))
}

fun ThinkspaceView.dispatchDocumentsUpdatedEvent() {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val docsArr = JSONArray()
  for (d in workspaceDocumentEntries) {
    docsArr.put(JSONObject().apply {
      put("id", d.id)
      put("title", d.title)
      put("pageCount", d.pageCount)
      put("uri", d.uri)
      put("colorAccent", d.colorAccent)
      put("folderId", d.folderId ?: "")
    })
  }
  val foldersArr = JSONArray()
  for (f in workspaceFolders) {
    foldersArr.put(JSONObject().apply {
      put("id", f.id)
      put("name", f.name)
      put("parentId", f.parentId ?: "")
      put("createdAt", f.createdAt)
    })
  }
  val data = Arguments.createMap().apply {
    putString("documentsJson", docsArr.toString())
    putString("foldersJson", foldersArr.toString())
  }
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topDocumentsUpdated", data))
}

fun ThinkspaceView.dispatchRequestAddDocumentEvent() {
  val surfaceId = UIManagerHelper.getSurfaceId(this)
  val eventDispatcher = getEventDispatcher()
  val data = Arguments.createMap()
  eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topRequestAddDocument", data))
}
