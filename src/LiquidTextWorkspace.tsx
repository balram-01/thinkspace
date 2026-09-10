import React, { useState, useRef } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  TextInput,
  StyleSheet,
  SafeAreaView,
  StatusBar,
} from 'react-native';
import DocumentViewer from './DocumentViewer';
import SplitDivider from './SplitDivider';
import ThinkspaceView from './ThinkspaceView';
import type {
  LiquidTextWorkspaceProps,
  WorkspaceTool,
  WorkspacePattern,
  InkStroke,
  ExcerptModel,
  InkLink,
  DocumentAnnotation,
  ExtractedTable,
} from './types';

const TOOLS: { id: WorkspaceTool; label: string; icon: string }[] = [
  { id: 'select', label: 'Select', icon: '✋' },
  { id: 'pen', label: 'Pen', icon: '✏️' },
  { id: 'highlighter', label: 'Highlighter', icon: '🖍️' },
  { id: 'eraser', label: 'Eraser', icon: '🧹' },
];

const PATTERNS: { id: WorkspacePattern; label: string }[] = [
  { id: 'looseleaf', label: 'Lined Notebook' },
  { id: 'dots', label: 'Dots' },
  { id: 'grid', label: 'Grid' },
  { id: 'none', label: 'Plain' },
];

const COLORS = [
  '#00ADB5',
  '#F59E0B',
  '#EF4444',
  '#3B82F6',
  '#8B5CF6',
  '#10B981',
];

export const LiquidTextWorkspace: React.FC<LiquidTextWorkspaceProps> = ({
  document,
  initialPattern = 'looseleaf',
  initialTool = 'select',
}) => {
  // Split ratio: 0.44 top (document), 0.56 bottom (canvas)
  const [splitRatio, setSplitRatio] = useState(0.44);

  // Tools & Canvas state
  const [tool, setTool] = useState<WorkspaceTool>(initialTool);
  const [color, setColor] = useState('#00ADB5');
  const [pattern, setPattern] = useState<WorkspacePattern>(initialPattern);
  const [strokes, setStrokes] = useState<InkStroke[]>([]);
  const [excerpts, setExcerpts] = useState<ExcerptModel[]>([]);
  const [inkLinks, setInkLinks] = useState<InkLink[]>([]);
  const [cameraTransform, setCameraTransform] = useState({
    panX: 0,
    panY: 0,
    scale: 1,
  });

  // Document states
  const [isSqueezed, setIsSqueezed] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchOpen, setIsSearchOpen] = useState(false);
  const [annotations, setAnnotations] = useState<DocumentAnnotation[]>([]);
  const [targetPage, setTargetPage] = useState<number | null>(null);
  const [statusMsg, setStatusMsg] = useState(
    'LiquidText Split Workspace Ready'
  );

  // Lift-and-Drag state
  const [liftDragItem, setLiftDragItem] = useState<{
    text: string;
    pageNumber: number;
    color: string;
    isTable?: boolean;
    tableData?: ExtractedTable;
    isImage?: boolean;
    imageUrl?: string;
  } | null>(null);
  const [ghostPos, setGhostPos] = useState<{ x: number; y: number }>({
    x: 0,
    y: 0,
  });

  const canvasLayoutRef = useRef<{
    x: number;
    y: number;
    width: number;
    height: number;
  }>({
    x: 0,
    y: 0,
    width: 800,
    height: 500,
  });

  // Extract passage to workspace
  const handleExtractPassage = (
    text: string,
    pageNumber: number,
    isImage = false,
    imageUrl?: string,
    tableData?: ExtractedTable
  ) => {
    const newId = `card-${Date.now()}`;
    const cardColor = isTable(tableData)
      ? '#3B82F6'
      : isImage
        ? '#F59E0B'
        : '#00ADB5';

    // Staggered world placement based on existing cards count
    const offsetX = 60 + (excerpts.length % 3) * 260;
    const offsetY = 60 + Math.floor(excerpts.length / 3) * 160;

    const newCard: ExcerptModel = {
      id: newId,
      documentId: document.id,
      pageNumber,
      text,
      color: cardColor,
      x: offsetX,
      y: offsetY,
      width: tableData ? 270 : isImage ? 240 : 230,
      isTable: Boolean(tableData),
      tableData,
      isImage,
      imageUrl,
      comment: `Extracted from Page ${pageNumber}`,
    };

    const newLink: InkLink = {
      id: `link-${Date.now()}`,
      sourceExcerptId: newId,
      targetPageNumber: pageNumber,
      color: cardColor,
    };

    setExcerpts((prev) => [...prev, newCard]);
    setInkLinks((prev) => [...prev, newLink]);
    setStatusMsg(`Extracted excerpt to workspace (Page ${pageNumber})`);
  };

  function isTable(t?: ExtractedTable): boolean {
    return Boolean(t && t.rows && t.rows.length > 0);
  }

  // Highlight passage in document
  const handleHighlightPassage = (
    text: string,
    sectionId: string,
    paragraphIndex: number,
    pageNumber: number,
    highlightColor: string
  ) => {
    const newAnn: DocumentAnnotation = {
      id: `ann-${Date.now()}`,
      sectionId,
      paragraphIndex,
      pageNumber,
      text,
      color: highlightColor,
      type: 'highlight',
    };
    setAnnotations((prev) => [...prev, newAnn]);
    setStatusMsg(`Highlighted paragraph on Page ${pageNumber}`);
  };

  // Lift and drag directly from document to canvas
  const handleStartLiftDrag = (
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
  ) => {
    setLiftDragItem(data);
    setGhostPos({ x: clientX, y: clientY });
  };

  const handlePointerMove = (e: any) => {
    if (liftDragItem) {
      setGhostPos({ x: e.clientX, y: e.clientY });
    }
  };

  const handlePointerUp = (e: any) => {
    if (liftDragItem) {
      const cLayout = canvasLayoutRef.current;
      const clientX = e.clientX || ghostPos.x;
      const clientY = e.clientY || ghostPos.y;

      // Check if dropped within canvas bounds
      if (clientY >= cLayout.y) {
        const localX = clientX - cLayout.x;
        const localY = clientY - cLayout.y;
        // Convert screen to canvas world coords
        const worldX = (localX - cameraTransform.panX) / cameraTransform.scale;
        const worldY = (localY - cameraTransform.panY) / cameraTransform.scale;

        const newId = `card-${Date.now()}`;
        const newCard: ExcerptModel = {
          id: newId,
          documentId: document.id,
          pageNumber: liftDragItem.pageNumber,
          text: liftDragItem.text,
          color: liftDragItem.color,
          x: Math.max(20, worldX - 110),
          y: Math.max(20, worldY - 50),
          width: liftDragItem.isTable ? 270 : liftDragItem.isImage ? 240 : 230,
          isTable: liftDragItem.isTable,
          tableData: liftDragItem.tableData,
          isImage: liftDragItem.isImage,
          imageUrl: liftDragItem.imageUrl,
          comment: `Lifted from Page ${liftDragItem.pageNumber}`,
        };

        const newLink: InkLink = {
          id: `link-${Date.now()}`,
          sourceExcerptId: newId,
          targetPageNumber: liftDragItem.pageNumber,
          color: liftDragItem.color,
        };

        setExcerpts((prev) => [...prev, newCard]);
        setInkLinks((prev) => [...prev, newLink]);
        setStatusMsg(`✋ Lifted and dropped card onto workspace canvas!`);
      }

      setLiftDragItem(null);
    }
  };

  // Synchronized navigation: tapping card in canvas scrolls document viewer
  const handleExcerptPress = (cardId: string) => {
    const card = excerpts.find((c) => c.id === cardId);
    if (card && card.pageNumber) {
      setTargetPage(card.pageNumber);
      setStatusMsg(
        `Navigated document to Page ${card.pageNumber} (${card.id})`
      );
    }
  };

  return (
    <SafeAreaView
      style={styles.safeArea}
      // @ts-ignore
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
    >
      <StatusBar barStyle="light-content" backgroundColor="#181A20" />

      {/* Top Header & LiquidText Controls */}
      <View style={styles.topBar}>
        <View style={styles.titleSection}>
          <Text style={styles.appTitle}>LiquidText Workspace</Text>
          <Text style={styles.statusText}>{statusMsg}</Text>
        </View>

        {/* Tools */}
        <View style={styles.toolSection}>
          {TOOLS.map((t) => {
            const isActive = tool === t.id;
            return (
              <TouchableOpacity
                key={t.id}
                style={[styles.toolBtn, isActive && styles.activeToolBtn]}
                onPress={() => setTool(t.id)}
              >
                <Text style={styles.toolIcon}>{t.icon}</Text>
                <Text
                  style={[styles.toolLabel, isActive && styles.activeToolLabel]}
                >
                  {t.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </View>

        {/* Color Palette */}
        <View style={styles.colorSection}>
          {COLORS.map((c) => (
            <TouchableOpacity
              key={c}
              style={[
                styles.colorDot,
                { backgroundColor: c },
                color === c && styles.activeColorDot,
              ]}
              onPress={() => setColor(c)}
            />
          ))}
        </View>

        {/* Patterns */}
        <View style={styles.patternSection}>
          {PATTERNS.map((p) => {
            const isSelected = pattern === p.id;
            return (
              <TouchableOpacity
                key={p.id}
                style={[
                  styles.patternBtn,
                  isSelected && styles.activePatternBtn,
                ]}
                onPress={() => setPattern(p.id)}
              >
                <Text
                  style={[
                    styles.patternText,
                    isSelected && styles.activePatternText,
                  ]}
                >
                  {p.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </View>

        {/* LiquidText Special: Accordion Squeeze Button */}
        <TouchableOpacity
          style={[styles.squeezeBtn, isSqueezed && styles.activeSqueezeBtn]}
          onPress={() => {
            const next = !isSqueezed;
            setIsSqueezed(next);
            setStatusMsg(
              next
                ? '🪗 Accordion Squeeze: Folded unhighlighted text'
                : 'Accordion Squeeze disabled'
            );
          }}
        >
          <Text
            style={[
              styles.squeezeBtnText,
              isSqueezed && styles.activeSqueezeBtnText,
            ]}
          >
            🪗 {isSqueezed ? 'Unsqueeze' : 'Squeeze'}
          </Text>
        </TouchableOpacity>

        {/* Search Input Toggle */}
        <View style={styles.searchSection}>
          {isSearchOpen ? (
            <TextInput
              style={styles.searchInput}
              placeholder="Search document..."
              placeholderTextColor="#64748B"
              value={searchQuery}
              onChangeText={setSearchQuery}
              autoFocus
            />
          ) : (
            <TouchableOpacity
              style={styles.searchBtn}
              onPress={() => setIsSearchOpen(true)}
            >
              <Text style={styles.searchBtnText}>🔍 Find</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>

      {/* Main Split Layout: Document Viewer (Top) + Split Divider + Workspace Canvas (Bottom) */}
      <View style={styles.splitContainer}>
        {/* Top Pane: Document Viewer */}
        <View style={[styles.documentPane, { flex: splitRatio }]}>
          <DocumentViewer
            document={document}
            annotations={annotations}
            isSqueezed={isSqueezed}
            searchQuery={searchQuery}
            targetPage={targetPage}
            onHighlightPassage={handleHighlightPassage}
            onExtractPassage={handleExtractPassage}
            onStartLiftDrag={handleStartLiftDrag}
          />
        </View>

        {/* Middle: Draggable Split Divider */}
        <SplitDivider
          splitRatio={splitRatio}
          onRatioChange={(r) => setSplitRatio(r)}
        />

        {/* Bottom Pane: Hardware-Accelerated Native Canvas */}
        <View
          style={[styles.canvasPane, { flex: 1 - splitRatio }]}
          onLayout={(e) => {
            canvasLayoutRef.current = e.nativeEvent.layout;
          }}
        >
          <ThinkspaceView
            style={styles.nativeCanvas}
            activeTool={tool}
            selectedColor={color}
            pattern={pattern}
            strokes={strokes}
            excerpts={excerpts}
            inkLinks={inkLinks}
            onAddStroke={(s) => setStrokes((prev) => [...prev, s])}
            onEraseStroke={(id) =>
              setStrokes((prev) => prev.filter((s) => s.id !== id))
            }
            onExcerptMoveEnd={(id, x, y, cId, sCount) => {
              setExcerpts((prev) =>
                prev.map((c) =>
                  c.id === id
                    ? {
                        ...c,
                        x,
                        y,
                        clusterId: cId || c.clusterId,
                        stackCount: sCount || c.stackCount,
                      }
                    : c
                )
              );
            }}
            onExcerptPress={handleExcerptPress}
            onCardDelete={(id) => {
              setExcerpts((prev) => prev.filter((c) => c.id !== id));
              setInkLinks((prev) =>
                prev.filter((l) => l.sourceExcerptId !== id)
              );
            }}
            onChangeCardColor={(id, newColor) => {
              setExcerpts((prev) =>
                prev.map((c) => (c.id === id ? { ...c, color: newColor } : c))
              );
            }}
            onTransformChange={(t) => setCameraTransform(t)}
          />
        </View>
      </View>

      {/* Floating Lift-and-Drag Ghost Card */}
      {liftDragItem && (
        <View
          style={[
            styles.liftDragGhost,
            {
              left: ghostPos.x - 90,
              top: ghostPos.y - 40,
              borderColor: liftDragItem.color,
            },
          ]}
          pointerEvents="none"
        >
          <View
            style={[
              styles.ghostAccent,
              { backgroundColor: liftDragItem.color },
            ]}
          />
          <Text style={styles.ghostBadge}>
            {liftDragItem.isTable ? '📊' : liftDragItem.isImage ? '🖼️' : '🔗'}{' '}
            Page {liftDragItem.pageNumber}
          </Text>
          <Text style={styles.ghostText} numberOfLines={2}>
            {liftDragItem.text}
          </Text>
        </View>
      )}
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#181A20',
  },
  topBar: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    backgroundColor: '#222530',
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255, 255, 255, 0.08)',
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 10,
  },
  titleSection: {
    marginRight: 6,
  },
  appTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: '#EEEEEE',
  },
  statusText: {
    fontSize: 10,
    color: '#00ADB5',
    maxWidth: 220,
  },
  toolSection: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#181A20',
    borderRadius: 8,
    padding: 3,
    gap: 3,
  },
  toolBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 5,
    paddingHorizontal: 8,
    borderRadius: 5,
  },
  activeToolBtn: {
    backgroundColor: '#393E46',
  },
  toolIcon: {
    fontSize: 12,
    marginRight: 3,
  },
  toolLabel: {
    fontSize: 11,
    color: '#94A3B8',
  },
  activeToolLabel: {
    color: '#00ADB5',
    fontWeight: '700',
  },
  colorSection: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
  },
  colorDot: {
    width: 16,
    height: 16,
    borderRadius: 8,
  },
  activeColorDot: {
    borderWidth: 2,
    borderColor: '#FFFFFF',
    transform: [{ scale: 1.25 }],
  },
  patternSection: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#181A20',
    borderRadius: 6,
    padding: 2,
    gap: 2,
  },
  patternBtn: {
    paddingVertical: 4,
    paddingHorizontal: 7,
    borderRadius: 4,
  },
  activePatternBtn: {
    backgroundColor: '#393E46',
  },
  patternText: {
    fontSize: 10,
    color: '#94A3B8',
  },
  activePatternText: {
    color: '#EEEEEE',
    fontWeight: '700',
  },
  squeezeBtn: {
    backgroundColor: 'rgba(245, 158, 11, 0.15)',
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: 'rgba(245, 158, 11, 0.35)',
  },
  activeSqueezeBtn: {
    backgroundColor: '#F59E0B',
    borderColor: '#FFFFFF',
  },
  squeezeBtnText: {
    fontSize: 11,
    color: '#F59E0B',
    fontWeight: '700',
  },
  activeSqueezeBtnText: {
    color: '#181A20',
  },
  searchSection: {
    marginLeft: 'auto',
  },
  searchBtn: {
    backgroundColor: '#2E3442',
    paddingHorizontal: 8,
    paddingVertical: 5,
    borderRadius: 6,
  },
  searchBtnText: {
    fontSize: 11,
    color: '#CBD5E1',
  },
  searchInput: {
    backgroundColor: '#181A20',
    color: '#EEEEEE',
    fontSize: 11,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 6,
    width: 140,
    borderWidth: 1,
    borderColor: '#00ADB5',
  },
  splitContainer: {
    flex: 1,
    flexDirection: 'column',
  },
  documentPane: {
    width: '100%',
    overflow: 'hidden',
  },
  canvasPane: {
    width: '100%',
    overflow: 'hidden',
  },
  nativeCanvas: {
    flex: 1,
  },
  liftDragGhost: {
    position: 'absolute',
    width: 180,
    padding: 10,
    backgroundColor: '#222831',
    borderRadius: 8,
    borderWidth: 1.5,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.4,
    shadowRadius: 10,
    elevation: 10,
    zIndex: 999,
  },
  ghostAccent: {
    position: 'absolute',
    left: 0,
    top: 6,
    bottom: 6,
    width: 3,
    borderRadius: 1.5,
  },
  ghostBadge: {
    fontSize: 9,
    fontWeight: '700',
    color: '#00ADB5',
    marginBottom: 4,
  },
  ghostText: {
    fontSize: 11,
    color: '#EEEEEE',
    lineHeight: 15,
  },
});

export default LiquidTextWorkspace;
