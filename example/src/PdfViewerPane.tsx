/**
 * PdfViewerPane.tsx
 *
 * Continuous scroll LiquidText-style PDF viewer panel.
 *
 * Features:
 *  - Continuous vertical scroll: lazy loads pages as the user scrolls downwards
 *  - Accurate text selection: touch & tap word detection using true PDF point coordinates
 *  - Image & Figure selection mode: crop/select any diagram, figure, or table with instant high-res crop extraction
 *  - Drag & Drop to canvas: hold and drag any selected text or cropped figure directly onto the Canvas
 *  - Floating page indicator pill showing "Page X of N"
 *  - Non-intrusive action bar with Add to Canvas, Drag, Snapshot, and Color Swatch
 *  - Document search with match navigation
 */

import { useState, useCallback, useEffect, useRef, useMemo, memo } from 'react';
import {
  View,
  Text,
  Image,
  FlatList,
  TouchableOpacity,
  TextInput,
  ActivityIndicator,
  StyleSheet,
  Animated,
  Platform,
  PanResponder,
  type LayoutChangeEvent,
} from 'react-native';
import { PdfEngine } from 'thinkspace';
import type {
  PdfDocumentInfo,
  PdfWord,
  PdfRenderedPage,
  PdfPageAnalysis,
  PdfSearchResult,
} from 'thinkspace';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface PdfViewerPaneProps {
  documentId: string;
  docInfo: PdfDocumentInfo;
  accentColor?: string;
  onAddTextExcerpt: (text: string, pageNumber: number, color: string) => void;
  onAddImageExcerpt: (
    imageUri: string,
    pageNumber: number,
    width: number,
    height: number
  ) => void;
  onStartDragText?: (
    text: string,
    pageNumber: number,
    color: string,
    startX: number,
    startY: number
  ) => void;
  onStartDragImage?: (
    imageUri: string,
    pageNumber: number,
    width: number,
    height: number,
    startX: number,
    startY: number
  ) => void;
  onClose: () => void;
}

export interface PageCacheEntry {
  rendered: PdfRenderedPage;
  words: PdfWord[];
  layout?: PdfPageAnalysis;
}

export interface SelectionState {
  pageIndex: number;
  selStart: number;
  selEnd: number;
}

export interface CropBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const RENDER_SCALE = 1.8;
const EXCERPT_COLORS = [
  '#00ADB5',
  '#3B82F6',
  '#F59E0B',
  '#EF4444',
  '#8B5CF6',
  '#10B981',
];

// ─── Individual Page Item ─────────────────────────────────────────────────────

interface PageItemProps {
  documentId: string;
  pageIndex: number;
  pageCount: number;
  cachedEntry?: PageCacheEntry;
  onPageLoaded: (pageIndex: number, entry: PageCacheEntry) => void;
  selection: SelectionState | null;
  onSelectWord: (pageIndex: number, wordIndex: number) => void;
  onDoubleTapWord: (pageIndex: number, wordIndex: number) => void;
  pageSearchResults: PdfSearchResult[];
  onSnapshot: (pageIndex: number) => void;
  currentColor: string;
  activeMode: 'text' | 'crop';
  onExtractCrop: (
    pageIndex: number,
    crop: CropBox,
    imageLayout: { width: number; height: number },
    pageDims: { width: number; height: number }
  ) => void;
  onStartDragCrop?: (
    pageIndex: number,
    crop: CropBox,
    imageLayout: { width: number; height: number },
    pageDims: { width: number; height: number },
    startX: number,
    startY: number
  ) => void;
}

const PageItem = memo(function PageItemComponent({
  documentId,
  pageIndex,
  pageCount,
  cachedEntry,
  onPageLoaded,
  selection,
  onSelectWord,
  onDoubleTapWord,
  pageSearchResults,
  onSnapshot,
  currentColor,
  activeMode,
  onExtractCrop,
  onStartDragCrop,
}: PageItemProps) {
  const [entry, setEntry] = useState<PageCacheEntry | null>(
    cachedEntry ?? null
  );
  const [loading, setLoading] = useState(!cachedEntry);
  const [imageLayout, setImageLayout] = useState({ width: 0, height: 0 });

  // Crop selection on this page
  const [cropBox, setCropBox] = useState<CropBox | null>(null);
  const [isCropping, setIsCropping] = useState(false);
  const cropStart = useRef({ x: 0, y: 0 });

  useEffect(() => {
    if (cachedEntry) {
      setEntry(cachedEntry);
      setLoading(false);
      return;
    }

    let isMounted = true;
    setLoading(true);

    Promise.all([
      PdfEngine.renderPage(documentId, pageIndex, RENDER_SCALE),
      PdfEngine.extractText(documentId, pageIndex),
    ])
      .then(([rendered, textRes]) => {
        if (!isMounted) return;
        const newEntry: PageCacheEntry = {
          rendered,
          words: textRes.words,
        };
        setEntry(newEntry);
        onPageLoaded(pageIndex, newEntry);
      })
      .catch((err) => {
        console.warn(`[PdfViewerPane] Failed to load page ${pageIndex}:`, err);
      })
      .finally(() => {
        if (isMounted) setLoading(false);
      });

    return () => {
      isMounted = false;
    };
  }, [documentId, pageIndex, cachedEntry, onPageLoaded]);

  const handleImageLayout = useCallback((e: LayoutChangeEvent) => {
    const { width, height } = e.nativeEvent.layout;
    setImageLayout({ width, height });
  }, []);

  const rendered = entry?.rendered;
  const words = useMemo(() => entry?.words ?? [], [entry?.words]);
  const hasLayout = imageLayout.width > 0 && !!rendered;

  // True PDF unscaled dimensions in points (72 DPI)
  const pdfPointsWidth =
    rendered?.pageWidth ||
    (rendered?.width ? rendered.width / (rendered.scale || RENDER_SCALE) : 1);
  const pdfPointsHeight =
    rendered?.pageHeight ||
    (rendered?.height ? rendered.height / (rendered.scale || RENDER_SCALE) : 1);

  const scaleX = hasLayout ? imageLayout.width / pdfPointsWidth : 1;
  const scaleY = hasLayout ? imageLayout.height / pdfPointsHeight : 1;

  // Crop PanResponder
  const cropPanResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => activeMode === 'crop',
      onMoveShouldSetPanResponder: () => activeMode === 'crop',
      onPanResponderGrant: (evt) => {
        const { locationX, locationY } = evt.nativeEvent;
        cropStart.current = { x: locationX, y: locationY };
        setCropBox({ left: locationX, top: locationY, width: 0, height: 0 });
        setIsCropping(true);
      },
      onPanResponderMove: (evt) => {
        const { locationX, locationY } = evt.nativeEvent;
        const x1 = Math.min(cropStart.current.x, locationX);
        const y1 = Math.min(cropStart.current.y, locationY);
        const w = Math.abs(locationX - cropStart.current.x);
        const h = Math.abs(locationY - cropStart.current.y);
        setCropBox({ left: x1, top: y1, width: w, height: h });
      },
      onPanResponderRelease: () => {
        setIsCropping(false);
      },
    })
  ).current;

  // Tap helper on page to find closest word
  const handlePagePress = useCallback(
    (evt: any) => {
      if (activeMode !== 'text' || words.length === 0 || !hasLayout) return;
      const { locationX, locationY } = evt.nativeEvent;
      const pdfX = locationX / scaleX;
      const pdfY = locationY / scaleY;

      // Find word matching bounds
      for (let i = 0; i < words.length; i++) {
        const b = words[i]!.bounds;
        if (
          pdfX >= b.left - 6 &&
          pdfX <= b.right + 6 &&
          pdfY >= b.top - 8 &&
          pdfY <= b.bottom + 8
        ) {
          onSelectWord(pageIndex, i);
          return;
        }
      }
    },
    [activeMode, words, hasLayout, scaleX, scaleY, pageIndex, onSelectWord]
  );

  return (
    <View style={styles.pageCard}>
      {/* Page Header */}
      <View style={styles.pageHeaderRow}>
        <View style={styles.pageNumberBadge}>
          <Text style={styles.pageNumberBadgeText}>
            PAGE {pageIndex + 1}{' '}
            <Text style={styles.pageNumberBadgeTotal}>/ {pageCount}</Text>
          </Text>
        </View>

        <View style={styles.pageHeaderActions}>
          <TouchableOpacity
            style={styles.pageSnapshotBtn}
            onPress={() => onSnapshot(pageIndex)}
            disabled={!rendered || loading}
            activeOpacity={0.7}
          >
            <Text style={styles.pageSnapshotIcon}>📷</Text>
            <Text style={styles.pageSnapshotLabel}>Snapshot</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Page Content Body */}
      {loading ? (
        <View style={styles.pageLoadingContainer}>
          <ActivityIndicator size="small" color="#00ADB5" />
          <Text style={styles.pageLoadingText}>
            Rendering page {pageIndex + 1}…
          </Text>
        </View>
      ) : rendered ? (
        <View
          style={styles.pageImageWrapper}
          {...(activeMode === 'crop' ? cropPanResponder.panHandlers : {})}
          onTouchEnd={activeMode === 'text' ? handlePagePress : undefined}
        >
          <Image
            source={{ uri: rendered.uri }}
            style={[
              styles.pageImage,
              { aspectRatio: rendered.width / rendered.height },
            ]}
            resizeMode="contain"
            onLayout={handleImageLayout}
          />

          {/* Word touch overlays for selection */}
          {hasLayout &&
            activeMode === 'text' &&
            words.map((word, wIdx) => {
              const isSelected =
                selection?.pageIndex === pageIndex &&
                wIdx >= Math.min(selection.selStart, selection.selEnd) &&
                wIdx <= Math.max(selection.selStart, selection.selEnd);

              const isMatched = pageSearchResults.some(
                (r) =>
                  word.bounds.left >= r.bounds.left - 2 &&
                  word.bounds.right <= r.bounds.right + 2 &&
                  word.bounds.top >= r.bounds.top - 2 &&
                  word.bounds.bottom <= r.bounds.bottom + 2
              );

              const left = word.bounds.left * scaleX;
              const top = word.bounds.top * scaleY;
              const width = Math.max(
                (word.bounds.right - word.bounds.left) * scaleX,
                6
              );
              const height = Math.max(
                (word.bounds.bottom - word.bounds.top) * scaleY,
                12
              );

              return (
                <TouchableOpacity
                  key={wIdx}
                  style={[
                    styles.wordOverlay,
                    { left, top, width, height },
                    isSelected && [
                      styles.wordOverlaySelected,
                      {
                        backgroundColor: currentColor + '40',
                        borderColor: currentColor,
                      },
                    ],
                    isMatched && !isSelected && styles.wordOverlayMatched,
                  ]}
                  onPress={() => onSelectWord(pageIndex, wIdx)}
                  onLongPress={() => onDoubleTapWord(pageIndex, wIdx)}
                  activeOpacity={0.6}
                  hitSlop={{ top: 4, bottom: 4, left: 2, right: 2 }}
                />
              );
            })}

          {/* Active Crop Box (Figure / Image Extraction) */}
          {cropBox && cropBox.width > 10 && cropBox.height > 10 && (
            <View
              style={[
                styles.cropOverlayBox,
                {
                  left: cropBox.left,
                  top: cropBox.top,
                  width: cropBox.width,
                  height: cropBox.height,
                },
              ]}
              pointerEvents="box-none"
            >
              <View style={styles.cropCornerTL} />
              <View style={styles.cropCornerTR} />
              <View style={styles.cropCornerBL} />
              <View style={styles.cropCornerBR} />

              {!isCropping && (
                <View style={styles.cropActionBadge}>
                  <TouchableOpacity
                    style={styles.cropExtractBtn}
                    onPress={() =>
                      onExtractCrop(pageIndex, cropBox, imageLayout, {
                        width: pdfPointsWidth,
                        height: pdfPointsHeight,
                      })
                    }
                    activeOpacity={0.8}
                  >
                    <Text style={styles.cropExtractBtnText}>📷 Add Figure</Text>
                  </TouchableOpacity>

                  {onStartDragCrop && (
                    <TouchableOpacity
                      style={styles.cropDragBtn}
                      onPress={(e) => {
                        const { pageX, pageY } = e.nativeEvent;
                        onStartDragCrop(
                          pageIndex,
                          cropBox,
                          imageLayout,
                          { width: pdfPointsWidth, height: pdfPointsHeight },
                          pageX,
                          pageY
                        );
                      }}
                      activeOpacity={0.8}
                    >
                      <Text style={styles.cropDragBtnText}>✋ Drag</Text>
                    </TouchableOpacity>
                  )}

                  <TouchableOpacity
                    style={styles.cropCancelBtn}
                    onPress={() => setCropBox(null)}
                    activeOpacity={0.8}
                  >
                    <Text style={styles.cropCancelBtnText}>✕</Text>
                  </TouchableOpacity>
                </View>
              )}
            </View>
          )}
        </View>
      ) : (
        <View style={styles.pageLoadingContainer}>
          <Text style={styles.pageLoadingText}>Failed to render page</Text>
        </View>
      )}
    </View>
  );
});

// ─── Main PdfViewerPane Component ─────────────────────────────────────────────

export default function PdfViewerPane({
  documentId,
  docInfo,
  accentColor = '#00ADB5',
  onAddTextExcerpt,
  onAddImageExcerpt,
  onStartDragText,
  onStartDragImage,
  onClose,
}: PdfViewerPaneProps) {
  // Page cache (survives scrolling)
  const pageCache = useRef<Map<number, PageCacheEntry>>(new Map());
  const layoutCache = useRef<Map<number, PdfPageAnalysis>>(new Map());
  const flatListRef = useRef<FlatList<number>>(null);

  // Active interaction mode: 'text' (tap/select text) or 'crop' (draw rectangle to crop images/figures)
  const [activeMode, setActiveMode] = useState<'text' | 'crop'>('text');

  // Visible page tracker (for floating indicator)
  const [visiblePageIndex, setVisiblePageIndex] = useState(0);

  // Selection state
  const [selection, setSelection] = useState<SelectionState | null>(null);

  // Search state
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<PdfSearchResult[]>([]);
  const [searchVisible, setSearchVisible] = useState(false);
  const [currentMatchIdx, setCurrentMatchIdx] = useState(0);

  // Color picker
  const [colorIdx, setColorIdx] = useState(0);
  const currentColor = EXCERPT_COLORS[colorIdx] ?? accentColor;

  // Action bar animation
  const actionBarAnim = useRef(new Animated.Value(0)).current;
  const hasSelection = selection !== null;

  useEffect(() => {
    Animated.spring(actionBarAnim, {
      toValue: hasSelection ? 1 : 0,
      useNativeDriver: true,
      tension: 80,
      friction: 10,
    }).start();
  }, [hasSelection, actionBarAnim]);

  // Page loaded callback
  const handlePageLoaded = useCallback(
    (pageIndex: number, entry: PageCacheEntry) => {
      pageCache.current.set(pageIndex, entry);
    },
    []
  );

  // Word tap selection
  const handleSelectWord = useCallback(
    (pageIndex: number, wordIndex: number) => {
      setSelection((prev) => {
        if (!prev || prev.pageIndex !== pageIndex) {
          return { pageIndex, selStart: wordIndex, selEnd: wordIndex };
        }
        if (prev.selStart === wordIndex && prev.selEnd === wordIndex) {
          return null; // deselect if tapping same single word
        }
        return { pageIndex, selStart: prev.selStart, selEnd: wordIndex };
      });
    },
    []
  );

  // Long-press / double tap word: select whole paragraph block
  const handleDoubleTapWord = useCallback(
    async (pageIndex: number, wordIndex: number) => {
      let layout = layoutCache.current.get(pageIndex);
      if (!layout) {
        try {
          layout = await PdfEngine.analyzePage(documentId, pageIndex);
          layoutCache.current.set(pageIndex, layout);
        } catch {
          // fallback
        }
      }

      const entry = pageCache.current.get(pageIndex);
      if (!entry) {
        setSelection({ pageIndex, selStart: wordIndex, selEnd: wordIndex });
        return;
      }

      const tappedWord = entry.words[wordIndex];
      if (!tappedWord) return;

      if (layout) {
        for (const block of layout.blocks) {
          const b = block.bounds;
          if (
            tappedWord.bounds.left >= b.left - 2 &&
            tappedWord.bounds.right <= b.right + 2 &&
            tappedWord.bounds.top >= b.top - 2 &&
            tappedWord.bounds.bottom <= b.bottom + 2
          ) {
            let lo = wordIndex;
            let hi = wordIndex;
            for (let i = 0; i < entry.words.length; i++) {
              const w = entry.words[i]!;
              if (
                w.bounds.left >= b.left - 4 &&
                w.bounds.right <= b.right + 4 &&
                w.bounds.top >= b.top - 4 &&
                w.bounds.bottom <= b.bottom + 4
              ) {
                if (i < lo) lo = i;
                if (i > hi) hi = i;
              }
            }
            setSelection({ pageIndex, selStart: lo, selEnd: hi });
            return;
          }
        }
      }

      setSelection({ pageIndex, selStart: wordIndex, selEnd: wordIndex });
    },
    [documentId]
  );

  // Snapshot handler (full page)
  const handleSnapshot = useCallback(
    async (pageIndex: number) => {
      let entry = pageCache.current.get(pageIndex);
      if (!entry) {
        try {
          const rendered = await PdfEngine.renderPage(
            documentId,
            pageIndex,
            2.0
          );
          onAddImageExcerpt(
            rendered.uri,
            pageIndex + 1,
            rendered.width,
            rendered.height
          );
        } catch (e) {
          console.warn('[PdfViewerPane] Snapshot failed', e);
        }
        return;
      }
      onAddImageExcerpt(
        entry.rendered.uri,
        pageIndex + 1,
        entry.rendered.width,
        entry.rendered.height
      );
    },
    [documentId, onAddImageExcerpt]
  );

  // Extract crop figure / region
  const handleExtractCrop = useCallback(
    async (
      pageIndex: number,
      crop: CropBox,
      imageLayout: { width: number; height: number },
      pageDims: { width: number; height: number }
    ) => {
      try {
        const scaleX = imageLayout.width / pageDims.width;
        const scaleY = imageLayout.height / pageDims.height;
        const pdfLeft = crop.left / scaleX;
        const pdfTop = crop.top / scaleY;
        const pdfRight = (crop.left + crop.width) / scaleX;
        const pdfBottom = (crop.top + crop.height) / scaleY;

        const cropped = await PdfEngine.renderPageRegion(
          documentId,
          pageIndex,
          pdfLeft,
          pdfTop,
          pdfRight,
          pdfBottom,
          2.5
        );
        onAddImageExcerpt(
          cropped.uri,
          pageIndex + 1,
          cropped.width,
          cropped.height
        );
      } catch (e) {
        console.warn(
          '[PdfViewerPane] Crop failed, falling back to full page snapshot',
          e
        );
        handleSnapshot(pageIndex);
      }
    },
    [documentId, onAddImageExcerpt, handleSnapshot]
  );

  // Start dragging a cropped figure
  const handleStartDragCrop = useCallback(
    async (
      pageIndex: number,
      crop: CropBox,
      imageLayout: { width: number; height: number },
      pageDims: { width: number; height: number },
      startX: number,
      startY: number
    ) => {
      if (!onStartDragImage) return;
      try {
        const scaleX = imageLayout.width / pageDims.width;
        const scaleY = imageLayout.height / pageDims.height;
        const pdfLeft = crop.left / scaleX;
        const pdfTop = crop.top / scaleY;
        const pdfRight = (crop.left + crop.width) / scaleX;
        const pdfBottom = (crop.top + crop.height) / scaleY;

        const cropped = await PdfEngine.renderPageRegion(
          documentId,
          pageIndex,
          pdfLeft,
          pdfTop,
          pdfRight,
          pdfBottom,
          2.0
        );
        onStartDragImage(
          cropped.uri,
          pageIndex + 1,
          cropped.width,
          cropped.height,
          startX,
          startY
        );
      } catch (e) {
        console.warn('[PdfViewerPane] startDragCrop failed', e);
      }
    },
    [documentId, onStartDragImage]
  );

  // Get selected text string
  const getSelectedText = useCallback(() => {
    if (!selection) return '';
    const entry = pageCache.current.get(selection.pageIndex);
    if (!entry) return '';
    const lo = Math.min(selection.selStart, selection.selEnd);
    const hi = Math.max(selection.selStart, selection.selEnd);
    return entry.words
      .slice(lo, hi + 1)
      .map((w) => w.text)
      .join(' ');
  }, [selection]);

  // Add text excerpt to canvas
  const handleAddText = useCallback(() => {
    if (!selection) return;
    const text = getSelectedText();
    if (!text.trim()) return;
    onAddTextExcerpt(text, selection.pageIndex + 1, currentColor);
    setSelection(null);
  }, [selection, getSelectedText, onAddTextExcerpt, currentColor]);

  // Search logic
  const handleSearch = useCallback(async () => {
    if (!searchQuery.trim()) {
      setSearchResults([]);
      return;
    }
    try {
      const results = await PdfEngine.searchDocument(
        documentId,
        searchQuery.trim()
      );
      setSearchResults(results);
      setCurrentMatchIdx(0);
      if (results.length > 0 && results[0]) {
        flatListRef.current?.scrollToIndex({
          index: results[0].pageIndex,
          animated: true,
        });
      }
    } catch (e) {
      console.warn('[PdfViewerPane] search failed', e);
    }
  }, [documentId, searchQuery]);

  const handleNextMatch = useCallback(() => {
    if (searchResults.length === 0) return;
    const nextIdx = (currentMatchIdx + 1) % searchResults.length;
    setCurrentMatchIdx(nextIdx);
    const match = searchResults[nextIdx];
    if (match) {
      flatListRef.current?.scrollToIndex({
        index: match.pageIndex,
        animated: true,
      });
    }
  }, [currentMatchIdx, searchResults]);

  // Visible items tracking
  const viewabilityConfig = useRef({
    itemVisiblePercentThreshold: 35,
  }).current;

  const onViewableItemsChanged = useRef(({ viewableItems }: any) => {
    if (viewableItems && viewableItems.length > 0) {
      const topItem = viewableItems[0];
      if (topItem && typeof topItem.index === 'number') {
        setVisiblePageIndex(topItem.index);
      }
    }
  }).current;

  const pageIndices = useRef(
    Array.from({ length: docInfo.pageCount }, (_, i) => i)
  ).current;

  const actionBarTranslate = actionBarAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [110, 0],
  });

  const selectedText = getSelectedText();

  return (
    <View style={styles.container}>
      {/* ── PANE HEADER ──────────────────────────────────────────── */}
      <View style={styles.paneHeader}>
        <View style={styles.paneHeaderLeft}>
          <View style={styles.docBadge}>
            <Text style={styles.docBadgeText}>PDF</Text>
          </View>
          <Text style={styles.paneTitle} numberOfLines={1}>
            {docInfo.title || 'Document'}
          </Text>
          <Text style={styles.panePageCountTag}>
            {docInfo.pageCount} {docInfo.pageCount === 1 ? 'page' : 'pages'}
          </Text>
        </View>

        {/* Mode Selector: Text Mode vs Crop/Figure Mode */}
        <View style={styles.modeToggleRow}>
          <TouchableOpacity
            style={[
              styles.modeToggleBtn,
              activeMode === 'text' && styles.modeToggleBtnActive,
            ]}
            onPress={() => setActiveMode('text')}
            activeOpacity={0.7}
          >
            <Text
              style={[
                styles.modeToggleText,
                activeMode === 'text' && styles.modeToggleTextActive,
              ]}
            >
              📝 Text
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[
              styles.modeToggleBtn,
              activeMode === 'crop' && styles.modeToggleBtnActive,
            ]}
            onPress={() => {
              setActiveMode('crop');
              setSelection(null);
            }}
            activeOpacity={0.7}
          >
            <Text
              style={[
                styles.modeToggleText,
                activeMode === 'crop' && styles.modeToggleTextActive,
              ]}
            >
              ✂️ Crop
            </Text>
          </TouchableOpacity>
        </View>

        <View style={styles.paneHeaderRight}>
          <TouchableOpacity
            style={[
              styles.headerIconBtn,
              searchVisible && styles.headerIconBtnActive,
            ]}
            onPress={() => setSearchVisible((v) => !v)}
            activeOpacity={0.7}
          >
            <Text style={styles.headerIconText}>🔍</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.closeBtn}
            onPress={onClose}
            activeOpacity={0.7}
          >
            <Text style={styles.closeBtnText}>✕</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* ── SEARCH BAR ───────────────────────────────────────────── */}
      {searchVisible && (
        <View style={styles.searchBar}>
          <TextInput
            style={styles.searchInput}
            placeholder="Search in PDF…"
            placeholderTextColor="#4B5563"
            value={searchQuery}
            onChangeText={setSearchQuery}
            onSubmitEditing={handleSearch}
            returnKeyType="search"
            autoFocus
          />
          <TouchableOpacity style={styles.searchGoBtn} onPress={handleSearch}>
            <Text style={styles.searchGoBtnText}>Search</Text>
          </TouchableOpacity>

          {searchResults.length > 0 && (
            <View style={styles.searchNavRow}>
              <Text style={styles.searchCount}>
                {currentMatchIdx + 1}/{searchResults.length}
              </Text>
              <TouchableOpacity
                style={styles.searchNavBtn}
                onPress={handleNextMatch}
              >
                <Text style={styles.searchNavBtnText}>Next ›</Text>
              </TouchableOpacity>
            </View>
          )}
        </View>
      )}

      {/* Crop Mode Banner */}
      {activeMode === 'crop' && (
        <View style={styles.cropModeBanner}>
          <Text style={styles.cropModeBannerText}>
            ✂️ Crop Mode Active: Drag on any page to select a figure, table, or
            diagram
          </Text>
        </View>
      )}

      {/* ── CONTINUOUS SCROLL PAGES LIST ─────────────────────────── */}
      <FlatList
        ref={flatListRef}
        data={pageIndices}
        keyExtractor={(item) => String(item)}
        renderItem={({ item: pIdx }) => (
          <PageItem
            documentId={documentId}
            pageIndex={pIdx}
            pageCount={docInfo.pageCount}
            cachedEntry={pageCache.current.get(pIdx)}
            onPageLoaded={handlePageLoaded}
            selection={selection}
            onSelectWord={handleSelectWord}
            onDoubleTapWord={handleDoubleTapWord}
            pageSearchResults={searchResults.filter(
              (r) => r.pageIndex === pIdx
            )}
            onSnapshot={handleSnapshot}
            currentColor={currentColor}
            activeMode={activeMode}
            onExtractCrop={handleExtractCrop}
            onStartDragCrop={handleStartDragCrop}
          />
        )}
        contentContainerStyle={styles.listContent}
        showsVerticalScrollIndicator={true}
        initialNumToRender={2}
        maxToRenderPerBatch={2}
        windowSize={5}
        removeClippedSubviews={Platform.OS === 'android'}
        viewabilityConfig={viewabilityConfig}
        onViewableItemsChanged={onViewableItemsChanged}
        scrollEnabled={activeMode === 'text'}
        onScrollToIndexFailed={(info) => {
          setTimeout(() => {
            flatListRef.current?.scrollToIndex({
              index: info.index,
              animated: true,
            });
          }, 100);
        }}
      />

      {/* ── FLOATING PAGE INDICATOR PILL ─────────────────────────── */}
      <View style={styles.floatingPagePill}>
        <Text style={styles.floatingPagePillText}>
          Page {visiblePageIndex + 1} of {docInfo.pageCount}
        </Text>
      </View>

      {/* ── SELECTION ACTION BAR (slides up on text selection) ───── */}
      <Animated.View
        style={[
          styles.actionBar,
          {
            transform: [{ translateY: actionBarTranslate }],
            opacity: actionBarAnim,
          },
        ]}
        pointerEvents={hasSelection ? 'auto' : 'none'}
      >
        {hasSelection && selection && (
          <>
            <View style={styles.actionHeader}>
              <View style={styles.selectionPageBadge}>
                <Text style={styles.selectionPageBadgeText}>
                  PAGE {selection.pageIndex + 1}
                </Text>
              </View>
              <Text style={styles.selectionPreview} numberOfLines={2}>
                "{selectedText.slice(0, 75)}
                {selectedText.length > 75 ? '…' : ''}"
              </Text>
              <TouchableOpacity
                style={styles.actionBtnClear}
                onPress={() => setSelection(null)}
                activeOpacity={0.8}
              >
                <Text style={styles.actionBtnClearText}>✕</Text>
              </TouchableOpacity>
            </View>

            <View style={styles.actionBtnRow}>
              {/* Add to Canvas button */}
              <TouchableOpacity
                style={[
                  styles.actionBtn,
                  {
                    backgroundColor: currentColor + '22',
                    borderColor: currentColor,
                  },
                ]}
                onPress={handleAddText}
                activeOpacity={0.8}
              >
                <Text style={styles.actionBtnIcon}>📋</Text>
                <Text style={[styles.actionBtnLabel, { color: currentColor }]}>
                  Add Text
                </Text>
              </TouchableOpacity>

              {/* Hold & Drag button */}
              {onStartDragText && (
                <TouchableOpacity
                  style={[
                    styles.actionBtn,
                    { backgroundColor: '#0A1929', borderColor: '#1E3A5F' },
                  ]}
                  onPress={(e) => {
                    const { pageX, pageY } = e.nativeEvent;
                    onStartDragText(
                      selectedText,
                      selection.pageIndex + 1,
                      currentColor,
                      pageX,
                      pageY
                    );
                    setSelection(null);
                  }}
                  activeOpacity={0.8}
                >
                  <Text style={styles.actionBtnIcon}>✋</Text>
                  <Text style={[styles.actionBtnLabel, { color: '#38BDF8' }]}>
                    Drag
                  </Text>
                </TouchableOpacity>
              )}

              {/* Snapshot button */}
              <TouchableOpacity
                style={styles.actionBtnSecondary}
                onPress={() => handleSnapshot(selection.pageIndex)}
                activeOpacity={0.8}
              >
                <Text style={styles.actionBtnIcon}>📷</Text>
                <Text style={styles.actionBtnLabelSecondary}>Snapshot</Text>
              </TouchableOpacity>

              {/* Color swatch picker */}
              <TouchableOpacity
                style={[styles.colorSwatch, { backgroundColor: currentColor }]}
                onPress={() =>
                  setColorIdx((i) => (i + 1) % EXCERPT_COLORS.length)
                }
                activeOpacity={0.8}
              />
            </View>
          </>
        )}
      </Animated.View>
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#07111E',
    position: 'relative',
  },

  // Header
  paneHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingVertical: 7,
    backgroundColor: '#050E1A',
    borderBottomWidth: 1,
    borderBottomColor: '#0F2036',
    gap: 8,
  },
  paneHeaderLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    flex: 1,
  },
  docBadge: {
    backgroundColor: '#00ADB5',
    borderRadius: 4,
    paddingHorizontal: 4,
    paddingVertical: 1,
  },
  docBadgeText: {
    color: '#fff',
    fontSize: 9,
    fontWeight: '800',
    letterSpacing: 0.8,
  },
  paneTitle: {
    color: '#E2E8F0',
    fontSize: 12,
    fontWeight: '600',
    maxWidth: 110,
  },
  panePageCountTag: {
    color: '#64748B',
    fontSize: 10,
    fontWeight: '500',
  },
  modeToggleRow: {
    flexDirection: 'row',
    backgroundColor: '#0A1929',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#1E3A5F',
    padding: 2,
    gap: 2,
  },
  modeToggleBtn: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
  },
  modeToggleBtnActive: {
    backgroundColor: '#00ADB5',
  },
  modeToggleText: {
    color: '#64748B',
    fontSize: 11,
    fontWeight: '600',
  },
  modeToggleTextActive: {
    color: '#FFFFFF',
    fontWeight: '700',
  },
  paneHeaderRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  headerIconBtn: {
    width: 28,
    height: 28,
    borderRadius: 7,
    backgroundColor: '#0A1929',
    borderWidth: 1,
    borderColor: '#1E3A5F',
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerIconBtnActive: {
    borderColor: '#00ADB5',
    backgroundColor: '#003D40',
  },
  headerIconText: { fontSize: 12 },
  closeBtn: {
    width: 26,
    height: 26,
    borderRadius: 13,
    backgroundColor: '#1E3A5F',
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeBtnText: { color: '#94A3B8', fontSize: 12, fontWeight: '700' },

  // Search bar
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 5,
    gap: 6,
    backgroundColor: '#050E1A',
    borderBottomWidth: 1,
    borderBottomColor: '#0F2036',
  },
  searchInput: {
    flex: 1,
    backgroundColor: '#0A1929',
    borderWidth: 1,
    borderColor: '#1E3A5F',
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 4,
    color: '#E2E8F0',
    fontSize: 12,
  },
  searchGoBtn: {
    backgroundColor: '#00ADB5',
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  searchGoBtnText: { color: '#fff', fontSize: 11, fontWeight: '700' },
  searchNavRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  searchCount: {
    color: '#00ADB5',
    fontSize: 11,
    fontWeight: '700',
  },
  searchNavBtn: {
    backgroundColor: '#0A1929',
    borderWidth: 1,
    borderColor: '#1E3A5F',
    borderRadius: 5,
    paddingHorizontal: 6,
    paddingVertical: 4,
  },
  searchNavBtnText: {
    color: '#94A3B8',
    fontSize: 10,
    fontWeight: '600',
  },

  // Crop banner
  cropModeBanner: {
    backgroundColor: '#003D40',
    paddingVertical: 4,
    paddingHorizontal: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#00ADB5',
  },
  cropModeBannerText: {
    color: '#E0F2FE',
    fontSize: 11,
    fontWeight: '600',
    textAlign: 'center',
  },

  // FlatList content
  listContent: {
    paddingVertical: 8,
    paddingHorizontal: 6,
    gap: 10,
  },

  // Page Card
  pageCard: {
    backgroundColor: '#0A1929',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#132840',
    overflow: 'hidden',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 6,
    elevation: 4,
  },
  pageHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 8,
    paddingVertical: 4,
    backgroundColor: '#061322',
    borderBottomWidth: 1,
    borderBottomColor: '#0F2036',
  },
  pageNumberBadge: {
    backgroundColor: '#0F2742',
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
  },
  pageNumberBadgeText: {
    color: '#93C5FD',
    fontSize: 9,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  pageNumberBadgeTotal: {
    color: '#475569',
    fontWeight: '400',
  },
  pageHeaderActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  pageSnapshotBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
    paddingHorizontal: 6,
    paddingVertical: 2,
    backgroundColor: '#0B2238',
    borderRadius: 5,
    borderWidth: 1,
    borderColor: '#1E3A5F',
  },
  pageSnapshotIcon: { fontSize: 10 },
  pageSnapshotLabel: {
    color: '#94A3B8',
    fontSize: 9,
    fontWeight: '600',
  },
  pageLoadingContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 45,
    gap: 8,
  },
  pageLoadingText: {
    color: '#64748B',
    fontSize: 11,
  },
  pageImageWrapper: {
    position: 'relative',
    width: '100%',
    backgroundColor: '#ffffff',
  },
  pageImage: {
    width: '100%',
    backgroundColor: '#fff',
  },

  // Word overlays
  wordOverlay: {
    position: 'absolute',
    borderRadius: 2,
  },
  wordOverlaySelected: {
    borderWidth: 1,
  },
  wordOverlayMatched: {
    backgroundColor: 'rgba(245, 158, 11, 0.35)',
    borderWidth: 0.8,
    borderColor: 'rgba(245, 158, 11, 0.7)',
  },

  // Crop overlay box
  cropOverlayBox: {
    position: 'absolute',
    borderWidth: 2,
    borderColor: '#00ADB5',
    borderStyle: 'dashed',
    backgroundColor: 'rgba(0, 173, 181, 0.15)',
  },
  cropCornerTL: {
    position: 'absolute',
    top: -4,
    left: -4,
    width: 8,
    height: 8,
    backgroundColor: '#00ADB5',
    borderRadius: 2,
  },
  cropCornerTR: {
    position: 'absolute',
    top: -4,
    right: -4,
    width: 8,
    height: 8,
    backgroundColor: '#00ADB5',
    borderRadius: 2,
  },
  cropCornerBL: {
    position: 'absolute',
    bottom: -4,
    left: -4,
    width: 8,
    height: 8,
    backgroundColor: '#00ADB5',
    borderRadius: 2,
  },
  cropCornerBR: {
    position: 'absolute',
    bottom: -4,
    right: -4,
    width: 8,
    height: 8,
    backgroundColor: '#00ADB5',
    borderRadius: 2,
  },
  cropActionBadge: {
    position: 'absolute',
    bottom: -32,
    left: 0,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: '#050E1A',
    borderRadius: 6,
    padding: 3,
    borderWidth: 1,
    borderColor: '#1E3A5F',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.5,
    shadowRadius: 4,
    elevation: 8,
  },
  cropExtractBtn: {
    backgroundColor: '#00ADB5',
    borderRadius: 4,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  cropExtractBtnText: {
    color: '#fff',
    fontSize: 10,
    fontWeight: '700',
  },
  cropDragBtn: {
    backgroundColor: '#0A1929',
    borderWidth: 1,
    borderColor: '#1E3A5F',
    borderRadius: 4,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  cropDragBtnText: {
    color: '#38BDF8',
    fontSize: 10,
    fontWeight: '700',
  },
  cropCancelBtn: {
    paddingHorizontal: 6,
    paddingVertical: 3,
  },
  cropCancelBtnText: {
    color: '#EF4444',
    fontSize: 11,
    fontWeight: '800',
  },

  // Floating Page Pill
  floatingPagePill: {
    position: 'absolute',
    bottom: 12,
    right: 12,
    backgroundColor: 'rgba(5, 14, 26, 0.88)',
    borderWidth: 1,
    borderColor: '#1E3A5F',
    borderRadius: 16,
    paddingHorizontal: 10,
    paddingVertical: 5,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.5,
    shadowRadius: 4,
    elevation: 8,
    pointerEvents: 'none',
  },
  floatingPagePillText: {
    color: '#93C5FD',
    fontSize: 10,
    fontWeight: '700',
  },

  // Selection Action Bar
  actionBar: {
    backgroundColor: '#050E1A',
    borderTopWidth: 1.5,
    borderTopColor: '#00ADB5',
    paddingHorizontal: 10,
    paddingTop: 8,
    paddingBottom: 8,
    gap: 6,
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -3 },
    shadowOpacity: 0.5,
    shadowRadius: 8,
    elevation: 16,
    zIndex: 999,
  },
  actionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  selectionPageBadge: {
    backgroundColor: '#003D40',
    borderWidth: 1,
    borderColor: '#00ADB5',
    borderRadius: 4,
    paddingHorizontal: 5,
    paddingVertical: 1,
  },
  selectionPageBadgeText: {
    color: '#00ADB5',
    fontSize: 9,
    fontWeight: '800',
  },
  selectionPreview: {
    flex: 1,
    color: '#94A3B8',
    fontSize: 11,
    fontStyle: 'italic',
    lineHeight: 14,
  },
  actionBtnClear: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: '#1F0A0A',
    borderWidth: 1,
    borderColor: '#3B0A0A',
    alignItems: 'center',
    justifyContent: 'center',
  },
  actionBtnClearText: {
    color: '#EF4444',
    fontSize: 11,
    fontWeight: '700',
  },
  actionBtnRow: {
    flexDirection: 'row',
    gap: 6,
    alignItems: 'center',
  },
  actionBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 5,
    paddingVertical: 7,
    borderRadius: 7,
    borderWidth: 1,
  },
  actionBtnIcon: { fontSize: 13 },
  actionBtnLabel: { fontSize: 11, fontWeight: '700' },
  actionBtnSecondary: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: 7,
    backgroundColor: '#0A1929',
    borderWidth: 1,
    borderColor: '#1E3A5F',
  },
  actionBtnLabelSecondary: {
    color: '#64748B',
    fontSize: 11,
    fontWeight: '600',
  },
  colorSwatch: {
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 2,
    borderColor: '#1E3A5F',
  },
});
