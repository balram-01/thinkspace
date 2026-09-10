import React, { useRef, useEffect } from 'react';
import {
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  StyleSheet,
} from 'react-native';
import type { DocumentViewerProps } from './types';

export const DocumentViewer: React.FC<DocumentViewerProps> = ({
  document,
  annotations = [],
  isSqueezed = false,
  searchQuery = '',
  targetPage,
  onHighlightPassage,
  onExtractPassage,
  onStartLiftDrag,
  onPageChange,
}) => {
  const scrollRef = useRef<any>(null);
  const pageOffsetsRef = useRef<Record<number, number>>({});

  // Scroll to target page when triggered by clicking an excerpt card on canvas
  useEffect(() => {
    if (targetPage && pageOffsetsRef.current[targetPage] !== undefined) {
      const y = pageOffsetsRef.current[targetPage] || 0;
      scrollRef.current?.scrollTo({ y, animated: true });
      onPageChange?.(targetPage);
    }
  }, [targetPage, onPageChange]);

  const highlightColors = [
    '#00ADB5',
    '#F59E0B',
    '#EF4444',
    '#3B82F6',
    '#10B981',
  ];

  return (
    <View style={styles.container}>
      {/* Top Document Header Bar */}
      <View style={styles.header}>
        <View style={styles.docInfo}>
          <Text style={styles.docTitle} numberOfLines={1}>
            📄 {document.title}
          </Text>
          <Text style={styles.docMeta}>
            {document.pageCount} Pages • {annotations.length} Highlights
          </Text>
        </View>

        {isSqueezed && (
          <View style={styles.squeezedBadge}>
            <Text style={styles.squeezedBadgeText}>🪗 Accordion Squeezed</Text>
          </View>
        )}
      </View>

      {/* Main Document Content ScrollView */}
      <ScrollView
        ref={scrollRef}
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={true}
      >
        {document.sections.map((sec) => {
          const sectionAnnotations = annotations.filter(
            (a) => a.sectionId === sec.id
          );
          const hasHighlights = sectionAnnotations.length > 0;

          // If Accordion Squeeze is enabled and section has no highlights, show collapsed banner
          if (isSqueezed && !hasHighlights && !sec.tables && !sec.imageUrl) {
            return (
              <View key={sec.id} style={styles.squeezedDivider}>
                <View style={styles.squeezedLine} />
                <Text style={styles.squeezedText}>
                  ── Page {sec.pageNumber}: {sec.heading} (Folded) ──
                </Text>
                <View style={styles.squeezedLine} />
              </View>
            );
          }

          return (
            <View
              key={sec.id}
              style={styles.pageCard}
              onLayout={(e) => {
                pageOffsetsRef.current[sec.pageNumber] = e.nativeEvent.layout.y;
              }}
            >
              {/* Page Number Margin Anchor */}
              <View style={styles.pageHeaderRow}>
                <View style={styles.pagePill}>
                  <View style={styles.marginAnchorDot} />
                  <Text style={styles.pagePillText}>Page {sec.pageNumber}</Text>
                </View>
                <Text style={styles.sectionHeading}>{sec.heading}</Text>
              </View>

              {/* Paragraphs */}
              {sec.paragraphs.map((para, pIdx) => {
                const isAnnotated = sectionAnnotations.some(
                  (a) => a.paragraphIndex === pIdx
                );
                const ann = sectionAnnotations.find(
                  (a) => a.paragraphIndex === pIdx
                );
                const isSearchMatch =
                  searchQuery.trim().length > 1 &&
                  para.toLowerCase().includes(searchQuery.toLowerCase());

                return (
                  <View
                    key={`p-${pIdx}`}
                    style={[
                      styles.paragraphContainer,
                      isAnnotated && {
                        backgroundColor: 'rgba(0, 173, 181, 0.12)',
                        borderLeftColor: ann?.color || '#00ADB5',
                        borderLeftWidth: 3.5,
                      },
                      isSearchMatch && styles.searchHighlight,
                    ]}
                  >
                    <Text style={styles.paragraphText}>{para}</Text>

                    {/* Interactive Passage Actions: Highlight & Extract */}
                    <View style={styles.passageToolbar}>
                      {/* Color dots to highlight */}
                      <View style={styles.paletteRow}>
                        {highlightColors.map((c) => (
                          <TouchableOpacity
                            key={c}
                            style={[
                              styles.highlightDot,
                              { backgroundColor: c },
                            ]}
                            onPress={() =>
                              onHighlightPassage?.(
                                para,
                                sec.id,
                                pIdx,
                                sec.pageNumber,
                                c
                              )
                            }
                          />
                        ))}
                      </View>

                      {/* Extract button */}
                      <TouchableOpacity
                        style={styles.extractBtn}
                        onPress={() => onExtractPassage?.(para, sec.pageNumber)}
                        onPressIn={(e) => {
                          const { pageX, pageY } = e.nativeEvent;
                          onStartLiftDrag?.(
                            {
                              text: para,
                              pageNumber: sec.pageNumber,
                              color: ann?.color || '#00ADB5',
                            },
                            pageX,
                            pageY
                          );
                        }}
                      >
                        <Text style={styles.extractBtnText}>
                          ✋ Extract to Workspace
                        </Text>
                      </TouchableOpacity>
                    </View>
                  </View>
                );
              })}

              {/* Tables */}
              {sec.tables?.map((tbl, tIdx) => (
                <View key={`tbl-${tIdx}`} style={styles.tableBox}>
                  <View style={styles.tableHeaderBar}>
                    <Text style={styles.tableTitle}>📊 Table {tIdx + 1}</Text>
                    <TouchableOpacity
                      style={styles.extractTableBtn}
                      onPress={() =>
                        onExtractPassage?.(
                          `Extracted Table from Page ${sec.pageNumber}`,
                          sec.pageNumber,
                          false,
                          undefined,
                          tbl
                        )
                      }
                    >
                      <Text style={styles.extractTableBtnText}>
                        Extract Table ✋
                      </Text>
                    </TouchableOpacity>
                  </View>

                  <View style={styles.tableGrid}>
                    {tbl.rows.map((row, rIdx) => {
                      const isHeader = rIdx === 0;
                      return (
                        <View
                          key={`tr-${rIdx}`}
                          style={[
                            styles.tableRow,
                            isHeader && styles.tableHeaderRow,
                            rIdx % 2 === 1 && !isHeader && styles.tableZebraRow,
                          ]}
                        >
                          {row.cells.map((cell, cIdx) => (
                            <View key={`td-${cIdx}`} style={styles.tableCell}>
                              <Text
                                style={[
                                  styles.cellText,
                                  isHeader && styles.headerCellText,
                                ]}
                                numberOfLines={1}
                              >
                                {cell}
                              </Text>
                            </View>
                          ))}
                        </View>
                      );
                    })}
                  </View>
                </View>
              ))}

              {/* Images / Figures */}
              {sec.imageUrl && (
                <View style={styles.figureBox}>
                  <View style={styles.figureHeader}>
                    <Text style={styles.figureTitle}>
                      🖼️ {sec.imageCaption || 'Figure'}
                    </Text>
                    <TouchableOpacity
                      style={styles.extractTableBtn}
                      onPress={() =>
                        onExtractPassage?.(
                          sec.imageCaption || 'Captured Figure',
                          sec.pageNumber,
                          true,
                          sec.imageUrl
                        )
                      }
                    >
                      <Text style={styles.extractTableBtnText}>
                        Extract Figure ✋
                      </Text>
                    </TouchableOpacity>
                  </View>
                  <View style={styles.figurePlaceholder}>
                    <Text style={styles.figurePlaceholderText}>
                      🖼️ [Captured Visual Figure from Document Page{' '}
                      {sec.pageNumber}]
                    </Text>
                  </View>
                </View>
              )}
            </View>
          );
        })}
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1E2028',
  },
  header: {
    height: 48,
    backgroundColor: '#222530',
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255, 255, 255, 0.08)',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
  },
  docInfo: {
    flex: 1,
  },
  docTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: '#EEEEEE',
  },
  docMeta: {
    fontSize: 10,
    color: '#94A3B8',
    marginTop: 1,
  },
  squeezedBadge: {
    backgroundColor: 'rgba(0, 173, 181, 0.2)',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: 'rgba(0, 173, 181, 0.4)',
  },
  squeezedBadgeText: {
    fontSize: 11,
    color: '#00ADB5',
    fontWeight: '700',
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    padding: 16,
    gap: 16,
  },
  pageCard: {
    backgroundColor: '#262934',
    borderRadius: 10,
    padding: 16,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.06)',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 4,
    elevation: 3,
  },
  pageHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginBottom: 12,
  },
  pagePill: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#181A20',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6,
    gap: 6,
  },
  marginAnchorDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: '#00ADB5',
  },
  pagePillText: {
    fontSize: 11,
    fontWeight: '700',
    color: '#00ADB5',
  },
  sectionHeading: {
    fontSize: 14,
    fontWeight: '700',
    color: '#F8FAFC',
    flex: 1,
  },
  paragraphContainer: {
    padding: 10,
    borderRadius: 6,
    backgroundColor: '#1E2028',
    marginBottom: 10,
    borderLeftWidth: 3.5,
    borderLeftColor: 'transparent',
  },
  searchHighlight: {
    backgroundColor: 'rgba(245, 158, 11, 0.22)',
    borderLeftColor: '#F59E0B',
  },
  paragraphText: {
    fontSize: 13,
    lineHeight: 20,
    color: '#CBD5E1',
  },
  passageToolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 8,
    paddingTop: 6,
    borderTopWidth: 1,
    borderTopColor: 'rgba(255, 255, 255, 0.05)',
  },
  paletteRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  highlightDot: {
    width: 14,
    height: 14,
    borderRadius: 7,
  },
  extractBtn: {
    backgroundColor: 'rgba(0, 173, 181, 0.15)',
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 4,
    borderWidth: 1,
    borderColor: 'rgba(0, 173, 181, 0.3)',
  },
  extractBtnText: {
    fontSize: 11,
    color: '#00ADB5',
    fontWeight: '600',
  },
  squeezedDivider: {
    flexDirection: 'row',
    alignItems: 'center',
    marginVertical: 6,
    gap: 8,
  },
  squeezedLine: {
    flex: 1,
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.1)',
  },
  squeezedText: {
    fontSize: 10,
    color: '#64748B',
    fontStyle: 'italic',
  },
  tableBox: {
    backgroundColor: '#181A20',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.08)',
    overflow: 'hidden',
    marginTop: 6,
    marginBottom: 10,
  },
  tableHeaderBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingVertical: 6,
    backgroundColor: '#20242E',
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255, 255, 255, 0.06)',
  },
  tableTitle: {
    fontSize: 12,
    fontWeight: '700',
    color: '#00ADB5',
  },
  extractTableBtn: {
    backgroundColor: 'rgba(0, 173, 181, 0.2)',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 4,
  },
  extractTableBtnText: {
    fontSize: 10,
    color: '#00ADB5',
    fontWeight: '600',
  },
  tableGrid: {
    width: '100%',
  },
  tableRow: {
    flexDirection: 'row',
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255, 255, 255, 0.05)',
  },
  tableHeaderRow: {
    backgroundColor: '#1C202A',
  },
  tableZebraRow: {
    backgroundColor: '#202430',
  },
  tableCell: {
    flex: 1,
    paddingHorizontal: 8,
    paddingVertical: 6,
    borderRightWidth: 1,
    borderRightColor: 'rgba(255, 255, 255, 0.04)',
  },
  cellText: {
    fontSize: 11,
    color: '#94A3B8',
  },
  headerCellText: {
    color: '#EEEEEE',
    fontWeight: '700',
  },
  figureBox: {
    backgroundColor: '#181A20',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.08)',
    overflow: 'hidden',
    marginTop: 6,
  },
  figureHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingVertical: 6,
    backgroundColor: '#20242E',
  },
  figureTitle: {
    fontSize: 12,
    fontWeight: '700',
    color: '#F59E0B',
  },
  figurePlaceholder: {
    height: 80,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#13151B',
  },
  figurePlaceholderText: {
    fontSize: 11,
    color: '#64748B',
    fontStyle: 'italic',
  },
});

export default DocumentViewer;
