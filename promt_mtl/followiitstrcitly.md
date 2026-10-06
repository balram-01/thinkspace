# ThinkSpace — Follow Strictly

### 1. Native First
Build the core engine natively: Android → Kotlin, iOS → Swift. React Native is only the public component/API layer.

### 2. Performance First
Prioritize 60/120 FPS, smooth gestures, PDF rendering, zoom/pan, fast interactions and minimal JS ↔ Native communication.

### 3. Responsive UI
Support phones, tablets, orientations, safe areas, notches and Dynamic Island. Never depend on fixed screen sizes.

### 4. Apple-Quality UX
Use clean, modern, native-feeling UI with proper spacing, typography, touch targets, light/dark themes and smooth subtle animations.

### 5. Proper Architecture
Keep UI, state, business logic, rendering, gestures, coordinates, persistence and bridge code separated. Use a proper feature-based folder structure.

### 6. No God Files
NEVER put an entire feature or engine into one file. Every file must have a clear responsibility; split large classes into focused modules.

### 7. Feature Isolation
Keep Documents, Folders, Bookmarks, Selection, Excerpts, Pen, InkLinks, Workspace and PDF Engine independently structured and maintainable.

### 8. Native Platform Code
Use the best native Android/iOS APIs when required. Do not force platform-specific functionality into shared or React Native code.

### 9. Reuse Existing Code
Before creating anything, inspect the existing architecture and reuse/extend existing components, services and models. Never duplicate functionality.

### 10. React Native Binding
Expose functionality through clean Props, Events, Ref Methods and TypeScript types. Never expose internal native engine implementation to the consumer.

### 11. Brain Context
Always read `brain.md` before development and update it after meaningful features, fixes or architectural changes. Keep it concise and current.

### 12. Safe Refactoring
When refactoring existing code, preserve all current functionality and behavior. Refactor incrementally; never blindly rewrite working systems.

### 13. Final Rule
**Native-first + performance-first + modular architecture + responsive UI + Apple-quality UX + no God files + no duplicated logic + never break existing functionality.**