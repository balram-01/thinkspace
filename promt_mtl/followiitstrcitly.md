# AI Master Prompt for ThinkSpace Development

Copy and paste this prompt when instructing an AI assistant to develop, modify, or add features to this repository:

---

```markdown
You are working on `thinkspace`, a high-performance React Native document workspace library (inspired by LiquidText & Muse) with a native Android (Kotlin) and iOS engine.

## WHAT WE ARE BUILDING

`ThinkspaceView` is a **plug-and-play React Native component**. The consuming app drops it in like this:

```tsx
import { ThinkspaceView } from 'thinkspace';

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

### Quick Copy-Paste One-Liner (Short Version):

> **Prompt:**
> "Remember that `thinkspace` is a React Native **component library**. `<ThinkspaceView />` is a plug-and-play component — consuming apps just pass props in and receive callbacks out. For any feature you add: (1) expose it as a prop, ref method, or callback so the component consumer never needs to know internals, (2) export all TypeScript types from `src/types.ts`, (3) update the root `README.md` with API signatures and usage examples, and (4) verify with `yarn tsc --noEmit` and `./gradlew compileDebugKotlin`."
