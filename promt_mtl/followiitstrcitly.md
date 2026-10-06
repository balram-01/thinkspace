# AI Master Prompt for ThinkSpace Development
 
---
 
You are working on `thinkspace`, a high-performance React Native document workspace library (inspired by LiquidText & Muse) with a native Android (Kotlin) and iOS engine.

## WHAT WE ARE BUILDING

`ThinkspaceView` is a **plug-and-play React Native component** that contains the entire native workspace engine (PDF viewer, LiquidText-style accordion squeeze, infinite canvas, cards, ink links, and **native Document Drawer / Sheet with folder management**).

The consuming app drops it in like this:

```tsx
import { ThinkspaceView } from 'thinkspace';

<ThinkspaceView
  style={{ flex: 1 }}
  documents={workspaceDocs}
  activeDocumentId={activeDocId}
  folders={workspaceFolders}
  activeTool="pen"
  penColor="#EF4444"
  penThickness={3.5}
  strokes={savedStrokes}
  excerpts={savedCards}
  semanticInkLinks={savedLinks}
  onDocumentChange={(doc) => saveActiveDoc(doc)}
  onDocumentsUpdated={(docs, folders) => saveWorkspaceDocs(docs, folders)}
  onAddStroke={(stroke) => saveStroke(stroke)}
  onExtractExcerpt={(card) => saveCard(card)}
  onInkLinkCreate={(link) => saveLink(link)}
/>
```

### Self-Contained Native Architecture (Kotlin on Android / Swift on iOS)
- **Zero External Modals**: The consuming app does NOT build or wire external React Native modals (`DocumentsSheet`, `DocumentViewer`). Everything is rendered and animated natively at 60/120 FPS inside the native engine.
- **Native Document Header & Drawer**: Tapping the document pill `[Title (Pages) ▾]` in the header opens the native sliding drawer / bottom sheet containing the full LiquidText-style document list and folder accordion tree.
- **Native File Picker & Resolver**: Tapping "+ Add Document" invokes native Android SAF `Intent.ACTION_OPEN_DOCUMENT` directly in Kotlin, resolves the clean `DISPLAY_NAME` in memory, and persists permissions natively.
- **Native Folder & Action Dialogs**: Folder creation, renaming, deletion, and "Move to Folder" use native dialogs with direct in-memory updates and haptics.
- **Native Document Switching**: Selecting a document switches the native PDF renderer in microseconds without JS bridge round-trip latency.
- **Events Out to React Native**: When documents or folders change, native dispatches `onDocumentChange` and `onDocumentsUpdated` so the host app (e.g., `WorkspaceScreen.tsx`) can effortlessly persist the workspace to SQLite or remote storage.

The consuming app does NOT need to know anything about internal rendering,
gesture handling, PDF tiling, canvas math, or native engine internals.
It only needs to:
- Pass props to configure and feed data into the component
- Optionally call ref methods for imperative control (undo, search, zoom, openDocumentsSheet, etc.)
- Optionally listen to callbacks to persist state changes

This is the fundamental contract that EVERY feature must respect.

## CRITICAL RULES TO FOLLOW ON EVERY TASK

1. COMPONENT-FIRST DESIGN — every feature must be usable purely via props:
   - Data flows IN via props (documents, folders, strokes, cards, colors, tool, camera state).
   - Changes flow OUT via callbacks (onDocumentChange, onDocumentsUpdated, onAddStroke, onExtractExcerpt, onInkLinkCreate, etc.).
   - Imperative actions (undo, zoom, search, switch doc, openDocumentsSheet) are exposed via `ThinkspaceViewRef`.
   - The consuming app NEVER reaches into internal component state or native engine directly.

2. ALWAYS EXPOSE COMPLETE TYPES & METHODS:
   Whenever you add, modify, or extend any native engine functionality:
   - Imperative Methods: Add the method signature to `ThinkspaceViewRef` in `src/types.ts`,
     wire it in `src/ThinkspaceView.native.tsx` (`useImperativeHandle`), and register the
     command in `ThinkspaceViewManager.kt` (`getCommandsMap` and `receiveCommand`).
   - Declarative Props: Add the prop to `ThinkspaceViewProps` in `src/types.ts`,
     `ThinkspaceViewNativeComponent.ts`, and `@ReactProp` in `ThinkspaceViewManager.kt`.
   - Events/Callbacks: Add direct event handlers to `ThinkspaceViewNativeComponent.ts`,
     `ThinkspaceViewProps`, and dispatch them from the native engine.
   - TypeScript Types: Always export clean, reusable types in `src/types.ts`.

3. ALWAYS UPDATE `README.md`:
   - Never consider a feature complete without documenting it in the root `README.md`.
   - Update the Props table, Ref Methods table, or Events table with exact signatures,
     parameter descriptions, and defaults.
   - Provide a concise TypeScript copy-paste usage example showing how a developer
     uses the new feature as a component consumer (not as an internal developer).

4. CODE INTEGRITY & TESTING:
   - Keep TypeScript error-free: Always verify with `yarn tsc --noEmit` and `yarn lint`.
   - Verify native build: Always verify with `./gradlew compileDebugKotlin` in `example/android`.
   - Never break existing functionality, multi-document navigation, or InkLink attachment logic.
```

---

5. *Responsive UI*
    - Never rely on fixed screen sizes.
    - Support different phones, orientations where applicable, Dynamic
      Island/notches, small screens, large screens, and tablets where required.
    - Use responsive layouts, flexbox, safe-area insets, and adaptive dimensions.

6. *Apple-quality UX*
    - Prefer clean, minimal, native-feeling interactions.
    - Use proper spacing, typography hierarchy, touch targets,
      animations, sheets, alerts, and transitions.
    - Avoid unnecessary visual clutter.

---
 ### 5. ALWAYS MAINTAIN `brain.md` CONTEXT

`brain.md` is the persistent engineering context for this project.

Before starting any task:
- Read `brain.md` first.
- Use its existing context to understand the architecture, implemented features, important files, decisions, known issues, and current state.
- DO NOT unnecessarily rescan the entire codebase when the required context is already documented in `brain.md`.

After completing ANY meaningful feature, architecture change, bug fix, or important decision:
- UPDATE `brain.md` with the new context.
- Keep the information concise and practical.
- Record only information that will help future development.
- Update existing entries instead of creating duplicate/outdated information.

`brain.md` should contain:
- Current architecture
- Important modules/files and their responsibilities
- Implemented features
- Native Android/iOS implementations
- KMP/shared responsibilities
- React Native bridge/API surface
- Important coordinate/gesture/rendering systems
- Current known issues/bottlenecks
- Important technical decisions and WHY they were made
- Feature-specific implementation context
- Current TODOs / next steps
- Any important constraints or rules discovered during development

IMPORTANT:
`brain.md` is a CONTEXT MEMORY, NOT a full code dump.

Never paste large source files into `brain.md`.
Never document every minor code change.
Document the architecture and decisions that future tasks need to know.
## STRICT MODULAR ARCHITECTURE — NO GOD FILES

Build ThinkSpace using a clean, scalable, production-grade architecture.

NEVER put an entire feature into one file.

Every feature must be broken into small, focused files based on responsibility.

### CORE RULE

ONE FILE = ONE CLEAR RESPONSIBILITY.

Do NOT create giant files containing:
- UI
- state
- business logic
- persistence
- gestures
- rendering
- coordinate conversion
- native platform logic
- React Native bridge

These responsibilities MUST be separated.

### FEATURE STRUCTURE

Every major feature should follow this pattern:

features/
└── FeatureName/
    ├── model/
    ├── state/
    ├── repository/
    ├── service/
    ├── controller/
    ├── ui/
    └── mapper/

Only create layers that are actually needed. Do not create empty or meaningless files.

Example:

features/bookmarks/
├── model/
│   └── Bookmark.kt
├── state/
│   └── BookmarkState.kt
├── repository/
│   ├── BookmarkRepository.kt
│   └── BookmarkRepositoryImpl.kt
├── service/
│   └── BookmarkNavigationService.kt
├── controller/
│   └── BookmarkController.kt
└── ui/
    ├── BookmarkList.kt
    └── BookmarkRow.kt
When implementing a new feature:
1. Read `brain.md`.
2. Identify the relevant existing context.
3. Inspect only the necessary source files.
4. Implement the feature.
5. Update `brain.md` with the final implementation context.
6. Update `README.md` with the public React Native API/documentation.

This keeps future development fast and prevents repeatedly searching the entire repository.