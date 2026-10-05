import { useState, useCallback, useMemo, useRef, useEffect } from 'react';
import PdfEngineTestScreen from './PdfEngineTestScreen';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  StatusBar,
  Modal,
  Animated,
  Easing,
} from 'react-native';
import {
  ThinkspaceView,
  PdfEngine,
  PenSettingsPanel,
  DEFAULT_PEN_FAVORITES,
  type ThinkspaceViewRef,
  type WorkspaceTool,
  type WorkspacePattern,
  type InkStroke,
  type ExcerptModel,
  type InkLink,
  type DocumentAnnotation,
  type PdfDocumentInfo,
  type WorkspaceDocument,
  type WorkspaceDocumentEntry,
  type NotebookPageModel,
  type NotebookPageStyle,
  type PenDrawingMode,
  type SemanticInkLink,
} from 'thinkspace';

const INITIAL_ANNOTATIONS: DocumentAnnotation[] = [];
const INITIAL_EXCERPTS: ExcerptModel[] = [];
const INITIAL_LINKS: InkLink[] = [];

type NavTabMode = 'drawing' | 'document' | 'workspace';

// ── Document accent colors (cyclic assignment) ────────────────────────────
const DOC_COLORS = [
  '#6C5CE7',
  '#00ADB5',
  '#F59E0B',
  '#EF4444',
  '#10B981',
  '#8B5CF6',
  '#F97316',
  '#3B82F6',
];

export default function App() {
  const [appScreen, setAppScreen] = useState<'workspace' | 'pdftest'>(
    'workspace'
  );
  const [tool, setTool] = useState<WorkspaceTool>('select');
  const [color] = useState('#00ADB5');
  const [pattern, setPattern] = useState<WorkspacePattern>('looseleaf');
  const [isSqueezed, setIsSqueezed] = useState(false);
  const [splitRatio, setSplitRatio] = useState(0.48);
  const [strokes, setStrokes] = useState<InkStroke[]>([]);
  const [excerpts, setExcerpts] = useState<ExcerptModel[]>(INITIAL_EXCERPTS);
  const [inkLinks, setInkLinks] = useState<InkLink[]>(INITIAL_LINKS);
  const [annotations] = useState<DocumentAnnotation[]>(INITIAL_ANNOTATIONS);
  const [notebookPages, setNotebookPages] = useState<NotebookPageModel[]>([]);
  const [isAddMenuOpen, setIsAddMenuOpen] = useState(false);
  const [isStylePickerOpen, setIsStylePickerOpen] = useState(false);
  const [isDocsModalOpen, setIsDocsModalOpen] = useState(false);
  const [isWorkspacesModalOpen, setIsWorkspacesModalOpen] = useState(false);
  const [isPageEditorOpen, setIsPageEditorOpen] = useState(false);
  const [activeNav, setActiveNav] = useState<NavTabMode>('workspace');
  const [isImmersive, setIsImmersive] = useState(false);
  const immersiveAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(immersiveAnim, {
      toValue: isImmersive ? 1 : 0,
      duration: 250,
      easing: Easing.bezier(0.25, 0.1, 0.25, 1),
      useNativeDriver: false,
    }).start();
  }, [isImmersive, immersiveAnim]);

  const statusBarH = StatusBar.currentHeight ?? 24;
  const topBarH = 48 + statusBarH;
  const bottomChromeH = 112;

  const topTranslateY = immersiveAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [0, -topBarH],
  });

  const bottomTranslateY = immersiveAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [0, bottomChromeH],
  });

  const chromeOpacity = immersiveAnim.interpolate({
    inputRange: [0, 0.6, 1],
    outputRange: [1, 0.3, 0],
  });

  const contentTop = immersiveAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [topBarH, 0],
  });

  const contentBottom = immersiveAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [bottomChromeH, 0],
  });

  // ── Multi-Document Workspace State ────────────────────────────────────────
  /**
   * All documents in the workspace, as WorkspaceDocumentEntry objects.
   * This is the multi-document API. Each entry has a unique ID, title, pageCount, and uri.
   */
  const [workspaceDocs, setWorkspaceDocs] = useState<WorkspaceDocumentEntry[]>(
    []
  );

  /** ID of the document currently shown in the PDF viewport */
  const [activeDocId, setActiveDocId] = useState<string | null>(null);

  // Legacy single-doc state kept for internal tracking
  const [pdfDoc, setPdfDoc] = useState<PdfDocumentInfo | null>(null);
  const [pdfUri, setPdfUri] = useState<string | null>(null);

  // Thinkspace Native View Reference
  const thinkspaceRef = useRef<ThinkspaceViewRef | null>(null);

  // LiquidText Real Pen & Inking System State
  const [penMode, setPenMode] = useState<PenDrawingMode>('freehand');
  const [penColor, setPenColor] = useState('#00ADB5');
  const [penThickness, setPenThickness] = useState(3.5);
  const [penFavorites, setPenFavorites] = useState<string[]>(
    DEFAULT_PEN_FAVORITES
  );
  const [isPenSettingsOpen, setIsPenSettingsOpen] = useState(false);
  const [semanticInkLinks, setSemanticInkLinks] = useState<SemanticInkLink[]>(
    []
  );

  const handlePenPress = useCallback(() => {
    if (tool !== 'pen') {
      setTool('pen');
      setIsPenSettingsOpen(true);
      thinkspaceRef.current?.setPenMode(penMode);
      thinkspaceRef.current?.setPenColor(penColor);
      thinkspaceRef.current?.setPenThickness(penThickness);
    } else {
      setIsPenSettingsOpen((prev) => !prev);
    }
  }, [tool, penMode, penColor, penThickness]);

  const handleHighlighterPress = useCallback(() => {
    if (tool !== 'highlighter') {
      setTool('highlighter');
      setIsPenSettingsOpen(true);
      thinkspaceRef.current?.setPenMode(penMode);
      thinkspaceRef.current?.setPenColor(penColor);
      thinkspaceRef.current?.setPenThickness(penThickness);
    } else {
      setIsPenSettingsOpen((prev) => !prev);
    }
  }, [tool, penMode, penColor, penThickness]);

  const handleSelectPenDrawingMode = useCallback((mode: PenDrawingMode) => {
    setPenMode(mode);
    thinkspaceRef.current?.setPenMode(mode);
  }, []);

  const handleSelectPenColor = useCallback((c: string) => {
    setPenColor(c);
    thinkspaceRef.current?.setPenColor(c);
  }, []);

  const handleSelectPenThickness = useCallback((th: number) => {
    setPenThickness(th);
    thinkspaceRef.current?.setPenThickness(th);
  }, []);

  const handleChangePenFavorites = useCallback((favs: string[]) => {
    setPenFavorites(favs);
    thinkspaceRef.current?.setPenFavorites(favs);
  }, []);

  // ── Active document object for ThinkspaceView (legacy, single-doc) ───────
  // Still used for backward compatibility when workspaceDocs is empty
  const activeDocumentLegacy: WorkspaceDocument | undefined = useMemo(() => {
    if (pdfDoc && workspaceDocs.length === 0) {
      return {
        id: pdfDoc.documentId,
        title: pdfDoc.title || 'PDF Document',
        pageCount: pdfDoc.pageCount,
        uri: pdfUri || undefined,
      };
    }
    return undefined;
  }, [pdfDoc, pdfUri, workspaceDocs]);

  // ── Import PDF via system file picker (multi-document) ────────────────────
  const handleImportPdf = useCallback(async () => {
    try {
      const file = await PdfEngine.pickPdfFile();
      const opened = await PdfEngine.openDocument(file.uri);
      const colorAccent = DOC_COLORS[workspaceDocs.length % DOC_COLORS.length]!;
      const entry: WorkspaceDocumentEntry = {
        id: opened.documentId,
        title:
          opened.title ||
          file.uri.split('/').pop()?.replace('.pdf', '') ||
          'Document',
        pageCount: opened.pageCount,
        uri: file.uri,
        colorAccent,
        addedAt: new Date().toISOString(),
      };
      setWorkspaceDocs((prev) => {
        // Replace if same ID, otherwise append
        const exists = prev.find((d) => d.id === opened.documentId);
        if (exists) return prev;
        return [...prev, entry];
      });
      setActiveDocId(opened.documentId);
      setPdfDoc(opened);
      setPdfUri(file.uri);
    } catch {
      // User cancelled or error handled
    }
  }, [workspaceDocs]);

  const [canUndo, setCanUndo] = useState(false);
  const [canRedo, setCanRedo] = useState(false);

  // ── Undo / Redo Actions ───────────────────────────────────────────────────
  const handleUndo = useCallback(() => {
    thinkspaceRef.current?.undo?.();
  }, []);

  const handleRedo = useCallback(() => {
    thinkspaceRef.current?.redo?.();
  }, []);

  // ── Toggle Accordion Squeeze ───────────────────────────────────────────────
  const handleToggleSqueeze = useCallback(() => {
    setIsSqueezed((prev) => !prev);
  }, []);

  // ── Add Text Box Excerpt ──────────────────────────────────────────────────
  const handleAddTextBox = useCallback(() => {
    const newId = `card-${Date.now()}`;
    const newCard: ExcerptModel = {
      id: newId,
      documentId: pdfDoc?.documentId ?? 'doc-active',
      pageNumber: 1,
      text: 'New thought or synthesis note...',
      color: '#00ADB5',
      x: 80 + Math.random() * 80,
      y: 60 + Math.random() * 60,
      width: 220,
    };
    setExcerpts((prev) => [...prev, newCard]);
  }, [pdfDoc]);

  // ── Tidy Workspace Cards into Masonry Columns ─────────────────────────────
  const handleTidy = useCallback(() => {
    if (excerpts.length === 0) return;
    const startX = 30;
    const startY = 60;
    const colW = 220;
    const gap = 18;
    const updated = excerpts.map((card, idx) => {
      const col = idx % 4;
      const row = Math.floor(idx / 4);
      return {
        ...card,
        x: startX + col * (colW + gap),
        y: startY + row * (120 + gap),
      };
    });
    setExcerpts(updated);
  }, [excerpts]);

  // ── Cycle Canvas Pattern ──────────────────────────────────────────────────
  const handleCyclePattern = useCallback(() => {
    setPattern((prev) => {
      if (prev === 'looseleaf') return 'grid';
      if (prev === 'grid') return 'plain';
      return 'looseleaf';
    });
  }, []);

  // ── Navigation Mode Switching ─────────────────────────────────────────────
  const handleSelectNav = useCallback((mode: NavTabMode) => {
    setActiveNav(mode);
    if (mode === 'drawing') {
      setTool((prev) => (prev === 'select' ? 'pen' : prev));
    } else {
      setIsPenSettingsOpen(false);
      if (mode === 'document') {
        setSplitRatio(0.72);
        setTool('select');
      } else {
        setSplitRatio(0.48);
        setTool('select');
      }
    }
  }, []);

  if (appScreen === 'pdftest') {
    return (
      <>
        <PdfEngineTestScreen />
        <TouchableOpacity
          style={styles.backBtn}
          onPress={() => setAppScreen('workspace')}
          activeOpacity={0.8}
        >
          <Text style={styles.backBtnText}>← Back to Workspace</Text>
        </TouchableOpacity>
      </>
    );
  }

  return (
    <View style={styles.container}>
      <StatusBar
        barStyle="light-content"
        backgroundColor={isImmersive ? '#000000' : '#0F172A'}
        hidden={isImmersive}
        animated={true}
        showHideTransition="slide"
      />

      {/* ── Top System Header matching Screenshot ─────────────────────────── */}
      <Animated.View
        style={[
          styles.topBar,
          {
            transform: [{ translateY: topTranslateY }],
            opacity: chromeOpacity,
          },
        ]}
        pointerEvents={isImmersive ? 'none' : 'auto'}
      >
        <View style={styles.topBarLeft}>
          <TouchableOpacity
            style={styles.iconBtn}
            activeOpacity={0.7}
            onPress={() => handleSelectNav('workspace')}
          >
            <Text style={styles.headerIcon}>⌂</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.iconBtn}
            activeOpacity={0.7}
            onPress={handleImportPdf}
          >
            <Text style={styles.headerIcon}>📄</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.iconBtn, !canUndo && styles.iconBtnDisabled]}
            activeOpacity={0.7}
            onPress={handleUndo}
          >
            <Text
              style={[styles.headerIcon, !canUndo && styles.headerIconDisabled]}
            >
              ↶
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.iconBtn, !canRedo && styles.iconBtnDisabled]}
            activeOpacity={0.7}
            onPress={handleRedo}
          >
            <Text
              style={[styles.headerIcon, !canRedo && styles.headerIconDisabled]}
            >
              ↷
            </Text>
          </TouchableOpacity>
        </View>

        <View style={styles.topBarRight}>
          <TouchableOpacity
            style={styles.iconBtn}
            activeOpacity={0.7}
            onPress={() => {}}
          >
            <Text style={styles.headerIcon}>↑</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.iconBtn}
            activeOpacity={0.7}
            onPress={() => {
              if (workspaceDocs.length > 0 || pdfDoc) {
                thinkspaceRef.current?.openSearch();
              }
            }}
          >
            <Text style={styles.headerIcon}>🔍</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.iconBtn}
            activeOpacity={0.7}
            onPress={() => setAppScreen('pdftest')}
          >
            <Text style={styles.headerIcon}>•••</Text>
          </TouchableOpacity>
        </View>
      </Animated.View>

      {/* ── 100% Native Kotlin Fabric Workspace Engine ────────────────────── */}
      <Animated.View
        style={[
          styles.workspaceWrapper,
          {
            top: contentTop,
            bottom: contentBottom,
          },
        ]}
      >
        <ThinkspaceView
          ref={thinkspaceRef}
          style={styles.nativeWorkspace}
          isImmersive={isImmersive}
          onToggleImmersive={(imm) => setIsImmersive(imm)}
          document={activeDocumentLegacy}
          annotations={annotations}
          isSqueezed={isSqueezed}
          splitRatio={splitRatio}
          activeTool={tool}
          selectedColor={color}
          pattern={pattern}
          strokes={strokes}
          excerpts={excerpts}
          inkLinks={inkLinks}
          penMode={penMode}
          penColor={penColor}
          penThickness={penThickness}
          penFavorites={penFavorites}
          semanticInkLinks={semanticInkLinks}
          onPenStateChange={(state) => {
            if (state.drawingMode) setPenMode(state.drawingMode);
            if (state.color) setPenColor(state.color);
            if (state.thickness) setPenThickness(state.thickness);
            if (state.favoriteColors) setPenFavorites(state.favoriteColors);
            if (typeof state.settingsOpen === 'boolean') {
              setIsPenSettingsOpen(state.settingsOpen);
            }
          }}
          onInkLinkCreate={(link) => {
            setSemanticInkLinks((prev) => [
              ...prev.filter((l) => l.id !== link.id),
              link,
            ]);
          }}
          onInkLinkDelete={(linkId) => {
            setSemanticInkLinks((prev) => prev.filter((l) => l.id !== linkId));
          }}
          onAddStroke={(s) => setStrokes((prev) => [...prev, s])}
          onEraseStroke={(id) =>
            setStrokes((prev) => prev.filter((s) => s.id !== id))
          }
          onExcerptMoveEnd={(id, x, y) => {
            setExcerpts((prev) =>
              prev.map((c) => (c.id === id ? { ...c, x, y } : c))
            );
          }}
          onCardDelete={(id) => {
            setExcerpts((prev) => prev.filter((c) => c.id !== id));
            setInkLinks((prev) => prev.filter((l) => l.sourceExcerptId !== id));
          }}
          onChangeCardColor={(id, col) => {
            setExcerpts((prev) =>
              prev.map((c) => (c.id === id ? { ...c, color: col } : c))
            );
          }}
          onSplitRatioChange={(r) => setSplitRatio(r)}
          onExtractExcerpt={(item: any) => {
            const newId = item.id || `excerpt-${Date.now()}`;
            const newCard: ExcerptModel = {
              id: newId,
              // Multi-doc: use documentId from the event, falling back to active doc
              documentId:
                item.documentId ||
                activeDocId ||
                pdfDoc?.documentId ||
                'doc-active',
              pageNumber: item.pageNumber,
              text: item.text,
              color: item.color,
              x: item.x || 60 + Math.random() * 120,
              y: item.y || 40 + Math.random() * 80,
              width: item.isImage ? 240 : 230,
              isImage: item.isImage,
              imageUrl: item.imageUrl,
              sourceRects: item.sourceRects,
            };
            setExcerpts((prev) => [...prev, newCard]);
            setInkLinks((prev) => [
              ...prev,
              {
                id: `link-${Date.now()}`,
                sourceExcerptId: newId,
                color: item.color,
              },
            ]);
          }}
          onToggleSqueeze={(sq) => {
            setIsSqueezed(sq);
          }}
          onUndoStateChange={(u, r) => {
            setCanUndo(u);
            setCanRedo(r);
          }}
          notebookPages={notebookPages}
          onNotebookPageAdded={(p) => {
            setNotebookPages((prev) =>
              prev.some((x) => x.id === p.id) ? prev : [...prev, p]
            );
          }}
          onNotebookPageMoved={(id, x, y) => {
            setNotebookPages((prev) =>
              prev.map((p) => (p.id === id ? { ...p, x, y } : p))
            );
          }}
          onNotebookPageDeleted={(id) => {
            setNotebookPages((prev) => prev.filter((p) => p.id !== id));
          }}
          workspaceDocuments={
            workspaceDocs.length > 0 ? workspaceDocs : undefined
          }
          activeDocumentId={activeDocId ?? undefined}
          onRequestDocumentSwitch={({ documentId, sourcePageNumber }) => {
            // Switch the active document viewport — the workspace canvas stays untouched
            setActiveDocId(documentId);
            // Update legacy pdfDoc reference for single-doc paths
            const entry = workspaceDocs.find((d) => d.id === documentId);
            if (entry) {
              setPdfUri(entry.uri);
            }
            // The native engine will scroll to sourcePageNumber automatically after switching
            console.log(
              `[ThinkSpace] Document switch requested: ${documentId} → p${sourcePageNumber}`
            );
          }}
        />
      </Animated.View>

      {/* ── Bottom Chrome: Contextual Secondary Toolbar + Bottom Navigation Bar ── */}
      <Animated.View
        style={[
          styles.bottomChromeContainer,
          {
            transform: [{ translateY: bottomTranslateY }],
            opacity: chromeOpacity,
          },
        ]}
        pointerEvents={isImmersive ? 'none' : 'auto'}
      >
        {activeNav === 'drawing' && (
          <PenSettingsPanel
            visible={isPenSettingsOpen}
            onClose={() => setIsPenSettingsOpen(false)}
            drawingMode={penMode}
            onSelectDrawingMode={handleSelectPenDrawingMode}
            selectedColor={penColor}
            onSelectColor={handleSelectPenColor}
            selectedThickness={penThickness}
            onSelectThickness={handleSelectPenThickness}
            favoriteColors={penFavorites}
            onChangeFavorites={handleChangePenFavorites}
          />
        )}

        <View style={styles.secondaryToolbar}>
          {activeNav === 'drawing' && (
            <>
              {/* [Text Select] */}
              <TouchableOpacity
                style={[
                  styles.toolItem,
                  tool === 'select' && styles.toolItemActive,
                ]}
                activeOpacity={0.7}
                onPress={() => {
                  setTool('select');
                  setIsPenSettingsOpen(false);
                }}
              >
                <Text
                  style={[
                    styles.toolIcon,
                    tool === 'select' && styles.toolIconActive,
                  ]}
                >
                  ↖
                </Text>
                <Text
                  style={[
                    styles.toolLabel,
                    tool === 'select' && styles.toolLabelActive,
                  ]}
                >
                  Text Select
                </Text>
              </TouchableOpacity>

              {/* [Pen] */}
              <TouchableOpacity
                style={[
                  styles.toolItem,
                  tool === 'pen' && styles.toolItemActive,
                ]}
                activeOpacity={0.7}
                onPress={handlePenPress}
              >
                <View
                  style={[
                    styles.toolIconBadge,
                    tool === 'pen' && styles.toolIconBadgeActive,
                  ]}
                >
                  <Text
                    style={[
                      styles.toolIcon,
                      tool === 'pen' && styles.toolIconActive,
                    ]}
                  >
                    ✏️
                  </Text>
                </View>
                <Text
                  style={[
                    styles.toolLabel,
                    tool === 'pen' && styles.toolLabelActive,
                  ]}
                >
                  Pen
                </Text>
                {tool === 'pen' && (
                  <View
                    style={[
                      styles.penColorIndicator,
                      { backgroundColor: penColor },
                    ]}
                  />
                )}
              </TouchableOpacity>

              {/* [Highlighter] */}
              <TouchableOpacity
                style={[
                  styles.toolItem,
                  tool === 'highlighter' && styles.toolItemActive,
                ]}
                activeOpacity={0.7}
                onPress={handleHighlighterPress}
              >
                <View
                  style={[
                    styles.toolIconBadge,
                    tool === 'highlighter' && styles.toolIconBadgeActive,
                  ]}
                >
                  <Text
                    style={[
                      styles.toolIcon,
                      tool === 'highlighter' && styles.toolIconActive,
                    ]}
                  >
                    🖍️
                  </Text>
                </View>
                <Text
                  style={[
                    styles.toolLabel,
                    tool === 'highlighter' && styles.toolLabelActive,
                  ]}
                >
                  Highlighter
                </Text>
                {tool === 'highlighter' && (
                  <View
                    style={[
                      styles.penColorIndicator,
                      { backgroundColor: penColor },
                    ]}
                  />
                )}
              </TouchableOpacity>

              {/* [Eraser] */}
              <TouchableOpacity
                style={[
                  styles.toolItem,
                  tool === 'eraser' && styles.toolItemActive,
                ]}
                activeOpacity={0.7}
                onPress={() => {
                  setTool('eraser');
                  setIsPenSettingsOpen(false);
                }}
              >
                <Text
                  style={[
                    styles.toolIcon,
                    tool === 'eraser' && styles.toolIconActive,
                  ]}
                >
                  🧹
                </Text>
                <Text
                  style={[
                    styles.toolLabel,
                    tool === 'eraser' && styles.toolLabelActive,
                  ]}
                >
                  Eraser
                </Text>
              </TouchableOpacity>

              {/* [Lasso] */}
              <TouchableOpacity
                style={[
                  styles.toolItem,
                  tool === 'lasso' && styles.toolItemActive,
                ]}
                activeOpacity={0.7}
                onPress={() => {
                  setTool('lasso');
                  setIsPenSettingsOpen(false);
                }}
              >
                <Text
                  style={[
                    styles.toolIcon,
                    tool === 'lasso' && styles.toolIconActive,
                  ]}
                >
                  ➰
                </Text>
                <Text
                  style={[
                    styles.toolLabel,
                    tool === 'lasso' && styles.toolLabelActive,
                  ]}
                >
                  Lasso
                </Text>
              </TouchableOpacity>
            </>
          )}

          {activeNav === 'document' && (
            <>
              {/* [Lasso] */}
              <TouchableOpacity
                style={[
                  styles.toolItem,
                  tool === 'lasso' && styles.toolItemActive,
                ]}
                activeOpacity={0.7}
                onPress={() => setTool('lasso')}
              >
                <Text
                  style={[
                    styles.toolIcon,
                    tool === 'lasso' && styles.toolIconActive,
                  ]}
                >
                  ➰
                </Text>
                <Text
                  style={[
                    styles.toolLabel,
                    tool === 'lasso' && styles.toolLabelActive,
                  ]}
                >
                  Lasso
                </Text>
              </TouchableOpacity>

              {/* [Documents] */}
              <TouchableOpacity
                style={[
                  styles.toolItem,
                  isDocsModalOpen && styles.toolItemActive,
                ]}
                activeOpacity={0.7}
                onPress={() => setIsDocsModalOpen(true)}
              >
                <Text
                  style={[
                    styles.toolIcon,
                    isDocsModalOpen && styles.toolIconActive,
                  ]}
                >
                  📑
                </Text>
                <Text
                  style={[
                    styles.toolLabel,
                    isDocsModalOpen && styles.toolLabelActive,
                  ]}
                >
                  Documents
                </Text>
              </TouchableOpacity>

              {/* [HiLiteView] */}
              <TouchableOpacity
                style={[styles.toolItem, isSqueezed && styles.toolItemActive]}
                activeOpacity={0.7}
                onPress={handleToggleSqueeze}
              >
                <Text style={[styles.toolIcon, isSqueezed && styles.cyanText]}>
                  ≈
                </Text>
                <Text style={[styles.toolLabel, isSqueezed && styles.cyanText]}>
                  HiLiteView
                </Text>
              </TouchableOpacity>

              {/* [Text Box] */}
              <TouchableOpacity
                style={styles.toolItem}
                activeOpacity={0.7}
                onPress={handleAddTextBox}
              >
                <Text style={[styles.toolIcon, styles.boldA]}>A</Text>
                <Text style={styles.toolLabel}>Text Box</Text>
              </TouchableOpacity>

              {/* [Page Editor] */}
              <TouchableOpacity
                style={[
                  styles.toolItem,
                  isPageEditorOpen && styles.toolItemActive,
                ]}
                activeOpacity={0.7}
                onPress={() => setIsPageEditorOpen(true)}
              >
                <Text
                  style={[
                    styles.toolIcon,
                    isPageEditorOpen && styles.toolIconActive,
                  ]}
                >
                  📄
                </Text>
                <Text
                  style={[
                    styles.toolLabel,
                    isPageEditorOpen && styles.toolLabelActive,
                  ]}
                >
                  Page Editor
                </Text>
              </TouchableOpacity>
            </>
          )}

          {activeNav === 'workspace' && (
            <>
              {/* [Workspaces] */}
              <TouchableOpacity
                style={[
                  styles.toolItem,
                  isWorkspacesModalOpen && styles.toolItemActive,
                ]}
                activeOpacity={0.7}
                onPress={() => setIsWorkspacesModalOpen(true)}
              >
                <Text
                  style={[
                    styles.toolIcon,
                    isWorkspacesModalOpen && styles.toolIconActive,
                  ]}
                >
                  ⊞
                </Text>
                <Text
                  style={[
                    styles.toolLabel,
                    isWorkspacesModalOpen && styles.toolLabelActive,
                  ]}
                >
                  Workspaces
                </Text>
              </TouchableOpacity>

              {/* [Text Box] */}
              <TouchableOpacity
                style={styles.toolItem}
                activeOpacity={0.7}
                onPress={handleAddTextBox}
              >
                <Text style={[styles.toolIcon, styles.boldA]}>A</Text>
                <Text style={styles.toolLabel}>Text Box</Text>
              </TouchableOpacity>

              {/* [Zoom Out] */}
              <TouchableOpacity
                style={styles.toolItem}
                activeOpacity={0.7}
                onPress={() => {
                  thinkspaceRef.current?.zoomToFit?.();
                }}
              >
                <Text style={styles.toolIcon}>⊝</Text>
                <Text style={styles.toolLabel}>Zoom Out</Text>
              </TouchableOpacity>
            </>
          )}
        </View>

        {/* ── Bottom Navigation Bar matching Screenshot ─────────────────────── */}
        <View style={styles.bottomNav}>
          <TouchableOpacity
            style={[
              styles.navTab,
              activeNav === 'drawing' && styles.navTabActive,
            ]}
            activeOpacity={0.8}
            onPress={() => handleSelectNav('drawing')}
          >
            <Text style={styles.navIcon}>✏️</Text>
            <Text
              style={[
                styles.navLabel,
                activeNav === 'drawing' && styles.navLabelActive,
              ]}
            >
              Drawing
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[
              styles.navTab,
              activeNav === 'document' && styles.navTabActive,
            ]}
            activeOpacity={0.8}
            onPress={() => handleSelectNav('document')}
          >
            <Text style={styles.navIcon}>📄</Text>
            <Text
              style={[
                styles.navLabel,
                activeNav === 'document' && styles.navLabelActive,
              ]}
            >
              Document
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[
              styles.navTab,
              activeNav === 'workspace' && styles.navTabActive,
            ]}
            activeOpacity={0.8}
            onPress={() => handleSelectNav('workspace')}
          >
            <Text
              style={[
                styles.navIconHex,
                activeNav === 'workspace' && styles.navIconActive,
              ]}
            >
              ⬡
            </Text>
            <Text
              style={[
                styles.navLabel,
                activeNav === 'workspace' && styles.navLabelActive,
              ]}
            >
              Workspace
            </Text>
          </TouchableOpacity>
        </View>

        {/* Floating Active Tool Indicator matching Screenshot 1 */}
        {activeNav === 'drawing' && (
          <View style={styles.bottomPillRow}>
            <View style={styles.floatingActiveToolPill}>
              <Text style={styles.floatingActiveToolIcon}>
                {tool === 'highlighter' ? '🖍️' : tool === 'pen' ? '✏️' : '↖'}
              </Text>
              <Text style={styles.floatingActiveToolLabel}>
                {tool === 'highlighter'
                  ? 'Highlighter'
                  : tool === 'pen'
                    ? 'Pen'
                    : tool === 'eraser'
                      ? 'Eraser'
                      : tool === 'lasso'
                        ? 'Lasso'
                        : 'Text Select'}
              </Text>
            </View>
          </View>
        )}
      </Animated.View>

      {/* ── Documents Switcher & Manager Modal ─────────────────────────────── */}
      <Modal
        visible={isDocsModalOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setIsDocsModalOpen(false)}
      >
        <TouchableOpacity
          style={styles.modalBackdrop}
          activeOpacity={1}
          onPress={() => setIsDocsModalOpen(false)}
        >
          <View style={styles.addMenuCard}>
            <View style={styles.menuHeader}>
              <Text style={styles.menuTitle}>Documents in Workspace</Text>
              <TouchableOpacity onPress={() => setIsDocsModalOpen(false)}>
                <Text style={styles.closeBtn}>✕</Text>
              </TouchableOpacity>
            </View>

            {workspaceDocs.length === 0 ? (
              <View style={styles.emptyDocsContainer}>
                <Text style={styles.emptyDocsText}>
                  No documents in this workspace yet.
                </Text>
              </View>
            ) : (
              workspaceDocs.map((doc) => {
                const isActive = doc.id === activeDocId;
                return (
                  <TouchableOpacity
                    key={doc.id}
                    style={[styles.docItem, isActive && styles.docItemActive]}
                    activeOpacity={0.7}
                    onPress={() => {
                      setActiveDocId(doc.id);
                      setPdfUri(doc.uri);
                      setIsDocsModalOpen(false);
                    }}
                  >
                    <View style={styles.menuItemRow}>
                      <View
                        style={[
                          styles.docDot,
                          { backgroundColor: doc.colorAccent || '#00ADB5' },
                        ]}
                      />
                      <View>
                        <Text style={styles.docTitleText}>{doc.title}</Text>
                        <Text style={styles.docPagesText}>
                          {doc.pageCount} pages
                        </Text>
                      </View>
                    </View>
                    {isActive && (
                      <Text style={styles.activeBadge}>✓ Active</Text>
                    )}
                  </TouchableOpacity>
                );
              })
            )}

            <TouchableOpacity
              style={styles.menuItemPrimary}
              activeOpacity={0.8}
              onPress={() => {
                setIsDocsModalOpen(false);
                handleImportPdf();
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>➕</Text>
                <Text style={styles.menuItemTextPrimary}>
                  Import PDF Document
                </Text>
              </View>
              <Text style={styles.arrowIcon}>›</Text>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </Modal>

      {/* ── Workspaces Manager Modal ───────────────────────────────────────── */}
      <Modal
        visible={isWorkspacesModalOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setIsWorkspacesModalOpen(false)}
      >
        <TouchableOpacity
          style={styles.modalBackdrop}
          activeOpacity={1}
          onPress={() => setIsWorkspacesModalOpen(false)}
        >
          <View style={styles.addMenuCard}>
            <View style={styles.menuHeader}>
              <Text style={styles.menuTitle}>Workspaces</Text>
              <TouchableOpacity onPress={() => setIsWorkspacesModalOpen(false)}>
                <Text style={styles.closeBtn}>✕</Text>
              </TouchableOpacity>
            </View>

            <View style={[styles.docItem, styles.docItemActive]}>
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>⊞</Text>
                <View>
                  <Text style={styles.docTitleText}>
                    Main Project Workspace
                  </Text>
                  <Text style={styles.docPagesText}>
                    {workspaceDocs.length} Documents · {excerpts.length}{' '}
                    Excerpts
                  </Text>
                </View>
              </View>
              <Text style={styles.activeBadge}>✓ Active</Text>
            </View>

            <TouchableOpacity
              style={[styles.menuItem, styles.workspaceActionItem]}
              activeOpacity={0.8}
              onPress={() => {
                setIsWorkspacesModalOpen(false);
                thinkspaceRef.current?.zoomToFit?.();
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>⊝</Text>
                <Text style={styles.menuItemText}>
                  Zoom to Fit All Elements
                </Text>
              </View>
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.menuItem, styles.workspaceActionItemSmall]}
              activeOpacity={0.8}
              onPress={() => {
                setIsWorkspacesModalOpen(false);
                handleTidy();
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>✨</Text>
                <Text style={styles.menuItemText}>Tidy Workspace Canvas</Text>
              </View>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </Modal>

      {/* ── Page Editor Modal ──────────────────────────────────────────────── */}
      <Modal
        visible={isPageEditorOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setIsPageEditorOpen(false)}
      >
        <TouchableOpacity
          style={styles.modalBackdrop}
          activeOpacity={1}
          onPress={() => setIsPageEditorOpen(false)}
        >
          <View style={styles.addMenuCard}>
            <View style={styles.menuHeader}>
              <Text style={styles.menuTitle}>Page Editor</Text>
              <TouchableOpacity onPress={() => setIsPageEditorOpen(false)}>
                <Text style={styles.closeBtn}>✕</Text>
              </TouchableOpacity>
            </View>

            <TouchableOpacity
              style={styles.menuItemPrimary}
              activeOpacity={0.8}
              onPress={() => {
                setIsPageEditorOpen(false);
                setIsStylePickerOpen(true);
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>📄</Text>
                <Text style={styles.menuItemTextPrimary}>
                  Add Notebook Page
                </Text>
              </View>
              <Text style={styles.arrowIcon}>›</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.menuItem}
              activeOpacity={0.8}
              onPress={() => {
                setIsPageEditorOpen(false);
                handleToggleSqueeze();
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>≈</Text>
                <Text style={styles.menuItemText}>
                  {isSqueezed
                    ? 'Exit HiLiteView (Expand All)'
                    : 'Toggle HiLiteView (Compress)'}
                </Text>
              </View>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.menuItem}
              activeOpacity={0.8}
              onPress={() => {
                setIsPageEditorOpen(false);
                handleCyclePattern();
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>⠿</Text>
                <Text style={styles.menuItemText}>
                  Change Canvas Pattern ({pattern})
                </Text>
              </View>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </Modal>

      {/* ── Add to Workspace Modal (Step 2 of UX Reference) ─────────────── */}
      <Modal
        visible={isAddMenuOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setIsAddMenuOpen(false)}
      >
        <TouchableOpacity
          style={styles.modalBackdrop}
          activeOpacity={1}
          onPress={() => setIsAddMenuOpen(false)}
        >
          <View style={styles.addMenuCard}>
            <View style={styles.menuHeader}>
              <Text style={styles.menuTitle}>Add to Workspace</Text>
              <TouchableOpacity onPress={() => setIsAddMenuOpen(false)}>
                <Text style={styles.closeBtn}>✕</Text>
              </TouchableOpacity>
            </View>

            <TouchableOpacity
              style={styles.menuItemPrimary}
              activeOpacity={0.8}
              onPress={() => {
                setIsAddMenuOpen(false);
                setIsStylePickerOpen(true);
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>📄</Text>
                <Text style={styles.menuItemTextPrimary}>Notebook Page</Text>
              </View>
              <Text style={styles.arrowIcon}>›</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.menuItem}
              activeOpacity={0.8}
              onPress={() => {
                setIsAddMenuOpen(false);
                handleAddTextBox();
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>📑</Text>
                <Text style={styles.menuItemText}>PDF Excerpt Card</Text>
              </View>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.menuItem}
              activeOpacity={0.8}
              onPress={() => {
                setIsAddMenuOpen(false);
                handleAddTextBox();
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>🔤</Text>
                <Text style={styles.menuItemText}>Text Box</Text>
              </View>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.menuItem}
              activeOpacity={0.8}
              onPress={() => {
                setIsAddMenuOpen(false);
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>🖼️</Text>
                <Text style={styles.menuItemText}>Image</Text>
              </View>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.menuItem}
              activeOpacity={0.8}
              onPress={() => {
                setIsAddMenuOpen(false);
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>🔷</Text>
                <Text style={styles.menuItemText}>Shape</Text>
              </View>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.menuItem}
              activeOpacity={0.8}
              onPress={() => {
                setIsAddMenuOpen(false);
                handleAddTextBox();
              }}
            >
              <View style={styles.menuItemRow}>
                <Text style={styles.menuItemIcon}>🏷️</Text>
                <Text style={styles.menuItemText}>Sticky Note</Text>
              </View>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </Modal>

      {/* ── Choose Page Style Modal (Step 4 of UX Reference) ─────────────── */}
      <Modal
        visible={isStylePickerOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setIsStylePickerOpen(false)}
      >
        <TouchableOpacity
          style={styles.modalBackdrop}
          activeOpacity={1}
          onPress={() => setIsStylePickerOpen(false)}
        >
          <View style={styles.stylePickerCard}>
            <View style={styles.menuHeader}>
              <Text style={styles.menuTitle}>Page Style</Text>
              <TouchableOpacity onPress={() => setIsStylePickerOpen(false)}>
                <Text style={styles.closeBtn}>✕</Text>
              </TouchableOpacity>
            </View>

            <View style={styles.styleGrid}>
              {[
                { id: 'blank', label: 'Blank', icon: '◻️' },
                { id: 'ruled', label: 'Ruled', icon: '☰' },
                { id: 'grid', label: 'Grid', icon: '▦' },
                { id: 'dotted', label: 'Dotted', icon: '⁖' },
                { id: 'sketch', label: 'Sketch', icon: '▨' },
                { id: 'cornell', label: 'Cornell', icon: '◫' },
                { id: 'squared', label: 'Squared', icon: '⊞' },
                { id: 'custom', label: 'Custom', icon: '＋' },
              ].map((item) => (
                <TouchableOpacity
                  key={item.id}
                  style={[
                    styles.styleOptionCard,
                    item.id === 'ruled' && styles.styleOptionSelected,
                  ]}
                  activeOpacity={0.7}
                  onPress={() => {
                    setIsStylePickerOpen(false);
                    thinkspaceRef.current?.addNotebookPage?.(
                      item.id as NotebookPageStyle,
                      `${item.label} Notes`
                    );
                  }}
                >
                  <View style={styles.styleThumbnail}>
                    <Text style={styles.styleThumbnailIcon}>{item.icon}</Text>
                  </View>
                  <Text style={styles.styleOptionLabel}>{item.label}</Text>
                </TouchableOpacity>
              ))}
            </View>
          </View>
        </TouchableOpacity>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0A101D',
  },
  topBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    paddingTop: StatusBar.currentHeight ?? 24,
    height: 48 + (StatusBar.currentHeight ?? 24),
    backgroundColor: '#0F172A',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
    zIndex: 100, // Make sure it sits above the workspace
  },
  topBarLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 22,
  },
  topBarRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 22,
  },
  iconBtn: {
    padding: 6,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerIcon: {
    color: '#E2E8F0',
    fontSize: 22,
    fontWeight: '500',
  },
  iconBtnDisabled: {
    opacity: 0.35,
  },
  headerIconDisabled: {
    color: '#64748B',
  },
  workspaceWrapper: {
    position: 'absolute',
    left: 0,
    right: 0,
    backgroundColor: '#0A101D',
  },
  bottomChromeContainer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    zIndex: 100,
  },
  nativeWorkspace: {
    flex: 1,
  },
  secondaryToolbar: {
    height: 58,
    backgroundColor: '#0B132B',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
    paddingHorizontal: 8,
    borderTopWidth: 1,
    borderTopColor: '#1E293B',
  },
  toolItem: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 4,
    paddingHorizontal: 8,
    minWidth: 54,
  },
  toolIcon: {
    color: '#94A3B8',
    fontSize: 18,
    marginBottom: 4,
  },
  boldA: {
    fontWeight: '900',
    fontSize: 17,
    color: '#CBD5E1',
  },
  goldSparkle: {
    color: '#F59E0B',
    fontSize: 17,
  },
  cyanText: {
    color: '#00ADB5',
    fontWeight: '700',
  },
  toolLabel: {
    color: '#94A3B8',
    fontSize: 10.5,
    fontWeight: '500',
  },
  toolItemActive: {
    backgroundColor: 'rgba(0, 173, 181, 0.16)',
    borderRadius: 8,
  },
  toolIconActive: {
    color: '#00ADB5',
  },
  toolLabelActive: {
    color: '#00ADB5',
    fontWeight: '700',
  },
  toolIconBadge: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 2,
  },
  toolIconBadgeActive: {
    backgroundColor: '#6B7FA3',
  },
  toolIconBadgeTextActive: {
    color: '#FFFFFF',
  },
  bottomPillRow: {
    paddingHorizontal: 16,
    paddingTop: 4,
    paddingBottom: 6,
    backgroundColor: '#080E1A',
    flexDirection: 'row',
  },
  floatingActiveToolPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: '#6B7FA3',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  floatingActiveToolIcon: {
    fontSize: 12,
  },
  floatingActiveToolLabel: {
    fontSize: 12,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  penColorIndicator: {
    width: 14,
    height: 3,
    borderRadius: 1.5,
    marginTop: 2,
  },
  docItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 10,
    backgroundColor: '#1E293B',
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#334155',
  },
  docItemActive: {
    borderColor: '#00ADB5',
    backgroundColor: 'rgba(0, 173, 181, 0.12)',
  },
  docDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  docTitleText: {
    color: '#F8FAFC',
    fontSize: 13.5,
    fontWeight: '600',
  },
  docPagesText: {
    color: '#94A3B8',
    fontSize: 11.5,
    marginTop: 2,
  },
  activeBadge: {
    color: '#00ADB5',
    fontSize: 12.5,
    fontWeight: '700',
  },
  emptyDocsContainer: {
    paddingVertical: 16,
    alignItems: 'center',
  },
  emptyDocsText: {
    color: '#94A3B8',
    fontSize: 13,
  },
  workspaceActionItem: {
    marginTop: 8,
  },
  workspaceActionItemSmall: {
    marginTop: 4,
  },
  bottomNav: {
    height: 54,
    backgroundColor: '#080E1A',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
    paddingHorizontal: 14,
    borderTopWidth: 1,
    borderTopColor: '#161F30',
  },
  navTab: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 22,
  },
  navTabActive: {
    backgroundColor: '#00ADB5',
    paddingHorizontal: 18,
  },
  navIcon: {
    fontSize: 15,
  },
  navIconHex: {
    fontSize: 16,
    color: '#94A3B8',
  },
  navIconActive: {
    color: '#FFFFFF',
    fontWeight: '700',
  },
  navLabel: {
    fontSize: 13,
    fontWeight: '600',
    color: '#94A3B8',
  },
  navLabelActive: {
    color: '#FFFFFF',
    fontWeight: '700',
  },
  backBtn: {
    position: 'absolute',
    top: 50,
    left: 16,
    backgroundColor: 'rgba(15, 23, 42, 0.95)',
    borderWidth: 1,
    borderColor: '#334155',
    borderRadius: 20,
    paddingHorizontal: 14,
    paddingVertical: 8,
    zIndex: 999,
  },
  backBtnText: {
    color: '#00ADB5',
    fontSize: 13,
    fontWeight: '700',
  },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.65)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  addMenuCard: {
    width: 290,
    backgroundColor: '#0F172A',
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#334155',
    padding: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.5,
    shadowRadius: 16,
    elevation: 20,
  },
  menuHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 14,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
  },
  menuTitle: {
    color: '#F8FAFC',
    fontSize: 15,
    fontWeight: '700',
  },
  closeBtn: {
    color: '#94A3B8',
    fontSize: 16,
    padding: 4,
  },
  menuItemPrimary: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#1E293B',
    borderRadius: 10,
    paddingVertical: 11,
    paddingHorizontal: 12,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#3B82F6',
  },
  menuItemRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  menuItemIcon: {
    fontSize: 16,
  },
  menuItemTextPrimary: {
    color: '#60A5FA',
    fontSize: 13.5,
    fontWeight: '600',
  },
  arrowIcon: {
    color: '#60A5FA',
    fontSize: 18,
    fontWeight: '700',
  },
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 9,
    paddingHorizontal: 12,
    borderRadius: 8,
    marginBottom: 4,
  },
  menuItemText: {
    color: '#CBD5E1',
    fontSize: 13,
  },
  stylePickerCard: {
    width: 320,
    backgroundColor: '#0F172A',
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#334155',
    padding: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.5,
    shadowRadius: 16,
    elevation: 20,
  },
  styleGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    justifyContent: 'space-between',
  },
  styleOptionCard: {
    width: '22%',
    alignItems: 'center',
    paddingVertical: 8,
    paddingHorizontal: 4,
    borderRadius: 10,
    borderWidth: 1.5,
    borderColor: 'transparent',
    backgroundColor: '#1E293B',
  },
  styleOptionSelected: {
    borderColor: '#3B82F6',
    backgroundColor: '#1E3A5F',
  },
  styleThumbnail: {
    width: 44,
    height: 44,
    borderRadius: 6,
    backgroundColor: '#FFFEF0',
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 6,
    borderWidth: 1,
    borderColor: '#D1D5DB',
  },
  styleThumbnailIcon: {
    fontSize: 20,
    color: '#334155',
  },
  styleOptionLabel: {
    color: '#CBD5E1',
    fontSize: 11,
    fontWeight: '600',
    textAlign: 'center',
  },
});
