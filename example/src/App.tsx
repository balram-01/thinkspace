import { useState, useCallback, useMemo, useRef } from 'react';
import PdfEngineTestScreen from './PdfEngineTestScreen';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  StatusBar,
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
} from 'thinkspace';

const INITIAL_ANNOTATIONS: DocumentAnnotation[] = [];
const INITIAL_EXCERPTS: ExcerptModel[] = [];
const INITIAL_LINKS: InkLink[] = [];

const RESUME_DOCUMENT: WorkspaceDocument = {
  id: 'kshitija-resume',
  title: 'Kshitija_Resume (34)',
  pageCount: 1,
  sections: [
    {
      id: 'sec-header',
      pageNumber: 1,
      heading: 'Kshitija Sanjay Shejal',
      paragraphs: [
        '8830484483 | kshitija.shejal22@vit.edu | LinkedIn | GitHub',
      ],
    },
    {
      id: 'sec-summary',
      pageNumber: 1,
      heading: 'PROFESSIONAL SUMMARY',
      paragraphs: [
        'Computer Science undergraduate specializing in Artificial Intelligence with practical experience in Data Science, Data Analysis, and Machine Learning. Proficient in building machine learning, deep learning, and Retrieval-Augmented Generation (RAG) systems, supported by a strong foundation in statistical analysis, exploratory data analysis (EDA), and Python/SQL data engineering. Published research applying data-driven and generative AI methods to real-world problems.',
      ],
    },
    {
      id: 'sec-skills',
      pageNumber: 1,
      heading: 'TECHNICAL SKILLS',
      paragraphs: [
        'Machine Learning & AI: Machine Learning, Deep Learning (DL), Natural Language Processing (NLP), Retrieval-Augmented Generation (RAG), Predictive Modeling\n\nGenAI / LLM Tools: FastAPI, ChromaDB, Gemini API, Streamlit, Semantic Search, Prompt-based Retrieval\n\nProgramming Languages: Python, SQL, MySQL\n\nData Analysis Libraries: Pandas, NumPy, Matplotlib, Seaborn, Scikit-learn\n\nData Analytics: Data Cleaning, Data Transformation, Data Wrangling, ETL, Exploratory Data Analysis (EDA), A/B Testing, Statistical Analysis\n\nVisualization & BI Tools: Excel, Power BI, Dashboards, Data Storytelling\n\nOther: Database Management Systems (DBMS), Git, Google Cloud Platform (GCP), Quality Assurance (QA)',
      ],
    },
    {
      id: 'sec-experience',
      pageNumber: 1,
      heading: 'PROFESSIONAL EXPERIENCE',
      paragraphs: [
        'Data Analysis Intern                                                   Oct 2024 - Nov 2024\nAICTE & VOIS                                                   Pune, Maharashtra, India\n- Executed comprehensive data cleaning, preprocessing, and Exploratory Data Analysis (EDA) on real-world datasets utilizing Python and SQL.\n- Analyzed complex business datasets to identify key trends and generate actionable insights, directly supporting stakeholder decision-making.',
      ],
    },
  ],
};

type NavTabMode = 'drawing' | 'document' | 'workspace';

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
  const [activeNav, setActiveNav] = useState<NavTabMode>('workspace');

  // PDF Engine Document State
  const [pdfDoc, setPdfDoc] = useState<PdfDocumentInfo | null>(null);
  const [pdfUri, setPdfUri] = useState<string | null>(null);

  // Thinkspace Native View Reference
  const thinkspaceRef = useRef<any>(null);

  // Active document object for ThinkspaceView
  const activeDocument: WorkspaceDocument = useMemo(() => {
    if (pdfDoc) {
      return {
        id: pdfDoc.documentId,
        title: pdfDoc.title || 'PDF Document',
        pageCount: pdfDoc.pageCount,
        uri: pdfUri || undefined,
      };
    }
    return RESUME_DOCUMENT;
  }, [pdfDoc, pdfUri]);

  // ── Import PDF via system file picker ─────────────────────────────────────
  const handleImportPdf = useCallback(async () => {
    try {
      const file = await PdfEngine.pickPdfFile();
      if (pdfDoc) {
        try {
          await PdfEngine.closeDocument(pdfDoc.documentId);
        } catch {}
      }
      const opened = await PdfEngine.openDocument(file.uri);
      setPdfDoc(opened);
      setPdfUri(file.uri);
    } catch {
      // User cancelled or error handled
    }
  }, [pdfDoc]);

  // ── Undo Action ───────────────────────────────────────────────────────────
  const handleUndo = useCallback(() => {
    if (strokes.length > 0) {
      setStrokes((prev) => prev.slice(0, prev.length - 1));
    } else if (excerpts.length > 0) {
      const last = excerpts[excerpts.length - 1];
      setExcerpts((prev) => prev.slice(0, prev.length - 1));
      if (last) {
        setInkLinks((prev) =>
          prev.filter((l) => l.sourceExcerptId !== last.id)
        );
      }
    }
  }, [strokes.length, excerpts]);

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
            style={styles.iconBtn}
            activeOpacity={0.7}
            onPress={handleUndo}
          >
            <Text style={styles.headerIcon}>↶</Text>
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
            onPress={() => thinkspaceRef.current?.openSearch()}
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
          document={activeDocument}
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
              documentId: pdfDoc?.documentId ?? 'doc-active',
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
            // Zoom out canvas view
            setTool('select');
          }}
        >
          <Text style={styles.toolIcon}>⊝</Text>
          <Text style={styles.toolLabel}>Zoom Out</Text>
        </TouchableOpacity>
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
});
