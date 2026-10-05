# AI Master Prompt for ThinkSpace Development

Copy and paste this prompt when instructing an AI assistant to develop, modify, or add features to this repository:

---

```markdown
You are working on `thinkspace`, a high-performance React Native document workspace library (inspired by LiquidText & Muse) with a native Android (Kotlin) and iOS engine.

CRITICAL RULES TO FOLLOW ON EVERY TASK:

1. REACT NATIVE LIBRARY CONTRACT:
   - This workspace is an npm library consumed by React Native apps.
   - The native Kotlin/C++ engine handles high-performance rendering (PDF tiling, 120Hz ink splines, touch physics, camera matrices).
   - React Native is the control & orchestration layer. EVERY native engine capability, state, gesture, action, and setting MUST be controllable, observable, and triggerable from React Native.

2. ALWAYS EXPOSE COMPLETE TYPES & METHODS:
   Whenever you add, modify, or extend any native engine functionality:
   - Imperative Methods: Add the method signature to `ThinkspaceViewRef` in `src/types.ts`, wire it in `src/ThinkspaceView.native.tsx` (`useImperativeHandle`), and register the command in `ThinkspaceViewManager.kt` (`getCommandsMap` and `receiveCommand`).
   - Declarative Props: Add the prop to `ThinkspaceViewProps` in `src/types.ts`, `ThinkspaceViewNativeComponent.ts`, and `@ReactProp` in `ThinkspaceViewManager.kt`.
   - Events/Callbacks: Add direct event handlers to `ThinkspaceViewNativeComponent.ts`, `ThinkspaceViewProps`, and dispatch them from the native engine.
   - TypeScript Types: Always export clean, reusable types in `src/types.ts` (e.g. tools, modes, coordinates, items).

3. ALWAYS UPDATE `README.md`:
   - Never consider a feature complete without documenting it in the root `README.md`.
   - Update the Props table, Ref Methods table, or Events table with exact signatures, parameter descriptions, and defaults.
   - Provide a concise TypeScript copy-paste usage example showing how a developer uses the new feature.

4. CODE INTEGRITY & TESTING:
   - Keep TypeScript error-free: Always verify with `yarn tsc --noEmit` and `yarn lint`.
   - Verify native build: Always verify with `./gradlew compileDebugKotlin` in `example/android`.
   - Never break existing functionality, multi-document navigation, or InkLink attachment logic.
```

---

### Quick Copy-Paste One-Liner (Short Version):

> **Prompt:**  
> "Remember that `thinkspace` is a React Native library. For any native feature you add or modify in the Kotlin/native engine, you MUST: (1) fully expose its types, props, events, and imperative ref methods in `src/types.ts` and `src/ThinkspaceView.native.tsx`, (2) update the root `README.md` with API signatures and examples showing how to use it, and (3) verify with `yarn tsc --noEmit` and `./gradlew compileDebugKotlin`."
