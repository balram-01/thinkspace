import { useState, useCallback, useMemo } from 'react';
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

const INITIAL_ANNOTATIONS: DocumentAnnotation[] = [
  {
    id: 'ann-1',
    sectionId: 'sec-preface',
    paragraphIndex: 0,
    pageNumber: 7,
    color: '#00ADB5',
    text: 'Prison is not a pleasant place to live in even for a short period, much less for long years...',
    type: 'highlight',
  },
];

const INITIAL_EXCERPTS: ExcerptModel[] = [
  {
    id: 'card-1',
    documentId: 'doc-discovery-of-india',
    pageNumber: 23,
    text: 'that essay. What was my philosophy of life? I did not know. Some years earlier I would not have been so hesitant. There was a definite-ness...',
    color: '#3B82F6',
    x: 60,
    y: 35,
    width: 225,
  },
  {
    id: 'card-2',
    documentId: 'doc-discovery-of-india',
    pageNumber: 22,
    text: 'successively different ages and periods and had for companions men and women who had lived long ago. I had leisure in jail there was no sens...',
    color: '#00ADB5',
    x: 320,
    y: 55,
    width: 235,
  },
  {
    id: 'card-3',
    documentId: 'doc-discovery-of-india',
    pageNumber: 22,
    text: 'of life have always a way out of it, if they so choose. That is always in our power to achieve...',
    color: '#F59E0B',
    x: 75,
    y: 195,
    width: 225,
  },
];

const INITIAL_LINKS: InkLink[] = [
  { id: 'link-1', sourceExcerptId: 'card-1', color: '#3B82F6' },
  { id: 'link-2', sourceExcerptId: 'card-2', color: '#00ADB5' },
  { id: 'link-3', sourceExcerptId: 'card-3', color: '#F59E0B' },
];

const DEFAULT_DOC: WorkspaceDocument = {
  id: 'doc-discovery-of-india',
  title: 'The Discovery of India',
  pageCount: 38,
  sections: [
    {
      id: 'sec-preface',
      pageNumber: 7,
      heading: 'PREFACE',
      paragraphs: [
        'Prison is not a pleasant place to live in even for a short period, much less for long years. But it brings a certain detachment and perspective.',
        'In Ahmednagar Fort, where we were cut off from the outside world, my mind wandered over India and its long story. What was this India?',
      ],
    },
    {
      id: 'sec-philosophy',
      pageNumber: 22,
      heading: 'PHILOSOPHY OF LIFE',
      paragraphs: [
        'What was my philosophy of life? I did not know. Some years earlier I would not have been so hesitant. There was a definiteness about my thinking then.',
        'Man has leisure in jail; there is no sense of hurry. In the long hours of evening, history unfolds like an endless tapestry.',
      ],
    },
    {
      id: 'sec-past',
      pageNumber: 23,
      heading: 'THE BURDEN OF THE PAST',
      paragraphs: [
        'The past oppresses me; it surrounds me with its invisible bonds. Yet without that past, what are we?',
        'The living present is an inheritance of five thousand years of continuous human experience. It shapes our impulses and thought patterns.',
      ],
    },
  ],
};

export default function App() {
  const [appScreen, setAppScreen] = useState<'workspace' | 'pdftest'>(
    'workspace'
  );
  const [tool] = useState<WorkspaceTool>('select');
  const [color] = useState('#00ADB5');
  const [pattern] = useState<WorkspacePattern>('looseleaf');
  const [isSqueezed, setIsSqueezed] = useState(false);
  const [splitRatio, setSplitRatio] = useState(0.5);
  const [strokes, setStrokes] = useState<InkStroke[]>([]);
  const [excerpts, setExcerpts] = useState<ExcerptModel[]>(INITIAL_EXCERPTS);
  const [inkLinks, setInkLinks] = useState<InkLink[]>(INITIAL_LINKS);
  const [annotations] = useState<DocumentAnnotation[]>(INITIAL_ANNOTATIONS);

  // PDF Engine Document State
  const [pdfDoc, setPdfDoc] = useState<PdfDocumentInfo | null>(null);
  const [pdfUri, setPdfUri] = useState<string | null>(null);

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
    return DEFAULT_DOC;
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

  const handleToggleSqueeze = useCallback(() => {
    setIsSqueezed((prev) => !prev);
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

  const docTitle =
    pdfDoc?.title || (pdfDoc ? 'PDF Document' : 'The Discovery of India');

  return (
    <View style={styles.container}>
      <StatusBar
        barStyle="light-content"
        backgroundColor="#0F172A"
        translucent={false}
      />

      {/* Sleek, Minimalist LiquidText Top Bar */}
      <View style={styles.topBar}>
        <View style={styles.topBarLeft}>
          <Text style={styles.logoText}>LiquidText</Text>
          <Text style={styles.docTitleText} numberOfLines={1}>
            {docTitle}
          </Text>
        </View>

        <View style={styles.topBarRight}>
          {/* Accordion Squeeze Button */}
          <TouchableOpacity
            style={[
              styles.btn,
              isSqueezed ? styles.btnActive : styles.btnSecondary,
            ]}
            activeOpacity={0.8}
            onPress={handleToggleSqueeze}
          >
            <Text style={[styles.btnText, isSqueezed && styles.btnTextActive]}>
              🪗 Squeeze
            </Text>
          </TouchableOpacity>

          {/* Import / Open PDF Button */}
          <TouchableOpacity
            style={[styles.btn, styles.btnPrimary]}
            activeOpacity={0.8}
            onPress={handleImportPdf}
          >
            <Text style={styles.btnPrimaryText}>📂 Open PDF</Text>
          </TouchableOpacity>

          {/* Diagnostic Test Button */}
          <TouchableOpacity
            style={styles.testIconBtn}
            activeOpacity={0.8}
            onPress={() => setAppScreen('pdftest')}
          >
            <Text style={styles.testIconText}>🔬</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* 100% Native Kotlin Fabric Workspace Engine */}
      <View style={styles.workspaceWrapper}>
        <ThinkspaceView
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
              documentId: pdfDoc?.documentId ?? 'doc-discovery-of-india',
              pageNumber: item.pageNumber,
              text: item.text,
              color: item.color,
              x: item.x || 60 + Math.random() * 120,
              y: item.y || 40 + Math.random() * 80,
              width: item.isImage ? 240 : 230,
              isImage: item.isImage,
              imageUrl: item.imageUrl,
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
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0B1120',
  },
  topBar: {
    paddingTop: StatusBar.currentHeight ?? 24,
    height: 48 + (StatusBar.currentHeight ?? 24),
    backgroundColor: '#0F172A',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
  },
  topBarLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    flex: 1,
  },
  logoText: {
    color: '#00ADB5',
    fontSize: 16,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  docTitleText: {
    color: '#94A3B8',
    fontSize: 13,
    fontWeight: '500',
    maxWidth: 160,
  },
  topBarRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  btn: {
    paddingHorizontal: 11,
    paddingVertical: 6,
    borderRadius: 7,
    justifyContent: 'center',
    alignItems: 'center',
  },
  btnSecondary: {
    backgroundColor: '#1E293B',
    borderWidth: 1,
    borderColor: '#334155',
  },
  btnActive: {
    backgroundColor: '#00ADB5',
  },
  btnText: {
    color: '#94A3B8',
    fontSize: 12,
    fontWeight: '600',
  },
  btnTextActive: {
    color: '#FFFFFF',
    fontWeight: '700',
  },
  btnPrimary: {
    backgroundColor: '#00ADB5',
  },
  btnPrimaryText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '700',
  },
  testIconBtn: {
    width: 32,
    height: 32,
    borderRadius: 6,
    backgroundColor: '#1E293B',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: '#334155',
  },
  testIconText: {
    fontSize: 14,
  },
  workspaceWrapper: {
    flex: 1,
    backgroundColor: '#0B1120',
  },
  nativeWorkspace: {
    flex: 1,
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
