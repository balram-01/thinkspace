# thinkspace

A high-performance native workspace engine for React Native, designed for document-centric research and thinking applications. Inspired by tools like LiquidText and Muse, ThinkSpace combines hardware-accelerated PDF rendering with an infinite 2D canvas, cross-zone lift-and-drag excerpting, fluid handwriting, and persistent semantic **PDF ↔ Card InkLinks**.

---

## Key Features

- **Split-Screen Workspace**: Smoothly resizable split-view featuring an active PDF reader on top and an infinite 2D canvas below.
- **Multi-Document Support**: Switch between multiple PDF documents with dedicated accent colors, tab management, and cross-document excerpt tracking.
- **Cross-Zone Lift-and-Drag**: Select text or crop any region from a PDF and drag it directly across the split line into a card on the workspace with a live 3D elastic tether.
- **Semantic InkLinks (PDF ↔ Card Connections)**: Draw with the pen from PDF text to any card on the canvas to form an interactive semantic link. Displays a LiquidText-style circular **V-marker** anchor on the card, moves with the card as it is dragged, and tapping the marker jumps directly back to the exact PDF source location.
- **Customizable Inking Engine**: Freehand and straight-line drawing modes, pressure simulation, dynamic color palette, stroke thickness control, highlighter, and eraser.
- **Infinite Canvas Camera**: Multi-touch pinch-to-zoom, pan, velocity inertia, and programmatic `zoomToFit()`.
- **Floating Notebook Pages**: Add customizable lined, grid, dotted, Cornell, and blank notebook cards to the canvas.
- **Document Squeeze (Pinch-to-Squeeze)**: Fold un-annotated passages accordion-style to bring distant excerpts together.
- **Distraction-Free Immersive Mode**: Single-tap toggle to hide system bars and maximize reading space.
- **Native Incremental Search**: High-performance in-document keyword search with match counter, highlights, and jump-to-result animations.
- **Undo / Redo Stack**: Comprehensive native history management for ink strokes, cards, notebook pages, and links.

---

## Table of Contents

1. [Installation](#installation)
2. [Native Setup](#native-setup)
   - [Android](#android)
   - [iOS](#ios)
3. [Quick Start](#quick-start)
4. [Component Props](#component-props)
5. [Imperative Ref Methods](#imperative-ref-methods-thinkspaceviewref)
6. [Native Event Callbacks](#native-event-callbacks)
7. [Core Concepts](#core-concepts)
   - [Multi-Document Management](#multi-document-management)
   - [Semantic InkLinks](#semantic-inklinks)
   - [Cross-Zone Lift & Drag](#cross-zone-lift--drag)
   - [Pinch-to-Squeeze](#pinch-to-squeeze)
8. [Complete TypeScript Example](#complete-typescript-example)
9. [Architecture & Performance](#architecture--performance)
10. [License](#license)

---

## Installation

```sh
# Using npm
npm install thinkspace

# Using yarn
yarn add thinkspace
```

### Peer Dependencies

Ensure your project has the required dependencies installed:

```sh
yarn add react-native react-native-safe-area-context
```

---

## Native Setup

### Android

1. **Minimum SDK**: Open `android/build.gradle` or `android/app/build.gradle` and verify:
   ```groovy
   minSdkVersion = 24
   compileSdkVersion = 35
   targetSdkVersion = 34
   ```

2. **Permissions**: Ensure your `AndroidManifest.xml` includes internet and storage permissions if loading remote or local file URIs:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
   ```

3. **Kotlin Version**: Recommend Kotlin `1.9.0` or higher.

### iOS

1. Run CocoaPods in your iOS project folder:
   ```sh
   cd ios && pod install && cd ..
   ```

2. Minimum iOS deployment target is **iOS 13.4** or higher.

---

## Quick Start

```tsx
import React, { useRef, useState } from 'react';
import { View, StyleSheet, SafeAreaView, Button } from 'react-native';
import { ThinkspaceView, ThinkspaceViewRef, WorkspaceDocument } from 'thinkspace';

const sampleDoc: WorkspaceDocument = {
  id: 'doc-1',
  title: 'Sample Research Paper',
  uri: 'https://example.com/research.pdf',
  pageCount: 12,
  size: '1.4 MB',
};

export default function App() {
  const thinkspaceRef = useRef<ThinkspaceViewRef>(null);
  const [splitRatio, setSplitRatio] = useState(0.45);
  const [penColor, setPenColor] = useState('#00ADB5');

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.toolbar}>
        <Button title="Zoom to Fit" onPress={() => thinkspaceRef.current?.zoomToFit()} />
        <Button title="Search" onPress={() => thinkspaceRef.current?.openSearch()} />
        <Button title="Undo" onPress={() => thinkspaceRef.current?.undo()} />
      </View>

      <ThinkspaceView
        ref={thinkspaceRef}
        style={styles.workspace}
        document={sampleDoc}
        activeDocumentId="doc-1"
        splitRatio={splitRatio}
        onSplitRatioChange={setSplitRatio}
        activeTool="pen"
        penColor={penColor}
        penThickness={3.5}
        penMode="freehand"
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0B0F19' },
  toolbar: { flexDirection: 'row', justifyContent: 'space-around', padding: 8 },
  workspace: { flex: 1 },
});
```

---

## Component Props

| Prop | Type | Default | Description |
|---|---|---|---|
| `document` | `WorkspaceDocument` | `undefined` | Single active document data (URI, title, pageCount). |
| `workspaceDocuments` | `WorkspaceDocument[]` | `[]` | Multi-document tab array for multi-doc workspaces. |
| `activeDocumentId` | `string` | `""` | The ID of the document currently visible in the PDF viewport. |
| `splitRatio` | `number` | `0.45` | Ratio dividing the PDF viewport from the 2D workspace (0.1 to 0.9). |
| `isSqueezed` | `boolean` | `false` | Whether un-annotated document sections are collapsed accordion-style. |
| `isImmersive` | `boolean` | `false` | Distraction-free full-screen mode hiding native system status/nav bars. |
| `activeTool` | `'select' \| 'pen' \| 'highlighter' \| 'eraser' \| 'lasso'` | `'select'` | Active interaction mode on the workspace. |
| `selectedColor` | `string` | `'#00ADB5'` | Primary highlight / annotation color hex. |
| `pattern` | `'blank' \| 'ruled' \| 'grid' \| 'looseleaf'` | `'looseleaf'` | Background grid/ruling pattern of the 2D canvas. |
| `strokes` | `InkStroke[]` | `[]` | Array of freehand ink strokes drawn on the canvas. |
| `excerpts` | `ConceptCard[]` | `[]` | Array of excerpt and concept cards positioned on the canvas. |
| `notebookPages` | `NotebookPage[]` | `[]` | Array of notebook page cards placed on the canvas. |
| `semanticInkLinks` | `SemanticInkLink[]` | `[]` | Array of persistent PDF ↔ Card semantic connections. |
| `penMode` | `'freehand' \| 'straight'` | `'freehand'` | Pen drawing mode. |
| `penColor` | `string` | `'#00ADB5'` | Dynamic stroke color for pen and ink links. |
| `penThickness` | `number` | `3.5` | Thickness in density-independent pixels. |
| `penFavorites` | `string[]` | `[...]` | Quick-select favorite color hex codes for the pen. |
| `panX` | `number` | `0` | Canvas horizontal camera offset. |
| `panY` | `number` | `0` | Canvas vertical camera offset. |
| `scale` | `number` | `1.0` | Canvas zoom scale factor. |

---

## Imperative Ref Methods (`ThinkspaceViewRef`)

Access all native engine commands programmatically via `ref`:

```tsx
const thinkspaceRef = useRef<ThinkspaceViewRef>(null);
```

### Search & Navigation
- **`openSearch(): void`**: Opens the native search bar with auto-focused keyboard.
- **`closeSearch(): void`**: Closes the search panel and clears search match highlights.
- **`nextMatch(): void`**: Scrolls to the next search match in the document.
- **`prevMatch(): void`**: Scrolls to the previous search match.
- **`search(query: string): void`**: Programmatically initiates an incremental search query.
- **`scrollToPage(pageNumber: number): void`**: Smoothly scrolls the PDF viewer to a specific page.
- **`switchToDocument(documentId: string): void`**: Switches the active document in the viewer.

### Viewport & Camera
- **`zoomToFit(): void`**: Calculates bounding boxes of all cards and strokes, animating the camera to fit all content with comfortable padding.
- **`zoomOut(): void`**: Re-centers and zooms out the workspace.
- **`setViewport(x: number, y: number, scale: number): void`**: Explicitly animates the workspace camera to a given coordinate and zoom level.

### History (Undo / Redo)
- **`undo(): void`**: Undoes the previous workspace action (card creation, move, pen stroke, inklink).
- **`redo(): void`**: Re-applies the most recently undone action.

### Pen & Inking
- **`setPenMode(mode: 'freehand' | 'straight'): void`**: Sets the pen mode to organic freehand or auto-straightened lines.
- **`setPenColor(colorHex: string): void`**: Sets the current pen and InkLink color.
- **`setPenThickness(thickness: number): void`**: Sets the stroke width.
- **`setPenFavorites(favorites: string[]): void`**: Configures the quick-select color palette.
- **`togglePenSettings(): void`**: Toggles the floating inking settings popover.

### Cards & Workspace Operations
- **`deleteCard(cardId: string): void`**: Removes a card and cleans up attached InkLinks.
- **`deleteInkLink(linkId: string): void`**: Deletes a specific semantic connection link.
- **`clearAllCards(): void`**: Clears all cards and InkLinks from the workspace.
- **`clearAllStrokes(): void`**: Clears all canvas handwriting strokes.
- **`clearSelection(): void`**: Clears active text or crop selections in the PDF.
- **`addNotebookPage(style?: string, title?: string): void`**: Adds a notebook page (`'ruled'`, `'grid'`, `'dotted'`, `'cornell'`, `'blank'`).

### Split & Layout
- **`setSplitRatio(ratio: number): void`**: Sets the split ratio between reader and canvas (0.1 to 0.9).
- **`toggleSqueezeMode(): void`**: Toggles the accordion squeeze view of the PDF.
- **`toggleImmersiveMode(): void`**: Toggles distraction-free full-screen reading mode.
- **`setImmersiveMode(enabled: boolean): void`**: Explicitly sets full-screen immersive mode.
- **`setActiveTool(tool: string): void`**: Switches the active tool (`'select'`, `'pen'`, `'highlighter'`, etc.).

---

## Native Event Callbacks

| Callback | Signature | Description |
|---|---|---|
| **`onInkLinkCreate`** | `(link: SemanticInkLink) => void` | Fired when a user drags a pen stroke from PDF text to a card, creating a permanent connection. |
| **`onInkLinkDelete`** | `(linkId: string) => void` | Fired when an InkLink is deleted. |
| **`onRequestDocumentSwitch`** | `({ documentId, sourcePageNumber, cardId }) => void` | Fired when a user taps the **V-marker** badge on a card. The native engine automatically navigates to the PDF source. |
| **`onExtractExcerpt`** | `(data: ExtractedExcerpt) => void` | Fired when text or an image region is extracted and dropped onto the canvas. |
| **`onExcerptMoveEnd`** | `(id: string, x: number, y: number) => void` | Fired when a user finishes dragging a card on the canvas. |
| **`onExcerptPress`** | `(id: string) => void` | Fired when a card is selected. |
| **`onCardDelete`** | `(id: string) => void` | Fired when a card is removed from the canvas. |
| **`onChangeCardColor`** | `(id: string, color: string) => void` | Fired when a card's accent color is updated. |
| **`onUpdateCardComment`** | `(id: string, comment: string) => void` | Fired when a user edits notes or comments on a card. |
| **`onAddStroke`** | `(stroke: InkStroke) => void` | Fired when an inking stroke is completed. |
| **`onEraseStroke`** | `(id: string) => void` | Fired when a stroke is erased. |
| **`onPenStateChange`** | `(state: PenState) => void` | Fired when the pen color, thickness, mode, or palette changes. |
| **`onTransformChange`** | `({ panX, panY, scale }) => void` | Fired continuously as the 2D workspace is panned or zoomed. |
| **`onSplitRatioChange`** | `(ratio: number) => void` | Fired when the split divider is dragged. |
| **`onToggleSqueeze`** | `(isSqueezed: boolean) => void` | Fired when squeeze mode is toggled. |
| **`onToggleImmersive`** | `(isImmersive: boolean) => void` | Fired when full-screen immersive mode changes. |
| **`onUndoStateChange`** | `(canUndo: boolean, canRedo: boolean) => void` | Fired whenever the undo/redo stack availability changes. |
| **`onNotebookPageAdded`** | `(page: NotebookPage) => void` | Fired when a notebook card is added. |
| **`onNotebookPageMoved`** | `(id: string, x: number, y: number) => void` | Fired when a notebook card is dragged. |
| **`onNotebookPageDeleted`**| `(id: string) => void` | Fired when a notebook card is removed. |

---

## Core Concepts

### Multi-Document Management

ThinkSpace natively supports working with multiple documents simultaneously. Pass an array of `WorkspaceDocument` items to `workspaceDocuments`:

```tsx
const docs: WorkspaceDocument[] = [
  { id: 'doc-ai', title: 'Attention Is All You Need', uri: 'file:///path/doc1.pdf', colorAccent: '#6C5CE7' },
  { id: 'doc-nlp', title: 'BERT Paper', uri: 'file:///path/doc2.pdf', colorAccent: '#00ADB5' },
];

<ThinkspaceView
  workspaceDocuments={docs}
  activeDocumentId={activeDocId}
  onRequestDocumentSwitch={({ documentId, sourcePageNumber }) => {
    setActiveDocId(documentId);
    thinkspaceRef.current?.scrollToPage(sourcePageNumber);
  }}
/>
```

### Semantic InkLinks

Semantic InkLinks connect PDF sources directly to cards:

```
PDF SOURCE (Page 4, Paragraph 2)
      │
      │  Solid connecting line (customized pen color)
      │
      ▼
   [ V ] Circular Anchor Badge (snapped to Card)
      │
      ▼
 WORKSPACE CARD
```

1. Select the **Pen** tool.
2. Touch down on any text or highlight in the PDF viewport.
3. Drag downward across the split divider toward any card on the workspace.
4. Release over the card: A permanent `NativeInkLink` is created.
5. Moving the card dynamically moves the connection line and anchor with it.
6. Tapping the **V-marker** anchor jumps the PDF reader directly back to that document page and pulses the exact source bounds.

### Cross-Zone Lift & Drag

Select any text in the PDF to reveal the inline action bar, then drag the selection callout downward into the canvas. The engine renders a 3D elastic tether connecting the PDF source coordinates to the ghost card under your finger. When released, an excerpt card is automatically created on the canvas.

### Pinch-to-Squeeze

Pinching vertically inside the PDF viewer activates accordion compression. Un-annotated paragraphs smoothly fold, collapsing empty space and pulling your highlights and excerpted sections together for effortless synthesis.

---

## Complete TypeScript Example

```tsx
import React, { useRef, useState, useCallback } from 'react';
import {
  StyleSheet,
  View,
  Text,
  TouchableOpacity,
  SafeAreaView,
  StatusBar,
} from 'react-native';
import {
  ThinkspaceView,
  ThinkspaceViewRef,
  WorkspaceDocument,
  SemanticInkLink,
  ConceptCard,
  InkStroke,
} from 'thinkspace';

const documents: WorkspaceDocument[] = [
  {
    id: 'doc-1',
    title: 'Cognitive Architecture.pdf',
    uri: 'https://arxiv.org/pdf/2301.00001.pdf',
    pageCount: 18,
    colorAccent: '#00ADB5',
  },
  {
    id: 'doc-2',
    title: 'Visual Thinking Models.pdf',
    uri: 'https://arxiv.org/pdf/2301.00002.pdf',
    pageCount: 24,
    colorAccent: '#6C5CE7',
  },
];

export default function WorkspaceScreen() {
  const thinkspaceRef = useRef<ThinkspaceViewRef>(null);

  const [activeDocId, setActiveDocId] = useState('doc-1');
  const [splitRatio, setSplitRatio] = useState(0.45);
  const [activeTool, setActiveTool] = useState<'select' | 'pen' | 'highlighter'>('pen');
  const [penColor, setPenColor] = useState('#00ADB5');
  const [cards, setCards] = useState<ConceptCard[]>([]);
  const [strokes, setStrokes] = useState<InkStroke[]>([]);
  const [inkLinks, setInkLinks] = useState<SemanticInkLink[]>([]);
  const [canUndo, setCanUndo] = useState(false);
  const [canRedo, setCanRedo] = useState(false);

  // Switch document when user taps a card's V-marker
  const handleRequestDocumentSwitch = useCallback(
    ({ documentId, sourcePageNumber }: { documentId: string; sourcePageNumber: number }) => {
      setActiveDocId(documentId);
      thinkspaceRef.current?.scrollToPage(sourcePageNumber);
    },
    []
  );

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="light-content" />

      {/* Top Header & Toolbar */}
      <View style={styles.header}>
        {/* Document Tabs */}
        <View style={styles.tabContainer}>
          {documents.map((doc) => (
            <TouchableOpacity
              key={doc.id}
              style={[styles.tab, activeDocId === doc.id && styles.activeTab]}
              onPress={() => {
                setActiveDocId(doc.id);
                thinkspaceRef.current?.switchToDocument(doc.id);
              }}
            >
              <Text style={styles.tabText}>{doc.title}</Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Action Controls */}
        <View style={styles.actions}>
          <TouchableOpacity
            style={[styles.btn, activeTool === 'pen' && styles.activeBtn]}
            onPress={() => setActiveTool('pen')}
          >
            <Text style={styles.btnText}>Pen</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.btn, activeTool === 'select' && styles.activeBtn]}
            onPress={() => setActiveTool('select')}
          >
            <Text style={styles.btnText}>Select</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.btn, !canUndo && styles.disabledBtn]}
            disabled={!canUndo}
            onPress={() => thinkspaceRef.current?.undo()}
          >
            <Text style={styles.btnText}>Undo</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.btn}
            onPress={() => thinkspaceRef.current?.zoomToFit()}
          >
            <Text style={styles.btnText}>Fit</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.btn}
            onPress={() => thinkspaceRef.current?.openSearch()}
          >
            <Text style={styles.btnText}>Search</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Thinkspace Native Engine */}
      <ThinkspaceView
        ref={thinkspaceRef}
        style={styles.engine}
        workspaceDocuments={documents}
        activeDocumentId={activeDocId}
        splitRatio={splitRatio}
        onSplitRatioChange={setSplitRatio}
        activeTool={activeTool}
        penColor={penColor}
        penThickness={3.5}
        penMode="freehand"
        excerpts={cards}
        strokes={strokes}
        semanticInkLinks={inkLinks}
        onUndoStateChange={(undoAvailable, redoAvailable) => {
          setCanUndo(undoAvailable);
          setCanRedo(redoAvailable);
        }}
        onInkLinkCreate={(newLink) => setInkLinks((prev) => [...prev, newLink])}
        onInkLinkDelete={(id) => setInkLinks((prev) => prev.filter((l) => l.id !== id))}
        onRequestDocumentSwitch={handleRequestDocumentSwitch}
        onExtractExcerpt={(excerpt) => {
          setCards((prev) => [
            ...prev,
            {
              id: excerpt.id || `card-${Date.now()}`,
              text: excerpt.text,
              pageNumber: excerpt.pageNumber,
              color: excerpt.color,
              x: excerpt.x || 100,
              y: excerpt.y || 100,
              width: 240,
              height: 120,
              sourceDocumentId: excerpt.documentId,
            },
          ]);
        }}
        onAddStroke={(s) => setStrokes((prev) => [...prev, s])}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0B0F19' },
  header: {
    height: 52,
    backgroundColor: '#0F172A',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
  },
  tabContainer: { flexDirection: 'row' },
  tab: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
    backgroundColor: '#1E293B',
    marginRight: 8,
  },
  activeTab: { backgroundColor: '#00ADB5' },
  tabText: { color: '#F8FAFC', fontSize: 13, fontWeight: '600' },
  actions: { flexDirection: 'row', gap: 6 },
  btn: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 6,
    backgroundColor: '#1E293B',
  },
  activeBtn: { backgroundColor: '#00ADB5' },
  disabledBtn: { opacity: 0.4 },
  btnText: { color: '#F8FAFC', fontSize: 12, fontWeight: '500' },
  engine: { flex: 1 },
});
```

---

## Architecture & Performance

ThinkSpace runs a dual-layer architecture:
- **UI / Orchestration Layer (React Native & TypeScript)**: Handles business logic, document cataloging, state synchronization, and reactive layouts.
- **Rendering & Gesture Engine (Kotlin & Android Canvas / C++ Core)**: High-frequency touch sampling (120Hz/240Hz), Bezier spline interpolation, hardware-accelerated PDF tiled rendering, physics-based inertial scrolling, and matrix transformations execute directly in native code without JavaScript thread bottlenecks.

---

## License

MIT © 2026 Balram & ThinkSpace Team
