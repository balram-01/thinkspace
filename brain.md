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


