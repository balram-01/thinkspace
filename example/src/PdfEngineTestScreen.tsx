/**
 * PdfEngineTestScreen.tsx
 *
 * A full-featured interactive test screen for the pdf-engine.
 * Lets you pick any PDF from device storage and run:
 *   - Open / metadata
 *   - Extract text (word-level boxes)
 *   - Analyze page layout (columns, headings, blocks)
 *   - Search across all pages
 *   - Render page as image
 *   - Close document
 */

import { useState, useCallback } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
  Image,
  ActivityIndicator,
  TextInput,
  StyleSheet,
  StatusBar,
  Platform,
} from 'react-native';
import { PdfEngine } from 'thinkspace';
import type {
  PdfDocumentInfo,
  PdfTextPage,
  PdfPageAnalysis,
  PdfSearchResult,
  PdfRenderedPage,
} from 'thinkspace';

// ─── Types ────────────────────────────────────────────────────────────────────

type TestTab = 'info' | 'text' | 'layout' | 'search' | 'render';
type Status = { loading: boolean; error: string | null };

// ─── Component ────────────────────────────────────────────────────────────────

export default function PdfEngineTestScreen() {
  // Document state
  const [pickedFile, setPickedFile] = useState<{
    uri: string;
    name: string;
  } | null>(null);
  const [doc, setDoc] = useState<PdfDocumentInfo | null>(null);
  const [activeTab, setActiveTab] = useState<TestTab>('info');

  // Per-tab results
  const [textResult, setTextResult] = useState<PdfTextPage | null>(null);
  const [layoutResult, setLayoutResult] = useState<PdfPageAnalysis | null>(
    null
  );
  const [searchResults, setSearchResults] = useState<PdfSearchResult[] | null>(
    null
  );
  const [renderResult, setRenderResult] = useState<PdfRenderedPage | null>(
    null
  );

  // UI state
  const [pageIndex, setPageIndex] = useState(0);
  const [searchQuery, setSearchQuery] = useState('');
  const [renderScale] = useState(1.5);
  const [status, setStatus] = useState<Status>({ loading: false, error: null });
  const [log, setLog] = useState<string[]>([]);

  const addLog = useCallback((msg: string) => {
    const ts = new Date().toLocaleTimeString();
    setLog((prev) => [`[${ts}] ${msg}`, ...prev.slice(0, 29)]);
  }, []);

  const withStatus = useCallback(
    async (label: string, fn: () => Promise<void>) => {
      setStatus({ loading: true, error: null });
      addLog(`▶ ${label}…`);
      try {
        await fn();
        addLog(`✓ ${label} done`);
        setStatus({ loading: false, error: null });
      } catch (e: any) {
        const msg = e?.message ?? String(e);
        addLog(`✗ ${label} failed: ${msg}`);
        setStatus({ loading: false, error: msg });
      }
    },
    [addLog]
  );

  // ── Actions ──────────────────────────────────────────────────────────────────

  const handlePickPdf = useCallback(async () => {
    await withStatus('Pick PDF', async () => {
      const file = await PdfEngine.pickPdfFile();
      setPickedFile(file);
      setDoc(null);
      setTextResult(null);
      setLayoutResult(null);
      setSearchResults(null);
      setRenderResult(null);
      setLog([]);
      addLog(`Picked: ${file.name}`);
    });
  }, [withStatus, addLog]);

  const handleOpenDoc = useCallback(async () => {
    if (!pickedFile) return;
    await withStatus('Open Document', async () => {
      if (doc) {
        try {
          await PdfEngine.closeDocument(doc.documentId);
        } catch {}
      }
      const opened = await PdfEngine.openDocument(pickedFile.uri);
      setDoc(opened);
      setTextResult(null);
      setLayoutResult(null);
      setSearchResults(null);
      setRenderResult(null);
      setActiveTab('info');
      addLog(
        `Opened: "${opened.title || pickedFile.name}" — ${opened.pageCount} pages`
      );
    });
  }, [pickedFile, doc, withStatus, addLog]);

  const handleExtractText = useCallback(async () => {
    if (!doc) return;
    await withStatus(`Extract Text (page ${pageIndex})`, async () => {
      const result = await PdfEngine.extractText(doc.documentId, pageIndex);
      setTextResult(result);
      setActiveTab('text');
      addLog(`Extracted ${result.words.length} words from page ${pageIndex}`);
    });
  }, [doc, pageIndex, withStatus, addLog]);

  const handleAnalyzePage = useCallback(async () => {
    if (!doc) return;
    await withStatus(`Analyze Layout (page ${pageIndex})`, async () => {
      const result = await PdfEngine.analyzePage(doc.documentId, pageIndex);
      setLayoutResult(result);
      setActiveTab('layout');
      addLog(
        `Layout: ${result.blocks.length} blocks, ${result.columnCount} columns, ${result.lines.length} lines`
      );
    });
  }, [doc, pageIndex, withStatus, addLog]);

  const handleSearch = useCallback(async () => {
    if (!doc || !searchQuery.trim()) return;
    await withStatus(`Search "${searchQuery}"`, async () => {
      const results = await PdfEngine.searchDocument(
        doc.documentId,
        searchQuery.trim()
      );
      setSearchResults(results);
      setActiveTab('search');
      addLog(`Found ${results.length} matches for "${searchQuery}"`);
    });
  }, [doc, searchQuery, withStatus, addLog]);

  const handleRenderPage = useCallback(async () => {
    if (!doc) return;
    await withStatus(`Render Page ${pageIndex} @ ${renderScale}x`, async () => {
      const result = await PdfEngine.renderPage(
        doc.documentId,
        pageIndex,
        renderScale
      );
      setRenderResult(result);
      setActiveTab('render');
      addLog(`Rendered: ${result.width}×${result.height}px → ${result.uri}`);
    });
  }, [doc, pageIndex, renderScale, withStatus, addLog]);

  const handleCloseDoc = useCallback(async () => {
    if (!doc) return;
    await withStatus('Close Document', async () => {
      await PdfEngine.closeDocument(doc.documentId);
      setDoc(null);
      setTextResult(null);
      setLayoutResult(null);
      setSearchResults(null);
      setRenderResult(null);
      addLog('Document closed and resources released');
    });
  }, [doc, withStatus, addLog]);

  // ── Tab content ──────────────────────────────────────────────────────────────

  const renderInfoTab = () => (
    <ScrollView style={styles.tabContent} showsVerticalScrollIndicator={false}>
      {doc ? (
        <View>
          <InfoRow label="Document ID" value={doc.documentId} />
          <InfoRow label="Title" value={doc.title || '(none)'} />
          <InfoRow label="Author" value={doc.author || '(none)'} />
          <InfoRow label="Subject" value={doc.subject || '(none)'} />
          <InfoRow label="Pages" value={String(doc.pageCount)} highlight />
          <InfoRow label="Encrypted" value={doc.isEncrypted ? 'Yes' : 'No'} />
          <InfoRow label="PDF Version" value={doc.pdfVersion || '(unknown)'} />
          <InfoRow label="File" value={pickedFile?.name ?? ''} />
        </View>
      ) : (
        <EmptyState
          icon="📄"
          title="No document open"
          subtitle="Pick a PDF file above, then tap Open Document"
        />
      )}
    </ScrollView>
  );

  const renderTextTab = () => (
    <ScrollView style={styles.tabContent} showsVerticalScrollIndicator={false}>
      {textResult ? (
        <View>
          <View style={styles.resultHeader}>
            <Text style={styles.resultHeaderText}>
              {textResult.words.length} words — Page {textResult.pageIndex}
            </Text>
          </View>
          {textResult.words.slice(0, 80).map((word, i) => (
            <View key={i} style={styles.wordRow}>
              <Text style={styles.wordText} numberOfLines={1}>
                {word.text}
              </Text>
              <Text style={styles.wordMeta}>
                {word.fontSize.toFixed(1)}pt
                {word.isBold ? ' B' : ''}
                {word.isItalic ? ' I' : ''}
                {'  '}
                <Text style={styles.wordBounds}>
                  ({word.bounds.left.toFixed(0)}, {word.bounds.top.toFixed(0)})
                </Text>
              </Text>
            </View>
          ))}
          {textResult.words.length > 80 && (
            <Text style={styles.truncNote}>
              … {textResult.words.length - 80} more words
            </Text>
          )}
        </View>
      ) : (
        <EmptyState
          icon="🔤"
          title="No text extracted"
          subtitle="Tap Extract Text below to extract word-level coordinates"
        />
      )}
    </ScrollView>
  );

  const renderLayoutTab = () => (
    <ScrollView style={styles.tabContent} showsVerticalScrollIndicator={false}>
      {layoutResult ? (
        <View>
          <View style={styles.resultHeader}>
            <Text style={styles.resultHeaderText}>
              Page {layoutResult.pageIndex} — {layoutResult.columnCount} column
              {layoutResult.columnCount !== 1 ? 's' : ''} —{' '}
              {layoutResult.pageWidth.toFixed(0)}×
              {layoutResult.pageHeight.toFixed(0)} pt
              {layoutResult.isScanned ? ' · SCANNED' : ''}
            </Text>
          </View>
          {layoutResult.blocks.map((block, i) => (
            <View
              key={block.id}
              style={[
                styles.blockCard,
                block.isHeading && styles.blockCardHeading,
              ]}
            >
              <View style={styles.blockHeader}>
                <Text style={styles.blockLabel}>
                  {block.isHeading ? '📌 Heading' : `Block ${i + 1}`}
                  {'  '}
                  <Text style={styles.blockMeta}>
                    Col {block.columnIndex} · {block.lines.length} lines
                  </Text>
                </Text>
              </View>
              <Text style={styles.blockText} numberOfLines={3}>
                {block.text}
              </Text>
            </View>
          ))}
        </View>
      ) : (
        <EmptyState
          icon="🗂️"
          title="No layout analyzed"
          subtitle="Tap Analyze Layout below to detect columns, headings, blocks"
        />
      )}
    </ScrollView>
  );

  const renderSearchTab = () => (
    <ScrollView style={styles.tabContent} showsVerticalScrollIndicator={false}>
      {searchResults ? (
        <View>
          <View style={styles.resultHeader}>
            <Text style={styles.resultHeaderText}>
              {searchResults.length} match
              {searchResults.length !== 1 ? 'es' : ''} for "{searchQuery}"
            </Text>
          </View>
          {searchResults.length === 0 ? (
            <EmptyState icon="🔍" title="No matches" subtitle="" />
          ) : (
            searchResults.map((r, i) => (
              <View key={i} style={styles.searchCard}>
                <View style={styles.searchPageBadge}>
                  <Text style={styles.searchPageText}>p.{r.pageIndex + 1}</Text>
                </View>
                <View style={styles.searchContent}>
                  <Text style={styles.searchMatch}>{r.matchedText}</Text>
                  <Text style={styles.searchContext} numberOfLines={2}>
                    {r.context}
                  </Text>
                  {r.isFromOcr && <Text style={styles.ocrBadge}>OCR</Text>}
                </View>
              </View>
            ))
          )}
        </View>
      ) : (
        <EmptyState
          icon="🔍"
          title="No search run"
          subtitle="Type a query above and tap Search"
        />
      )}
    </ScrollView>
  );

  const renderRenderTab = () => (
    <ScrollView
      style={styles.tabContent}
      showsVerticalScrollIndicator={false}
      contentContainerStyle={styles.renderTabContent}
    >
      {renderResult ? (
        <View style={styles.renderContainer}>
          <Text style={styles.renderMeta}>
            Page {renderResult.pageIndex} · {renderResult.width}×
            {renderResult.height}px · {renderScale}× scale
          </Text>
          <Image
            source={{ uri: renderResult.uri }}
            style={{
              width: '100%',
              aspectRatio: renderResult.width / renderResult.height,
              borderRadius: 8,
              borderWidth: 1,
              borderColor: '#334155',
            }}
            resizeMode="contain"
          />
          <Text style={styles.renderUri} numberOfLines={2}>
            {renderResult.uri}
          </Text>
        </View>
      ) : (
        <EmptyState
          icon="🖼️"
          title="No page rendered"
          subtitle="Tap Render Page to rasterize a page"
        />
      )}
    </ScrollView>
  );

  const tabContent = () => {
    switch (activeTab) {
      case 'info':
        return renderInfoTab();
      case 'text':
        return renderTextTab();
      case 'layout':
        return renderLayoutTab();
      case 'search':
        return renderSearchTab();
      case 'render':
        return renderRenderTab();
    }
  };

  // ── Render ───────────────────────────────────────────────────────────────────

  return (
    <View style={styles.container}>
      <StatusBar
        barStyle="light-content"
        backgroundColor="#050E1A"
        translucent={false}
      />

      {/* ── HEADER ──────────────────────────────────────────────── */}
      <View style={styles.header}>
        <View style={styles.headerTitleRow}>
          <View style={styles.engineBadge}>
            <Text style={styles.engineBadgeText}>ENGINE</Text>
          </View>
          <Text style={styles.headerTitle}>PDF Engine Test</Text>
        </View>
        <Text style={styles.headerSub}>
          {doc
            ? `${doc.title || pickedFile?.name || 'Document'} · ${doc.pageCount} pages`
            : 'Pick a PDF to begin'}
        </Text>
      </View>

      {/* ── PICK + OPEN CONTROLS ─────────────────────────────────── */}
      <View style={styles.topControls}>
        <TouchableOpacity
          style={[styles.btn, styles.btnPrimary]}
          onPress={handlePickPdf}
          activeOpacity={0.8}
          disabled={status.loading}
        >
          <Text style={styles.btnIcon}>📂</Text>
          <Text style={styles.btnLabel}>Pick PDF</Text>
        </TouchableOpacity>

        {pickedFile && (
          <TouchableOpacity
            style={[
              styles.btn,
              styles.btnAccent,
              !pickedFile && styles.btnDisabled,
            ]}
            onPress={handleOpenDoc}
            activeOpacity={0.8}
            disabled={status.loading}
          >
            <Text style={styles.btnIcon}>📖</Text>
            <Text style={styles.btnLabel}>Open</Text>
          </TouchableOpacity>
        )}

        {doc && (
          <TouchableOpacity
            style={[styles.btn, styles.btnDanger]}
            onPress={handleCloseDoc}
            activeOpacity={0.8}
            disabled={status.loading}
          >
            <Text style={styles.btnIcon}>🗑</Text>
            <Text style={styles.btnLabel}>Close</Text>
          </TouchableOpacity>
        )}
      </View>

      {pickedFile && (
        <View style={styles.fileChip}>
          <Text style={styles.fileChipIcon}>📄</Text>
          <Text style={styles.fileChipName} numberOfLines={1}>
            {pickedFile.name}
          </Text>
        </View>
      )}

      {/* ── STATUS BAR ───────────────────────────────────────────── */}
      {status.loading && (
        <View style={styles.statusBar}>
          <ActivityIndicator size="small" color="#00ADB5" />
          <Text style={styles.statusText}>Working…</Text>
        </View>
      )}
      {status.error && (
        <View style={[styles.statusBar, styles.statusError]}>
          <Text style={styles.statusErrorText}>⚠ {status.error}</Text>
        </View>
      )}

      {/* ── TAB BAR ──────────────────────────────────────────────── */}
      {doc && (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={styles.tabBar}
          contentContainerStyle={styles.tabBarContent}
        >
          {(
            [
              { id: 'info', icon: 'ℹ', label: 'Info' },
              { id: 'text', icon: '🔤', label: 'Text' },
              { id: 'layout', icon: '🗂', label: 'Layout' },
              { id: 'search', icon: '🔍', label: 'Search' },
              { id: 'render', icon: '🖼', label: 'Render' },
            ] as { id: TestTab; icon: string; label: string }[]
          ).map((tab) => (
            <TouchableOpacity
              key={tab.id}
              style={[styles.tab, activeTab === tab.id && styles.tabActive]}
              onPress={() => setActiveTab(tab.id)}
              activeOpacity={0.7}
            >
              <Text style={styles.tabIcon}>{tab.icon}</Text>
              <Text
                style={[
                  styles.tabLabel,
                  activeTab === tab.id && styles.tabLabelActive,
                ]}
              >
                {tab.label}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      )}

      {/* ── MAIN CONTENT ─────────────────────────────────────────── */}
      <View style={styles.content}>
        {doc ? (
          tabContent()
        ) : (
          <View style={styles.welcomeContainer}>
            <Text style={styles.welcomeIcon}>🔬</Text>
            <Text style={styles.welcomeTitle}>PDF Engine Test Lab</Text>
            <Text style={styles.welcomeBody}>
              Pick any PDF from your device to test the Kotlin pdf-engine.
              {'\n\n'}
              You'll be able to extract text with word-level bounding boxes,
              analyze multi-column layouts, full-text search across all pages,
              and render high-resolution page images.
            </Text>
          </View>
        )}
      </View>

      {/* ── ACTION TOOLBAR ───────────────────────────────────────── */}
      {doc && (
        <View style={styles.toolbar}>
          {/* Page selector */}
          <View style={styles.pageControl}>
            <TouchableOpacity
              style={styles.pageBtn}
              onPress={() => setPageIndex((p) => Math.max(0, p - 1))}
              disabled={pageIndex === 0 || status.loading}
            >
              <Text style={styles.pageBtnText}>‹</Text>
            </TouchableOpacity>
            <Text style={styles.pageNum}>
              {pageIndex + 1} / {doc.pageCount}
            </Text>
            <TouchableOpacity
              style={styles.pageBtn}
              onPress={() =>
                setPageIndex((p) => Math.min(doc.pageCount - 1, p + 1))
              }
              disabled={pageIndex >= doc.pageCount - 1 || status.loading}
            >
              <Text style={styles.pageBtnText}>›</Text>
            </TouchableOpacity>
          </View>

          {/* Action buttons */}
          <View style={styles.actionBtns}>
            <ActionBtn
              icon="🔤"
              label="Text"
              onPress={handleExtractText}
              disabled={status.loading}
            />
            <ActionBtn
              icon="🗂"
              label="Layout"
              onPress={handleAnalyzePage}
              disabled={status.loading}
            />
            <ActionBtn
              icon="🖼"
              label="Render"
              onPress={handleRenderPage}
              disabled={status.loading}
            />
          </View>

          {/* Search input */}
          <View style={styles.searchRow}>
            <TextInput
              style={styles.searchInput}
              placeholder="Search PDF…"
              placeholderTextColor="#4B5563"
              value={searchQuery}
              onChangeText={setSearchQuery}
              onSubmitEditing={handleSearch}
              returnKeyType="search"
            />
            <TouchableOpacity
              style={styles.searchBtn}
              onPress={handleSearch}
              disabled={status.loading || !searchQuery.trim()}
            >
              <Text style={styles.searchBtnText}>🔍</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}

      {/* ── LOG STRIP ────────────────────────────────────────────── */}
      {log.length > 0 && (
        <View style={styles.logStrip}>
          <Text style={styles.logText} numberOfLines={1}>
            {log[0]}
          </Text>
        </View>
      )}
    </View>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function InfoRow({
  label,
  value,
  highlight,
}: {
  label: string;
  value: string;
  highlight?: boolean;
}) {
  return (
    <View style={styles.infoRow}>
      <Text style={styles.infoLabel}>{label}</Text>
      <Text style={[styles.infoValue, highlight && styles.infoValueHighlight]}>
        {value}
      </Text>
    </View>
  );
}

function ActionBtn({
  icon,
  label,
  onPress,
  disabled,
}: {
  icon: string;
  label: string;
  onPress: () => void;
  disabled: boolean;
}) {
  return (
    <TouchableOpacity
      style={[styles.actionBtn, disabled && styles.actionBtnDisabled]}
      onPress={onPress}
      disabled={disabled}
      activeOpacity={0.7}
    >
      <Text style={styles.actionBtnIcon}>{icon}</Text>
      <Text style={styles.actionBtnLabel}>{label}</Text>
    </TouchableOpacity>
  );
}

function EmptyState({
  icon,
  title,
  subtitle,
}: {
  icon: string;
  title: string;
  subtitle: string;
}) {
  return (
    <View style={styles.emptyState}>
      <Text style={styles.emptyIcon}>{icon}</Text>
      <Text style={styles.emptyTitle}>{title}</Text>
      {subtitle ? <Text style={styles.emptySub}>{subtitle}</Text> : null}
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#050E1A',
    paddingTop: Platform.OS === 'android' ? (StatusBar.currentHeight ?? 24) : 0,
  },

  // Header
  header: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#0F2036',
  },
  headerTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  engineBadge: {
    backgroundColor: '#00ADB5',
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
  },
  engineBadgeText: {
    color: '#fff',
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1,
  },
  headerTitle: {
    color: '#F1F5F9',
    fontSize: 20,
    fontWeight: '700',
  },
  headerSub: {
    color: '#64748B',
    fontSize: 12,
    marginTop: 2,
    marginLeft: 2,
  },

  // Top controls
  topControls: {
    flexDirection: 'row',
    gap: 8,
    paddingHorizontal: 16,
    paddingTop: 12,
  },
  btn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 14,
    paddingVertical: 9,
    borderRadius: 10,
  },
  btnPrimary: {
    backgroundColor: '#1E3A5F',
    borderWidth: 1,
    borderColor: '#2563EB',
  },
  btnAccent: {
    backgroundColor: '#003D40',
    borderWidth: 1,
    borderColor: '#00ADB5',
  },
  btnDanger: {
    backgroundColor: '#3B0A0A',
    borderWidth: 1,
    borderColor: '#EF4444',
  },
  btnDisabled: { opacity: 0.4 },
  btnIcon: { fontSize: 16 },
  btnLabel: { color: '#CBD5E1', fontSize: 13, fontWeight: '600' },

  // File chip
  fileChip: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 16,
    marginTop: 8,
    backgroundColor: '#0F2036',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 6,
    gap: 6,
  },
  fileChipIcon: { fontSize: 14 },
  fileChipName: { color: '#94A3B8', fontSize: 12, flex: 1 },

  // Status
  statusBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginHorizontal: 16,
    marginTop: 6,
    backgroundColor: '#0F2036',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  statusError: {
    backgroundColor: '#1F0A0A',
    borderColor: '#EF4444',
    borderWidth: 1,
  },
  statusText: { color: '#94A3B8', fontSize: 12 },
  statusErrorText: { color: '#F87171', fontSize: 12 },

  // Tab bar
  tabBar: {
    marginTop: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#0F2036',
    maxHeight: 52,
  },
  tabBarContent: { paddingHorizontal: 12, gap: 4 },
  tab: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 8,
  },
  tabActive: { backgroundColor: '#0F2036' },
  tabIcon: { fontSize: 14 },
  tabLabel: { color: '#4B5563', fontSize: 13, fontWeight: '600' },
  tabLabelActive: { color: '#00ADB5' },

  // Content area
  content: {
    flex: 1,
  },
  tabContent: {
    flex: 1,
    paddingHorizontal: 16,
    paddingTop: 12,
  },

  // Welcome
  welcomeContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 32,
  },
  welcomeIcon: { fontSize: 56, marginBottom: 16 },
  welcomeTitle: {
    color: '#F1F5F9',
    fontSize: 22,
    fontWeight: '700',
    marginBottom: 12,
    textAlign: 'center',
  },
  welcomeBody: {
    color: '#475569',
    fontSize: 14,
    lineHeight: 22,
    textAlign: 'center',
  },

  // Info tab
  infoRow: {
    flexDirection: 'row',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#0F2036',
    gap: 12,
  },
  infoLabel: {
    color: '#4B5563',
    fontSize: 13,
    width: 100,
    flexShrink: 0,
  },
  infoValue: { color: '#CBD5E1', fontSize: 13, flex: 1 },
  infoValueHighlight: { color: '#00ADB5', fontWeight: '700', fontSize: 16 },

  // Text tab
  resultHeader: {
    backgroundColor: '#0A1929',
    borderRadius: 8,
    padding: 10,
    marginBottom: 10,
  },
  resultHeaderText: { color: '#00ADB5', fontSize: 13, fontWeight: '600' },
  wordRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 5,
    borderBottomWidth: 1,
    borderBottomColor: '#0A1929',
  },
  wordText: {
    color: '#E2E8F0',
    fontSize: 13,
    flex: 1,
    fontWeight: '500',
  },
  wordMeta: { color: '#4B5563', fontSize: 11 },
  wordBounds: { color: '#1E3A5F' },
  truncNote: {
    color: '#4B5563',
    fontSize: 12,
    textAlign: 'center',
    padding: 12,
  },

  // Layout tab
  blockCard: {
    backgroundColor: '#0A1929',
    borderRadius: 8,
    padding: 10,
    marginBottom: 8,
    borderLeftWidth: 3,
    borderLeftColor: '#1E3A5F',
  },
  blockCardHeading: { borderLeftColor: '#00ADB5' },
  blockHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 4,
  },
  blockLabel: { color: '#94A3B8', fontSize: 12, fontWeight: '700' },
  blockMeta: { color: '#4B5563', fontWeight: '400' },
  blockText: { color: '#CBD5E1', fontSize: 12, lineHeight: 18 },

  // Search tab
  searchCard: {
    flexDirection: 'row',
    backgroundColor: '#0A1929',
    borderRadius: 8,
    marginBottom: 8,
    overflow: 'hidden',
  },
  searchPageBadge: {
    backgroundColor: '#00ADB520',
    width: 44,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 8,
  },
  searchPageText: { color: '#00ADB5', fontSize: 11, fontWeight: '800' },
  searchContent: { flex: 1, padding: 10 },
  searchMatch: {
    color: '#F1F5F9',
    fontSize: 13,
    fontWeight: '600',
    marginBottom: 3,
  },
  searchContext: { color: '#64748B', fontSize: 11, lineHeight: 16 },
  ocrBadge: {
    color: '#F59E0B',
    fontSize: 10,
    fontWeight: '700',
    marginTop: 4,
  },

  // Render tab
  renderTabContent: { paddingHorizontal: 16, paddingVertical: 12 },
  renderContainer: { gap: 10 },
  renderMeta: {
    color: '#00ADB5',
    fontSize: 12,
    fontWeight: '600',
    textAlign: 'center',
  },
  renderUri: { color: '#1E3A5F', fontSize: 10, textAlign: 'center' },

  // Empty state
  emptyState: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 48,
    gap: 10,
  },
  emptyIcon: { fontSize: 40 },
  emptyTitle: { color: '#4B5563', fontSize: 16, fontWeight: '600' },
  emptySub: {
    color: '#2D3748',
    fontSize: 13,
    textAlign: 'center',
    maxWidth: 260,
  },

  // Toolbar
  toolbar: {
    borderTopWidth: 1,
    borderTopColor: '#0F2036',
    paddingHorizontal: 12,
    paddingTop: 12,
    paddingBottom: Platform.OS === 'android' ? 20 : 12,
    gap: 10,
    backgroundColor: '#050E1A',
  },
  pageControl: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 16,
  },
  pageBtn: {
    backgroundColor: '#0F2036',
    width: 36,
    height: 36,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pageBtnText: { color: '#CBD5E1', fontSize: 22, fontWeight: '600' },
  pageNum: {
    color: '#94A3B8',
    fontSize: 14,
    fontWeight: '600',
    minWidth: 70,
    textAlign: 'center',
  },

  actionBtns: {
    flexDirection: 'row',
    gap: 8,
    justifyContent: 'center',
  },
  actionBtn: {
    flex: 1,
    backgroundColor: '#0A1929',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#1E3A5F',
    alignItems: 'center',
    paddingVertical: 8,
    gap: 3,
  },
  actionBtnDisabled: { opacity: 0.35 },
  actionBtnIcon: { fontSize: 18 },
  actionBtnLabel: { color: '#64748B', fontSize: 11, fontWeight: '600' },

  searchRow: {
    flexDirection: 'row',
    gap: 8,
    alignItems: 'center',
  },
  searchInput: {
    flex: 1,
    backgroundColor: '#0A1929',
    borderWidth: 1,
    borderColor: '#1E3A5F',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    color: '#E2E8F0',
    fontSize: 13,
  },
  searchBtn: {
    backgroundColor: '#003D40',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#00ADB5',
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  searchBtnText: { fontSize: 16 },

  // Log strip
  logStrip: {
    backgroundColor: '#020912',
    paddingHorizontal: 16,
    paddingVertical: 5,
    paddingBottom: Platform.OS === 'android' ? 20 : 8,
    borderTopWidth: 1,
    borderTopColor: '#0A1929',
  },
  logText: {
    color: '#1E3A5F',
    fontSize: 11,
    fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier',
  },
});
