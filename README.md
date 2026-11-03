# NovaPDF: Reader, Editor & ReadEra-Style TTS

A modern, high-performance Android PDF reader and annotator optimized for **smartphones, foldables, and tablets**, engineered using architectural insights reverse-engineered from **Xodo** (`com.xodo.pdf.reader`) and **ReadEra** (`org.readera`) with **REA** (`morluto/rea`).

---

## 🌟 Key Highlights & Feature Matrix

### 1. ReadEra-Style "Read Out Loud" (TTS) Suite
- **Interactive Tap-to-Read:** Tap directly on any sentence or line on the page to jump speech to that exact position instantly.
- **Synchronized Visual Highlighting:** Uses Android's `UtteranceProgressListener` and character range callbacks to render a live, pulsing, glowing highlight over the actively spoken sentence and lines.
- **Granular Speech Control:**
  - Speed adjustment from **0.5x to 3.0x** (quick-cycle speed pill: 0.75x, 1.0x, 1.25x, 1.5x, 2.0x).
  - Voice pitch modulation (0.5x to 2.0x).
  - Sleep timer (15, 30, 45, 60 minutes).
- **Voice & Accent Customization:** Lists all available offline and high-quality system voices grouped by language and dialect (e.g. US, UK, Indian, Canadian, Australian English, and global languages).
- **Background Media Playback:** Runs via `SpeechForegroundService` with `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. Users can turn off their screen or use other apps while controlling playback from lock-screen notifications and Bluetooth headsets.
- **Auto-Turn Page:** Automatically flips to the next page and continues reading when the bottom of a page is reached.

### 2. Xodo-Grade Stylus & Annotation Suite
- **Stylus S-Pen & Palm Rejection:** Distinguishes stylus (`TOOL_TYPE_STYLUS`) from finger touches (`TOOL_TYPE_FINGER`). Fingers pan/scroll while the stylus draws seamlessly.
- **Vector Bézier Smoothing:** Freehand ink captures high-precision input points and interpolates smooth quadratic Bézier curves with pressure sensitivity.
- **Highlighter with Multiply Blend Mode:** Freehand and snapped highlights retain 100% underlying text legibility without muddying fonts.
- **Geometric Shapes:** Rectangles, Ovals, Lines, and Arrows with customizable line widths and colors.
- **Signature Stamp:** Modal vector signing pad to create and insert handwritten signatures into contracts.
- **Text Markup:** Snapped Underlines and Strikeouts.
- **Complete Undo / Redo:** Full command-history stack supporting multi-level undo, redo, and canvas clearing.
- **Eraser:** Proximity-based vector stroke erasing.

### 3. Multi-Document Tabs & Tablet UX
- **Document Tab Strip:** Browser-like tabs at the top for opening and switching between multiple PDF documents.
- **Adaptive Dual-Page Spread:** Automatically switches to side-by-side two-page reading mode with a book spine on tablets in landscape orientation.
- **Navigation Drawers:**
  - Page Thumbnail Grid drawer with live page preview.
  - Table of Contents (Outline) navigation tree.
  - Bookmarks manager with active page toggling.
- **Reading Themes:** Light, Dark, Sepia, and Night high-contrast modes.

---

## 🏗️ Architecture & Technology Stack

```
com.novapdf.reader/
├── engine/
│   ├── PdfEngine.kt               # PdfRenderer wrapper with LRU Bitmap Cache
│   ├── PdfTextExtractor.kt        # Page text extractor & line/sentence layout builder
│   └── AnnotationEngine.kt        # Vector annotation store with Undo/Redo history
├── model/
│   ├── AnnotationModels.kt        # Sealed class hierarchy for vector annotations
│   ├── DocumentTab.kt             # Multi-document tab data model
│   ├── PdfDocumentModel.kt        # Document metadata & page dimensions
│   ├── TextBounds.kt              # Normalized character, line & sentence bounding boxes
│   └── TtsModels.kt               # Speech position, voices & settings models
├── tts/
│   ├── SentenceTokenizer.kt       # Natural sentence boundary segmentation
│   ├── SpeechForegroundService.kt # Foreground service with Media Notification
│   └── TtsController.kt           # Central coordinator for Android TextToSpeech engine
└── ui/
    ├── MainActivity.kt            # Edge-to-edge Activity with SAF & intent filters
    ├── annotations/
    │   ├── AnnotationOverlayCanvas.kt # Low-latency drawing layer with palm rejection
    │   ├── AnnotationStylePicker.kt   # Color palette & stroke thickness sheet
    │   ├── SignatureDialog.kt         # Vector signature creation modal
    │   └── StylusToolbar.kt           # Xodo-style toolbar
    ├── navigation/
    │   ├── BookmarksDrawer.kt         # Saved bookmarks list
    │   ├── OutlineDrawer.kt           # Table of contents tree
    │   └── ThumbnailDrawer.kt         # Page thumbnail grid
    ├── tabs/
    │   └── DocumentTabBar.kt          # Top multi-document tab strip
    ├── theme/
    │   ├── Color.kt & Theme.kt        # Material 3 colors & reading theme modes
    │   └── Type.kt
    ├── tts/
    │   ├── TtsBottomBar.kt            # ReadEra audio playback control bar
    │   ├── TtsSettingsDialog.kt       # Speed, pitch & sleep timer modal
    │   └── VoiceSelectionSheet.kt     # Accent & voice selector sheet
    └── viewer/
        ├── DualPageSpreadView.kt      # Tablet-optimized dual page spread
        ├── PageCanvasView.kt          # Page bitmap + annotations + glowing TTS highlight
        ├── PdfViewerScreen.kt         # Master viewer screen
        └── ZoomableContainer.kt       # Gesture pinch-to-zoom container
```

---

## 🔬 Reverse-Engineering Findings (Xodo & ReadEra via REA)

Inspection of `Xodo.apk` and `ReadEra.apk` provided several foundational findings:

1. **Xodo Native Core:** Bundles `lib/arm64-v8a/libPDFNetC.so` (62.5 MB) from Apryse (PDFTron). To avoid proprietary licensing encumbrances, NovaPDF replaces this with an open-source architecture using Android's native `PdfRenderer` hardware pipeline paired with an asynchronous LRU tile cache.
2. **ReadEra TTS Layout & Purity:** `ReadEra.apk` has no native `lib/` binaries; its reading surface (`ReadSurface`) and TTS engine (`SpeechService`) are implemented purely on top of Android standard APIs (`android.speech.tts.TextToSpeech`, `UtteranceProgressListener.onRangeStart`, and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`). NovaPDF adopted this clean, battery-efficient approach for line-by-line speech tracking and background notifications.

---

## 🚀 Building & Running

### Prerequisites
- Android Studio Ladybug (2024.2+) or newer
- JDK 17 or JDK 21
- Android SDK 35 (Android 15)

### Command-Line Build
```bash
# Debug APK Build
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug
```

