import { codegenNativeComponent, type ViewProps } from 'react-native';
import type {
  DirectEventHandler,
  Float,
} from 'react-native/Libraries/Types/CodegenTypes';

export type StrokeEvent = Readonly<{
  strokeJson: string;
}>;

export type StrokeIdEvent = Readonly<{
  id: string;
}>;

export type ExcerptMoveEvent = Readonly<{
  id: string;
  x: Float;
  y: Float;
  clusterId?: string;
  stackCount?: Float;
}>;

export type ExcerptPressEvent = Readonly<{
  id: string;
}>;

export type CardDeleteEvent = Readonly<{
  id: string;
}>;

export type CardColorChangeEvent = Readonly<{
  id: string;
  color: string;
}>;

export type CardCommentEvent = Readonly<{
  id: string;
  comment: string;
}>;

export type CardHoldEvent = Readonly<{
  id: string;
}>;

export type TransformChangeEvent = Readonly<{
  panX: Float;
  panY: Float;
  scale: Float;
}>;

export type SplitRatioChangeEvent = Readonly<{
  ratio: Float;
}>;

export type ExtractExcerptEvent = Readonly<{
  text: string;
  pageNumber: Float;
  color: string;
  isTable: boolean;
  isImage: boolean;
  imageUrl?: string;
  id?: string;
  x?: Float;
  y?: Float;
}>;

export type ToggleSqueezeEvent = Readonly<{
  isSqueezed: boolean;
}>;

export type UndoStateChangeEvent = Readonly<{
  canUndo: boolean;
  canRedo: boolean;
}>;

/**
 * Fired when the user taps the source badge on an excerpt card.
 * React Native should switch the active document and scroll to the source page.
 */
export type RequestDocumentSwitchEvent = Readonly<{
  documentId: string;
  sourcePageNumber: Float;
  cardId: string;
}>;

export type NotebookPageAddedEvent = Readonly<{
  id: string;
  x: Float;
  y: Float;
  width: Float;
  height: Float;
  pageStyle: string;
  title: string;
}>;

export type NotebookPageMovedEvent = Readonly<{
  id: string;
  x: Float;
  y: Float;
}>;

export type NotebookPageDeletedEvent = Readonly<{
  id: string;
}>;

export type ToggleImmersiveEvent = Readonly<{
  isImmersive: boolean;
}>;

export type InkLinkCreateEvent = Readonly<{
  linkJson: string;
}>;

export type InkLinkDeleteEvent = Readonly<{
  id: string;
}>;

export type PenStateChangeEvent = Readonly<{
  mode: string;
  color: string;
  thickness: Float;
  favoritesJson: string;
}>;

export type DocumentChangeEvent = Readonly<{
  documentId: string;
  title: string;
  uri: string;
  pageCount: Float;
}>;

export type DocumentsUpdatedEvent = Readonly<{
  documentsJson: string;
  foldersJson: string;
}>;

export type RequestAddDocumentEvent = Readonly<{}>;

export interface NativeProps extends ViewProps {
  documentJson?: string;
  annotationsJson?: string;
  /** Full list of workspace documents as JSON — multi-document mode. */
  workspaceDocumentsJson?: string;
  /** Full list of workspace folders as JSON. */
  workspaceFoldersJson?: string;
  /** The document ID currently active in the PDF viewport. */
  activeDocumentId?: string;
  isSqueezed?: boolean;
  /** Immersive distraction-free full content mode. */
  isImmersive?: boolean;
  splitRatio?: Float;
  activeTool?: string;
  selectedColor?: string;
  pattern?: string;
  strokesJson?: string;
  excerptsJson?: string;
  inkLinksJson?: string;
  notebookPagesJson?: string;
  panX?: Float;
  panY?: Float;
  scale?: Float;
  // ── Pen / Inking System Props ──────────────────────────────────────────
  penMode?: string;
  penColor?: string;
  penThickness?: Float;
  penFavoritesJson?: string;
  semanticInkLinksJson?: string;
  showDocumentHeader?: boolean;
  onAddStroke?: DirectEventHandler<StrokeEvent>;
  onEraseStroke?: DirectEventHandler<StrokeIdEvent>;
  onExcerptMoveEnd?: DirectEventHandler<ExcerptMoveEvent>;
  onExcerptPress?: DirectEventHandler<ExcerptPressEvent>;
  onCardDelete?: DirectEventHandler<CardDeleteEvent>;
  onChangeCardColor?: DirectEventHandler<CardColorChangeEvent>;
  onUpdateCardComment?: DirectEventHandler<CardCommentEvent>;
  onHoldCard?: DirectEventHandler<CardHoldEvent>;
  onTransformChange?: DirectEventHandler<TransformChangeEvent>;
  onSplitRatioChange?: DirectEventHandler<SplitRatioChangeEvent>;
  onExtractExcerpt?: DirectEventHandler<ExtractExcerptEvent>;
  onToggleSqueeze?: DirectEventHandler<ToggleSqueezeEvent>;
  onUndoStateChange?: DirectEventHandler<UndoStateChangeEvent>;
  onNotebookPageAdded?: DirectEventHandler<NotebookPageAddedEvent>;
  onNotebookPageMoved?: DirectEventHandler<NotebookPageMovedEvent>;
  onNotebookPageDeleted?: DirectEventHandler<NotebookPageDeletedEvent>;
  /** Fired when user taps a source badge on a card — native requests doc switch. */
  onRequestDocumentSwitch?: DirectEventHandler<RequestDocumentSwitchEvent>;
  /** Fired when user taps content to toggle immersive mode. */
  onToggleImmersive?: DirectEventHandler<ToggleImmersiveEvent>;
  onInkLinkCreate?: DirectEventHandler<InkLinkCreateEvent>;
  onInkLinkDelete?: DirectEventHandler<InkLinkDeleteEvent>;
  onPenStateChange?: DirectEventHandler<PenStateChangeEvent>;
  /** Fired when active document changes in native engine. */
  onDocumentChange?: DirectEventHandler<DocumentChangeEvent>;
  /** Fired when documents/folders are updated in native engine. */
  onDocumentsUpdated?: DirectEventHandler<DocumentsUpdatedEvent>;
  /** Fired when user requests adding document in native document sheet. */
  onRequestAddDocument?: DirectEventHandler<RequestAddDocumentEvent>;
}

export default codegenNativeComponent<NativeProps>('ThinkspaceView');
