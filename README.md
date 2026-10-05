# thinkspace

A high-performance native workspace engine for React Native. Drop `<ThinkspaceView />` into your app, pass the props you need, and you get a complete **research workspace** — PDF reader, infinite 2D canvas, cross-zone excerpting, handwriting, semantic InkLinks, multi-document tabs, and more — all powered by a native Kotlin engine under the hood.

> **Your app doesn't need to know anything about the internals.** Just render the component, pass props, and optionally call ref methods when you need imperative control.

---

## Table of Contents

1. [Install](#install)
2. [Native Setup](#native-setup)
3. [The 30-Second Integration](#the-30-second-integration)
4. [How It Works (Mental Model)](#how-it-works-mental-model)
5. [All Props](#all-props)
6. [Imperative Ref Methods](#imperative-ref-methods)
7. [Event Callbacks](#event-callbacks)
8. [Recipes](#recipes)
   - [Minimal — Just Open a PDF](#recipe-1-minimal--just-open-a-pdf)
   - [Multi-Document Workspace](#recipe-2-multi-document-workspace)
   - [Pen + Highlighter with Color Picker](#recipe-3-pen--highlighter-with-color-picker)
   - [Persisting Strokes and Cards](#recipe-4-persisting-strokes-and-cards)
   - [Semantic InkLinks](#recipe-5-semantic-inklinks)
   - [Full Controlled Workspace](#recipe-6-full-controlled-workspace)
9. [TypeScript Types](#typescript-types)
10. [Architecture & Performance](#architecture--performance)
11. [License](#license)

---

## Install

```sh
yarn add thinkspace
# or
npm install thinkspace
```

### Peer Dependencies

```sh
yarn add react-native react-native-safe-area-context
```

---

## Native Setup

### Android

Open your `android/app/build.gradle` and verify:

```groovy
minSdkVersion = 24
compileSdkVersion = 35
targetSdkVersion = 34
```

Add permissions to `AndroidManifest.xml` if loading local or remote PDFs:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

### iOS

```sh
cd ios && pod install && cd ..
```

Minimum deployment target: **iOS 13.4**

---

## The 30-Second Integration

This is all you need to get a fully working PDF workspace in your app:

```tsx
import React from 'react';
import { StyleSheet, SafeAreaView } from 'react-native';
import { ThinkspaceView } from 'thinkspace';

export default function MyScreen() {
  return (
    <SafeAreaView style={styles.root}>
      <ThinkspaceView
        style={styles.workspace}
        document={{
          id: 'my-doc',
          title: 'My Research Paper',
          uri: 'https://example.com/paper.pdf',
          pageCount: 20,
        }}
        activeDocumentId="my-doc"
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  workspace: { flex: 1 },
});
```

That's it. The workspace boots up with a PDF reader, an infinite canvas below it, gesture handling, inking, and excerpt cards — fully working, zero additional wiring needed.

---

## How It Works (Mental Model)

```
Your React Native App
        │
        │  passes props ──────────────────────────────────────────┐
        │  calls ref methods (optional) ──────────────────────┐   │
        │  receives callbacks (optional) ─────────────────┐   │   │
        ▼                                                 │   │   │
 ┌─────────────────────────────────────────────────────────────────┐
 │                    <ThinkspaceView />                           │
 │  ┌────────────────────────┐  ┌────────────────────────────┐    │
 │  │     PDF Viewport       │  │    Infinite 2D Canvas      │    │
 │  │  - Page rendering      │  │  - Excerpt cards           │    │
 │  │  - Text selection      │  │  - Ink strokes             │    │
 │  │  - Search/highlight    │  │  - Notebook pages          │    │
 │  │  - Squeeze mode        │  │  - Semantic InkLinks       │    │
 │  └────────────────────────┘  └────────────────────────────┘    │
 │             Native Kotlin Engine (120Hz, hardware-accelerated)  │
 └─────────────────────────────────────────────────────────────────┘
        │                   │                   │
        ▼                   ▼                   ▼
   onAddStroke        onExtractExcerpt    onInkLinkCreate
   (you persist it)   (you save the card) (you save the link)
```

**Key principle:** The component owns all rendering and gesture logic. Your app only needs to:
- **Pass data in** via props (documents, strokes, cards, colors, tool)
- **React to events** via callbacks if you want to persist changes
- **Call ref methods** if you need to trigger things programmatically (undo, zoom, search, switch doc)

---

## All Props              

### Document Props

| Prop | Type | Default | Description |
|---|---|---|---|
| `document` | `WorkspaceDocument` | `undefined` | Single document to open. Use for single-doc workspaces. |
| `workspaceDocuments` | `WorkspaceDocumentEntry[]` | `[]` | Array of documents for multi-doc workspaces with tab switching. |
| `activeDocumentId` | `string` | `""` | ID of the currently visible document. |

### Layout Props

| Prop | Type | Default | Description |
|---|---|---|---|
| `style` | `ViewStyle` | — | Style applied to the root container (use `flex: 1` to fill parent). |
| `splitRatio` | `number` | `0.45` | Fraction of height given to the PDF reader (0.1 → 0.9). |
| `isSqueezed` | `boolean` | `false` | Accordion-compress un-annotated PDF sections. |
| `isImmersive` | `boolean` | `false` | Hides system bars for distraction-free reading. |
| `pattern` | `'blank' \| 'ruled' \| 'grid' \| 'looseleaf' \| 'dots' \| 'none'` | `'looseleaf'` | Canvas background pattern. |

### Tool & Inking Props

| Prop | Type | Default | Description |
|---|---|---|---|
| `activeTool` | `'select' \| 'pen' \| 'highlighter' \| 'eraser' \| 'lasso'` | `'select'` | The currently active interaction tool. |
| `selectedColor` | `string` | `'#00ADB5'` | Primary color for highlights and annotations. |
| `penMode` | `'freehand' \| 'straight'` | `'freehand'` | Pen drawing mode. |
| `penColor` | `string` | `'#00ADB5'` | Active pen/highlighter stroke color. |
| `penThickness` | `number` | `3.5` | Stroke width in dp. |
| `penFavorites` | `string[]` | built-in palette | Quick-select favorite colors shown in the pen panel. |

### Data Props

| Prop | Type | Default | Description |
|---|---|---|---|
| `strokes` | `InkStroke[]` | `[]` | Ink strokes drawn on the canvas. Pass saved strokes to restore them. |
| `excerpts` | `ConceptCard[]` | `[]` | Excerpt/concept cards positioned on the canvas. |
| `annotations` | `DocumentAnnotation[]` | `[]` | Text highlight annotations on the document. |
| `notebookPages` | `NotebookPage[]` | `[]` | Notebook page cards on the canvas. |
| `semanticInkLinks` | `SemanticInkLink[]` | `[]` | Persistent PDF ↔ Card connection links. |
| `inkLinks` | `InkLink[]` | `[]` | General ink link connections. |

### Camera Props

| Prop | Type | Default | Description |
|---|---|---|---|
| `panX` | `number` | `0` | Canvas horizontal camera offset. |
| `panY` | `number` | `0` | Canvas vertical camera offset. |
| `scale` | `number` | `1.0` | Canvas zoom level. |

---

## Imperative Ref Methods

Use a `ref` when you need to **programmatically trigger something** — like opening search from a toolbar button, undoing the last action, or switching documents.

```tsx
import { useRef } from 'react';
import { ThinkspaceView, ThinkspaceViewRef } from 'thinkspace';

const ref = useRef<ThinkspaceViewRef>(null);

// In JSX:
<ThinkspaceView ref={ref} ... />

// Call from anywhere:
ref.current?.undo();
ref.current?.openSearch();
ref.current?.zoomToFit();
```

### Search & Navigation

| Method | Signature | Description |
|---|---|---|
| `openSearch` | `() => void` | Opens the native search bar. |
| `closeSearch` | `() => void` | Closes the search panel. |
| `search` | `(query: string) => void` | Runs a search query programmatically. |
| `nextMatch` | `() => void` | Jump to next search result. |
| `prevMatch` | `() => void` | Jump to previous search result. |
| `scrollToPage` | `(pageNumber: number) => void` | Scroll to a specific page. |
| `switchToDocument` | `(documentId: string) => void` | Switch the active document tab. |

### History

| Method | Signature | Description |
|---|---|---|
| `undo` | `() => void` | Undo last action (stroke, card, link). |
| `redo` | `() => void` | Redo last undone action. |

### Camera & Viewport

| Method | Signature | Description |
|---|---|---|
| `zoomToFit` | `() => void` | Zoom to fit all content. |
| `zoomOut` | `() => void` | Zoom out and re-center. |
| `setViewport` | `(x: number, y: number, scale: number) => void` | Set camera position and zoom. |

### Pen & Inking

| Method | Signature | Description |
|---|---|---|
| `setPenMode` | `(mode: 'freehand' \| 'straight') => void` | Change drawing mode. |
| `setPenColor` | `(color: string) => void` | Change the pen color. |
| `setPenThickness` | `(thickness: number) => void` | Change stroke width. |
| `setPenFavorites` | `(favorites: string[]) => void` | Update the color palette. |
| `togglePenSettings` | `() => void` | Toggle the inking settings popover. |

### Tool Control

| Method | Signature | Description |
|---|---|---|
| `setActiveTool` | `(tool: WorkspaceTool) => void` | Switch active tool. |
| `clearSelection` | `() => void` | Clear any active text/region selection. |

### Cards & Canvas

| Method | Signature | Description |
|---|---|---|
| `deleteCard` | `(cardId: string) => void` | Remove a card and its InkLinks. |
| `deleteInkLink` | `(linkId: string) => void` | Delete a specific InkLink. |
| `clearAllCards` | `() => void` | Remove all cards and links. |
| `clearAllStrokes` | `() => void` | Remove all ink strokes. |
| `addNotebookPage` | `(style?: string, title?: string) => void` | Add a notebook card (`'ruled'`, `'grid'`, `'dotted'`, `'cornell'`, `'blank'`). |

### Layout

| Method | Signature | Description |
|---|---|---|
| `setSplitRatio` | `(ratio: number) => void` | Set the PDF/canvas split ratio. |
| `toggleSqueezeMode` | `() => void` | Toggle accordion squeeze mode. |
| `toggleImmersiveMode` | `() => void` | Toggle full-screen reading mode. |
| `setImmersiveMode` | `(enabled: boolean) => void` | Explicitly enable/disable immersive mode. |

---

## Event Callbacks

Callbacks let your app **react to things happening inside the workspace**. You don't have to use them all — only subscribe to what you need.

### Ink & Drawing

| Callback | Signature | When it fires |
|---|---|---|
| `onAddStroke` | `(stroke: InkStroke) => void` | A pen/highlighter stroke was completed. |
| `onEraseStroke` | `(id: string) => void` | A stroke was erased. |
| `onPenStateChange` | `(state: PenState) => void` | Color, thickness, mode, or palette changed. |

### Cards & Excerpts

| Callback | Signature | When it fires |
|---|---|---|
| `onExtractExcerpt` | `(data: ExtractedExcerpt) => void` | Text/image was dragged from PDF → canvas. |
| `onExcerptMoveEnd` | `(id: string, x: number, y: number) => void` | A card was dragged and dropped. |
| `onExcerptPress` | `(id: string) => void` | A card was tapped/selected. |
| `onCardDelete` | `(id: string) => void` | A card was deleted. |
| `onChangeCardColor` | `(id: string, color: string) => void` | A card's accent color changed. |
| `onUpdateCardComment` | `(id: string, comment: string) => void` | A card's comment was edited. |
| `onHoldCard` | `(id: string) => void` | A card was long-pressed. |

### InkLinks

| Callback | Signature | When it fires |
|---|---|---|
| `onInkLinkCreate` | `(link: SemanticInkLink) => void` | A PDF → Card semantic InkLink was created. |
| `onInkLinkDelete` | `(linkId: string) => void` | An InkLink was deleted. |

### Navigation & Layout

| Callback | Signature | When it fires |
|---|---|---|
| `onRequestDocumentSwitch` | `({ documentId, sourcePageNumber, cardId }) => void` | User tapped a V-marker badge to jump to PDF source. |
| `onSplitRatioChange` | `(ratio: number) => void` | The split divider was dragged. |
| `onTransformChange` | `({ panX, panY, scale }) => void` | Canvas was panned or zoomed. |
| `onToggleSqueeze` | `(isSqueezed: boolean) => void` | Squeeze mode toggled. |
| `onToggleImmersive` | `(isImmersive: boolean) => void` | Immersive mode toggled. |

### History

| Callback | Signature | When it fires |
|---|---|---|
| `onUndoStateChange` | `(canUndo: boolean, canRedo: boolean) => void` | Undo/redo availability changed. |

### Notebook Pages

| Callback | Signature | When it fires |
|---|---|---|
| `onNotebookPageAdded` | `(page: NotebookPage) => void` | A notebook card was added. |
| `onNotebookPageMoved` | `(id: string, x: number, y: number) => void` | A notebook card was dragged. |
| `onNotebookPageDeleted` | `(id: string) => void` | A notebook card was removed. |

---

## Recipes

### Recipe 1: Minimal — Just Open a PDF

```tsx
import { ThinkspaceView } from 'thinkspace';

export default function ReadingScreen() {
  return (
    <ThinkspaceView
      style={{ flex: 1 }}
      document={{
        id: 'paper-1',
        title: 'My PDF',
        uri: 'file:///storage/emulated/0/Download/paper.pdf',
        pageCount: 15,
      }}
      activeDocumentId="paper-1"
    />
  );
}
```

---

### Recipe 2: Multi-Document Workspace

Switch between multiple PDFs with accent colors per document:

```tsx
import React, { useState } from 'react';
import { View, TouchableOpacity, Text } from 'react-native';
import { ThinkspaceView } from 'thinkspace';

const DOCS = [
  { id: 'doc-a', title: 'Paper A', uri: 'file:///paper-a.pdf', pageCount: 10, colorAccent: '#6C5CE7' },
  { id: 'doc-b', title: 'Paper B', uri: 'file:///paper-b.pdf', pageCount: 8,  colorAccent: '#00ADB5' },
];

export default function MultiDocScreen() {
  const [activeDocId, setActiveDocId] = useState('doc-a');

  return (
    <View style={{ flex: 1 }}>
      {/* Your custom tab bar */}
      <View style={{ flexDirection: 'row' }}>
        {DOCS.map((doc) => (
          <TouchableOpacity key={doc.id} onPress={() => setActiveDocId(doc.id)}>
            <Text>{doc.title}</Text>
          </TouchableOpacity>
        ))}
      </View>

      {/* Workspace — just pass docs and the active ID */}
      <ThinkspaceView
        style={{ flex: 1 }}
        workspaceDocuments={DOCS}
        activeDocumentId={activeDocId}
        onRequestDocumentSwitch={({ documentId }) => setActiveDocId(documentId)}
      />
    </View>
  );
}
```

---

### Recipe 3: Pen + Highlighter with Color Picker

Your app controls the tool and color — the workspace handles all the drawing:

```tsx
import React, { useState } from 'react';
import { View, TouchableOpacity, Text } from 'react-native';
import { ThinkspaceView, WorkspaceTool } from 'thinkspace';

const COLORS = ['#EF4444', '#FACC15', '#22C55E', '#3B82F6', '#A855F7'];

export default function AnnotationScreen() {
  const [tool, setTool] = useState<WorkspaceTool>('pen');
  const [penColor, setPenColor] = useState('#EF4444');
  const [penThickness, setPenThickness] = useState(3.5);

  return (
    <View style={{ flex: 1 }}>
      {/* Your custom toolbar */}
      <View style={{ flexDirection: 'row', padding: 8 }}>
        <TouchableOpacity onPress={() => setTool('pen')}>
          <Text>Pen</Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={() => setTool('highlighter')}>
          <Text>Highlight</Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={() => setTool('eraser')}>
          <Text>Erase</Text>
        </TouchableOpacity>
        {COLORS.map((c) => (
          <TouchableOpacity
            key={c}
            onPress={() => setPenColor(c)}
            style={{ width: 24, height: 24, borderRadius: 12, backgroundColor: c, margin: 4 }}
          />
        ))}
      </View>

      {/* Component handles all drawing — you just pass tool + color */}
      <ThinkspaceView
        style={{ flex: 1 }}
        document={{ id: 'doc', title: 'Doc', uri: 'file:///doc.pdf', pageCount: 10 }}
        activeDocumentId="doc"
        activeTool={tool}
        penColor={penColor}
        penThickness={penThickness}
        penMode="freehand"
      />
    </View>
  );
}
```

---

### Recipe 4: Persisting Strokes and Cards

Listen to events and save data yourself (AsyncStorage, SQLite, server, etc.):

```tsx
import React, { useState } from 'react';
import { ThinkspaceView, InkStroke, ConceptCard } from 'thinkspace';

export default function PersistentWorkspace() {
  const [strokes, setStrokes] = useState<InkStroke[]>([]);
  const [cards, setCards] = useState<ConceptCard[]>([]);

  return (
    <ThinkspaceView
      style={{ flex: 1 }}
      document={{ id: 'doc', title: 'My Doc', uri: 'file:///doc.pdf', pageCount: 10 }}
      activeDocumentId="doc"

      // Pass saved data back in to restore the workspace
      strokes={strokes}
      excerpts={cards}

      // Save new strokes as they are drawn
      onAddStroke={(stroke) => setStrokes((prev) => [...prev, stroke])}
      onEraseStroke={(id) => setStrokes((prev) => prev.filter((s) => s.id !== id))}

      // Save new cards as they are created
      onExtractExcerpt={(excerpt) => {
        setCards((prev) => [
          ...prev,
          {
            id: excerpt.id ?? `card-${Date.now()}`,
            text: excerpt.text,
            pageNumber: excerpt.pageNumber,
            color: excerpt.color,
            x: excerpt.x ?? 100,
            y: excerpt.y ?? 100,
            width: 240,
            height: 120,
            sourceDocumentId: excerpt.documentId,
          },
        ]);
      }}

      // Update card position when dragged
      onExcerptMoveEnd={(id, x, y) =>
        setCards((prev) => prev.map((c) => (c.id === id ? { ...c, x, y } : c)))
      }

      // Remove deleted cards
      onCardDelete={(id) => setCards((prev) => prev.filter((c) => c.id !== id))}
    />
  );
}
```

---

### Recipe 5: Semantic InkLinks

Draw a line from a PDF passage to a card to create a permanent link. Tapping the badge jumps back to the source:

```tsx
import React, { useState } from 'react';
import { ThinkspaceView, SemanticInkLink } from 'thinkspace';

export default function InkLinkWorkspace() {
  const [inkLinks, setInkLinks] = useState<SemanticInkLink[]>([]);

  return (
    <ThinkspaceView
      style={{ flex: 1 }}
      workspaceDocuments={[
        { id: 'doc-1', title: 'Paper A', uri: 'file:///a.pdf', pageCount: 12, colorAccent: '#6C5CE7' },
        { id: 'doc-2', title: 'Paper B', uri: 'file:///b.pdf', pageCount: 9,  colorAccent: '#00ADB5' },
      ]}
      activeDocumentId="doc-1"
      activeTool="pen"

      // Pass saved links back to restore them
      semanticInkLinks={inkLinks}

      // Save new links
      onInkLinkCreate={(link) => setInkLinks((prev) => [...prev, link])}
      onInkLinkDelete={(id) => setInkLinks((prev) => prev.filter((l) => l.id !== id))}

      // Engine auto-navigates on badge tap
      onRequestDocumentSwitch={({ documentId }) => console.log('Jumped to', documentId)}
    />
  );
}
```

---

### Recipe 6: Full Controlled Workspace

A complete example with a custom toolbar, ref method calls, undo/redo state, and all common callbacks:

```tsx
import React, { useRef, useState } from 'react';
import { View, TouchableOpacity, Text, StyleSheet, SafeAreaView } from 'react-native';
import {
  ThinkspaceView,
  ThinkspaceViewRef,
  WorkspaceTool,
  InkStroke,
  SemanticInkLink,
  ConceptCard,
} from 'thinkspace';

export default function FullWorkspaceScreen() {
  const ref = useRef<ThinkspaceViewRef>(null);

  const [activeDocId, setActiveDocId] = useState('doc-1');
  const [splitRatio, setSplitRatio] = useState(0.45);
  const [tool, setTool] = useState<WorkspaceTool>('select');
  const [penColor] = useState('#00ADB5');
  const [strokes, setStrokes] = useState<InkStroke[]>([]);
  const [cards, setCards] = useState<ConceptCard[]>([]);
  const [inkLinks, setInkLinks] = useState<SemanticInkLink[]>([]);
  const [canUndo, setCanUndo] = useState(false);
  const [canRedo, setCanRedo] = useState(false);

  const docs = [
    { id: 'doc-1', title: 'Paper A', uri: 'file:///a.pdf', pageCount: 12, colorAccent: '#6C5CE7' },
    { id: 'doc-2', title: 'Paper B', uri: 'file:///b.pdf', pageCount: 8,  colorAccent: '#00ADB5' },
  ];

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: '#0B0F19' }}>

      {/* ── Your Custom Toolbar ── */}
      <View style={styles.toolbar}>
        {(['select', 'pen', 'highlighter', 'eraser'] as WorkspaceTool[]).map((t) => (
          <TouchableOpacity
            key={t}
            style={[styles.btn, tool === t && styles.btnActive]}
            onPress={() => setTool(t)}
          >
            <Text style={styles.btnText}>{t}</Text>
          </TouchableOpacity>
        ))}

        <TouchableOpacity
          style={[styles.btn, !canUndo && styles.btnDisabled]}
          disabled={!canUndo}
          onPress={() => ref.current?.undo()}
        >
          <Text style={styles.btnText}>Undo</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.btn, !canRedo && styles.btnDisabled]}
          disabled={!canRedo}
          onPress={() => ref.current?.redo()}
        >
          <Text style={styles.btnText}>Redo</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.btn} onPress={() => ref.current?.openSearch()}>
          <Text style={styles.btnText}>Search</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.btn} onPress={() => ref.current?.zoomToFit()}>
          <Text style={styles.btnText}>Fit</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.btn} onPress={() => ref.current?.addNotebookPage('ruled')}>
          <Text style={styles.btnText}>+ Note</Text>
        </TouchableOpacity>
      </View>

      {/* ── ThinkspaceView — Drop it in, pass props, done ── */}
      <ThinkspaceView
        ref={ref}
        style={{ flex: 1 }}

        workspaceDocuments={docs}
        activeDocumentId={activeDocId}

        splitRatio={splitRatio}
        onSplitRatioChange={setSplitRatio}

        activeTool={tool}
        penColor={penColor}
        penThickness={3.5}
        penMode="freehand"

        strokes={strokes}
        excerpts={cards}
        semanticInkLinks={inkLinks}

        onAddStroke={(s) => setStrokes((p) => [...p, s])}
        onEraseStroke={(id) => setStrokes((p) => p.filter((s) => s.id !== id))}
        onExtractExcerpt={(e) => setCards((p) => [
          ...p,
          {
            id: e.id ?? `c-${Date.now()}`,
            text: e.text,
            pageNumber: e.pageNumber,
            color: e.color,
            x: e.x ?? 80,
            y: e.y ?? 80,
            width: 220,
            height: 110,
            sourceDocumentId: e.documentId,
          },
        ])}
        onExcerptMoveEnd={(id, x, y) =>
          setCards((p) => p.map((c) => c.id === id ? { ...c, x, y } : c))
        }
        onCardDelete={(id) => setCards((p) => p.filter((c) => c.id !== id))}
        onInkLinkCreate={(l) => setInkLinks((p) => [...p, l])}
        onInkLinkDelete={(id) => setInkLinks((p) => p.filter((l) => l.id !== id))}
        onRequestDocumentSwitch={({ documentId }) => setActiveDocId(documentId)}
        onUndoStateChange={(u, r) => { setCanUndo(u); setCanRedo(r); }}
      />

    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  toolbar: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    padding: 8,
    backgroundColor: '#0F172A',
    gap: 6,
  },
  btn: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 6,
    backgroundColor: '#1E293B',
  },
  btnActive: { backgroundColor: '#00ADB5' },
  btnDisabled: { opacity: 0.35 },
  btnText: { color: '#F8FAFC', fontSize: 12, fontWeight: '600' },
});
```

---

## TypeScript Types

All types are exported from `thinkspace`:

```tsx
import type {
  // Documents
  WorkspaceDocument,
  WorkspaceDocumentEntry,

  // Tools & Modes
  WorkspaceTool,       // 'select' | 'pen' | 'highlighter' | 'eraser' | 'lasso'
  ToolContext,         // 'drawing' | 'document' | 'workspace'
  PenDrawingMode,      // 'freehand' | 'straight'

  // Ink & Drawing
  InkStroke,
  InkPoint,
  PenState,

  // Cards & Excerpts
  ConceptCard,
  ExcerptModel,
  DocumentAnnotation,

  // InkLinks
  SemanticInkLink,
  InkLink,
  InkLinkEndpoint,
  InkLinkPdfEndpoint,
  InkLinkCardEndpoint,

  // Notebook
  NotebookPage,
  NotebookPageStyle,   // 'ruled' | 'grid' | 'dotted' | 'cornell' | 'blank'

  // Camera
  WorkspaceViewport,

  // Ref & Props
  ThinkspaceViewRef,
  ThinkspaceViewProps,

  // PDF Engine
  PdfDocumentInfo,
  PdfTextPage,
  PdfSearchResult,
  PdfRenderedPage,
  PdfTextSelection,
} from 'thinkspace';
```

---

## Architecture & Performance

ThinkSpace is a **dual-layer** system:

| Layer | Technology | Responsibility |
|---|---|---|
| **Orchestration** | React Native / TypeScript | Props, state sync, callbacks, UI composition |
| **Engine** | Kotlin (Android) / C++ Core | 120Hz touch, Bezier splines, PDF tiling, physics, matrix transforms |

The split means **zero JavaScript thread bottlenecks** for drawing, zooming, or scrolling. Your app only communicates with the engine through props and refs — the heavy lifting never leaves native code.

---

## License

MIT © 2026 Balram & ThinkSpace Team
