export type WorkspacePattern = 'dots' | 'grid' | 'looseleaf' | 'plain' | 'none';

export type WorkspaceTool =
  'select' | 'pen' | 'highlighter' | 'eraser' | 'addNote';

export type ToolContext = 'drawing' | 'document' | 'workspace';

export interface NativeBBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface NativeTextLine {
  text: string;
  bbox: NativeBBox;
}

export interface NativeExtractedElement {
  id: string;
  anchorId: string;
  pageNumber: number;
  readingOrder: number;
  type: 'TEXT' | 'IMAGE' | 'TABLE' | 'VECTOR_GRAPHIC';
  bbox: NativeBBox;
  content?: string;
  attributes?: {
    fontSize?: number;
    fontName?: string;
    isBold?: boolean;
    isItalic?: boolean;
    lines?: NativeTextLine[];
    url?: string;
  };
}

export interface NativeExtractedPage {
  pageNumber: number;
  widthPt: number;
  heightPt: number;
  rotation: number;
  rasterUrl?: string;
  status: string;
  elements: NativeExtractedElement[];
}

export interface InkPoint {
  x: number;
  y: number;
}

export interface InkStroke {
  id: string;
  points: InkPoint[];
  color: string;
  strokeWidth: number;
  isHighlighter: boolean;
}

export interface ExtractedTableRow {
  cells: string[];
}

export interface ExtractedTable {
  rows: ExtractedTableRow[];
}

export interface DocumentSection {
  id: string;
  pageNumber: number;
  heading: string;
  paragraphs: string[];
  tables?: ExtractedTable[];
  imageUrl?: string;
  imageCaption?: string;
}

export interface DocumentAnnotation {
  id: string;
  sectionId: string;
  paragraphIndex: number;
  color: string;
  type: 'highlight' | 'excerpt';
  pageNumber: number;
  text: string;
  excerptId?: string;
}

export interface WorkspaceDocument {
  id: string;
  title: string;
  pageCount: number;
  uri?: string;
  author?: string;
  sections?: DocumentSection[];
}

export interface ExcerptModel {
  id: string;
  documentId?: string;
  pageNumber?: number;
  text: string;
  color: string;
  x: number;
  y: number;
  width?: number;
  comment?: string;
  tags?: string[];
  clusterId?: string;
  stackCount?: number;
  imageUrl?: string;
  imageWidth?: number;
  imageHeight?: number;
  isImage?: boolean;
  isTable?: boolean;
  tableData?: ExtractedTable;
  sourceRects?: { left: number; top: number; right: number; bottom: number }[];
}

export interface InkLink {
  id: string;
  sourceExcerptId: string;
  targetPageNumber?: number;
  targetY?: number;
  color?: string;
}

export interface CanvasTransform {
  panX: number;
  panY: number;
  scale: number;
}

export interface DocumentSelection {
  text: string;
  pageNumber: number;
  sectionId?: string;
  paragraphIndex?: number;
  highlightRects: { x: number; y: number; width: number; height: number }[];
  startHandle: { x: number; y: number; height: number };
  endHandle: { x: number; y: number; height: number };
  startCharIndex: number;
  endCharIndex: number;
}

export interface ThinkspaceViewProps {
  ref?: any;
  style?: any;
  document?: WorkspaceDocument;
  annotations?: DocumentAnnotation[];
  isSqueezed?: boolean;
  splitRatio?: number;
  activeTool?: WorkspaceTool;
  selectedColor?: string;
  pattern?: WorkspacePattern;
  strokes?: InkStroke[];
  excerpts?: ExcerptModel[];
  inkLinks?: InkLink[];
  panX?: number;
  panY?: number;
  scale?: number;
  onAddStroke?: (stroke: InkStroke) => void;
  onEraseStroke?: (strokeId: string) => void;
  onExcerptMoveEnd?: (
    id: string,
    x: number,
    y: number,
    clusterId?: string,
    stackCount?: number
  ) => void;
  onExcerptPress?: (id: string) => void;
  onCardDelete?: (id: string) => void;
  onChangeCardColor?: (id: string, color: string) => void;
  onUpdateCardComment?: (id: string, comment: string) => void;
  onHoldCard?: (id: string | null) => void;
  onTransformChange?: (transform: CanvasTransform) => void;
  onSplitRatioChange?: (ratio: number) => void;
  onExtractExcerpt?: (data: {
    text: string;
    pageNumber: number;
    color: string;
    isTable: boolean;
    isImage: boolean;
    sourceRects?: {
      left: number;
      top: number;
      right: number;
      bottom: number;
    }[];
  }) => void;
  onToggleSqueeze?: (isSqueezed: boolean) => void;
  onSelectText?: (selection: DocumentSelection | null) => void;
  onCopyText?: (text: string) => void;
  onHighlightText?: (
    text: string,
    pageNumber: number,
    sectionId: string,
    pIdx: number
  ) => void;
}

export interface DocumentViewerProps {
  document: WorkspaceDocument;
  annotations?: DocumentAnnotation[];
  isSqueezed?: boolean;
  searchQuery?: string;
  targetPage?: number | null;
  onHighlightPassage?: (
    text: string,
    sectionId: string,
    pIdx: number,
    pageNumber: number,
    color: string
  ) => void;
  onExtractPassage?: (
    text: string,
    pageNumber: number,
    isImage?: boolean,
    imageUrl?: string,
    tableData?: ExtractedTable
  ) => void;
  onStartLiftDrag?: (
    data: {
      text: string;
      pageNumber: number;
      color: string;
      isTable?: boolean;
      tableData?: ExtractedTable;
      isImage?: boolean;
      imageUrl?: string;
    },
    clientX: number,
    clientY: number
  ) => void;
  onPageChange?: (page: number) => void;
}

export interface SplitDividerProps {
  splitRatio: number;
  onRatioChange: (newRatio: number) => void;
  minRatio?: number;
  maxRatio?: number;
}

export interface LiquidTextWorkspaceProps {
  document: WorkspaceDocument;
  initialPattern?: WorkspacePattern;
  initialTool?: WorkspaceTool;
}

// ─────────────────────────────────────────────────────────────────────────────
// PDF Engine Types
// These types mirror the Kotlin pdf-engine data model classes.
// All coordinates are in PDF page space: points (1/72 inch), top-left origin.
// ─────────────────────────────────────────────────────────────────────────────

/** Axis-aligned bounding box in page coordinates (points). */
export interface PdfBoundingBox {
  left: number;
  top: number;
  right: number;
  bottom: number;
  width: number;
  height: number;
}

/** A 2D point in page coordinates. */
export interface PdfPoint {
  x: number;
  y: number;
}

/**
 * Arbitrary quadrilateral for rotated / skewed text selections.
 * Vertices are clockwise: topLeft → topRight → bottomRight → bottomLeft.
 */
export interface PdfQuad {
  topLeft: PdfPoint;
  topRight: PdfPoint;
  bottomRight: PdfPoint;
  bottomLeft: PdfPoint;
}

/** A single extracted word with exact bounding box and font metadata. */
export interface PdfWord {
  text: string;
  bounds: PdfBoundingBox;
  fontSize: number;
  fontName: string;
  isBold: boolean;
  isItalic: boolean;
  baseline: number;
  orderIndex: number;
  pageIndex: number;
}

/** A line of text composed of words. */
export interface PdfTextLine {
  text: string;
  bounds: PdfBoundingBox;
  averageFontSize: number;
  orderIndex: number;
  words: PdfWord[];
}

/** A visual block of text lines (belongs to a layout column). */
export interface PdfTextBlock {
  id: string;
  text: string;
  bounds: PdfBoundingBox;
  isHeading: boolean;
  columnIndex: number;
  orderIndex: number;
  lines: PdfTextLine[];
}

/** Result of `PdfEngine.extractText()` — all words on a page. */
export interface PdfTextPage {
  pageIndex: number;
  words: PdfWord[];
}

/** Result of `PdfEngine.analyzePage()` — full layout structure of a page. */
export interface PdfPageAnalysis {
  pageIndex: number;
  columnCount: number;
  pageWidth: number;
  pageHeight: number;
  isScanned: boolean;
  lines: PdfTextLine[];
  blocks: PdfTextBlock[];
}

/** A single search match returned by `PdfEngine.searchDocument()`. */
export interface PdfSearchResult {
  pageIndex: number;
  matchedText: string;
  bounds: PdfBoundingBox;
  context: string;
  startOffset: number;
  endOffset: number;
  quads: PdfQuad[];
  isFromOcr: boolean;
}

/**
 * Rendered page image returned by `PdfEngine.renderPage()`.
 * `uri` is a temp `file://` URI pointing to a PNG on disk — use it
 * directly with `<Image source={{ uri }} />`.
 */
export interface PdfRenderedPage {
  uri: string;
  width: number;
  height: number;
  pageWidth?: number;
  pageHeight?: number;
  scale?: number;
  pageIndex: number;
}

/** Per-page slice of a multi-page text selection. */
export interface PdfPageTextSelection {
  pageIndex: number;
  text: string;
  bounds: PdfBoundingBox;
  quads: PdfQuad[];
}

/** Result of `PdfEngine.getSelectionGeometry()`. */
export interface PdfTextSelection {
  text: string;
  startPage: number;
  endPage: number;
  bounds: PdfBoundingBox;
  quads: PdfQuad[];
  pageSelections: PdfPageTextSelection[];
}

/**
 * Document handle returned by `PdfEngine.openDocument()`.
 * Store `documentId` and pass it to all subsequent API calls.
 */
export interface PdfDocumentInfo {
  documentId: string;
  pageCount: number;
  title: string;
  author: string;
  subject: string;
  isEncrypted: boolean;
  pdfVersion: string;
}

/** Options for `PdfEngine.processDocument()` batch pre-processing. */
export interface PdfProcessingOptions {
  /** Enable on-device ML Kit OCR for scanned pages. Default: false. */
  enableOcr?: boolean;
  /** Also extract embedded images. Default: false. */
  extractImages?: boolean;
  /** Start processing from this page (zero-based). Default: 0. */
  startPageIndex?: number;
  /** Limit processing to this many pages. Omit for all pages. */
  pageLimit?: number;
}

/** Result of `PdfEngine.processDocument()`. */
export interface PdfProcessingResult {
  totalPages: number;
  processedPages: number;
  totalDurationMs: number;
  isFullyIndexed: boolean;
}
