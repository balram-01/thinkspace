export type WorkspacePattern = 'dots' | 'grid' | 'looseleaf' | 'none';

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
  author?: string;
  sections: DocumentSection[];
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
