package com.thinkspace

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.EventDispatcher
import com.thinkspace.engine.models.NativeCard
import com.thinkspace.engine.models.NativeInkLink
import com.thinkspace.engine.models.NativePoint
import com.thinkspace.engine.models.ThinkspaceEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Extension functions for ThinkspaceView: Pen favorites, semantic ink links persistence,
 * state events, and inking configuration.
 */
  // ---------------------------------------------------------------------------
  // LiquidText Real Pen & Semantic Inking System API & Events
  // ---------------------------------------------------------------------------
fun ThinkspaceView.setPenFavoritesFromJson(json: String?) {
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

fun ThinkspaceView.setSemanticInkLinksFromJson(json: String?) {
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

fun ThinkspaceView.togglePenSettings() {
    isPenSettingsOpen = !isPenSettingsOpen
    dispatchPenStateChangeEvent()
    invalidate()
  }

fun ThinkspaceView.persistPenSettingsLocally() {
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

fun ThinkspaceView.loadPersistedPenSettings() {
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

fun ThinkspaceView.persistSemanticInkLinksLocally() {
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

fun ThinkspaceView.loadPersistedSemanticInkLinks() {
    try {
      val file = File(context.filesDir, "thinkspace_ink_links.json")
      if (file.exists() && semanticInkLinks.isEmpty()) {
        setSemanticInkLinksFromJson(file.readText())
      }
    } catch (_: Exception) {}
  }

internal fun ThinkspaceView.dispatchPenStateChangeEvent() {
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

internal fun ThinkspaceView.dispatchInkLinkCreateEvent(link: NativeInkLink) {
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

internal fun ThinkspaceView.dispatchInkLinkDeleteEvent(linkId: String) {
    val surfaceId = UIManagerHelper.getSurfaceId(this)
    val eventDispatcher = getEventDispatcher()
    val data = Arguments.createMap().apply {
      putString("id", linkId)
    }
    eventDispatcher?.dispatchEvent(ThinkspaceEvent(surfaceId, id, "topInkLinkDelete", data))
  }


// ── InkLink Perimeter & Distance Geometry Helpers ──
  /**
   * Calculates the exact point on the perimeter/edge of [card] that faces [fromWorldX], [fromWorldY].
   * Ensures the InkLink line attaches cleanly to the card's exterior edge rather than floating or stopping inside.
   */
internal fun ThinkspaceView.getCardEdgeAnchor(card: NativeCard, fromWorldX: Float, fromWorldY: Float): PointF {
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

internal fun ThinkspaceView.distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val l2 = dx * dx + dy * dy
    if (l2 < 0.0001f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
    val projX = ax + t * dx
    val projY = ay + t * dy
    return hypot(px - projX, py - projY)
  }
