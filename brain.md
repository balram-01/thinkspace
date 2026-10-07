# ThinkSpace Engineering Context & Architecture Memory

## Overview
`thinkspace` is a high-performance React Native document workspace library (inspired by LiquidText & Muse) with native Android (Kotlin) and iOS engines.

## Card Selection & Typography Action Bars (LiquidText Style)
- **Visual Design**: Apple-grade rounded capsule toolbar (52dp height, 26dp radius) with dual-layer ambient drop shadow and theme-aware styling:
  - Dark mode: `#1E2534` rich slate glass with `#475569` outline
  - Light mode: `#5A6B82` frosted slate glass with `#72849B` outline (matching LiquidText visual reference)
- **Action Buttons & Icons**:
  - `Comment`: Speech bubble vector icon (16dp) + bold label (10.5sp)
  - `Edit`: Pencil vector icon (16dp) + bold label (10.5sp)
  - `Copy`: Overlapping documents vector icon (16dp) + bold label (10.5sp)
  - `Delete`: Trash can vector icon (16dp) + bold label in `#F87171`
  - `Tags`: Price tag vector icon (16dp) + bold label (10.5sp)
  - `Color`: Vibrant `SweepGradient` rainbow swatch disc with inner card-color indicator dot
  - Hairline divider `|`
  - `Tᴛ`: Apple serif typography icon (16.5sp / 12sp) with active pill state
- **Typography Formatting Bar**:
  - `[ ↩ ]` (Undo / Back navigation), `Style` (13.5sp with sheet popover), `B` (Bold), `I` (Italic), `U` (Underline), `S` (Strikethrough), `0` (Font size step), `A` (Text color with swatch underline), `···` (More)
  - Active buttons highlighted with Apple blue pill `#2563EB`
- **Intelligent Non-Clipping Positioning**:
  - Horizontally centered relative to selected card, clamped within `[safeLeft, safeRight - barW]`.
  - Intelligently chooses above or below card based on vertical clearance; if card fills viewport vertically, docks safely near top of workspace.
  - Strictly clamped within `[safeTop, safeBottom - barH]`, guaranteeing the bar is never hidden behind split divider or bottom navigation bar.
- **Apple Physics Animation**:
  - Snappy spring interpolation (`cardActionBarAnimProgress`, 0.0 -> 1.0) with subtle scale (0.92 -> 1.0) and vertical translation (+8dp -> 0dp).
- **Platform Implementations**:
  - Android: `ThinkspaceView.kt` (`drawCardActionBar`, `drawTypographyBar`, vector icon helpers)
  - iOS: `CardSelectionToolbarView.swift` & `InfiniteCanvasView.swift`

## LiquidText-Style Hierarchical Document & Folder Management
- **Architecture**: Unified inline accordion / tree list within `DocumentsSheet.tsx`. No drill-down page transitions or back buttons; documents and folders live in the same scrollable list.
- **Hierarchical Tree Model**:
  - `WorkspaceFolder`: `{ id, name, parentId?, createdAt? }` supports arbitrary nested subfolder depth.
  - `WorkspaceDocumentEntry`: has optional `folderId?` indicating which folder currently houses the document.
- **Accordion Interaction**:
  - Tapping a folder toggles inline expand/collapse.
  - Dynamic folder icons: Closed folder 📁 (tabbed outline) vs Open folder 📂 (open front flap with perspective).
  - Indentation scales with depth: `paddingLeft = 16 + depth * 22`.
- **"Add to New Folder" Flow**:
  - Document `⋮` menu -> "Add to New Folder" -> launches native iOS-style centered alert dialog (`"New Folder"` / `"Enter name for new folder."` with "Okay" & "Cancel").
  - On submit: creates the folder at the document's current parent depth, moves the document inside it, and keeps outer folders expanded.
- **Document `⋮` Action Sheet (Streamlined & Focused)**:
  - Cleaned up to essential actions: `Rename`, `Add to New Folder`, `Move to Folder...`, `Delete`, `Replace Document`, `Tags & Metadata`.
  - Removed clutter options: `Show`, `Show in Parallel`, `Copy Link`, `OCR Text Detection`, `Page Editor`, `Visibility to Collaborators`, `Add First Tag`.
- **"Move to Folder" Dialog**:
  - Tapping `Move to Folder...` opens an Apple-style destination modal showing:
    - Root Level option (`Root Level (Remove from Folder)`) if document is inside a folder.
    - Full list of existing folders with hierarchical depth indentation (`depth * 20px`).
    - Highlights current folder with `✓ Current` badge.
    - Selecting any folder moves the document, auto-expands the destination folder in the tree view, and persists state via `onMoveDocumentToFolder`.
- **Native iOS Alert Dialogs**:
  - Authentic Apple-grade modal (`maxWidth: 290`, `#F2F2F7`, crisp hairline dividers, blue `#007AFF` action buttons, text input with clear `✕` button).

## PDF Display Name Resolution (SAF & Content Resolver)
- **Problem**: When importing PDFs on Android via Storage Access Framework (`ACTION_GET_CONTENT` / `ACTION_OPEN_DOCUMENT`), content URIs like `content://.../document%3A1000000029` resulted in raw IDs (e.g. `document%3A1000000029`) being used as the title when PDF metadata `/Title` was blank.
- **Fix**:
  - In `PdfEngineModule.kt`: Implemented `resolveDisplayName` using `OpenableColumns.DISPLAY_NAME` with cursor column indexing, MediaStore fallback for media documents (`MediaStore.Files.getContentUri("external")`), persistable URI permissions, and URL decoding.
  - In `ThinkspaceView.kt`: Header title prioritizes `activeDocument.title` over PDF metadata, and sanitizes any raw `document%` or `content:` identifiers.
  - In `App.tsx`: Added `cleanDocumentTitle` which prioritizes `file.name` from the picker over raw URIs, strips `.pdf`, decodes URI components, and falls back cleanly.

## Native Kotlin Document Drawer & Folder Management (Self-Contained Engine)
- **Component-First Encapsulation**: `ThinkspaceView` is a single plug-and-play component. Consuming apps do not need to wire or import external React Native modals (`DocumentsSheet`, `DocumentViewer`).
- **Trigger**: Tapping the document header pill `[Title (Pages) ▾]` (`headerDocPillRect`) in `ThinkspaceView.kt` or calling `ref.current.openDocumentsSheet()` launches the native `Dialog` directly in Kotlin on Android.
- **Native UI Architecture**:
  - Fullscreen translucent scrim (`#B3050C16`) with frosted dark slate card container (`#141D2B`, 18dp rounded corners, 28dp elevation).
  - Top header with Title (`📄 Documents`), `+ Doc` button (dispatches `onRequestAddDocument`), `📁+ Folder` button (launches native folder creation dialog), and `✕` close button.
  - Live search input (`EditText`) with real-time filtering across document titles and folder names.
  - Native `ScrollView` containing recursive accordion tree layout with depth indentation (`depth * 18dp`).
  - Folders: dynamic toggle icons (`▾ 📂` expanded vs `▸ 📁` collapsed), item count badges `(N)`, and `⋮` actions (Rename Folder, Delete Folder with nested item fallback to parent/root).
  - Documents: accent color indicator bar, `📄` icon, title, page count badge, active indicator with teal border & checkmark `✓`. Tapping instantly calls `switchToDocument(doc.id)` and dispatches `onDocumentChange`.
  - Document `⋮` actions: Rename Document, Add to New Folder, Move to Folder… (with root option), Delete Document.
- **Bi-directional Bridge Events & Props**:
  - Props: `documents?: WorkspaceDocumentEntry[]`, `folders?: WorkspaceFolder[]`, `activeDocumentId?: string`.
  - Callbacks: `onDocumentChange({ documentId, title, uri, pageCount })`, `onDocumentsUpdated(documents, folders)`, `onRequestAddDocument()`.
  - Ref Methods: `openDocumentsSheet()`, `closeDocumentsSheet()`.

## LiquidText-Style Area & Figure Crop Selection Architecture
- **Problem & Video Analysis (photoextractio.mp4)**:
  - Previously, long-press initialized a microscopic 32×24px dot, and when touched again, `crop.screenRect.contains(x, y)` immediately lifted it as an image excerpt card without ever letting the user see, frame, or resize a proper selection box.
- **Solution & Native Architecture**:
  - **No Auto-Lift on Long Press**: Touching/holding on an active selection keeps the selection active for repositioning or handle manipulation. It only lifts into an excerpt when the user explicitly taps `[ AutoExcerpt ]` on the floating toolbar or drags the selection box across the split divider.
  - **Handsome, Visible Selection Box**: On long-press, creates a generous, clearly visible `220dp × 150dp` selection box with ample room between handles and the floating toolbar (`AutoExcerpt`, `Comment`, Color Palette, `Tags`, `•••`).
  - **4 Drag-Resizable Corner Handles**: All 4 corners (Top-Left, Top-Right, Bottom-Left, Bottom-Right) rendered with circular accent fills and white borders, with an expanded 38dp touch target radius for effortless grab on mobile screens.
  - **Full 4-Way Handle Dynamics**:
    - Top-Left: Adjusts `left` and `top`.
    - Top-Right: Adjusts `right` and `top`.
    - Bottom-Left: Adjusts `left` and `bottom`.
    - Bottom-Right: Adjusts `right` and `bottom`.
    - Automatically enforces min-bounds constraints (`36dp` width, `28dp` height) and bounds clamping within page layout.
  - **Preserved Custom Selections**: Custom user-dragged rectangles are strictly preserved on release.
  - **Reposition & Workspace Extraction**: Dragging inside the selection body repositions the box; dragging across the split divider smoothly converts the framed region into a floating photo card excerpt on the infinite workspace canvas.

## Multi-Touch Gesture Arbitration: PDF Zoom In/Out & Pinch-to-Compare (Squeeze)
- **Root Cause of Previous Conflicts**:
  - `shouldRouteToScaleDetector` checked `pdfScaleFactor > 1.25f`, which completely blocked `ScaleGestureDetector` from receiving events at default 1.0x zoom.
  - `ACTION_POINTER_DOWN` immediately started `compressionEngine.onManualPinchBegin(...)` on touch-down, blocking zoom detection before fingers moved.
- **Unified Multi-Touch Architecture**:
  - **All 2+ finger gestures routed to `ScaleGestureDetector`**: Provides unified focal point, span tracking, and scaling.
  - **Natural State-Aware Gesture Resolution**:
    1. Spreading fingers (`scaleFactor > 1.0f`):
       - If document is currently squeezed (`compressionEngine.isAnyPageCompressed()`), expands squeezed pages back to normal.
       - If document is normal, smoothly zooms into PDF (1.0x to 5.0x).
    2. Pinching fingers inward (`scaleFactor < 1.0f`):
       - If zoomed in (`pdfScaleFactor > 1.02f`), smoothly zooms out toward 1.0x.
       - If at base 1.0x zoom, vertical pinch inward collapses unannotated pages to compare distant sections (LiquidText squeeze).
    3. Two-finger sliding while zoomed in pans `docScrollX` and `docScrollY` smoothly.
    4. One-tap squeeze toggle via `≈` tab on right edge remains fully functional.
  - **Tightened Word Hit Testing**: Direct bounding box testing (`px in b.left..b.right && py in b.top..b.bottom`) ensures long-pressing on images/whitespace triggers area crop framing without selecting words 48pt away.

## Split Divider Dragging & Workspace Smoothness Architecture
- **Problem & Root Cause Analysis (divider.mp4)**:
  - When dragging the split divider up and down, the workspace canvas vibrated and shook violently.
  - Three distinct root causes:
    1. **Lack of Drag Offset (`dividerDragOffsetY`)**: `newRatio = (sy / viewH)` was used without tracking the touch down offset `dividerDragOffsetY = sy - splitY`. Touching anywhere within the hit radius snapped the divider by 30–70px immediately on the first move, causing rapid jitter.
    2. **Bridge Flooding & Component Re-render Cycles**: Any `dispatchSplitRatioEvent` calls during `ACTION_MOVE` caused React Native to re-render `App.tsx` 15–60 times/sec. When `App.tsx` re-rendered, it pushed all props (`panX=0`, `panY=0`, `scale=1`, `splitRatio`) back down to Android. If `panY` was being modified, `setPanY(view, 0f)` reset it back to 0 on every re-render, causing the entire workspace to slam up and down by 100+ pixels.
    3. **Bridge Echo Overwriting Live Drag**: Delayed prop updates from React Native fought with the native UI touch thread.
- **Solution & Native Architecture**:
  - **Touch-Down Offset Tracking (`dividerDragOffsetY = sy - splitY`)**:
    - During `ACTION_MOVE`, `val targetSplitY = sy - dividerDragOffsetY` tracks the finger with 1:1 pixel fidelity and ZERO initial snap.
  - **100% Silent Bridge During Active Drag**:
    - Zero `dispatchSplitRatioEvent` calls are dispatched during `ACTION_MOVE`. The divider moves entirely on the native Android UI thread with hardware-accelerated 120 FPS rendering.
    - React Native never re-renders while dragging; zero bridge traffic, zero garbage collection pauses.
    - On `ACTION_UP`, the finalized `splitRatio` is dispatched once to persist state.
  - **Camera & Transform Protection in `ThinkspaceViewManager`**:
    - `setSplitRatio`, `setPanX`, `setPanY`, and `setScale` ignore incoming React Native prop updates while `isDraggingDivider` is true and for 600ms after release.
    - Camera values (`panX`, `panY`) are preserved and not overwritten with default zeros when the user has interacting natively.
  - **Clean SplitRatio Property**:
    - Direct, pure updates to `splitRatio` with bounds clamping (0.18f to 0.82f) without unexpected camera mutation side-effects.






