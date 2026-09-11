import React, { useRef, useEffect, useState, useCallback } from 'react';
import { View, StyleSheet } from 'react-native';
import type {
  ThinkspaceViewProps,
  InkStroke,
  InkPoint,
  ExcerptModel,
} from './types';

const CONTEXT_COLORS = ['#00ADB5', '#F59E0B', '#EF4444', '#3B82F6', '#8B5CF6'];

function chaikinSmooth(pts: InkPoint[], iterations = 1): InkPoint[] {
  if (pts.length <= 2) return pts;
  let current = pts;
  for (let it = 0; it < iterations; it++) {
    const next: InkPoint[] = [current[0]!];
    for (let i = 0; i < current.length - 1; i++) {
      const p0 = current[i]!;
      const p1 = current[i + 1]!;
      next.push({
        x: 0.75 * p0.x + 0.25 * p1.x,
        y: 0.75 * p0.y + 0.25 * p1.y,
      });
      next.push({
        x: 0.25 * p0.x + 0.75 * p1.x,
        y: 0.25 * p0.y + 0.75 * p1.y,
      });
    }
    next.push(current[current.length - 1]!);
    current = next;
  }
  return current;
}

interface MeasuredLine {
  text: string;
  startIndex: number;
  endIndex: number;
  x: number;
  y: number;
  top: number;
  height: number;
  width: number;
}

interface MeasuredParagraph {
  secId: string;
  pageNumber: number;
  pIdx: number;
  fullText: string;
  lines: MeasuredLine[];
  topY: number;
  height: number;
}

interface InternalDocSelection {
  text: string;
  pageNumber: number;
  sectionId: string;
  pIdx: number;
  startCharIndex: number;
  endCharIndex: number;
  highlightRects: { x: number; y: number; width: number; height: number }[];
  startHandle: { x: number; y: number; height: number };
  endHandle: { x: number; y: number; height: number };
  calloutRect: { x: number; y: number; width: number; height: number };
  calloutExcerptBtn: { x: number; y: number; width: number; height: number };
  calloutCopyBtn: { x: number; y: number; width: number; height: number };
  calloutHighlightBtn: { x: number; y: number; width: number; height: number };
  calloutPrevWordBtn: { x: number; y: number; width: number; height: number };
  calloutNextWordBtn: { x: number; y: number; width: number; height: number };
  calloutCloseBtn: { x: number; y: number; width: number; height: number };
}

function measureAndWrapParagraph(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
  startX: number,
  startY: number,
  lineHeight: number
): { lines: MeasuredLine[]; totalHeight: number } {
  const words = text.split(' ');
  const lines: MeasuredLine[] = [];
  let curLineText = '';
  let curLineStart = 0;
  let charIndexTracker = 0;
  let curY = startY;

  for (let i = 0; i < words.length; i++) {
    const word = words[i]!;
    const testLine = curLineText ? `${curLineText} ${word}` : word;
    const testWidth = ctx.measureText(testLine).width;

    if (testWidth > maxWidth && curLineText) {
      lines.push({
        text: curLineText,
        startIndex: curLineStart,
        endIndex: curLineStart + curLineText.length,
        x: startX,
        y: curY + lineHeight * 0.78,
        top: curY,
        height: lineHeight,
        width: ctx.measureText(curLineText).width,
      });
      curY += lineHeight;
      curLineStart = charIndexTracker;
      curLineText = word;
      charIndexTracker += word.length + 1;
    } else {
      curLineText = testLine;
      charIndexTracker += word.length + 1;
    }
  }

  if (curLineText) {
    lines.push({
      text: curLineText,
      startIndex: curLineStart,
      endIndex: curLineStart + curLineText.length,
      x: startX,
      y: curY + lineHeight * 0.78,
      top: curY,
      height: lineHeight,
      width: ctx.measureText(curLineText).width,
    });
    curY += lineHeight;
  }

  return { lines, totalHeight: Math.max(lineHeight, curY - startY) };
}

function buildDocSelection(
  ctx: CanvasRenderingContext2D,
  para: MeasuredParagraph,
  rawStart: number,
  rawEnd: number,
  canvasWidth: number
): InternalDocSelection {
  const startChar = Math.max(
    0,
    Math.min(Math.min(rawStart, rawEnd), para.fullText.length)
  );
  const endChar = Math.max(
    0,
    Math.min(Math.max(rawStart, rawEnd), para.fullText.length)
  );
  const selectedText =
    startChar < endChar ? para.fullText.slice(startChar, endChar) : '';

  const highlightRects: {
    x: number;
    y: number;
    width: number;
    height: number;
  }[] = [];
  for (const line of para.lines) {
    if (line.endIndex <= startChar || line.startIndex >= endChar) continue;
    const lStart = Math.max(startChar, line.startIndex);
    const lEnd = Math.min(endChar, line.endIndex);
    const leadSub = line.text.slice(0, lStart - line.startIndex);
    const selSub = line.text.slice(
      lStart - line.startIndex,
      lEnd - line.startIndex
    );
    const leftX = line.x + ctx.measureText(leadSub).width;
    const w = Math.max(4, ctx.measureText(selSub).width);
    highlightRects.push({
      x: leftX,
      y: line.top,
      width: w,
      height: line.height,
    });
  }

  if (highlightRects.length === 0) {
    const firstLine = para.lines[0];
    highlightRects.push({
      x: firstLine ? firstLine.x : 50,
      y: firstLine ? firstLine.top : para.topY,
      width: 25,
      height: firstLine ? firstLine.height : 20,
    });
  }

  const firstR = highlightRects[0]!;
  const lastR = highlightRects[highlightRects.length - 1]!;
  const startHandle = { x: firstR.x, y: firstR.y, height: firstR.height };
  const endHandle = {
    x: lastR.x + lastR.width,
    y: lastR.y,
    height: lastR.height,
  };

  // Position Callout Dock above selection
  const calloutW = 384;
  const calloutH = 36;
  let calloutTop = firstR.y - calloutH - 12;
  if (calloutTop < 42) {
    calloutTop = lastR.y + lastR.height + 12;
  }
  const midX = (firstR.x + lastR.x + lastR.width) / 2;
  const calloutLeft = Math.max(
    10,
    Math.min(midX - calloutW / 2, canvasWidth - calloutW - 10)
  );
  const calloutRect = {
    x: calloutLeft,
    y: calloutTop,
    width: calloutW,
    height: calloutH,
  };

  return {
    text: selectedText,
    pageNumber: para.pageNumber,
    sectionId: para.secId,
    pIdx: para.pIdx,
    startCharIndex: startChar,
    endCharIndex: endChar,
    highlightRects,
    startHandle,
    endHandle,
    calloutRect,
    calloutExcerptBtn: {
      x: calloutLeft + 6,
      y: calloutTop + 3,
      width: 84,
      height: calloutH - 6,
    },
    calloutCopyBtn: {
      x: calloutLeft + 94,
      y: calloutTop + 3,
      width: 78,
      height: calloutH - 6,
    },
    calloutHighlightBtn: {
      x: calloutLeft + 176,
      y: calloutTop + 3,
      width: 96,
      height: calloutH - 6,
    },
    calloutPrevWordBtn: {
      x: calloutLeft + 276,
      y: calloutTop + 3,
      width: 30,
      height: calloutH - 6,
    },
    calloutNextWordBtn: {
      x: calloutLeft + 310,
      y: calloutTop + 3,
      width: 30,
      height: calloutH - 6,
    },
    calloutCloseBtn: {
      x: calloutLeft + 344,
      y: calloutTop + 3,
      width: 34,
      height: calloutH - 6,
    },
  };
}

export const ThinkspaceView: React.FC<ThinkspaceViewProps> = ({
  style,
  document,
  annotations = [],
  isSqueezed = false,
  splitRatio: propSplitRatio = 0.45,
  activeTool = 'select',
  selectedColor = '#00ADB5',
  pattern = 'looseleaf',
  strokes = [],
  excerpts = [],
  inkLinks = [],
  panX: propPanX = 0,
  panY: propPanY = 0,
  scale: propScale = 1,
  onAddStroke,
  onEraseStroke,
  onExcerptMoveEnd,
  onExcerptPress,
  onCardDelete,
  onChangeCardColor,
  onHoldCard,
  onTransformChange,
  onSplitRatioChange,
  onExtractExcerpt,
  onSelectText,
  onCopyText,
  onHighlightText,
}) => {
  const canvasRef = useRef<any>(null);

  const [splitRatio, setSplitRatio] = useState(propSplitRatio);
  const [pan, setPan] = useState({ x: propPanX, y: propPanY });
  const [scale, setScale] = useState(propScale);
  const [docScrollY, setDocScrollY] = useState(0);

  // Document Text Selection State
  const [docSelection, setDocSelection] = useState<InternalDocSelection | null>(
    null
  );
  const [isDraggingStartHandle, setIsDraggingStartHandle] = useState(false);
  const [isDraggingEndHandle, setIsDraggingEndHandle] = useState(false);
  const [isDirectDraggingSelection, setIsDirectDraggingSelection] =
    useState(false);
  const [copiedToastText, setCopiedToastText] = useState<string | null>(null);
  const measuredParagraphsRef = useRef<MeasuredParagraph[]>([]);

  const [isDrawing, setIsDrawing] = useState(false);
  const [currentStroke, setCurrentStroke] = useState<InkPoint[]>([]);
  const [selectedCardId, setSelectedCardId] = useState<string | null>(null);
  const [draggingCardId, setDraggingCardId] = useState<string | null>(null);
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const [totalDragDist, setTotalDragDist] = useState(0);

  // Divider drag
  const [isDraggingDivider, setIsDraggingDivider] = useState(false);

  // Cross-zone Lift-and-Drag
  const [liftItem, setLiftItem] = useState<{
    text: string;
    pageNumber: number;
    color: string;
    originX: number;
    originY: number;
  } | null>(null);
  const [ghostPos, setGhostPos] = useState({ x: 0, y: 0 });

  // Ripple
  const [ripple, setRipple] = useState<{
    x: number;
    y: number;
    color: string;
    radius: number;
    opacity: number;
  } | null>(null);

  useEffect(() => {
    setSplitRatio(propSplitRatio);
  }, [propSplitRatio]);

  useEffect(() => {
    setPan({ x: propPanX, y: propPanY });
  }, [propPanX, propPanY]);

  useEffect(() => {
    setScale(propScale);
  }, [propScale]);

  // Ripple animation loop
  useEffect(() => {
    if (!ripple) return;
    if (ripple.opacity <= 0.05) {
      setRipple(null);
      return;
    }
    const frame = requestAnimationFrame(() => {
      setRipple((prev) =>
        prev
          ? {
              ...prev,
              radius: prev.radius + 6,
              opacity: prev.opacity * 0.91,
            }
          : null
      );
    });
    return () => cancelAnimationFrame(frame);
  }, [ripple]);

  const triggerShockwave = useCallback(
    (wx: number, wy: number, color: string) => {
      setRipple({ x: wx, y: wy, color, radius: 10, opacity: 0.95 });
    },
    []
  );

  const hasDoc = Boolean(
    document && document.sections && document.sections.length > 0
  );

  const getCanvasTopY = useCallback(
    (height: number) => {
      return hasDoc ? height * splitRatio + 12 : 0;
    },
    [hasDoc, splitRatio]
  );

  const worldToCanvasScreen = useCallback(
    (wx: number, wy: number, canvasTopY: number) => ({
      x: wx * scale + pan.x,
      y: wy * scale + canvasTopY + pan.y,
    }),
    [pan, scale]
  );

  const canvasScreenToWorld = useCallback(
    (sx: number, sy: number, canvasTopY: number) => ({
      x: (sx - pan.x) / scale,
      y: (sy - canvasTopY - pan.y) / scale,
    }),
    [pan, scale]
  );

  const getCardHeight = (card: ExcerptModel) => {
    if (card.isTable || card.tableData) return 170;
    if (card.isImage || card.imageUrl) return 160;
    return card.text && card.text.length > 70 ? 130 : 105;
  };

  // Master Redraw
  const render = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext ? canvas.getContext('2d') : null;
    if (!ctx) return;

    const width = canvas.width || 1000;
    const height = canvas.height || 800;
    ctx.clearRect(0, 0, width, height);

    const splitY = hasDoc ? height * splitRatio : 0;
    const docBottomY = Math.max(0, splitY - 12);
    const canvasTopY = hasDoc ? splitY + 12 : 0;

    // =========================================================================
    // 1. TOP ZONE: Document Viewer
    // =========================================================================
    if (hasDoc && document && docBottomY > 10) {
      ctx.save();
      ctx.beginPath();
      ctx.rect(0, 0, width, docBottomY);
      ctx.clip();

      ctx.fillStyle = '#0F172A';
      ctx.fillRect(0, 0, width, docBottomY);

      // Subheader Strip
      const subheaderH = 38;
      ctx.fillStyle = '#131922';
      ctx.fillRect(0, 0, width, subheaderH);

      // Document Dropdown Pill
      const docTitle =
        document.title.length > 22
          ? document.title.slice(0, 20) + '... ▾'
          : `${document.title} ▾`;
      ctx.fillStyle = '#1A202C';
      ctx.strokeStyle = '#00ADB5';
      ctx.lineWidth = 1;
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(14, 6, 175, 26, 13);
      else ctx.rect(14, 6, 175, 26);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = '#00ADB5';
      ctx.font = 'bold 11px sans-serif';
      ctx.fillText(docTitle, 24, 23);

      // Page Badge
      ctx.fillStyle = '#CBD5E1';
      ctx.font = '11px sans-serif';
      ctx.fillText(`p. 7/${document.pageCount || 58}`, 200, 23);

      // Crop button
      ctx.fillStyle = '#1A202C';
      ctx.strokeStyle = '#00ADB5';
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(width - 145, 6, 70, 26, 13);
      else ctx.rect(width - 145, 6, 70, 26);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = '#00ADB5';
      ctx.font = 'bold 11px sans-serif';
      ctx.fillText('✂️ Crop', width - 133, 23);

      // Zoom
      ctx.fillStyle = '#64748B';
      ctx.font = '11px sans-serif';
      ctx.fillText('-', width - 62, 23);
      ctx.fillText('1:1', width - 48, 23);
      ctx.fillText('+', width - 26, 23);

      // Document Content: White Book Paper Page Container
      const paperMargin = 16;
      const paperW = Math.min(width - paperMargin * 2, 600);
      const paperX = (width - paperW) / 2;
      var curY = subheaderH + 16 - docScrollY;
      const newMeasuredParas: MeasuredParagraph[] = [];

      (document.sections ?? []).forEach((sec) => {
        const secAnns = annotations.filter((a) => a.sectionId === sec.id);
        const hasAnn = secAnns.length > 0;

        if (isSqueezed && !hasAnn && !sec.tables && !sec.imageUrl) {
          ctx.fillStyle = '#1E293B';
          ctx.strokeStyle = '#334155';
          ctx.beginPath();
          if (ctx.roundRect) ctx.roundRect(paperX, curY, paperW, 26, 6);
          else ctx.rect(paperX, curY, paperW, 26);
          ctx.fill();
          ctx.stroke();

          ctx.fillStyle = '#64748B';
          ctx.font = 'italic 11px sans-serif';
          ctx.fillText(
            `── Page ${sec.pageNumber}: ${sec.heading} (Folded) ──`,
            paperX + 16,
            curY + 17
          );
          curY += 32;
          return;
        }

        // Measure Page Height
        const headingH = 45;
        let totalParasH = 0;
        ctx.font = '12px Georgia, serif';
        sec.paragraphs.forEach((para) => {
          const { totalHeight } = measureAndWrapParagraph(
            ctx,
            para,
            paperW - 52,
            paperX + 26,
            0,
            18
          );
          totalParasH += totalHeight + 14;
        });
        const totalSecH = headingH + totalParasH + 20;

        // Draw Paper Page
        ctx.save();
        ctx.shadowColor = 'rgba(0,0,0,0.3)';
        ctx.shadowBlur = 10;
        ctx.shadowOffsetY = 4;
        ctx.fillStyle = '#FFFFFF';
        ctx.beginPath();
        if (ctx.roundRect) ctx.roundRect(paperX, curY, paperW, totalSecH, 8);
        else ctx.rect(paperX, curY, paperW, totalSecH);
        ctx.fill();
        ctx.restore();

        ctx.strokeStyle = '#E2E8F0';
        ctx.lineWidth = 1;
        ctx.stroke();

        // Heading centered (e.g. "PREFACE")
        ctx.fillStyle = '#0F172A';
        ctx.font = 'bold 15px Georgia, serif';
        const hText = sec.heading.toUpperCase();
        const hw = ctx.measureText(hText).width;
        ctx.fillText(hText, paperX + (paperW - hw) / 2, curY + 32);

        var pY = curY + headingH;

        // Paragraphs
        sec.paragraphs.forEach((para, pIdx) => {
          const ann = secAnns.find((a) => a.paragraphIndex === pIdx);
          const isHighlighted = Boolean(ann);

          ctx.font = '12px Georgia, serif';
          const { lines, totalHeight } = measureAndWrapParagraph(
            ctx,
            para,
            paperW - 52,
            paperX + 26,
            pY,
            18
          );

          newMeasuredParas.push({
            secId: sec.id,
            pageNumber: sec.pageNumber,
            pIdx,
            fullText: para,
            lines,
            topY: pY,
            height: totalHeight,
          });

          if (isHighlighted) {
            ctx.fillStyle = 'rgba(0, 173, 181, 0.16)';
            ctx.fillRect(paperX + 16, pY - 4, paperW - 32, totalHeight + 8);
            ctx.fillStyle = ann?.color || '#00ADB5';
            ctx.fillRect(paperX + 16, pY - 4, 3.5, totalHeight + 8);
          }

          ctx.fillStyle = '#1E293B';
          ctx.font = '12px Georgia, serif';
          lines.forEach((line) => {
            ctx.fillText(line.text, line.x, line.y);
          });

          pY += totalHeight + 14;
        });

        curY += totalSecH + 18;
      });

      measuredParagraphsRef.current = newMeasuredParas;

      // Draw Active Text Selection (Teal highlights, handle pins, callout menu)
      if (docSelection) {
        // 1. Highlight Rectangles
        docSelection.highlightRects.forEach((r) => {
          ctx.fillStyle = 'rgba(0, 173, 181, 0.35)';
          ctx.beginPath();
          if (ctx.roundRect) ctx.roundRect(r.x, r.y, r.width, r.height, 3);
          else ctx.rect(r.x, r.y, r.width, r.height);
          ctx.fill();

          ctx.strokeStyle = '#00ADB5';
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.moveTo(r.x, r.y + r.height);
          ctx.lineTo(r.x + r.width, r.y + r.height);
          ctx.stroke();
        });

        // 2. Start Handle Pin (Vertical cyan bar + top circle)
        const sH = docSelection.startHandle;
        ctx.strokeStyle = '#00ADB5';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(sH.x, sH.y - 6);
        ctx.lineTo(sH.x, sH.y + sH.height);
        ctx.stroke();

        ctx.fillStyle = '#00ADB5';
        ctx.beginPath();
        ctx.arc(sH.x, sH.y - 7, 7, 0, Math.PI * 2);
        ctx.fill();

        // 3. End Handle Pin (Vertical cyan bar + bottom circle)
        const eH = docSelection.endHandle;
        ctx.strokeStyle = '#00ADB5';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(eH.x, eH.y);
        ctx.lineTo(eH.x, eH.y + eH.height + 6);
        ctx.stroke();

        ctx.fillStyle = '#00ADB5';
        ctx.beginPath();
        ctx.arc(eH.x, eH.y + eH.height + 7, 7, 0, Math.PI * 2);
        ctx.fill();

        // 4. Floating Callout Menu Dock
        const c = docSelection.calloutRect;
        ctx.save();
        ctx.shadowColor = 'rgba(0, 0, 0, 0.4)';
        ctx.shadowBlur = 12;
        ctx.shadowOffsetY = 4;
        ctx.fillStyle = '#1E293B';
        ctx.beginPath();
        if (ctx.roundRect) ctx.roundRect(c.x, c.y, c.width, c.height, 8);
        else ctx.rect(c.x, c.y, c.width, c.height);
        ctx.fill();
        ctx.restore();

        ctx.strokeStyle = '#334155';
        ctx.lineWidth = 1.5;
        ctx.stroke();

        // [+ Excerpt] Button
        const exB = docSelection.calloutExcerptBtn;
        ctx.fillStyle = '#00ADB5';
        ctx.beginPath();
        if (ctx.roundRect)
          ctx.roundRect(exB.x, exB.y, exB.width, exB.height, 5);
        else ctx.rect(exB.x, exB.y, exB.width, exB.height);
        ctx.fill();
        ctx.fillStyle = '#FFFFFF';
        ctx.font = 'bold 11px sans-serif';
        ctx.fillText('+ Excerpt', exB.x + 14, exB.y + 19);

        // [📋 Copy] Button
        const cpB = docSelection.calloutCopyBtn;
        ctx.fillStyle = '#0F172A';
        ctx.strokeStyle = '#334155';
        ctx.lineWidth = 1;
        ctx.beginPath();
        if (ctx.roundRect)
          ctx.roundRect(cpB.x, cpB.y, cpB.width, cpB.height, 5);
        else ctx.rect(cpB.x, cpB.y, cpB.width, cpB.height);
        ctx.fill();
        ctx.stroke();
        ctx.fillStyle = '#38BDF8';
        ctx.font = 'bold 11px sans-serif';
        ctx.fillText(copiedToastText || '📋 Copy', cpB.x + 12, cpB.y + 19);

        // [🖍️ Highlight] Button
        const hlB = docSelection.calloutHighlightBtn;
        ctx.fillStyle = '#F59E0B';
        ctx.beginPath();
        if (ctx.roundRect)
          ctx.roundRect(hlB.x, hlB.y, hlB.width, hlB.height, 5);
        else ctx.rect(hlB.x, hlB.y, hlB.width, hlB.height);
        ctx.fill();
        ctx.fillStyle = '#FFFFFF';
        ctx.font = 'bold 11px sans-serif';
        ctx.fillText('🖍️ Highlight', hlB.x + 10, hlB.y + 19);

        // [◀] & [▶] Word Expander Buttons
        const prevB = docSelection.calloutPrevWordBtn;
        ctx.fillStyle = '#0F172A';
        ctx.beginPath();
        if (ctx.roundRect)
          ctx.roundRect(prevB.x, prevB.y, prevB.width, prevB.height, 4);
        else ctx.rect(prevB.x, prevB.y, prevB.width, prevB.height);
        ctx.fill();
        ctx.fillStyle = '#94A3B8';
        ctx.font = 'bold 11px sans-serif';
        ctx.fillText('◀', prevB.x + 9, prevB.y + 19);

        const nextB = docSelection.calloutNextWordBtn;
        ctx.fillStyle = '#0F172A';
        ctx.beginPath();
        if (ctx.roundRect)
          ctx.roundRect(nextB.x, nextB.y, nextB.width, nextB.height, 4);
        else ctx.rect(nextB.x, nextB.y, nextB.width, nextB.height);
        ctx.fill();
        ctx.fillStyle = '#94A3B8';
        ctx.font = 'bold 11px sans-serif';
        ctx.fillText('▶', nextB.x + 9, nextB.y + 19);

        // [✕] Close Button
        const clB = docSelection.calloutCloseBtn;
        ctx.fillStyle = '#94A3B8';
        ctx.font = 'bold 12px sans-serif';
        ctx.fillText('✕', clB.x + 9, clB.y + 19);
      }

      // Floating Squeeze Button on Right Margin
      const sqW = 30;
      const sqH = 42;
      const sqY = Math.max(
        subheaderH + 12,
        Math.min(docBottomY - sqH - 12, docBottomY / 2 - sqH / 2)
      );
      ctx.fillStyle = '#1E293B';
      ctx.strokeStyle = isSqueezed ? '#00ADB5' : '#334155';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(width - sqW - 8, sqY, sqW, sqH, 15);
      else ctx.rect(width - sqW - 8, sqY, sqW, sqH);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = '#00ADB5';
      ctx.font = 'bold 16px sans-serif';
      ctx.fillText('≈', width - sqW + 1, sqY + 26);

      ctx.restore();
    }

    // =========================================================================
    // 2. CENTER ZONE: Draggable Split Divider
    // =========================================================================
    if (hasDoc) {
      ctx.save();
      ctx.strokeStyle = '#334155';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(0, splitY);
      ctx.lineTo(width, splitY);
      ctx.stroke();

      // Pill Handle
      const handleW = 68;
      const handleH = 18;
      ctx.fillStyle = '#1A202C';
      ctx.strokeStyle = '#00ADB5';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      if (ctx.roundRect)
        ctx.roundRect(
          width / 2 - handleW / 2,
          splitY - handleH / 2,
          handleW,
          handleH,
          9
        );
      else
        ctx.rect(
          width / 2 - handleW / 2,
          splitY - handleH / 2,
          handleW,
          handleH
        );
      ctx.fill();
      ctx.stroke();

      // 3 Horizontal Grip lines
      ctx.strokeStyle = '#00ADB5';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.moveTo(width / 2 - 14, splitY - 3.5);
      ctx.lineTo(width / 2 + 14, splitY - 3.5);
      ctx.moveTo(width / 2 - 14, splitY);
      ctx.lineTo(width / 2 + 14, splitY);
      ctx.moveTo(width / 2 - 14, splitY + 3.5);
      ctx.lineTo(width / 2 + 14, splitY + 3.5);
      ctx.stroke();
      ctx.restore();
    }

    // =========================================================================
    // 3. BOTTOM ZONE: Infinite Canvas
    // =========================================================================
    ctx.save();
    ctx.beginPath();
    ctx.rect(0, canvasTopY, width, height - canvasTopY);
    ctx.clip();

    ctx.fillStyle = '#131922';
    ctx.fillRect(0, canvasTopY, width, height - canvasTopY);

    // Pattern
    if (pattern === 'looseleaf') {
      const lineSpacing = 36 * scale;
      const startY =
        canvasTopY + (((pan.y % lineSpacing) + lineSpacing) % lineSpacing);
      ctx.strokeStyle = '#1E293B';
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (let y = startY; y < height; y += lineSpacing) {
        ctx.moveTo(0, y);
        ctx.lineTo(width, y);
      }
      ctx.stroke();

      const marginX = 70 * scale + pan.x;
      if (marginX >= 0 && marginX <= width) {
        ctx.strokeStyle = '#4B1D24';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(marginX, canvasTopY);
        ctx.lineTo(marginX, height);
        ctx.stroke();
      }
    } else if (pattern === 'dots') {
      const spacing = 36 * scale;
      const startX = ((pan.x % spacing) + spacing) % spacing;
      const startY = canvasTopY + (((pan.y % spacing) + spacing) % spacing);
      ctx.fillStyle = '#2D3748';
      for (let x = startX; x < width; x += spacing) {
        for (let y = startY; y < height; y += spacing) {
          ctx.beginPath();
          ctx.arc(x, y, 1.8, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    } else if (pattern === 'grid') {
      const spacing = 44 * scale;
      const startX = ((pan.x % spacing) + spacing) % spacing;
      const startY = canvasTopY + (((pan.y % spacing) + spacing) % spacing);
      ctx.strokeStyle = '#1E293B';
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (let x = startX; x < width; x += spacing) {
        ctx.moveTo(x, canvasTopY);
        ctx.lineTo(x, height);
      }
      for (let y = startY; y < height; y += spacing) {
        ctx.moveTo(0, y);
        ctx.lineTo(width, y);
      }
      ctx.stroke();
    }

    // Tether Cords
    inkLinks.forEach((link) => {
      const card = excerpts.find((e) => e.id === link.sourceExcerptId);
      if (!card) return;
      const isHeld = draggingCardId === card.id || selectedCardId === card.id;
      const cardPos = worldToCanvasScreen(card.x, card.y, canvasTopY);
      const startX = Math.max(
        10,
        Math.min(width - 60, cardPos.x - 100 * scale)
      );
      const startY = Math.max(20, canvasTopY - 14);
      const endX = cardPos.x + 16 * scale;
      const endY = cardPos.y + 19 * scale;

      ctx.save();
      // Glow
      ctx.strokeStyle = link.color || '#00ADB5';
      ctx.lineWidth = (isHeld ? 6 : 4) * scale;
      ctx.globalAlpha = 0.25;
      if (ctx.setLineDash) ctx.setLineDash([8 * scale, 5 * scale]);
      ctx.beginPath();
      ctx.moveTo(startX, startY);
      ctx.bezierCurveTo(
        startX + (endX - startX) * 0.4,
        startY + 20 * scale,
        startX + (endX - startX) * 0.7,
        endY - 15 * scale,
        endX,
        endY
      );
      ctx.stroke();

      // Main dashed cord
      ctx.globalAlpha = 1;
      ctx.lineWidth = (isHeld ? 2.8 : 2) * scale;
      ctx.beginPath();
      ctx.moveTo(startX, startY);
      ctx.bezierCurveTo(
        startX + (endX - startX) * 0.4,
        startY + 20 * scale,
        startX + (endX - startX) * 0.7,
        endY - 15 * scale,
        endX,
        endY
      );
      ctx.stroke();

      if (ctx.setLineDash) ctx.setLineDash([]);
      ctx.fillStyle = link.color || '#00ADB5';
      ctx.beginPath();
      ctx.arc(endX, endY, (isHeld ? 6 : 4.5) * scale, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#FFFFFF';
      ctx.beginPath();
      ctx.arc(endX, endY, (isHeld ? 2.5 : 1.8) * scale, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    });

    // Inking Strokes
    const allStrokes: InkStroke[] = [...strokes];
    if (currentStroke.length > 0) {
      allStrokes.push({
        id: 'active',
        points: currentStroke,
        color: selectedColor,
        strokeWidth: activeTool === 'highlighter' ? 14 : 3.5,
        isHighlighter: activeTool === 'highlighter',
      });
    }

    allStrokes.forEach((stroke) => {
      if (!stroke.points || stroke.points.length < 2) return;
      const pts = chaikinSmooth(stroke.points, 1);
      ctx.save();
      ctx.strokeStyle = stroke.color;
      ctx.lineWidth = stroke.strokeWidth * scale;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      if (stroke.isHighlighter) ctx.globalAlpha = 0.38;

      ctx.beginPath();
      const s0 = worldToCanvasScreen(pts[0]!.x, pts[0]!.y, canvasTopY);
      ctx.moveTo(s0.x, s0.y);

      for (let i = 1; i < pts.length - 1; i++) {
        const s1 = worldToCanvasScreen(pts[i]!.x, pts[i]!.y, canvasTopY);
        const s2 = worldToCanvasScreen(
          pts[i + 1]!.x,
          pts[i + 1]!.y,
          canvasTopY
        );
        ctx.quadraticCurveTo(s1.x, s1.y, (s1.x + s2.x) / 2, (s1.y + s2.y) / 2);
      }
      const sLast = worldToCanvasScreen(
        pts[pts.length - 1]!.x,
        pts[pts.length - 1]!.y,
        canvasTopY
      );
      ctx.lineTo(sLast.x, sLast.y);
      ctx.stroke();
      ctx.restore();
    });

    // Landing Ripple
    if (ripple && ripple.opacity > 0.05) {
      const screenPos = worldToCanvasScreen(ripple.x, ripple.y, canvasTopY);
      ctx.save();
      ctx.strokeStyle = ripple.color;
      ctx.globalAlpha = ripple.opacity;
      ctx.lineWidth = 3.5 * scale;
      ctx.beginPath();
      ctx.arc(screenPos.x, screenPos.y, ripple.radius * scale, 0, Math.PI * 2);
      ctx.stroke();
      ctx.restore();
    }

    // Excerpt Cards
    excerpts.forEach((card) => {
      const pos = worldToCanvasScreen(card.x, card.y, canvasTopY);
      const cardW = (card.width || 220) * scale;
      const cardH = getCardHeight(card) * scale;
      const isSelected = selectedCardId === card.id;

      ctx.save();

      // Card shadow
      ctx.shadowColor = 'rgba(0, 0, 0, 0.25)';
      ctx.shadowBlur = 8 * scale;
      ctx.shadowOffsetY = 4 * scale;

      ctx.fillStyle = '#FFFFFF';
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(pos.x, pos.y, cardW, cardH, 12 * scale);
      else ctx.rect(pos.x, pos.y, cardW, cardH);
      ctx.fill();
      ctx.restore();

      ctx.strokeStyle = isSelected ? '#00ADB5' : '#E2E8F0';
      ctx.lineWidth = (isSelected ? 2.5 : 1.5) * scale;
      ctx.stroke();

      // Left Accent Pill
      ctx.fillStyle = card.color || '#00ADB5';
      ctx.beginPath();
      if (ctx.roundRect)
        ctx.roundRect(
          pos.x,
          pos.y + 8 * scale,
          4 * scale,
          cardH - 16 * scale,
          2 * scale
        );
      else ctx.rect(pos.x, pos.y + 8 * scale, 4 * scale, cardH - 16 * scale);
      ctx.fill();

      // Color dot
      ctx.fillStyle = card.color || '#00ADB5';
      ctx.beginPath();
      ctx.arc(
        pos.x + 16 * scale,
        pos.y + 19 * scale,
        4.5 * scale,
        0,
        Math.PI * 2
      );
      ctx.fill();

      // Page Badge Pill (Light cyan capsule)
      const badgePillW = 60 * scale;
      ctx.fillStyle = 'rgba(0, 173, 181, 0.12)';
      ctx.strokeStyle = 'rgba(0, 173, 181, 0.3)';
      ctx.lineWidth = 0.5 * scale;
      ctx.beginPath();
      if (ctx.roundRect)
        ctx.roundRect(
          pos.x + 24 * scale,
          pos.y + 9 * scale,
          badgePillW,
          20 * scale,
          5 * scale
        );
      else
        ctx.rect(pos.x + 24 * scale, pos.y + 9 * scale, badgePillW, 20 * scale);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = '#00ADB5';
      ctx.font = `bold ${Math.max(9, Math.round(10 * scale))}px sans-serif`;
      ctx.fillText(
        `🔗 p. ${card.pageNumber || 1}`,
        pos.x + 30 * scale,
        pos.y + 23 * scale
      );

      // Close Button
      ctx.fillStyle = '#94A3B8';
      ctx.font = `${Math.max(10, Math.round(12 * scale))}px sans-serif`;
      ctx.fillText('✕', pos.x + cardW - 18 * scale, pos.y + 22 * scale);

      // Text (Dark slate, italic quotes)
      ctx.fillStyle = '#1E293B';
      ctx.font = `italic ${Math.max(10, Math.round(11.5 * scale))}px Georgia, serif`;
      const previewText = `"${card.text.length > 55 ? card.text.slice(0, 52) + '...' : card.text}"`;
      ctx.fillText(
        previewText,
        pos.x + 16 * scale,
        pos.y + 50 * scale,
        cardW - 32 * scale
      );

      // Context Toolbar if selected
      if (isSelected) {
        ctx.save();
        ctx.fillStyle = '#1A1D24';
        ctx.strokeStyle = '#393E46';
        ctx.lineWidth = 1;
        const tbW = 150 * scale;
        const tbH = 28 * scale;
        const tbX = pos.x + (cardW - tbW) / 2;
        const tbY = pos.y - 36 * scale;
        if (ctx.roundRect) ctx.roundRect(tbX, tbY, tbW, tbH, 6 * scale);
        else ctx.rect(tbX, tbY, tbW, tbH);
        ctx.fill();
        ctx.stroke();

        CONTEXT_COLORS.forEach((col, idx) => {
          ctx.fillStyle = col;
          ctx.beginPath();
          ctx.arc(
            tbX + (18 + idx * 28) * scale,
            tbY + 14 * scale,
            7 * scale,
            0,
            Math.PI * 2
          );
          ctx.fill();
        });
        ctx.restore();
      }

      ctx.restore();
    });

    ctx.restore();

    // =========================================================================
    // 4. FLOATING 3D CROSS-ZONE LIFT-AND-DRAG CARD (LiquidText interaction)
    // =========================================================================
    if (liftItem) {
      const isOverCanvas = ghostPos.y >= canvasTopY - 20;
      const themeColor = isOverCanvas ? '#10B981' : '#00ADB5';

      ctx.save();

      // Elastic Spring Tether string
      ctx.strokeStyle = themeColor;
      ctx.lineWidth = 3;
      ctx.setLineDash([7, 5]);
      ctx.beginPath();
      ctx.moveTo(liftItem.originX, liftItem.originY);
      ctx.quadraticCurveTo(
        (liftItem.originX + ghostPos.x) / 2,
        Math.max(liftItem.originY, ghostPos.y) + 30,
        ghostPos.x,
        ghostPos.y
      );
      ctx.stroke();
      ctx.setLineDash([]);

      // 3D Floating Lifted Tile
      const ghostW = 230;
      const ghostH = 92;

      ctx.translate(ghostPos.x, ghostPos.y);
      ctx.rotate((-2.5 * Math.PI) / 180);
      ctx.scale(1.08, 1.08);

      const gx = -ghostW / 2;
      const gy = -ghostH / 2;

      // Elevated shadow
      ctx.shadowColor = 'rgba(0, 0, 0, 0.35)';
      ctx.shadowBlur = 14;
      ctx.shadowOffsetY = 6;
      ctx.fillStyle = '#FFFFFF';
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(gx, gy, ghostW, ghostH, 12);
      else ctx.rect(gx, gy, ghostW, ghostH);
      ctx.fill();

      // Border glow
      ctx.strokeStyle = themeColor;
      ctx.lineWidth = 2.5;
      ctx.stroke();

      // Left Accent Pill
      ctx.fillStyle = themeColor;
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(gx, gy + 8, 4.5, ghostH - 16, 2);
      else ctx.rect(gx, gy + 8, 4.5, ghostH - 16);
      ctx.fill();

      // Header status indicator
      ctx.fillStyle = themeColor;
      ctx.beginPath();
      ctx.arc(gx + 16, gy + 18, 4.5, 0, Math.PI * 2);
      ctx.fill();

      ctx.font = 'bold 10px sans-serif';
      ctx.fillText(
        isOverCanvas ? '🎯 RELEASE TO DROP ON CANVAS' : '✨ DRAGGING EXCERPT',
        gx + 26,
        gy + 22
      );

      // Quote text
      ctx.fillStyle = '#1E293B';
      ctx.font = 'italic 11.5px Georgia, serif';
      const preview = `"${liftItem.text.length > 40 ? liftItem.text.slice(0, 38) + '...' : liftItem.text}"`;
      ctx.fillText(preview, gx + 16, gy + 48, ghostW - 28);

      // Page badge
      ctx.fillStyle = 'rgba(0, 173, 181, 0.14)';
      ctx.strokeStyle = 'rgba(0, 173, 181, 0.35)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(gx + 16, gy + 62, 80, 18, 5);
      else ctx.rect(gx + 16, gy + 62, 80, 18);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = '#00ADB5';
      ctx.font = 'bold 10px sans-serif';
      ctx.fillText(`🔗 Page ${liftItem.pageNumber}`, gx + 22, gy + 75);

      ctx.restore();
    }
  }, [
    document,
    annotations,
    isSqueezed,
    splitRatio,
    activeTool,
    selectedColor,
    pattern,
    strokes,
    excerpts,
    inkLinks,
    pan,
    scale,
    selectedCardId,
    docScrollY,
    liftItem,
    ghostPos,
    ripple,
    currentStroke,
    draggingCardId,
    hasDoc,
    worldToCanvasScreen,
    docSelection,
    copiedToastText,
  ]);

  // Handle Resize
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const updateSize = () => {
      const rect = canvas.getBoundingClientRect();
      if (rect.width && rect.height) {
        canvas.width = rect.width;
        canvas.height = rect.height;
        render();
      }
    };
    updateSize();
    window.addEventListener('resize', updateSize);
    return () => window.removeEventListener('resize', updateSize);
  }, [render]);

  useEffect(() => {
    render();
  }, [render]);

  // Pointer Interaction Handlers
  const handlePointerDown = (e: any) => {
    const rect = e.currentTarget?.getBoundingClientRect?.() || {
      left: 0,
      top: 0,
    };
    const sx = e.clientX - rect.left;
    const sy = e.clientY - rect.top;
    const canvasH = rect.height || 800;
    const splitY = hasDoc ? canvasH * splitRatio : 0;
    const canvasTopY = getCanvasTopY(canvasH);

    setTotalDragDist(0);

    // 1. Check Divider Hit
    if (hasDoc && Math.abs(sy - splitY) < 14) {
      setIsDraggingDivider(true);
      return;
    }

    // 2. Document Zone Interaction
    if (hasDoc && sy < splitY - 12) {
      // A. Check if user clicked inside floating Callout buttons
      if (docSelection) {
        const c = docSelection.calloutRect;
        if (
          sx >= c.x &&
          sx <= c.x + c.width &&
          sy >= c.y &&
          sy <= c.y + c.height
        ) {
          const exB = docSelection.calloutExcerptBtn;
          if (
            sx >= exB.x &&
            sx <= exB.x + exB.width &&
            sy >= exB.y &&
            sy <= exB.y + exB.height
          ) {
            // Drop onto canvas
            const newId = `card-${Date.now()}`;
            const canvasMidY = splitY + 80;
            const world = canvasScreenToWorld(
              canvasH / 2,
              canvasMidY,
              canvasTopY
            );
            const newExcerpt: ExcerptModel = {
              id: newId,
              text: docSelection.text,
              pageNumber: docSelection.pageNumber,
              color: '#00ADB5',
              x: Math.max(20, world.x - 110),
              y: Math.max(20, world.y - 50),
              width: 220,
            };
            excerpts.push(newExcerpt);
            inkLinks.push({
              id: `link-${Date.now()}`,
              sourceExcerptId: newId,
              targetPageNumber: docSelection.pageNumber,
              color: '#00ADB5',
            });
            onExcerptPress?.(newId);
            onExtractExcerpt?.({
              text: docSelection.text,
              pageNumber: docSelection.pageNumber,
              color: '#00ADB5',
              isTable: false,
              isImage: false,
            });
            triggerShockwave(newExcerpt.x + 110, newExcerpt.y + 50, '#00ADB5');
            setDocSelection(null);
            return;
          }

          const cpB = docSelection.calloutCopyBtn;
          if (
            sx >= cpB.x &&
            sx <= cpB.x + cpB.width &&
            sy >= cpB.y &&
            sy <= cpB.y + cpB.height
          ) {
            if (navigator?.clipboard?.writeText) {
              navigator.clipboard.writeText(docSelection.text);
            }
            onCopyText?.(docSelection.text);
            setCopiedToastText('✓ Copied!');
            setTimeout(() => setCopiedToastText(null), 1500);
            return;
          }

          const hlB = docSelection.calloutHighlightBtn;
          if (
            sx >= hlB.x &&
            sx <= hlB.x + hlB.width &&
            sy >= hlB.y &&
            sy <= hlB.y + hlB.height
          ) {
            onHighlightText?.(
              docSelection.text,
              docSelection.pageNumber,
              docSelection.sectionId,
              docSelection.pIdx
            );
            annotations.push({
              id: `ann-${Date.now()}`,
              sectionId: docSelection.sectionId,
              paragraphIndex: docSelection.pIdx,
              color: '#F59E0B',
              type: 'highlight',
              pageNumber: docSelection.pageNumber,
              text: docSelection.text,
            });
            setDocSelection(null);
            return;
          }

          const prevB = docSelection.calloutPrevWordBtn;
          if (
            sx >= prevB.x &&
            sx <= prevB.x + prevB.width &&
            sy >= prevB.y &&
            sy <= prevB.y + prevB.height
          ) {
            const p = measuredParagraphsRef.current.find(
              (mp) =>
                mp.secId === docSelection.sectionId &&
                mp.pIdx === docSelection.pIdx
            );
            if (p && canvasRef.current) {
              const ctx = canvasRef.current.getContext('2d');
              let s = docSelection.startCharIndex;
              while (s > 0 && /\s/.test(p.fullText[s - 1] || '')) s--;
              while (s > 0 && !/\s/.test(p.fullText[s - 1] || '')) s--;
              setDocSelection(
                buildDocSelection(
                  ctx,
                  p,
                  s,
                  docSelection.endCharIndex,
                  rect.width
                )
              );
            }
            return;
          }

          const nextB = docSelection.calloutNextWordBtn;
          if (
            sx >= nextB.x &&
            sx <= nextB.x + nextB.width &&
            sy >= nextB.y &&
            sy <= nextB.y + nextB.height
          ) {
            const p = measuredParagraphsRef.current.find(
              (mp) =>
                mp.secId === docSelection.sectionId &&
                mp.pIdx === docSelection.pIdx
            );
            if (p && canvasRef.current) {
              const ctx = canvasRef.current.getContext('2d');
              let wordEnd = docSelection.endCharIndex;
              while (
                wordEnd < p.fullText.length &&
                /\s/.test(p.fullText[wordEnd] || '')
              )
                wordEnd++;
              while (
                wordEnd < p.fullText.length &&
                !/\s/.test(p.fullText[wordEnd] || '')
              )
                wordEnd++;
              setDocSelection(
                buildDocSelection(
                  ctx,
                  p,
                  docSelection.startCharIndex,
                  wordEnd,
                  rect.width
                )
              );
            }
            return;
          }

          const clB = docSelection.calloutCloseBtn;
          if (
            sx >= clB.x &&
            sx <= clB.x + clB.width &&
            sy >= clB.y &&
            sy <= clB.y + clB.height
          ) {
            setDocSelection(null);
            return;
          }

          return;
        }

        // B. Check if user grabbed Start Handle pin
        const sH = docSelection.startHandle;
        if (
          Math.hypot(sx - sH.x, sy - (sH.y - 7)) < 22 ||
          (Math.abs(sx - sH.x) < 16 &&
            sy >= sH.y - 12 &&
            sy <= sH.y + sH.height + 12)
        ) {
          setIsDraggingStartHandle(true);
          return;
        }

        // C. Check if user grabbed End Handle pin
        const eH = docSelection.endHandle;
        if (
          Math.hypot(sx - eH.x, sy - (eH.y + eH.height + 7)) < 22 ||
          (Math.abs(sx - eH.x) < 16 &&
            sy >= eH.y - 12 &&
            sy <= eH.y + eH.height + 12)
        ) {
          setIsDraggingEndHandle(true);
          return;
        }

        // D. Check if user touched directly ON active selection highlight rects
        // (Direct 3D Lift & Drag from selection to canvas workspace!)
        const hitSelection = docSelection.highlightRects.some(
          (r) =>
            sx >= r.x - 6 &&
            sx <= r.x + r.width + 6 &&
            sy >= r.y - 4 &&
            sy <= r.y + r.height + 4
        );
        if (hitSelection) {
          setIsDirectDraggingSelection(true);
          setLiftItem({
            text: docSelection.text,
            pageNumber: docSelection.pageNumber,
            color: '#00ADB5',
            originX: docSelection.startHandle.x,
            originY: docSelection.startHandle.y,
          });
          setGhostPos({ x: sx, y: sy });
          setDragOffset({ x: sx, y: sy });
          return;
        }
      }

      // E. User tapped elsewhere in document: Select word at tapped location!
      const ctx = canvasRef.current?.getContext('2d');
      if (ctx) {
        for (const p of measuredParagraphsRef.current) {
          if (sy >= p.topY - 4 && sy <= p.topY + p.height + 4) {
            for (const line of p.lines) {
              if (sy >= line.top - 2 && sy <= line.top + line.height + 2) {
                // Determine character offset in line
                let offset = line.startIndex;
                let minDiff = Infinity;
                for (let c = 0; c <= line.text.length; c++) {
                  const subW = ctx.measureText(line.text.slice(0, c)).width;
                  const charX = line.x + subW;
                  const diff = Math.abs(sx - charX);
                  if (diff < minDiff) {
                    minDiff = diff;
                    offset = line.startIndex + c;
                  }
                }

                // Expand to word boundaries
                let wStart = offset;
                let wEnd = offset;
                while (wStart > 0 && !/\s/.test(p.fullText[wStart - 1] || ''))
                  wStart--;
                while (
                  wEnd < p.fullText.length &&
                  !/\s/.test(p.fullText[wEnd] || '')
                )
                  wEnd++;
                if (wStart === wEnd && wStart < p.fullText.length)
                  wEnd = Math.min(p.fullText.length, wStart + 1);

                const newSel = buildDocSelection(
                  ctx,
                  p,
                  wStart,
                  wEnd,
                  rect.width
                );
                setDocSelection(newSel);
                onSelectText?.(newSel);
                return;
              }
            }
          }
        }
      }

      // Fallback: document scroll
      setIsDrawing(true);
      setDragOffset({ x: sx, y: sy });
      return;
    }

    // 3. Canvas Zone Interaction
    if (sy >= canvasTopY) {
      const world = canvasScreenToWorld(sx, sy, canvasTopY);

      if (activeTool === 'select') {
        if (selectedCardId) {
          const selCard = excerpts.find((c) => c.id === selectedCardId);
          if (selCard) {
            const cardW = selCard.width || 220;
            const tbW = 150;
            const tbH = 28;
            const tbX = selCard.x + (cardW - tbW) / 2;
            const tbY = selCard.y - 36;
            if (
              world.x >= tbX &&
              world.x <= tbX + tbW &&
              world.y >= tbY &&
              world.y <= tbY + tbH
            ) {
              CONTEXT_COLORS.forEach((col, idx) => {
                const px = tbX + 18 + idx * 28;
                const py = tbY + 14;
                if (Math.hypot(world.x - px, world.y - py) <= 12) {
                  selCard.color = col;
                  onChangeCardColor?.(selCard.id, col);
                }
              });
              return;
            }
          }
        }
        for (let i = excerpts.length - 1; i >= 0; i--) {
          const card = excerpts[i]!;
          const cardW = card.width || 220;
          const cardH = getCardHeight(card);

          if (
            world.x >= card.x &&
            world.x <= card.x + cardW &&
            world.y >= card.y &&
            world.y <= card.y + cardH
          ) {
            if (world.x >= card.x + cardW - 30 && world.y <= card.y + 30) {
              onCardDelete?.(card.id);
              return;
            }
            setDraggingCardId(card.id);
            setSelectedCardId(card.id);
            setDragOffset({ x: world.x - card.x, y: world.y - card.y });
            onHoldCard?.(card.id);
            return;
          }
        }
        setIsDrawing(true);
        setSelectedCardId(null);
        setDragOffset({ x: sx - pan.x, y: sy - pan.y });
      } else if (activeTool === 'pen' || activeTool === 'highlighter') {
        setIsDrawing(true);
        setCurrentStroke([world]);
      } else if (activeTool === 'eraser') {
        for (const stroke of strokes) {
          for (const pt of stroke.points) {
            if (Math.hypot(pt.x - world.x, pt.y - world.y) < 24) {
              onEraseStroke?.(stroke.id);
              return;
            }
          }
        }
      }
    }
  };

  const handlePointerMove = (e: any) => {
    const rect = e.currentTarget?.getBoundingClientRect?.() || {
      left: 0,
      top: 0,
    };
    const sx = e.clientX - rect.left;
    const sy = e.clientY - rect.top;
    const canvasH = rect.height || 800;
    const canvasTopY = getCanvasTopY(canvasH);

    if (isDraggingDivider) {
      const newRatio = Math.max(0.15, Math.min(0.85, sy / canvasH));
      setSplitRatio(newRatio);
      onSplitRatioChange?.(newRatio);
      return;
    }

    // Dragging Start Handle
    if (isDraggingStartHandle && docSelection && canvasRef.current) {
      const ctx = canvasRef.current.getContext('2d');
      const p = measuredParagraphsRef.current.find(
        (mp) =>
          mp.secId === docSelection.sectionId && mp.pIdx === docSelection.pIdx
      );
      if (p && ctx) {
        let newStart = docSelection.startCharIndex;
        for (const line of p.lines) {
          if (sy >= line.top - 8 && sy <= line.top + line.height + 8) {
            for (let c = 0; c <= line.text.length; c++) {
              const charX =
                line.x + ctx.measureText(line.text.slice(0, c)).width;
              if (sx <= charX || Math.abs(sx - charX) < 12) {
                newStart = line.startIndex + c;
                break;
              }
            }
            break;
          }
        }
        const updated = buildDocSelection(
          ctx,
          p,
          newStart,
          docSelection.endCharIndex,
          rect.width
        );
        setDocSelection(updated);
        onSelectText?.(updated);
      }
      return;
    }

    // Dragging End Handle
    if (isDraggingEndHandle && docSelection && canvasRef.current) {
      const ctx = canvasRef.current.getContext('2d');
      const p = measuredParagraphsRef.current.find(
        (mp) =>
          mp.secId === docSelection.sectionId && mp.pIdx === docSelection.pIdx
      );
      if (p && ctx) {
        let newEnd = docSelection.endCharIndex;
        for (const line of p.lines) {
          if (sy >= line.top - 8 && sy <= line.top + line.height + 8) {
            for (let c = 0; c <= line.text.length; c++) {
              const charX =
                line.x + ctx.measureText(line.text.slice(0, c)).width;
              if (sx <= charX || Math.abs(sx - charX) < 12) {
                newEnd = line.startIndex + c;
                break;
              }
            }
            break;
          }
        }
        const updated = buildDocSelection(
          ctx,
          p,
          docSelection.startCharIndex,
          newEnd,
          rect.width
        );
        setDocSelection(updated);
        onSelectText?.(updated);
      }
      return;
    }

    if (isDirectDraggingSelection && liftItem) {
      setGhostPos({ x: sx, y: sy });
      return;
    }

    if (liftItem) {
      setGhostPos({ x: sx, y: sy });
      return;
    }

    if (draggingCardId) {
      setTotalDragDist((prev) => prev + 1);
      const world = canvasScreenToWorld(sx, sy, canvasTopY);
      const card = excerpts.find((c) => c.id === draggingCardId);
      if (card) {
        card.x = world.x - dragOffset.x;
        card.y = world.y - dragOffset.y;
      }
      return;
    }

    if (isDrawing) {
      if (hasDoc && sy < canvasH * splitRatio - 12) {
        // Document scrolling
        const dy = sy - dragOffset.y;
        setDocScrollY((prev) => Math.max(0, prev - dy * 0.8));
        setDragOffset({ x: sx, y: sy });
      } else if (activeTool === 'select') {
        const newPanX = sx - dragOffset.x;
        const newPanY = sy - dragOffset.y;
        setPan({ x: newPanX, y: newPanY });
        onTransformChange?.({ panX: newPanX, panY: newPanY, scale });
      } else if (activeTool === 'pen' || activeTool === 'highlighter') {
        const world = canvasScreenToWorld(sx, sy, canvasTopY);
        setCurrentStroke((prev) => [...prev, world]);
      }
    }
  };

  const handlePointerUp = (e: any) => {
    const rect = e.currentTarget?.getBoundingClientRect?.() || {
      left: 0,
      top: 0,
    };
    const sx = e.clientX - rect.left;
    const sy = e.clientY - rect.top;
    const canvasH = rect.height || 800;
    const canvasTopY = getCanvasTopY(canvasH);

    if (isDraggingDivider) {
      setIsDraggingDivider(false);
      return;
    }

    // Complete Cross-Zone Lift-and-Drop: drop into canvas!
    if (liftItem) {
      if (sy >= canvasTopY) {
        const world = canvasScreenToWorld(sx, sy, canvasTopY);
        const newId = `card-${Date.now()}`;
        const newCard: ExcerptModel = {
          id: newId,
          documentId: document?.id,
          pageNumber: liftItem.pageNumber,
          text: liftItem.text,
          color: liftItem.color,
          x: Math.max(20, world.x - 110),
          y: Math.max(20, world.y - 50),
          width: 220,
          comment: `Excerpt from p. ${liftItem.pageNumber}`,
        };
        excerpts.push(newCard);
        inkLinks.push({
          id: `link-${Date.now()}`,
          sourceExcerptId: newId,
          targetPageNumber: liftItem.pageNumber,
          color: liftItem.color,
        });
        triggerShockwave(newCard.x + 110, newCard.y + 50, newCard.color);
        onExtractExcerpt?.({
          text: liftItem.text,
          pageNumber: liftItem.pageNumber,
          color: liftItem.color,
          isTable: false,
          isImage: false,
        });
        setDocSelection(null);
      }
      setLiftItem(null);
      setIsDirectDraggingSelection(false);
      return;
    }

    setIsDraggingStartHandle(false);
    setIsDraggingEndHandle(false);
    setIsDirectDraggingSelection(false);

    if (draggingCardId) {
      const card = excerpts.find((c) => c.id === draggingCardId);
      if (card) {
        if (totalDragDist > 6) {
          triggerShockwave(
            card.x + (card.width || 220) / 2,
            card.y + 50,
            card.color || '#00ADB5'
          );
          onExcerptMoveEnd?.(
            card.id,
            card.x,
            card.y,
            card.clusterId,
            card.stackCount
          );
        } else {
          onExcerptPress?.(card.id);
        }
      }
      setDraggingCardId(null);
      onHoldCard?.(null);
    }

    if (isDrawing) {
      if (
        (activeTool === 'pen' || activeTool === 'highlighter') &&
        currentStroke.length > 1
      ) {
        const smoothed = chaikinSmooth(currentStroke, 1);
        onAddStroke?.({
          id: `stroke-${Date.now()}`,
          points: smoothed,
          color: selectedColor,
          strokeWidth: activeTool === 'highlighter' ? 14 : 3.5,
          isHighlighter: activeTool === 'highlighter',
        });
      }
      setIsDrawing(false);
      setCurrentStroke([]);
    }
  };

  const handleWheel = (e: any) => {
    if (e.preventDefault) e.preventDefault();
    const rect = e.currentTarget?.getBoundingClientRect?.() || {
      top: 0,
      height: 800,
    };
    const sy = e.clientY - rect.top;
    const canvasH = rect.height || 800;
    const splitY = hasDoc ? canvasH * splitRatio : 0;

    if (hasDoc && sy < splitY - 12) {
      setDocScrollY((prev) => Math.max(0, prev + e.deltaY * 0.7));
    } else {
      const zoomFactor = e.deltaY < 0 ? 1.08 : 0.92;
      const newScale = Math.min(Math.max(0.15, scale * zoomFactor), 4.5);
      setScale(newScale);
      onTransformChange?.({ panX: pan.x, panY: pan.y, scale: newScale });
    }
  };

  return (
    <View style={[styles.container, style]}>
      {/* @ts-ignore */}
      <canvas
        ref={canvasRef}
        width={1200}
        height={900}
        style={{ width: '100%', height: '100%', display: 'block' }}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onWheel={handleWheel}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#181A20',
    overflow: 'hidden',
  },
});

export default ThinkspaceView;
