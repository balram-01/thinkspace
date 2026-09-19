package com.thinkspace.engine

import com.thinkspace.NativeAnnotation
import com.thinkspace.NativeCard
import com.thinkspace.NativeLink
import com.thinkspace.NativeStroke
import java.util.ArrayDeque

/**
 * Reversible workspace action interface matching LiquidText's command pattern.
 */
interface UndoableAction {
  val description: String
  fun undo()
  fun redo()
}

/**
 * Adding a freehand ink stroke (pen or highlighter).
 */
class AddStrokeAction(
  val stroke: NativeStroke,
  private val strokesList: MutableList<NativeStroke>,
  private val onUndoDispatched: ((NativeStroke) -> Unit)? = null,
  private val onRedoDispatched: ((NativeStroke) -> Unit)? = null
) : UndoableAction {
  override val description: String = if (stroke.isHighlighter) "Highlight" else "Ink stroke"

  override fun undo() {
    strokesList.removeAll { it.id == stroke.id }
    onUndoDispatched?.invoke(stroke)
  }

  override fun redo() {
    if (strokesList.none { it.id == stroke.id }) {
      strokesList.add(stroke)
    }
    onRedoDispatched?.invoke(stroke)
  }
}

/**
 * Erasing one or more ink strokes.
 */
class EraseStrokesAction(
  val erasedStrokes: List<NativeStroke>,
  private val strokesList: MutableList<NativeStroke>,
  private val onUndoDispatched: ((List<NativeStroke>) -> Unit)? = null,
  private val onRedoDispatched: ((List<NativeStroke>) -> Unit)? = null
) : UndoableAction {
  override val description: String = if (erasedStrokes.size == 1) "Erase stroke" else "Erase ${erasedStrokes.size} strokes"

  override fun undo() {
    for (s in erasedStrokes) {
      if (strokesList.none { it.id == s.id }) {
        strokesList.add(s)
      }
    }
    onUndoDispatched?.invoke(erasedStrokes)
  }

  override fun redo() {
    val ids = erasedStrokes.map { it.id }.toSet()
    strokesList.removeAll { ids.contains(it.id) }
    onRedoDispatched?.invoke(erasedStrokes)
  }
}

/**
 * Creating an excerpt card (text, crop, or text-box) with optional tether ink-link.
 */
class CreateCardAction(
  val card: NativeCard,
  val link: NativeLink?,
  private val cardsList: MutableList<NativeCard>,
  private val linksList: MutableList<NativeLink>,
  private val onUndoDispatched: ((NativeCard) -> Unit)? = null,
  private val onRedoDispatched: ((NativeCard, NativeLink?) -> Unit)? = null
) : UndoableAction {
  override val description: String = if (card.isImage) "Extract figure" else "Create excerpt"

  override fun undo() {
    cardsList.removeAll { it.id == card.id }
    if (link != null) {
      linksList.removeAll { it.id == link.id || it.sourceExcerptId == card.id }
    }
    onUndoDispatched?.invoke(card)
  }

  override fun redo() {
    if (cardsList.none { it.id == card.id }) {
      cardsList.add(card)
    }
    if (link != null && linksList.none { it.id == link.id }) {
      linksList.add(link)
    }
    onRedoDispatched?.invoke(card, link)
  }
}

/**
 * Deleting an excerpt card with its attached ink links.
 */
class DeleteCardAction(
  val card: NativeCard,
  val associatedLinks: List<NativeLink>,
  private val cardsList: MutableList<NativeCard>,
  private val linksList: MutableList<NativeLink>,
  private val onUndoDispatched: ((NativeCard, List<NativeLink>) -> Unit)? = null,
  private val onRedoDispatched: ((NativeCard) -> Unit)? = null
) : UndoableAction {
  override val description: String = "Delete excerpt"

  override fun undo() {
    if (cardsList.none { it.id == card.id }) {
      cardsList.add(card)
    }
    for (l in associatedLinks) {
      if (linksList.none { it.id == l.id }) {
        linksList.add(l)
      }
    }
    onUndoDispatched?.invoke(card, associatedLinks)
  }

  override fun redo() {
    cardsList.removeAll { it.id == card.id }
    val linkIds = associatedLinks.map { it.id }.toSet()
    linksList.removeAll { linkIds.contains(it.id) || it.sourceExcerptId == card.id }
    onRedoDispatched?.invoke(card)
  }
}

/**
 * Moving an excerpt card across the infinite workspace.
 */
class MoveCardAction(
  val cardId: String,
  val prevX: Float,
  val prevY: Float,
  val newX: Float,
  val newY: Float,
  private val cardsList: MutableList<NativeCard>,
  private val onPositionChanged: ((String, Float, Float) -> Unit)? = null
) : UndoableAction {
  override val description: String = "Move excerpt"

  override fun undo() {
    val card = cardsList.find { it.id == cardId }
    if (card != null) {
      card.x = prevX
      card.y = prevY
      onPositionChanged?.invoke(cardId, prevX, prevY)
    }
  }

  override fun redo() {
    val card = cardsList.find { it.id == cardId }
    if (card != null) {
      card.x = newX
      card.y = newY
      onPositionChanged?.invoke(cardId, newX, newY)
    }
  }
}

/**
 * Magnetically snapping and stacking one card into another's pile.
 */
class StackCardAction(
  val targetCardId: String,
  val stackedItem: GroupedExcerpt,
  val originalCard: NativeCard,
  val previousLink: NativeLink?,
  private val cardsList: MutableList<NativeCard>,
  private val linksList: MutableList<NativeLink>,
  private val onUndoDispatched: ((NativeCard, NativeLink?) -> Unit)? = null,
  private val onRedoDispatched: ((String, GroupedExcerpt) -> Unit)? = null
) : UndoableAction {
  override val description: String = "Stack excerpt"

  override fun undo() {
    val target = cardsList.find { it.id == targetCardId }
    if (target != null && target.groupedItems != null) {
      target.groupedItems?.removeAll { it.id == stackedItem.id }
      if (target.groupedItems?.size ?: 0 <= 1) {
        target.groupedItems = null
      }
    }
    if (cardsList.none { it.id == originalCard.id }) {
      cardsList.add(originalCard)
    }
    if (previousLink != null && linksList.none { it.id == previousLink.id }) {
      linksList.add(previousLink)
    }
    onUndoDispatched?.invoke(originalCard, previousLink)
  }

  override fun redo() {
    val target = cardsList.find { it.id == targetCardId }
    if (target != null) {
      MagneticStackingEngine.stackIntoCard(target, stackedItem)
    }
    cardsList.removeAll { it.id == originalCard.id }
    if (previousLink != null) {
      linksList.removeAll { it.id == previousLink.id || it.sourceExcerptId == originalCard.id }
    }
    onRedoDispatched?.invoke(targetCardId, stackedItem)
  }
}

/**
 * Changing excerpt card background or accent color.
 */
class ChangeCardColorAction(
  val cardId: String,
  val prevColor: Int,
  val newColor: Int,
  private val cardsList: MutableList<NativeCard>,
  private val onColorChanged: ((String, Int) -> Unit)? = null
) : UndoableAction {
  override val description: String = "Change color"

  override fun undo() {
    val card = cardsList.find { it.id == cardId }
    if (card != null) {
      card.color = prevColor
      onColorChanged?.invoke(cardId, prevColor)
    }
  }

  override fun redo() {
    val card = cardsList.find { it.id == cardId }
    if (card != null) {
      card.color = newColor
      onColorChanged?.invoke(cardId, newColor)
    }
  }
}

/**
 * Editing excerpt card text and typography styling.
 */
class EditCardTextAction(
  val cardId: String,
  val prevText: String,
  val newText: String,
  val prevFontSize: Float,
  val newFontSize: Float,
  val prevBold: Boolean,
  val newBold: Boolean,
  val prevItalic: Boolean,
  val newItalic: Boolean,
  val prevUnderline: Boolean,
  val newUnderline: Boolean,
  val prevStrike: Boolean,
  val newStrike: Boolean,
  val prevTextColor: Int,
  val newTextColor: Int,
  val prevStyleName: String,
  val newStyleName: String,
  private val cardsList: MutableList<NativeCard>,
  private val onTextChanged: ((String, String) -> Unit)? = null
) : UndoableAction {
  override val description: String = "Edit note"

  override fun undo() {
    val card = cardsList.find { it.id == cardId }
    if (card != null) {
      card.text = prevText
      card.fontSize = prevFontSize
      card.isBold = prevBold
      card.isItalic = prevItalic
      card.isUnderline = prevUnderline
      card.isStrikethrough = prevStrike
      card.textColor = prevTextColor
      card.textStyleName = prevStyleName
      onTextChanged?.invoke(cardId, prevText)
    }
  }

  override fun redo() {
    val card = cardsList.find { it.id == cardId }
    if (card != null) {
      card.text = newText
      card.fontSize = newFontSize
      card.isBold = newBold
      card.isItalic = newItalic
      card.isUnderline = newUnderline
      card.isStrikethrough = newStrike
      card.textColor = newTextColor
      card.textStyleName = newStyleName
      onTextChanged?.invoke(cardId, newText)
    }
  }
}

/**
 * Atomic compound action combining multiple actions together.
 */
class CompoundAction(
  val actions: List<UndoableAction>,
  override val description: String
) : UndoableAction {
  override fun undo() {
    for (i in actions.indices.reversed()) {
      actions[i].undo()
    }
  }

  override fun redo() {
    for (a in actions) {
      a.redo()
    }
  }
}

/**
 * Adding a text or region highlight annotation directly in the PDF document.
 */
class AddAnnotationAction(
  val annotation: NativeAnnotation,
  private val annotationsList: MutableList<NativeAnnotation>,
  private val onUndoDispatched: ((NativeAnnotation) -> Unit)? = null,
  private val onRedoDispatched: ((NativeAnnotation) -> Unit)? = null
) : UndoableAction {
  override val description: String = "PDF Highlight (p. ${annotation.pageNumber})"

  override fun undo() {
    annotationsList.removeAll { it.id == annotation.id }
    onUndoDispatched?.invoke(annotation)
  }

  override fun redo() {
    if (annotationsList.none { it.id == annotation.id }) {
      annotationsList.add(annotation)
    }
    onRedoDispatched?.invoke(annotation)
  }
}

/**
 * Deleting a highlight annotation from the PDF document.
 */
class DeleteAnnotationAction(
  val annotation: NativeAnnotation,
  private val annotationsList: MutableList<NativeAnnotation>,
  private val onUndoDispatched: ((NativeAnnotation) -> Unit)? = null,
  private val onRedoDispatched: ((NativeAnnotation) -> Unit)? = null
) : UndoableAction {
  override val description: String = "Erase PDF Highlight (p. ${annotation.pageNumber})"

  override fun undo() {
    if (annotationsList.none { it.id == annotation.id }) {
      annotationsList.add(annotation)
    }
    onUndoDispatched?.invoke(annotation)
  }

  override fun redo() {
    annotationsList.removeAll { it.id == annotation.id }
    onRedoDispatched?.invoke(annotation)
  }
}

/**
 * Master Undo/Redo Manager for the ThinkSpace Workspace Engine.
 * Implements a bounded historical timeline matching LiquidText's responsiveness.
 */
class UndoRedoManager(
  var maxStackSize: Int = 50
) {
  private val undoStack: ArrayDeque<UndoableAction> = ArrayDeque()
  private val redoStack: ArrayDeque<UndoableAction> = ArrayDeque()

  var onStateChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null

  val canUndo: Boolean
    get() = undoStack.isNotEmpty()

  val canRedo: Boolean
    get() = redoStack.isNotEmpty()

  fun record(action: UndoableAction) {
    undoStack.addLast(action)
    while (undoStack.size > maxStackSize) {
      undoStack.removeFirst()
    }
    redoStack.clear()
    notifyState()
  }

  fun undo(): UndoableAction? {
    if (undoStack.isEmpty()) return null
    val action = undoStack.removeLast()
    action.undo()
    redoStack.addLast(action)
    notifyState()
    return action
  }

  fun redo(): UndoableAction? {
    if (redoStack.isEmpty()) return null
    val action = redoStack.removeLast()
    action.redo()
    undoStack.addLast(action)
    notifyState()
    return action
  }

  fun clear() {
    undoStack.clear()
    redoStack.clear()
    notifyState()
  }

  private fun notifyState() {
    onStateChanged?.invoke(canUndo, canRedo)
  }
}
