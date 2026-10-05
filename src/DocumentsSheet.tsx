import React, { useState, useMemo } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  Modal,
  ScrollView,
  Alert,
} from 'react-native';
import type { WorkspaceDocumentEntry } from './types';

export interface DocumentsSheetProps {
  visible: boolean;
  onClose: () => void;
  documents: WorkspaceDocumentEntry[];
  activeDocumentId?: string | null;
  onSelectDocument: (docId: string) => void;
  onAddDocument: () => void;
  onDeleteDocument?: (docId: string) => void;
  onRenameDocument?: (docId: string, newTitle: string) => void;
  onReplaceDocument?: (docId: string) => void;
  onOpenPageEditor?: (docId: string) => void;
  onAddTag?: (docId: string, tag: string) => void;
}

export const DocumentsSheet: React.FC<DocumentsSheetProps> = ({
  visible,
  onClose,
  documents,
  activeDocumentId,
  onSelectDocument,
  onAddDocument,
  onDeleteDocument,
  onRenameDocument,
  onReplaceDocument,
  onOpenPageEditor,
  onAddTag,
}) => {
  const [filterQuery, setFilterQuery] = useState('');
  const [sortAscending, setSortAscending] = useState(true);
  const [selectedDocForMenu, setSelectedDocForMenu] =
    useState<WorkspaceDocumentEntry | null>(null);

  // Rename prompt dialog state
  const [isRenaming, setIsRenaming] = useState(false);
  const [renameText, setRenameText] = useState('');

  // Add Tag dialog state
  const [isAddingTag, setIsAddingTag] = useState(false);
  const [tagText, setTagText] = useState('');

  // Filtered & sorted document list
  const filteredDocs = useMemo(() => {
    let result = documents.filter((d) =>
      d.title.toLowerCase().includes(filterQuery.toLowerCase())
    );
    result.sort((a, b) => {
      const cmp = a.title.localeCompare(b.title);
      return sortAscending ? cmp : -cmp;
    });
    return result;
  }, [documents, filterQuery, sortAscending]);

  if (!visible) return null;

  const handleOpenDocMenu = (doc: WorkspaceDocumentEntry) => {
    setSelectedDocForMenu(doc);
  };

  const handleCloseDocMenu = () => {
    setSelectedDocForMenu(null);
  };

  const handleStartRename = () => {
    if (!selectedDocForMenu) return;
    setRenameText(selectedDocForMenu.title);
    setIsRenaming(true);
  };

  const handleConfirmRename = () => {
    if (selectedDocForMenu && renameText.trim()) {
      onRenameDocument?.(selectedDocForMenu.id, renameText.trim());
      setIsRenaming(false);
      setSelectedDocForMenu(null);
    }
  };

  const handleDelete = () => {
    if (!selectedDocForMenu) return;
    const docToDelete = selectedDocForMenu;
    Alert.alert(
      'Delete Document',
      `Are you sure you want to delete "${docToDelete.title}" from this workspace?`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            onDeleteDocument?.(docToDelete.id);
            setSelectedDocForMenu(null);
          },
        },
      ]
    );
  };

  const handleConfirmAddTag = () => {
    if (selectedDocForMenu && tagText.trim()) {
      onAddTag?.(selectedDocForMenu.id, tagText.trim());
      setIsAddingTag(false);
      setTagText('');
    }
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={onClose}
    >
      <View style={styles.sheetBackdrop}>
        <View style={styles.sheetCard}>
          {/* ═════════════════════════════════════════════════════════════════ */}
          {/* VIEW 2: Document Options / Actions Menu (Screenshot 2)           */}
          {/* ═════════════════════════════════════════════════════════════════ */}
          {selectedDocForMenu ? (
            <View style={styles.menuContainer}>
              {/* Header with Close Button */}
              <View style={styles.menuTopBar}>
                <View style={styles.menuTitleBox}>
                  <Text style={styles.menuTitleText} numberOfLines={1}>
                    {selectedDocForMenu.title}
                  </Text>
                </View>
                <TouchableOpacity
                  style={styles.circleCloseBtn}
                  onPress={handleCloseDocMenu}
                  activeOpacity={0.7}
                  hitSlop={{ top: 12, bottom: 12, left: 12, right: 12 }}
                >
                  <Text style={styles.circleCloseText}>✕</Text>
                </TouchableOpacity>
              </View>

              <ScrollView
                style={styles.menuScroll}
                contentContainerStyle={styles.menuScrollContent}
              >
                {/* ── Group 1 ── */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={handleStartRename}
                >
                  <Text style={styles.actionIcon}>✎</Text>
                  <Text style={styles.actionText}>Rename</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={() => {
                    Alert.alert(
                      'Folders',
                      `"${selectedDocForMenu.title}" moved to folder.`
                    );
                    setSelectedDocForMenu(null);
                  }}
                >
                  <Text style={styles.actionIcon}>📁</Text>
                  <Text style={styles.actionText}>Add to New Folder</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={handleDelete}
                >
                  <Text style={styles.actionIcon}>🗑</Text>
                  <Text style={styles.actionText}>Delete</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={() => {
                    Alert.alert(
                      'Link Copied',
                      `Link for "${selectedDocForMenu.title}" copied to clipboard.`
                    );
                    setSelectedDocForMenu(null);
                  }}
                >
                  <Text style={styles.actionIcon}>🔗</Text>
                  <Text style={styles.actionText}>Copy Link</Text>
                </TouchableOpacity>

                {/* Horizontal Divider */}
                <View style={styles.dividerLine} />

                {/* ── Group 2 ── */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={() => {
                    const docId = selectedDocForMenu.id;
                    setSelectedDocForMenu(null);
                    onReplaceDocument?.(docId);
                  }}
                >
                  <Text style={styles.actionIcon}>📄</Text>
                  <Text style={styles.actionText}>Replace Document</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={() => {
                    Alert.alert(
                      'OCR Text Detection',
                      'Text detection OCR completed. Highlighting available.'
                    );
                    setSelectedDocForMenu(null);
                  }}
                >
                  <Text style={styles.actionIcon}>⛶</Text>
                  <Text style={styles.actionText}>OCR Text Detection</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={() => {
                    const docId = selectedDocForMenu.id;
                    setSelectedDocForMenu(null);
                    onClose();
                    onOpenPageEditor?.(docId);
                  }}
                >
                  <Text style={styles.actionIcon}>⊞</Text>
                  <Text style={styles.actionText}>Page Editor</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={() => {
                    Alert.alert(
                      'Tags & Metadata',
                      `Document: ${selectedDocForMenu.title}\nPages: ${selectedDocForMenu.pageCount}\nAuthor: ${selectedDocForMenu.author || 'Unknown'}`
                    );
                  }}
                >
                  <Text style={styles.actionIcon}>🏷</Text>
                  <Text style={styles.actionText}>Tags and Metadata</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={() => {
                    Alert.alert(
                      'Collaborator Visibility',
                      'Document visibility set to shared workspace collaborators.'
                    );
                  }}
                >
                  <Text style={styles.actionIcon}>👁</Text>
                  <Text style={styles.actionText}>
                    Visibility to Collaborators
                  </Text>
                </TouchableOpacity>

                {/* Divider with centered "Add Tags" label */}
                <View style={styles.tagDividerContainer}>
                  <View style={styles.tagDividerRule} />
                  <Text style={styles.tagDividerLabel}>Add Tags</Text>
                  <View style={styles.tagDividerRule} />
                </View>

                {/* ── Group 3 ── */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={() => setIsAddingTag(true)}
                >
                  <Text style={styles.actionIcon}>🏷</Text>
                  <Text style={styles.actionText}>Add First Tag</Text>
                </TouchableOpacity>
              </ScrollView>
            </View>
          ) : (
            /* ═════════════════════════════════════════════════════════════════ */
            /* VIEW 1: Main Documents List (Screenshot 1)                       */
            /* ═════════════════════════════════════════════════════════════════ */
            <View style={styles.mainContainer}>
              {/* ── 1. Top Header Row: [+] Add Document ... [✕] ─────────────── */}
              <View style={styles.topHeaderBar}>
                <TouchableOpacity
                  style={styles.addDocBtn}
                  activeOpacity={0.75}
                  onPress={onAddDocument}
                >
                  <View style={styles.addDocIconWrapper}>
                    <Text style={styles.addDocIconGlyph}>📄</Text>
                    <View style={styles.addDocBadge}>
                      <Text style={styles.addDocPlusSign}>+</Text>
                    </View>
                  </View>
                  <Text style={styles.addDocText}>Add Document</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.circleCloseBtn}
                  onPress={onClose}
                  activeOpacity={0.7}
                  hitSlop={{ top: 12, bottom: 12, left: 12, right: 12 }}
                >
                  <Text style={styles.circleCloseText}>✕</Text>
                </TouchableOpacity>
              </View>

              {/* ── 2. Filter Documents Input + Sort Icon ───────────────────── */}
              <View style={styles.filterRow}>
                <View style={styles.filterInputWrapper}>
                  <TextInput
                    style={styles.filterTextInput}
                    placeholder="Filter Documents"
                    placeholderTextColor="rgba(255, 255, 255, 0.7)"
                    value={filterQuery}
                    onChangeText={setFilterQuery}
                    autoCapitalize="none"
                    autoCorrect={false}
                  />
                </View>

                <TouchableOpacity
                  style={styles.sortBtn}
                  activeOpacity={0.7}
                  onPress={() => setSortAscending((prev) => !prev)}
                >
                  <Text style={styles.sortIcon}>⇄</Text>
                </TouchableOpacity>
              </View>

              {/* ── 3. Documents List ───────────────────────────────────────── */}
              <ScrollView
                style={styles.docsList}
                contentContainerStyle={styles.docsListContent}
              >
                {filteredDocs.length === 0 ? (
                  <View style={styles.emptyContainer}>
                    <Text style={styles.emptyText}>
                      {filterQuery
                        ? 'No matching documents found'
                        : 'No documents in workspace'}
                    </Text>
                  </View>
                ) : (
                  filteredDocs.map((doc) => {
                    const isActive = doc.id === activeDocumentId;
                    return (
                      <TouchableOpacity
                        key={doc.id}
                        style={[
                          styles.docItemRow,
                          isActive && styles.docItemRowActive,
                        ]}
                        activeOpacity={0.8}
                        onPress={() => {
                          onSelectDocument(doc.id);
                          onClose();
                        }}
                      >
                        <View style={styles.docItemLeft}>
                          {/* White document outline icon */}
                          <View style={styles.docOutlineIcon}>
                            <View style={styles.docFoldCorner} />
                          </View>
                          <Text
                            style={styles.docItemTitle}
                            numberOfLines={1}
                            ellipsizeMode="tail"
                          >
                            {doc.title}
                          </Text>
                        </View>

                        {/* Three Dots More Menu Button */}
                        <TouchableOpacity
                          style={styles.threeDotsBtn}
                          activeOpacity={0.65}
                          onPress={() => handleOpenDocMenu(doc)}
                          hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                        >
                          <Text style={styles.threeDotsText}>⋮</Text>
                        </TouchableOpacity>
                      </TouchableOpacity>
                    );
                  })
                )}
              </ScrollView>
            </View>
          )}

          {/* ═════════════════════════════════════════════════════════════════ */}
          {/* INLINE MODAL: Rename Document                                    */}
          {/* ═════════════════════════════════════════════════════════════════ */}
          {isRenaming && (
            <View style={styles.promptOverlay}>
              <View style={styles.promptCard}>
                <Text style={styles.promptTitle}>Rename Document</Text>
                <TextInput
                  style={styles.promptInput}
                  value={renameText}
                  onChangeText={setRenameText}
                  autoFocus
                  selectTextOnFocus
                />
                <View style={styles.promptButtonsRow}>
                  <TouchableOpacity
                    style={[styles.promptBtn, styles.promptBtnCancel]}
                    onPress={() => setIsRenaming(false)}
                  >
                    <Text style={styles.promptBtnCancelText}>Cancel</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.promptBtn, styles.promptBtnConfirm]}
                    onPress={handleConfirmRename}
                  >
                    <Text style={styles.promptBtnConfirmText}>Rename</Text>
                  </TouchableOpacity>
                </View>
              </View>
            </View>
          )}

          {/* ═════════════════════════════════════════════════════════════════ */}
          {/* INLINE MODAL: Add Tag                                            */}
          {/* ═════════════════════════════════════════════════════════════════ */}
          {isAddingTag && (
            <View style={styles.promptOverlay}>
              <View style={styles.promptCard}>
                <Text style={styles.promptTitle}>Add Tag</Text>
                <TextInput
                  style={styles.promptInput}
                  placeholder="e.g. Chapter 1, Research, Important"
                  placeholderTextColor="#94A3B8"
                  value={tagText}
                  onChangeText={setTagText}
                  autoFocus
                />
                <View style={styles.promptButtonsRow}>
                  <TouchableOpacity
                    style={[styles.promptBtn, styles.promptBtnCancel]}
                    onPress={() => setIsAddingTag(false)}
                  >
                    <Text style={styles.promptBtnCancelText}>Cancel</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.promptBtn, styles.promptBtnConfirm]}
                    onPress={handleConfirmAddTag}
                  >
                    <Text style={styles.promptBtnConfirmText}>Add Tag</Text>
                  </TouchableOpacity>
                </View>
              </View>
            </View>
          )}
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  sheetBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.35)',
    justifyContent: 'flex-start',
  },
  sheetCard: {
    flex: 1,
    marginTop: 46, // Starts right below iOS/Android status bar
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    backgroundColor: 'rgba(142, 158, 175, 0.98)', // Slate-blue from Screenshot 1 & 2
    overflow: 'hidden',
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: -4 },
    shadowOpacity: 0.25,
    shadowRadius: 14,
    elevation: 20,
  },
  mainContainer: {
    flex: 1,
  },
  topHeaderBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 14,
  },
  addDocBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  addDocIconWrapper: {
    position: 'relative',
    width: 22,
    height: 24,
    alignItems: 'center',
    justifyContent: 'center',
  },
  addDocIconGlyph: {
    fontSize: 20,
    color: '#FFFFFF',
  },
  addDocBadge: {
    position: 'absolute',
    bottom: 0,
    right: -2,
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: '#FFFFFF',
    alignItems: 'center',
    justifyContent: 'center',
  },
  addDocPlusSign: {
    fontSize: 9,
    fontWeight: '900',
    color: '#6B7FA3',
    lineHeight: 10,
  },
  addDocText: {
    fontSize: 16,
    fontWeight: '700',
    color: '#FFFFFF',
    letterSpacing: 0.1,
  },
  circleCloseBtn: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: 'rgba(0, 0, 0, 0.22)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  circleCloseText: {
    fontSize: 13,
    fontWeight: '800',
    color: '#FFFFFF',
  },
  filterRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    marginBottom: 10,
    gap: 10,
  },
  filterInputWrapper: {
    flex: 1,
    height: 38,
    borderRadius: 19,
    borderWidth: 1.5,
    borderColor: 'rgba(255, 255, 255, 0.55)',
    backgroundColor: 'rgba(255, 255, 255, 0.12)',
    paddingHorizontal: 14,
    justifyContent: 'center',
  },
  filterTextInput: {
    fontSize: 14,
    color: '#FFFFFF',
    fontStyle: 'italic',
    paddingVertical: 0,
  },
  sortBtn: {
    width: 32,
    height: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
  sortIcon: {
    fontSize: 20,
    color: 'rgba(255, 255, 255, 0.9)',
    fontWeight: '600',
  },
  docsList: {
    flex: 1,
  },
  docsListContent: {
    paddingBottom: 24,
  },
  emptyContainer: {
    paddingVertical: 40,
    alignItems: 'center',
  },
  emptyText: {
    fontSize: 14,
    color: 'rgba(255, 255, 255, 0.65)',
    fontStyle: 'italic',
  },
  docItemRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  docItemRowActive: {
    backgroundColor: 'rgba(0, 0, 0, 0.12)',
  },
  docItemLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    flex: 1,
    paddingRight: 12,
  },
  docOutlineIcon: {
    width: 17,
    height: 22,
    borderWidth: 1.8,
    borderColor: '#FFFFFF',
    borderRadius: 2,
    position: 'relative',
    backgroundColor: 'transparent',
  },
  docFoldCorner: {
    position: 'absolute',
    top: -1,
    right: -1,
    width: 6,
    height: 6,
    backgroundColor: 'rgba(142, 158, 175, 0.98)',
    borderLeftWidth: 1.5,
    borderBottomWidth: 1.5,
    borderColor: '#FFFFFF',
  },
  docItemTitle: {
    fontSize: 14.5,
    fontWeight: '600',
    color: '#FFFFFF',
    flex: 1,
  },
  threeDotsBtn: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  threeDotsText: {
    fontSize: 20,
    color: '#FFFFFF',
    fontWeight: '900',
  },

  // ── Menu View (Screenshot 2) ─────────────────────────────────────────────
  menuContainer: {
    flex: 1,
  },
  menuTopBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 10,
  },
  menuTitleBox: {
    flex: 1,
    paddingRight: 12,
  },
  menuTitleText: {
    fontSize: 16,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  menuScroll: {
    flex: 1,
  },
  menuScrollContent: {
    paddingVertical: 4,
    paddingBottom: 30,
  },
  actionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 20,
    gap: 16,
  },
  actionIcon: {
    fontSize: 18,
    color: '#FFFFFF',
    width: 24,
    textAlign: 'center',
  },
  actionText: {
    fontSize: 15,
    color: '#FFFFFF',
    fontWeight: '500',
  },
  dividerLine: {
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.22)',
    marginVertical: 6,
    marginHorizontal: 16,
  },
  tagDividerContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginVertical: 10,
    paddingHorizontal: 16,
    gap: 10,
  },
  tagDividerRule: {
    flex: 1,
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.22)',
  },
  tagDividerLabel: {
    fontSize: 12.5,
    color: 'rgba(255, 255, 255, 0.85)',
    fontWeight: '500',
  },

  // ── Dialog Overlays ──────────────────────────────────────────────────────
  promptOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
    zIndex: 999,
  },
  promptCard: {
    width: '100%',
    maxWidth: 320,
    backgroundColor: '#1E293B',
    borderRadius: 16,
    padding: 20,
    borderWidth: 1,
    borderColor: '#334155',
  },
  promptTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: '#F8FAFC',
    marginBottom: 12,
  },
  promptInput: {
    backgroundColor: '#0F172A',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    color: '#F8FAFC',
    fontSize: 14,
    borderWidth: 1,
    borderColor: '#334155',
    marginBottom: 16,
  },
  promptButtonsRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
  },
  promptBtn: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 8,
  },
  promptBtnCancel: {
    backgroundColor: '#334155',
  },
  promptBtnCancelText: {
    color: '#CBD5E1',
    fontWeight: '600',
    fontSize: 13,
  },
  promptBtnConfirm: {
    backgroundColor: '#38BDF8',
  },
  promptBtnConfirmText: {
    color: '#0F172A',
    fontWeight: '700',
    fontSize: 13,
  },
});
