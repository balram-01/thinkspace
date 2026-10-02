import { useState, useCallback, useMemo, useRef } from 'react';
import PdfEngineTestScreen from './PdfEngineTestScreen';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  StatusBar,
  Modal,
} from 'react-native';
import {
  ThinkspaceView,
  PdfEngine,
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
  const [activeNav, setActiveNav] = useState<NavTabMode>('workspace');

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
  const thinkspaceRef = useRef<any>(null);

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
      setTool('pen');
    } else if (mode === 'document') {
      setSplitRatio(0.8);
      setTool('select');
    } else {
      setSplitRatio(0.48);
      setTool('select');
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
        backgroundColor="#0F172A"
        translucent={false}
      />

      {/* ── Top System Header matching Screenshot ─────────────────────────── */}
      <View style={styles.topBar}>
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
      </View>

      {/* ── 100% Native Kotlin Fabric Workspace Engine ────────────────────── */}
      <View style={styles.workspaceWrapper}>
        <ThinkspaceView
          ref={thinkspaceRef}
          style={styles.nativeWorkspace}
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
      </View>

      {/* ── Secondary Action Toolbar matching Screenshot ──────────────────── */}
      <View style={styles.secondaryToolbar}>
        <TouchableOpacity
          style={styles.toolItem}
          activeOpacity={0.7}
          onPress={() => {}}
        >
          <Text style={styles.toolIcon}>⊞</Text>
          <Text style={styles.toolLabel}>Workspaces</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.toolItem}
          activeOpacity={0.7}
          onPress={handleAddTextBox}
        >
          <Text style={[styles.toolIcon, styles.boldA]}>A</Text>
          <Text style={styles.toolLabel}>Text Box</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.toolItem}
          activeOpacity={0.7}
          onPress={handleTidy}
        >
          <Text style={[styles.toolIcon, styles.goldSparkle]}>✨</Text>
          <Text style={styles.toolLabel}>Tidy</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.toolItem}
          activeOpacity={0.7}
          onPress={handleToggleSqueeze}
        >
          <Text style={[styles.toolIcon, isSqueezed && styles.cyanText]}>
            ≈
          </Text>
          <Text style={[styles.toolLabel, isSqueezed && styles.cyanText]}>
            Squeeze
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.toolItem}
          activeOpacity={0.7}
          onPress={handleCyclePattern}
        >
          <Text style={styles.toolIcon}>⠿</Text>
          <Text style={styles.toolLabel}>Pattern</Text>
        </TouchableOpacity>

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

        {/* 
          ── Add to Workspace / Notebook Page (Hidden for now) ──────────────────
          Hidden for now as requested. To re-enable the "Add to Workspace / Add Page"
          option in the bottom toolbar later, simply uncomment the TouchableOpacity below.
        */}
        {/*
        <TouchableOpacity
          style={styles.toolItem}
          activeOpacity={0.7}
          onPress={() => setIsAddMenuOpen(true)}
        >
          <Text style={[styles.toolIcon, styles.goldSparkle]}>📋</Text>
          <Text style={[styles.toolLabel, styles.cyanText]}>Add Page</Text>
        </TouchableOpacity>
        */}
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
    flex: 1,
    backgroundColor: '#0A101D',
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
