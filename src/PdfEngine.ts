/**
 * Thinkspace PDF Engine — TypeScript API
 *
 * Wraps the native Android `PdfEngineModule` bridge with a fully-typed, Promise-based API.
 * All coordinates returned are in PDF page space (points, 1/72 inch, top-left origin).
 *
 * ### Quick Example
 * ```ts
 * import { PdfEngine } from 'thinkspace';
 *
 * const doc = await PdfEngine.openDocument('file:///sdcard/test.pdf');
 * const words = await PdfEngine.extractText(doc.documentId, 0);
 * const hits  = await PdfEngine.searchDocument(doc.documentId, 'architecture');
 * const page  = await PdfEngine.renderPage(doc.documentId, 0, 1.5);
 * await PdfEngine.closeDocument(doc.documentId);
 * ```
 */

import { NativeModules, Platform } from 'react-native';
import type {
  PdfDocumentInfo,
  PdfTextPage,
  PdfPageAnalysis,
  PdfSearchResult,
  PdfRenderedPage,
  PdfTextSelection,
  PdfProcessingResult,
  PdfProcessingOptions,
} from './types';

// ── Guard: module is Android-only ─────────────────────────────────────────────

const LINKING_ERROR =
  `The native module 'PdfEngineModule' is not available. ` +
  `Make sure your Android project includes ':pdf-engine' and that ` +
  `you are running on a real device or emulator (not web/iOS).`;

function getNativeModule() {
  const mod = NativeModules.PdfEngineModule;
  if (!mod) {
    if (Platform.OS === 'android') {
      throw new Error(LINKING_ERROR);
    }
    // On iOS / web we return a no-op stub that always rejects
    const stub = new Proxy(
      {},
      {
        get: (_t, name) => () =>
          Promise.reject(
            new Error(`PdfEngine.${String(name)} is only available on Android.`)
          ),
      }
    );
    return stub as NativePdfEngineModule;
  }
  return mod as NativePdfEngineModule;
}

// ── Raw native module interface (mirrors Kotlin @ReactMethod signatures) ───────

interface NativePdfEngineModule {
  openDocument(
    uriOrPath: string,
    password: string | null
  ): Promise<PdfDocumentInfo>;
  extractText(docId: string, pageIndex: number): Promise<PdfTextPage>;
  analyzePage(docId: string, pageIndex: number): Promise<PdfPageAnalysis>;
  searchDocument(docId: string, query: string): Promise<PdfSearchResult[]>;
  renderPage(
    docId: string,
    pageIndex: number,
    scale: number
  ): Promise<PdfRenderedPage>;
  renderPageRegion(
    docId: string,
    pageIndex: number,
    left: number,
    top: number,
    right: number,
    bottom: number,
    scale: number
  ): Promise<PdfRenderedPage>;
  getSelectionGeometry(
    docId: string,
    pageIndex: number,
    startX: number,
    startY: number,
    endX: number,
    endY: number
  ): Promise<PdfTextSelection>;
  processDocument(
    docId: string,
    options: PdfProcessingOptions | null
  ): Promise<PdfProcessingResult>;
  pickPdfFile(): Promise<{ uri: string; name: string }>;
  closeDocument(docId: string): Promise<void>;
  shutdown(): Promise<void>;
}

// ── Public API singleton ───────────────────────────────────────────────────────

/**
 * Singleton PDF engine API. Use this to open, process, search, render, and
 * close PDF documents on Android.
 */
export const PdfEngine = {
  /**
   * Opens a PDF document from a URI or absolute file path.
   *
   * @param uriOrPath - `content://` URI, `file://` URI, or absolute path
   * @param password  - Optional decryption password for encrypted PDFs
   * @returns Document handle info (documentId, pageCount, title, author, …)
   *
   * @example
   * const doc = await PdfEngine.openDocument('file:///sdcard/sample.pdf');
   * console.log(doc.documentId, doc.pageCount);
   */
  async openDocument(
    uriOrPath: string,
    password?: string
  ): Promise<PdfDocumentInfo> {
    return getNativeModule().openDocument(uriOrPath, password ?? null);
  },

  /**
   * Extracts all words with exact bounding boxes from a single page.
   *
   * @param documentId - ID returned by `openDocument`
   * @param pageIndex  - Zero-based page index
   * @returns Page text data with word-level geometry
   *
   * @example
   * const page = await PdfEngine.extractText(doc.documentId, 0);
   * page.words.forEach(w => console.log(w.text, w.bounds));
   */
  async extractText(
    documentId: string,
    pageIndex: number
  ): Promise<PdfTextPage> {
    return getNativeModule().extractText(documentId, pageIndex);
  },

  /**
   * Performs geometric layout analysis — clusters words into lines, blocks,
   * paragraphs, and detects column count and headings.
   *
   * @param documentId - ID returned by `openDocument`
   * @param pageIndex  - Zero-based page index
   * @returns Structured layout analysis result
   */
  async analyzePage(
    documentId: string,
    pageIndex: number
  ): Promise<PdfPageAnalysis> {
    return getNativeModule().analyzePage(documentId, pageIndex);
  },

  /**
   * Full-document text search returning exact bounding boxes and context snippets.
   *
   * @param documentId - ID returned by `openDocument`
   * @param query      - Search string (case-insensitive substring match)
   * @returns Array of search matches with page positions and quads
   *
   * @example
   * const hits = await PdfEngine.searchDocument(doc.documentId, 'machine learning');
   * hits.forEach(h => console.log(`Found on page ${h.pageIndex}: "${h.matchedText}"`));
   */
  async searchDocument(
    documentId: string,
    query: string
  ): Promise<PdfSearchResult[]> {
    return getNativeModule().searchDocument(documentId, query);
  },

  /**
   * Renders a page to a PNG temp file. Use the returned `uri` with
   * `<Image source={{ uri }} />`.
   *
   * @param documentId - ID returned by `openDocument`
   * @param pageIndex  - Zero-based page index
   * @param scale      - Render scale factor (1.0 = 72 dpi, 2.0 = 144 dpi). Default 1.5
   * @returns Rendered page info with temp file URI, width, and height in pixels
   *
   * @example
   * const rendered = await PdfEngine.renderPage(doc.documentId, 0, 2.0);
   * // Use in React Native:
   * // <Image source={{ uri: rendered.uri }} style={{ width: rendered.width, height: rendered.height }} />
   */
  async renderPage(
    documentId: string,
    pageIndex: number,
    scale = 1.5
  ): Promise<PdfRenderedPage> {
    return getNativeModule().renderPage(documentId, pageIndex, scale);
  },

  /**
   * Renders a specific rectangular sub-region (crop) of a page as an image.
   * Useful for extracting figures, diagrams, and images from the PDF.
   */
  async renderPageRegion(
    documentId: string,
    pageIndex: number,
    left: number,
    top: number,
    right: number,
    bottom: number,
    scale = 2.0
  ): Promise<PdfRenderedPage> {
    return getNativeModule().renderPageRegion(
      documentId,
      pageIndex,
      left,
      top,
      right,
      bottom,
      scale
    );
  },

  /**
   * Computes the exact text selection geometry for a point-range on a page.
   * Returns the selected text and bounding quads (for drawing highlight overlays).
   *
   * @param documentId - ID returned by `openDocument`
   * @param pageIndex  - Zero-based page index
   * @param startX     - Start point X in page coordinates (points)
   * @param startY     - Start point Y in page coordinates (points)
   * @param endX       - End point X in page coordinates (points)
   * @param endY       - End point Y in page coordinates (points)
   */
  async getSelectionGeometry(
    documentId: string,
    pageIndex: number,
    startX: number,
    startY: number,
    endX: number,
    endY: number
  ): Promise<PdfTextSelection> {
    return getNativeModule().getSelectionGeometry(
      documentId,
      pageIndex,
      startX,
      startY,
      endX,
      endY
    );
  },

  /**
   * Runs full batch processing of a document — text extraction, layout analysis,
   * optional OCR, and search indexing.
   *
   * Call this once after `openDocument` to pre-process all pages before calling
   * `searchDocument`, which requires the index to be built.
   *
   * @param documentId - ID returned by `openDocument`
   * @param options    - Processing options (OCR, image extraction, page range)
   */
  async processDocument(
    documentId: string,
    options?: PdfProcessingOptions
  ): Promise<PdfProcessingResult> {
    return getNativeModule().processDocument(documentId, options ?? null);
  },

  /**
   * Opens Android's system file picker filtered to PDF files.
   * Returns `{ uri, name }` where `uri` is a `content://` URI ready
   * to pass directly into `openDocument()`.
   *
   * @example
   * const file = await PdfEngine.pickPdfFile();
   * const doc  = await PdfEngine.openDocument(file.uri);
   */
  async pickPdfFile(): Promise<{ uri: string; name: string }> {
    return getNativeModule().pickPdfFile();
  },

  /**
   * Closes a document and releases all native resources (file descriptors,
   * bitmaps, temp files). Always call this when done.
   *
   * @param documentId - ID returned by `openDocument`
   */
  async closeDocument(documentId: string): Promise<void> {
    return getNativeModule().closeDocument(documentId);
  },

  /**
   * Shuts down the engine completely, closing all open documents and releasing
   * all memory. Call on app teardown or when the PDF feature is no longer needed.
   */
  async shutdown(): Promise<void> {
    return getNativeModule().shutdown();
  },
};
