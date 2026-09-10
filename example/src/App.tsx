import { useState, useCallback } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  SafeAreaView,
  StatusBar,
  Modal,
} from 'react-native';
import {
  ThinkspaceView,
  type WorkspaceTool,
  type WorkspacePattern,
  type InkStroke,
  type ExcerptModel,
  type InkLink,
  type WorkspaceDocument,
  type DocumentAnnotation,
  type ToolContext,
} from 'thinkspace';

// -----------------------------------------------------------------------------
// Real Document: The Discovery of India by Jawaharlal Nehru
// -----------------------------------------------------------------------------
const DISCOVERY_OF_INDIA_DOC: WorkspaceDocument = {
  id: 'doc-discovery-of-india',
  title: 'The-Discovery-Of-India.pdf',
  pageCount: 58,
  author: 'Jawaharlal Nehru',
  sections: [
    {
      id: 'sec-preface',
      pageNumber: 7,
      heading: 'PREFACE',
      paragraphs: [
        'This book was written by me in Ahmadnagar Fort prison during the five months, April to September 1944. Some of my colleagues in prison were good enough to read the manuscript and make a number of valuable suggestions. On revising the book in prison I took advantage of these suggestions and made some additions. No one, I need hardly add, is responsible for what I have written or necessarily agrees with it. But I must express my deep gratitude to my fellow-prisoners in Ahmadnagar Fort for the innumerable talks and discussions we had, which helped me greatly to clear my own mind about various aspects of Indian history and culture. Prison is not a pleasant place to live in even for a short period, much less for long years. But it was a privilege for me to live in close contact with men of outstanding ability and culture and a wide human outlook which even the passions of the moment did not obscure.',
        'My eleven companions in Ahmadnagar Fort were an interesting cross-section of India and represented in their several ways not only politics but Indian scholarship, old and new, and various aspects of present-day India. Nearly all the principal living Indian languages, as well as the classical languages which have powerfully influenced India in the past and present, were represented and the standard was often that of high scholarship. Among the classical languages were Sanskrit and Pali, Arabic and Persian; the modern languages were Hindi, Urdu, Bengali, Gujarati, Marathi, Telugu, Sindhi and Oriya. I had all this wealth to draw upon and the only limitation was my own capacity to profit by it. Though I am grateful to all my companions, I should like to mention especially Maulana Abul Kalam Azad, whose vast erudition invariably delighted me but sometimes also rather overwhelmed me, Govind Ballabh Pant, Narendra Deva and M. Asaf Ali.',
        'It is a year and a quarter since I finished writing this book and some parts of it are already somewhat out of date, and much has happened since I wrote it. I have felt tempted to add and make alterations, but I have resisted the temptation. Indeed I could not normally have done so in prison or out of it, for life was too full of excitement and activities.',
      ],
    },
    {
      id: 'sec-quest',
      pageNumber: 22,
      heading: 'THE QUEST',
      paragraphs: [
        'What was this India that obsessed me and influenced my thoughts and actions? What did she represent in the past? What was the essence of that ancient wisdom which seemed to hold its own through centuries of storm and change?',
        'Successively different ages and periods and had for companions men and women who had lived long ago. I had leisure in jail there was no sense of hurry, and I could dream and wander in the past.',
        'Of life have always a way out of it, if they so choose. That is always in our power to achieve peace and tranquility.',
      ],
    },
    {
      id: 'sec-philosophy',
      pageNumber: 23,
      heading: 'PHILOSOPHY OF LIFE',
      paragraphs: [
        'That essay. What was my philosophy of life? I did not know. Some years earlier I would not have been so hesitant. There was a definite-ness about my outlook then; now I had many doubts and questions.',
      ],
    },
  ],
};

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

// Exact cards from the user's mobile screenshot
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
    text: 'of life have always a way out of it, if they so choose. Tha t is always in our power to achieve...',
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

const DRAWING_TOOLS: { id: WorkspaceTool; label: string; icon: string }[] = [
  { id: 'select', label: 'Select', icon: '✋' },
  { id: 'pen', label: 'Pen', icon: '✏️' },
  { id: 'highlighter', label: 'Highlighter', icon: '🖍️' },
  { id: 'eraser', label: 'Eraser', icon: '🧹' },
];

const PATTERNS: { id: WorkspacePattern; label: string }[] = [
  { id: 'looseleaf', label: 'Lined Notebook' },
  { id: 'dots', label: 'Dots' },
  { id: 'grid', label: 'Grid' },
  { id: 'none', label: 'Blank' },
];

const COLORS = [
  '#00ADB5',
  '#F59E0B',
  '#EF4444',
  '#3B82F6',
  '#8B5CF6',
  '#10B981',
];

export default function App() {
  const [toolContext, setToolContext] = useState<ToolContext>('workspace');
  const [tool, setTool] = useState<WorkspaceTool>('select');
  const [color, setColor] = useState('#00ADB5');
  const [pattern, setPattern] = useState<WorkspacePattern>('looseleaf');
  const [patternModalOpen, setPatternModalOpen] = useState(false);
  const [isSqueezed, setIsSqueezed] = useState(false);
  const [splitRatio, setSplitRatio] = useState(0.48);

  const [strokes, setStrokes] = useState<InkStroke[]>([]);
  const [excerpts, setExcerpts] = useState<ExcerptModel[]>(INITIAL_EXCERPTS);
  const [inkLinks, setInkLinks] = useState<InkLink[]>(INITIAL_LINKS);
  const [statusMsg, setStatusMsg] = useState('Total Native Workspace');

  // Actions
  const handleToggleSqueeze = useCallback(() => {
    setIsSqueezed((prev) => {
      const next = !prev;
      setStatusMsg(
        next
          ? '🪗 Accordion Squeezed: unannotated pages folded'
          : 'Accordion Squeeze disabled'
      );
      return next;
    });
  }, []);

  const handleAddNote = useCallback(() => {
    const newId = `note-${Date.now()}`;
    const newCard: ExcerptModel = {
      id: newId,
      documentId: 'doc-discovery-of-india',
      pageNumber: 7,
      text: 'New thought note added to workspace canvas.',
      color,
      x: 180,
      y: 120,
      width: 220,
    };
    setExcerpts((prev) => [...prev, newCard]);
    setStatusMsg('New Note Added');
  }, [color]);

  const handleTidy = useCallback(() => {
    setExcerpts((prev) =>
      prev.map((c, idx) => ({
        ...c,
        x: 60 + (idx % 2) * 260,
        y: 40 + Math.floor(idx / 2) * 150,
      }))
    );
    setStatusMsg('✨ Canvas Tidied');
  }, []);

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#0F172A" />

      {/* 1. TOP PROJECT HEADER (Matches screenshot) */}
      <View style={styles.projectHeader}>
        <View style={styles.headerLeft}>
          <TouchableOpacity style={styles.iconBtn} activeOpacity={0.7}>
            <Text style={styles.headerIcon}>🏠</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconBtn} activeOpacity={0.7}>
            <Text style={styles.headerIcon}>📄</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconBtn} activeOpacity={0.7}>
            <Text style={styles.headerIcon}>↩️</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.headerCenter}>
          <Text style={styles.headerStatusText} numberOfLines={1}>
            {statusMsg}
          </Text>
        </View>

        <View style={styles.headerRight}>
          <TouchableOpacity style={styles.iconBtn} activeOpacity={0.7}>
            <Text style={styles.headerIcon}>📤</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconBtn} activeOpacity={0.7}>
            <Text style={styles.headerIcon}>🔍</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconBtn} activeOpacity={0.7}>
            <Text style={styles.headerIcon}>•••</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* 2. MASTER TOTAL NATIVE WORKSPACE VIEW */}
      <View style={styles.workspaceWrapper}>
        <ThinkspaceView
          style={styles.nativeView}
          document={DISCOVERY_OF_INDIA_DOC}
          annotations={INITIAL_ANNOTATIONS}
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
          onExcerptPress={(id) => {
            const card = excerpts.find((c) => c.id === id);
            setStatusMsg(
              card
                ? `Jumped to Page ${card.pageNumber} in Document`
                : 'Card Selected'
            );
          }}
          onCardDelete={(id) => {
            setExcerpts((prev) => prev.filter((c) => c.id !== id));
            setInkLinks((prev) => prev.filter((l) => l.sourceExcerptId !== id));
            setStatusMsg('Card Removed');
          }}
          onChangeCardColor={(id, col) => {
            setExcerpts((prev) =>
              prev.map((c) => (c.id === id ? { ...c, color: col } : c))
            );
          }}
          onSplitRatioChange={(r) => setSplitRatio(r)}
          onSelectText={(sel) => {
            if (sel) {
              setStatusMsg(
                `Selected ${sel.text.length} chars: "${sel.text.slice(0, 25)}..."`
              );
            }
          }}
          onCopyText={(t) => {
            setStatusMsg(`📋 Copied ${t.length} chars to clipboard!`);
          }}
          onHighlightText={(_, pageNum) => {
            setStatusMsg(`🖍️ Highlighted passage on Page ${pageNum}`);
          }}
          onExtractExcerpt={(item) => {
            const newId = `excerpt-${Date.now()}`;
            const newCard: ExcerptModel = {
              id: newId,
              documentId: 'doc-discovery-of-india',
              pageNumber: item.pageNumber,
              text: item.text,
              color: item.color,
              x: 120,
              y: 80,
              width: 230,
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
            setStatusMsg(
              `Extracted excerpt from Page ${item.pageNumber} to Canvas`
            );
          }}
          onToggleSqueeze={handleToggleSqueeze}
        />
      </View>

      {/* 3. CONTEXTUAL TOOLBAR STRIP (Matches screenshot) */}
      <View style={styles.contextToolbarStrip}>
        {toolContext === 'workspace' && (
          <View style={styles.toolbarRow}>
            <TouchableOpacity
              style={styles.contextBtn}
              activeOpacity={0.7}
              onPress={handleTidy}
            >
              <Text style={styles.contextBtnIcon}>⊞</Text>
              <Text style={styles.contextBtnLabel}>Workspaces</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.contextBtn}
              activeOpacity={0.7}
              onPress={handleAddNote}
            >
              <Text style={[styles.contextBtnIcon, { fontWeight: '900' }]}>
                A
              </Text>
              <Text style={styles.contextBtnLabel}>Text Box</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.contextBtn}
              activeOpacity={0.7}
              onPress={handleTidy}
            >
              <Text style={styles.contextBtnIcon}>✨</Text>
              <Text style={styles.contextBtnLabel}>Tidy</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.contextBtn, isSqueezed && styles.contextBtnActive]}
              activeOpacity={0.7}
              onPress={handleToggleSqueeze}
            >
              <Text
                style={[
                  styles.contextBtnIcon,
                  isSqueezed && { color: '#00ADB5' },
                ]}
              >
                ≈
              </Text>
              <Text
                style={[
                  styles.contextBtnLabel,
                  isSqueezed && { color: '#00ADB5' },
                ]}
              >
                Squeeze
              </Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.contextBtn}
              activeOpacity={0.7}
              onPress={() => setPatternModalOpen(true)}
            >
              <Text style={styles.contextBtnIcon}>:::</Text>
              <Text style={styles.contextBtnLabel}>Pattern</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.contextBtn}
              activeOpacity={0.7}
              onPress={() => setStatusMsg('Zoom Reset (100%)')}
            >
              <Text style={styles.contextBtnIcon}>⊝</Text>
              <Text style={styles.contextBtnLabel}>Zoom Out</Text>
            </TouchableOpacity>
          </View>
        )}

        {toolContext === 'drawing' && (
          <View style={styles.toolbarRow}>
            {DRAWING_TOOLS.map((t) => {
              const isActive = tool === t.id;
              return (
                <TouchableOpacity
                  key={t.id}
                  style={[
                    styles.contextBtn,
                    isActive && styles.contextBtnActive,
                  ]}
                  onPress={() => setTool(t.id)}
                >
                  <Text style={styles.contextBtnIcon}>{t.icon}</Text>
                  <Text
                    style={[
                      styles.contextBtnLabel,
                      isActive && { color: '#00ADB5' },
                    ]}
                  >
                    {t.label}
                  </Text>
                </TouchableOpacity>
              );
            })}

            <View style={styles.colorRow}>
              {COLORS.map((c) => (
                <TouchableOpacity
                  key={c}
                  style={[
                    styles.colorPill,
                    { backgroundColor: c },
                    color === c && styles.activeColorPill,
                  ]}
                  onPress={() => setColor(c)}
                />
              ))}
            </View>
          </View>
        )}

        {toolContext === 'document' && (
          <View style={styles.toolbarRow}>
            <TouchableOpacity
              style={styles.contextBtn}
              activeOpacity={0.7}
              onPress={handleToggleSqueeze}
            >
              <Text style={styles.contextBtnIcon}>🪗</Text>
              <Text style={styles.contextBtnLabel}>
                {isSqueezed ? 'Unsqueeze' : 'Squeeze'}
              </Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.contextBtn}
              activeOpacity={0.7}
              onPress={handleAddNote}
            >
              <Text style={styles.contextBtnIcon}>✏️</Text>
              <Text style={styles.contextBtnLabel}>Add Note</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.contextBtn}
              activeOpacity={0.7}
              onPress={() =>
                setStatusMsg('Crop Region: Drag on document to extract figure')
              }
            >
              <Text style={styles.contextBtnIcon}>✂️</Text>
              <Text style={styles.contextBtnLabel}>Crop Figure</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.contextBtn}
              activeOpacity={0.7}
              onPress={() =>
                setStatusMsg('Search document: "Discovery of India"')
              }
            >
              <Text style={styles.contextBtnIcon}>🔍</Text>
              <Text style={styles.contextBtnLabel}>Search</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>

      {/* 4. BOTTOM CONTEXTUAL TAB BAR (Matches screenshot) */}
      <View style={styles.bottomTabBar}>
        <TouchableOpacity
          style={styles.bottomTab}
          activeOpacity={0.7}
          onPress={() => setToolContext('drawing')}
        >
          <View
            style={[
              styles.tabPill,
              toolContext === 'drawing' && styles.tabPillActive,
            ]}
          >
            <Text style={styles.tabIcon}>✏️</Text>
            <Text
              style={[
                styles.tabLabel,
                toolContext === 'drawing' && styles.tabLabelActive,
              ]}
            >
              Drawing
            </Text>
          </View>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.bottomTab}
          activeOpacity={0.7}
          onPress={() => setToolContext('document')}
        >
          <View
            style={[
              styles.tabPill,
              toolContext === 'document' && styles.tabPillActive,
            ]}
          >
            <Text style={styles.tabIcon}>📄</Text>
            <Text
              style={[
                styles.tabLabel,
                toolContext === 'document' && styles.tabLabelActive,
              ]}
            >
              Document
            </Text>
          </View>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.bottomTab}
          activeOpacity={0.7}
          onPress={() => setToolContext('workspace')}
        >
          <View
            style={[
              styles.tabPill,
              toolContext === 'workspace' && styles.tabPillActive,
            ]}
          >
            <Text style={styles.tabIcon}>⬡</Text>
            <Text
              style={[
                styles.tabLabel,
                toolContext === 'workspace' && styles.tabLabelActive,
              ]}
            >
              Workspace
            </Text>
          </View>
        </TouchableOpacity>
      </View>

      {/* Pattern Picker Modal */}
      <Modal visible={patternModalOpen} transparent animationType="fade">
        <TouchableOpacity
          style={styles.modalOverlay}
          activeOpacity={1}
          onPress={() => setPatternModalOpen(false)}
        >
          <View style={styles.modalContent}>
            <Text style={styles.modalTitle}>WORKSPACE PATTERN</Text>
            {PATTERNS.map((p) => (
              <TouchableOpacity
                key={p.id}
                style={[
                  styles.modalItem,
                  pattern === p.id && styles.activeModalItem,
                ]}
                onPress={() => {
                  setPattern(p.id);
                  setPatternModalOpen(false);
                }}
              >
                <Text
                  style={[
                    styles.modalItemText,
                    pattern === p.id && styles.activeModalItemText,
                  ]}
                >
                  {p.label}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        </TouchableOpacity>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0F172A',
  },
  // Top Project Header
  projectHeader: {
    height: 48,
    backgroundColor: '#0F172A',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
  },
  headerLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
  },
  headerCenter: {
    flex: 1,
    alignItems: 'center',
    paddingHorizontal: 8,
  },
  headerStatusText: {
    color: '#94A3B8',
    fontSize: 12,
    fontWeight: '500',
  },
  headerRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
  },
  iconBtn: {
    padding: 4,
  },
  headerIcon: {
    fontSize: 18,
    color: '#E2E8F0',
  },
  // Master Workspace
  workspaceWrapper: {
    flex: 1,
    backgroundColor: '#131922',
  },
  nativeView: {
    flex: 1,
    width: '100%',
    height: '100%',
  },
  // Contextual Toolbar Strip
  contextToolbarStrip: {
    height: 60,
    backgroundColor: '#0F172A',
    borderTopWidth: 1,
    borderTopColor: '#1E293B',
    paddingHorizontal: 8,
    justifyContent: 'center',
  },
  toolbarRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
  },
  contextBtn: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 4,
    paddingHorizontal: 8,
    borderRadius: 8,
  },
  contextBtnActive: {
    backgroundColor: 'rgba(0, 173, 181, 0.12)',
  },
  contextBtnIcon: {
    fontSize: 16,
    color: '#CBD5E1',
    marginBottom: 2,
  },
  contextBtnLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: '#94A3B8',
  },
  colorRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  colorPill: {
    width: 18,
    height: 18,
    borderRadius: 9,
  },
  activeColorPill: {
    borderWidth: 2,
    borderColor: '#FFFFFF',
    transform: [{ scale: 1.2 }],
  },
  // Bottom Contextual Tab Bar
  bottomTabBar: {
    height: 52,
    backgroundColor: '#090D16',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
    paddingHorizontal: 12,
    borderTopWidth: 1,
    borderTopColor: '#1E293B',
  },
  bottomTab: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 16,
    paddingVertical: 7,
    borderRadius: 20,
    justifyContent: 'center',
  },
  tabPillActive: {
    backgroundColor: '#00ADB5',
  },
  tabIcon: {
    fontSize: 14,
  },
  tabLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: '#94A3B8',
  },
  tabLabelActive: {
    color: '#FFFFFF',
    fontWeight: '700',
  },
  // Modal
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.6)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  modalContent: {
    width: 240,
    backgroundColor: '#1A202C',
    borderRadius: 14,
    padding: 16,
    borderWidth: 1,
    borderColor: '#334155',
  },
  modalTitle: {
    fontSize: 11,
    fontWeight: '800',
    color: '#94A3B8',
    letterSpacing: 0.8,
    marginBottom: 12,
    textAlign: 'center',
  },
  modalItem: {
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 8,
    marginBottom: 4,
  },
  activeModalItem: {
    backgroundColor: 'rgba(0, 173, 181, 0.15)',
  },
  modalItemText: {
    color: '#E2E8F0',
    fontSize: 13,
    fontWeight: '600',
  },
  activeModalItemText: {
    color: '#00ADB5',
    fontWeight: '700',
  },
});
