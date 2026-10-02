import React, { useMemo, useRef, forwardRef, useImperativeHandle } from 'react';
import { UIManager, findNodeHandle } from 'react-native';
import ThinkspaceViewNativeComponent from './ThinkspaceViewNativeComponent';
import type {
  ThinkspaceViewProps,
  InkStroke,
  WorkspaceDocumentEntry,
} from './types';

export interface ThinkspaceViewRef {
  openSearch: () => void;
  closeSearch: () => void;
  nextMatch: () => void;
  prevMatch: () => void;
  undo: () => void;
  redo: () => void;
  zoomToFit: () => void;
  zoomOut: () => void;
  addNotebookPage: (style?: string, title?: string) => void;
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
    />
  );
});

export default ThinkspaceView;
