import React, { useMemo, useRef, forwardRef, useImperativeHandle } from 'react';
import { UIManager, findNodeHandle } from 'react-native';
import ThinkspaceViewNativeComponent from './ThinkspaceViewNativeComponent';
import type {
  ThinkspaceViewProps,
  InkStroke,
  WorkspaceDocumentEntry,
  PenDrawingMode,
  SemanticInkLink,
  WorkspaceTool,
} from './types';

export interface ThinkspaceViewRef {
  openSearch: () => void;
  closeSearch: () => void;
  nextMatch: () => void;
  prevMatch: () => void;
  search: (query: string) => void;
  undo: () => void;
  redo: () => void;
  zoomToFit: () => void;
  zoomOut: () => void;
  setViewport: (x: number, y: number, scale: number) => void;
  setSplitRatio: (ratio: number) => void;
  toggleSqueezeMode: () => void;
  setActiveTool: (tool: WorkspaceTool) => void;
  clearSelection: () => void;
  addNotebookPage: (style?: string, title?: string) => void;
  toggleImmersiveMode: () => void;
  setImmersiveMode: (enabled: boolean) => void;
  setPenMode: (mode: PenDrawingMode) => void;
  setPenColor: (color: string) => void;
  setPenThickness: (thickness: number) => void;
  setPenFavorites: (favorites: string[]) => void;
  togglePenSettings: () => void;
  switchToDocument: (documentId: string) => void;
  scrollToPage: (pageNumber: number) => void;
  deleteCard: (cardId: string) => void;
  deleteInkLink: (linkId: string) => void;
  clearAllCards: () => void;
  clearAllStrokes: () => void;
}

export const ThinkspaceView = forwardRef(function ThinkspaceViewComponent(
  props: ThinkspaceViewProps,
  ref: React.ForwardedRef<ThinkspaceViewRef>
) {
  const {
    style,
    document,
    workspaceDocuments,
    activeDocumentId,
    annotations = [],
    isSqueezed = false,
    isImmersive = false,
    splitRatio = 0.45,
    activeTool = 'select',
    selectedColor = '#00ADB5',
    pattern = 'looseleaf',
    strokes = [],
    excerpts = [],
    inkLinks = [],
    notebookPages = [],
    panX = 0,
    panY = 0,
    scale = 1,
    onAddStroke,
    onEraseStroke,
    onExcerptMoveEnd,
    onExcerptPress,
    onCardDelete,
    onChangeCardColor,
    onUpdateCardComment,
    onHoldCard,
    onTransformChange,
    onSplitRatioChange,
    onExtractExcerpt,
    onToggleSqueeze,
    onUndoStateChange,
    onNotebookPageAdded,
    onNotebookPageMoved,
    onNotebookPageDeleted,
    onRequestDocumentSwitch,
    onToggleImmersive,
    penMode,
    penColor,
    penThickness,
    penFavorites,
    semanticInkLinks,
    onPenStateChange,
    onInkLinkCreate,
    onInkLinkDelete,
  } = props;

  const nativeRef = useRef<any>(null);

  useImperativeHandle(ref, () => ({
    openSearch: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        (UIManager as any).dispatchViewManagerCommand(handle, 'openSearch', []);
      }
    },
    closeSearch: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        (UIManager as any).dispatchViewManagerCommand(
          handle,
          'closeSearch',
          []
        );
      }
    },
    nextMatch: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        (UIManager as any).dispatchViewManagerCommand(handle, 'nextMatch', []);
      }
    },
    prevMatch: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        (UIManager as any).dispatchViewManagerCommand(handle, 'prevMatch', []);
      }
    },
    undo: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        (UIManager as any).dispatchViewManagerCommand(handle, 'undo', []);
      }
    },
    redo: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        (UIManager as any).dispatchViewManagerCommand(handle, 'redo', []);
      }
    },
    zoomToFit: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        (UIManager as any).dispatchViewManagerCommand(handle, 'zoomToFit', []);
      }
    },
    zoomOut: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        (UIManager as any).dispatchViewManagerCommand(handle, 'zoomToFit', []);
      }
    },
    addNotebookPage: (pageStyle?: string, title?: string) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.addNotebookPage ?? 8;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [
          pageStyle ?? 'ruled',
          title ?? '',
        ]);
      }
    },
    toggleImmersiveMode: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.toggleImmersiveMode ?? 9;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, []);
      }
    },
    setImmersiveMode: (enabled: boolean) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.setImmersiveMode ?? 10;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [enabled]);
      }
    },
    setPenMode: (mode: PenDrawingMode) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.setPenMode ?? 11;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [mode]);
      }
    },
    setPenColor: (color: string) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.setPenColor ?? 12;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [color]);
      }
    },
    setPenThickness: (thickness: number) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.setPenThickness ?? 13;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [thickness]);
      }
    },
    setPenFavorites: (favorites: string[]) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.setPenFavorites ?? 14;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [
          JSON.stringify(favorites),
        ]);
      }
    },
    togglePenSettings: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.togglePenSettings ?? 15;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, []);
      }
    },
    switchToDocument: (docId: string) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.switchToDocument ?? 16;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [docId]);
      }
    },
    scrollToPage: (pageNum: number) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.scrollToPage ?? 17;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [pageNum]);
      }
    },
    search: (query: string) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.performSearch ?? 18;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [query]);
      }
    },
    clearSelection: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.clearSelection ?? 19;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, []);
      }
    },
    deleteCard: (cardId: string) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.deleteCard ?? 20;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [cardId]);
      }
    },
    deleteInkLink: (linkId: string) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.deleteInkLink ?? 21;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [linkId]);
      }
    },
    clearAllCards: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.clearAllCards ?? 22;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, []);
      }
    },
    clearAllStrokes: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.clearAllStrokes ?? 23;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, []);
      }
    },
    setViewport: (x: number, y: number, zoomScale: number) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.setViewport ?? 24;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [
          x,
          y,
          zoomScale,
        ]);
      }
    },
    setSplitRatio: (ratio: number) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.setSplitRatio ?? 25;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [ratio]);
      }
    },
    toggleSqueezeMode: () => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.toggleSqueezeMode ?? 26;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, []);
      }
    },
    setActiveTool: (tool: WorkspaceTool) => {
      const handle = findNodeHandle(nativeRef.current);
      if (handle) {
        const cmd =
          (UIManager as any).getViewManagerConfig?.('ThinkspaceView')?.Commands
            ?.setActiveTool ?? 27;
        (UIManager as any).dispatchViewManagerCommand(handle, cmd, [tool]);
      }
    },
  }));

  // ── Serialized JSON props ─────────────────────────────────────────────────
  const documentJson = useMemo(
    () => (document ? JSON.stringify(document) : ''),
    [document]
  );

  // Multi-document: serialize the full workspace doc list
  const workspaceDocumentsJson = useMemo(() => {
    if (workspaceDocuments && workspaceDocuments.length > 0) {
      return JSON.stringify(workspaceDocuments);
    }
    // Backward compat: if legacy single `document` prop is used, wrap it
    if (document) {
      const entry: WorkspaceDocumentEntry = {
        id: document.id,
        title: document.title,
        pageCount: document.pageCount,
        uri: document.uri ?? '',
        colorAccent: '#00ADB5',
      };
      return JSON.stringify([entry]);
    }
    return '';
  }, [workspaceDocuments, document]);

  const annotationsJson = useMemo(
    () => JSON.stringify(annotations),
    [annotations]
  );
  const strokesJson = useMemo(() => JSON.stringify(strokes), [strokes]);
  const excerptsJson = useMemo(() => JSON.stringify(excerpts), [excerpts]);
  const inkLinksJson = useMemo(() => JSON.stringify(inkLinks), [inkLinks]);
  const notebookPagesJson = useMemo(
    () => JSON.stringify(notebookPages),
    [notebookPages]
  );
  const penFavoritesJson = useMemo(
    () => (penFavorites ? JSON.stringify(penFavorites) : undefined),
    [penFavorites]
  );
  const semanticInkLinksJson = useMemo(
    () => (semanticInkLinks ? JSON.stringify(semanticInkLinks) : undefined),
    [semanticInkLinks]
  );

  return (
    <ThinkspaceViewNativeComponent
      ref={nativeRef}
      style={style}
      documentJson={documentJson}
      workspaceDocumentsJson={workspaceDocumentsJson}
      activeDocumentId={
        activeDocumentId ?? workspaceDocuments?.[0]?.id ?? document?.id ?? ''
      }
      annotationsJson={annotationsJson}
      isSqueezed={isSqueezed}
      isImmersive={isImmersive}
      splitRatio={splitRatio}
      activeTool={activeTool}
      selectedColor={selectedColor}
      pattern={pattern}
      strokesJson={strokesJson}
      excerptsJson={excerptsJson}
      inkLinksJson={inkLinksJson}
      panX={panX}
      panY={panY}
      scale={scale}
      onAddStroke={
        onAddStroke
          ? (e: any) => {
              try {
                const parsed: InkStroke = JSON.parse(e.nativeEvent.strokeJson);
                onAddStroke(parsed);
              } catch (err) {
                console.error(
                  '[ThinkspaceView] Failed to parse stroke event',
                  err
                );
              }
            }
          : undefined
      }
      onEraseStroke={
        onEraseStroke
          ? (e: any) => {
              onEraseStroke(e.nativeEvent.id);
            }
          : undefined
      }
      onExcerptMoveEnd={
        onExcerptMoveEnd
          ? (e: any) => {
              const { id, x, y, clusterId, stackCount } = e.nativeEvent;
              onExcerptMoveEnd(id, x, y, clusterId, stackCount);
            }
          : undefined
      }
      onExcerptPress={
        onExcerptPress
          ? (e: any) => {
              onExcerptPress(e.nativeEvent.id);
            }
          : undefined
      }
      onCardDelete={
        onCardDelete
          ? (e: any) => {
              onCardDelete(e.nativeEvent.id);
            }
          : undefined
      }
      onChangeCardColor={
        onChangeCardColor
          ? (e: any) => {
              onChangeCardColor(e.nativeEvent.id, e.nativeEvent.color);
            }
          : undefined
      }
      onUpdateCardComment={
        onUpdateCardComment
          ? (e: any) => {
              onUpdateCardComment(e.nativeEvent.id, e.nativeEvent.comment);
            }
          : undefined
      }
      onHoldCard={
        onHoldCard
          ? (e: any) => {
              onHoldCard(e.nativeEvent.id);
            }
          : undefined
      }
      onTransformChange={
        onTransformChange
          ? (e: any) => {
              const { panX: px, panY: py, scale: s } = e.nativeEvent;
              onTransformChange({ panX: px, panY: py, scale: s });
            }
          : undefined
      }
      onSplitRatioChange={
        onSplitRatioChange
          ? (e: any) => {
              onSplitRatioChange(e.nativeEvent.ratio);
            }
          : undefined
      }
      onExtractExcerpt={
        onExtractExcerpt
          ? (e: any) => {
              const {
                text,
                pageNumber,
                color: c,
                isTable,
                isImage,
                imageUrl,
                id,
                x,
                y,
                sourceRects,
                documentId,
              } = e.nativeEvent;
              onExtractExcerpt({
                id,
                text,
                pageNumber,
                color: c,
                isTable,
                isImage,
                imageUrl,
                x,
                y,
                sourceRects,
                documentId,
              } as any);
            }
          : undefined
      }
      onToggleSqueeze={
        onToggleSqueeze
          ? (e: any) => {
              onToggleSqueeze(e.nativeEvent.isSqueezed);
            }
          : undefined
      }
      onUndoStateChange={
        onUndoStateChange
          ? (e: any) => {
              onUndoStateChange(e.nativeEvent.canUndo, e.nativeEvent.canRedo);
            }
          : undefined
      }
      notebookPagesJson={notebookPagesJson}
      onNotebookPageAdded={
        onNotebookPageAdded
          ? (e: any) => {
              onNotebookPageAdded(e.nativeEvent);
            }
          : undefined
      }
      onNotebookPageMoved={
        onNotebookPageMoved
          ? (e: any) => {
              const { id, x, y } = e.nativeEvent;
              onNotebookPageMoved(id, x, y);
            }
          : undefined
      }
      onNotebookPageDeleted={
        onNotebookPageDeleted
          ? (e: any) => {
              onNotebookPageDeleted(e.nativeEvent.id);
            }
          : undefined
      }
      onRequestDocumentSwitch={
        onRequestDocumentSwitch
          ? (e: any) => {
              const { documentId, sourcePageNumber, cardId } = e.nativeEvent;
              onRequestDocumentSwitch({ documentId, sourcePageNumber, cardId });
            }
          : undefined
      }
      onToggleImmersive={
        onToggleImmersive
          ? (e: any) => {
              onToggleImmersive(e.nativeEvent.isImmersive);
            }
          : undefined
      }
      penMode={penMode}
      penColor={penColor}
      penThickness={penThickness}
      penFavoritesJson={penFavoritesJson}
      semanticInkLinksJson={semanticInkLinksJson}
      onPenStateChange={
        onPenStateChange
          ? (e: any) => {
              const ev = e.nativeEvent || {};
              let favs: string[] = [];
              if (ev.favoritesJson) {
                try {
                  favs = JSON.parse(ev.favoritesJson);
                } catch {
                  // Ignore JSON parse errors
                }
              } else if (Array.isArray(ev.favorites)) {
                favs = ev.favorites;
              }
              onPenStateChange({
                active: true,
                settingsOpen: true,
                drawingMode: (ev.mode || 'freehand') as PenDrawingMode,
                color: ev.color || '#E87A90',
                thickness: ev.thickness || 3.5,
                favoriteColors: favs,
              });
            }
          : undefined
      }
      onInkLinkCreate={
        onInkLinkCreate
          ? (e: any) => {
              const ev = e.nativeEvent || {};
              if (ev.linkJson) {
                try {
                  const parsed = JSON.parse(ev.linkJson);
                  onInkLinkCreate(parsed);
                  return;
                } catch {
                  // Ignore JSON parse errors
                }
              }
              const semanticLink: SemanticInkLink = {
                id: ev.id,
                sourceEndpoint: {
                  type: 'pdf',
                  documentId: ev.sourceDocId || '',
                  pageIndex: ev.sourcePageIndex || 0,
                  sourceRect: {
                    left: (ev.sourceX || 0) - 30,
                    top: (ev.sourceY || 0) - 10,
                    right: (ev.sourceX || 0) + 30,
                    bottom: (ev.sourceY || 0) + 10,
                  },
                  anchorPoint: { x: ev.sourceX || 0, y: ev.sourceY || 0 },
                },
                targetEndpoint: {
                  type: 'card',
                  cardId: ev.targetCardId || '',
                  anchorPoint: { x: 0, y: 0 },
                },
                strokePoints: [],
                color:
                  typeof ev.color === 'number'
                    ? // eslint-disable-next-line no-bitwise
                      `#${(ev.color & 0xffffff).toString(16).padStart(6, '0')}`
                    : ev.color || '#E87A90',
                thickness: ev.strokeWidth || 3.5,
                style: (ev.style as any) || 'elastic',
                createdAt: ev.createdAt || new Date().toISOString(),
              };
              onInkLinkCreate(semanticLink);
            }
          : undefined
      }
      onInkLinkDelete={
        onInkLinkDelete
          ? (e: any) => {
              onInkLinkDelete(e.nativeEvent?.id);
            }
          : undefined
      }
    />
  );
});

export default ThinkspaceView;
