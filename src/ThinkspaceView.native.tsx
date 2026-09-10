import React, { useMemo } from 'react';
import ThinkspaceViewNativeComponent from './ThinkspaceViewNativeComponent';
import type { ThinkspaceViewProps, InkStroke } from './types';

export const ThinkspaceView: React.FC<ThinkspaceViewProps> = ({
  style,
  document,
  annotations = [],
  isSqueezed = false,
  splitRatio = 0.45,
  activeTool = 'select',
  selectedColor = '#00ADB5',
  pattern = 'looseleaf',
  strokes = [],
  excerpts = [],
  inkLinks = [],
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
}) => {
  const documentJson = useMemo(
    () => (document ? JSON.stringify(document) : ''),
    [document]
  );
  const annotationsJson = useMemo(
    () => JSON.stringify(annotations),
    [annotations]
  );
  const strokesJson = useMemo(() => JSON.stringify(strokes), [strokes]);
  const excerptsJson = useMemo(() => JSON.stringify(excerpts), [excerpts]);
  const inkLinksJson = useMemo(() => JSON.stringify(inkLinks), [inkLinks]);

  return (
    <ThinkspaceViewNativeComponent
      style={style}
      documentJson={documentJson}
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
              } = e.nativeEvent;
              onExtractExcerpt({
                text,
                pageNumber,
                color: c,
                isTable,
                isImage,
              });
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
    />
  );
};

export default ThinkspaceView;
