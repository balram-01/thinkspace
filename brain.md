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
