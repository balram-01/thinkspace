# AI Master Prompt for ThinkSpace Development
 
---
 
You are working on `thinkspace`, a high-performance React Native document workspace library (inspired by LiquidText & Muse) with a native Android (Kotlin) and iOS engine.

## WHAT WE ARE BUILDING

`ThinkspaceView` is a **plug-and-play React Native component**. The consuming app drops it in like this:

```tsx
import { ThinkspaceView } from 'thinkspace';
exmaple - 
<ThinkspaceView
  style={{ flex: 1 }}
  document={{ id: 'doc-1', title: 'Paper', uri: 'file:///paper.pdf', pageCount: 10 }}
  activeDocumentId="doc-1"
  activeTool="pen"
  penColor="#EF4444"
  penThickness={3.5}
  strokes={savedStrokes}
  excerpts={savedCards}
  semanticInkLinks={savedLinks}
  onAddStroke={(stroke) => saveStroke(stroke)}
  onExtractExcerpt={(card) => saveCard(card)}
  onInkLinkCreate={(link) => saveLink(link)}
/>
```

The consuming app does NOT need to know anything about internal rendering,
gesture handling, PDF tiling, canvas math, or native engine internals.
It only needs to:
- Pass props to configure and feed data into the component
- Optionally call ref methods for imperative control (undo, search, zoom, etc.)
- Optionally listen to callbacks to persist state changes

This is the fundamental contract that EVERY feature must respect.

## CRITICAL RULES TO FOLLOW ON EVERY TASK

1. COMPONENT-FIRST DESIGN — every feature must be usable purely via props:
   - Data flows IN via props (documents, strokes, cards, colors, tool, camera state).
   - Changes flow OUT via callbacks (onAddStroke, onExtractExcerpt, onInkLinkCreate, etc.).
   - Imperative actions (undo, zoom, search, switch doc) are exposed via `ThinkspaceViewRef`.
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

When implementing a new feature:
1. Read `brain.md`.
2. Identify the relevant existing context.
3. Inspect only the necessary source files.
4. Implement the feature.
5. Update `brain.md` with the final implementation context.
6. Update `README.md` with the public React Native API/documentation.

This keeps future development fast and prevents repeatedly searching the entire repository.