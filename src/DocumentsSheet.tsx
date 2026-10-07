import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  Modal,
  ScrollView,
  Alert,
  Animated,
  Easing,
  Platform,
} from 'react-native';
import type { WorkspaceDocumentEntry, WorkspaceFolder } from './types';

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
  /** Optional folders list passed from parent (controlled/persisted) */
  folders?: WorkspaceFolder[];
  /** Callback fired whenever the folders collection is modified */
  onFoldersChange?: (folders: WorkspaceFolder[]) => void;
  /** Callback when a document is assigned or moved to a folder */
  onMoveDocumentToFolder?: (docId: string, folderId: string | null) => void;
  /** Callback when a new folder or subfolder is created */
  onCreateFolder?: (name: string, parentId?: string | null) => void;
  /** Callback when a folder is renamed */
  onRenameFolder?: (folderId: string, newName: string) => void;
  /** Callback when a folder is deleted */
  onDeleteFolder?: (folderId: string) => void;
}

interface AlertPromptConfig {
  mode:
    | 'create_folder_for_doc'
    | 'create_subfolder'
    | 'rename_folder'
    | 'rename_doc'
    | 'add_tag';
  title: string;
  subtitle?: string;
  defaultValue: string;
  targetId?: string;
  parentId?: string | null;
}

// ── Icons ──────────────────────────────────────────────────────────────────

/** Closed folder outline icon */
const ClosedFolderIcon: React.FC = () => (
  <View style={styles.folderIconContainer}>
    <View style={styles.closedFolderTab} />
    <View style={styles.closedFolderBody} />
  </View>
);

/** Open folder outline icon (matching LiquidText video frame_030/040) */
const OpenFolderIcon: React.FC = () => (
  <View style={styles.folderIconContainer}>
    <View style={styles.openFolderBackTab} />
    <View style={styles.openFolderBackPlate} />
    <View style={styles.openFolderFrontFlap} />
  </View>
);

/** Document outline icon with dog-ear folded corner */
const DocOutlineIcon: React.FC = () => (
  <View style={styles.docOutlineIcon}>
    <View style={styles.docFoldCorner} />
  </View>
);

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
  folders: controlledFolders,
  onFoldersChange,
  onMoveDocumentToFolder,
  onCreateFolder,
  onRenameFolder,
  onDeleteFolder,
}) => {
  const [filterQuery, setFilterQuery] = useState('');
  const [sortAscending, setSortAscending] = useState(true);

  // Folder state (controlled or self-managed)
  const [internalFolders, setInternalFolders] = useState<WorkspaceFolder[]>([]);
  const currentFolders = controlledFolders ?? internalFolders;

  const updateFolders = (nextFolders: WorkspaceFolder[]) => {
    if (!controlledFolders) {
      setInternalFolders(nextFolders);
    }
    onFoldersChange?.(nextFolders);
  };

  // Expanded folders set (accordion tree)
  const [expandedFolderIds, setExpandedFolderIds] = useState<Set<string>>(
    new Set()
  );

  // Active 3-dots menus
  const [selectedDocForMenu, setSelectedDocForMenu] =
    useState<WorkspaceDocumentEntry | null>(null);
  const [selectedFolderForMenu, setSelectedFolderForMenu] =
    useState<WorkspaceFolder | null>(null);

  // Move Document Dialog state
  const [isMovingDoc, setIsMovingDoc] = useState<WorkspaceDocumentEntry | null>(
    null
  );

  // iOS-style Alert Prompt Dialog state
  const [alertPrompt, setAlertPrompt] = useState<AlertPromptConfig | null>(
    null
  );
  const [alertInputText, setAlertInputText] = useState('');

  // Slide-up animation for menus
  const docMenuAnim = useRef(new Animated.Value(0)).current;
  const folderMenuAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (selectedDocForMenu) {
      Animated.timing(docMenuAnim, {
        toValue: 1,
        duration: 220,
        easing: Easing.bezier(0.25, 0.1, 0.25, 1),
        useNativeDriver: true,
      }).start();
    } else {
      docMenuAnim.setValue(0);
    }
  }, [selectedDocForMenu, docMenuAnim]);

  useEffect(() => {
    if (selectedFolderForMenu) {
      Animated.timing(folderMenuAnim, {
        toValue: 1,
        duration: 220,
        easing: Easing.bezier(0.25, 0.1, 0.25, 1),
        useNativeDriver: true,
      }).start();
    } else {
      folderMenuAnim.setValue(0);
    }
  }, [selectedFolderForMenu, folderMenuAnim]);

  if (!visible) return null;

  // Toggle folder expand/collapse
  const toggleFolderExpand = (folderId: string) => {
    setExpandedFolderIds((prev) => {
      const next = new Set(prev);
      if (next.has(folderId)) {
        next.delete(folderId);
      } else {
        next.add(folderId);
      }
      return next;
    });
  };

  // ── Open Alert Prompt helper ──────────────────────────────────────────────
  const openAlert = (config: AlertPromptConfig) => {
    setAlertInputText(config.defaultValue);
    setAlertPrompt(config);
  };

  const handleAlertSubmit = () => {
    if (!alertPrompt) return;
    const value = alertInputText.trim();
    if (!value) {
      setAlertPrompt(null);
      return;
    }

    switch (alertPrompt.mode) {
      case 'create_folder_for_doc': {
        const docId = alertPrompt.targetId!;
        const newFolderId = `folder-${Date.now()}`;
        const newFolder: WorkspaceFolder = {
          id: newFolderId,
          name: value,
          parentId: alertPrompt.parentId ?? null,
          createdAt: new Date().toISOString(),
        };
        const next = [...currentFolders, newFolder];
        updateFolders(next);
        onCreateFolder?.(value, alertPrompt.parentId ?? null);
        onMoveDocumentToFolder?.(docId, newFolderId);

        // If doc was inside an outer folder, ensure outer folder stays expanded
        if (alertPrompt.parentId) {
          setExpandedFolderIds((prev) =>
            new Set(prev).add(alertPrompt.parentId!)
          );
        }
        break;
      }

      case 'create_subfolder': {
        const parentId = alertPrompt.parentId!;
        const newFolderId = `folder-${Date.now()}`;
        const newFolder: WorkspaceFolder = {
          id: newFolderId,
          name: value,
          parentId,
          createdAt: new Date().toISOString(),
        };
        const next = [...currentFolders, newFolder];
        updateFolders(next);
        onCreateFolder?.(value, parentId);

        // Auto-expand the parent folder so the new subfolder is immediately visible
        setExpandedFolderIds((prev) => new Set(prev).add(parentId));
        break;
      }

      case 'rename_folder': {
        const folderId = alertPrompt.targetId!;
        const next = currentFolders.map((f) =>
          f.id === folderId ? { ...f, name: value } : f
        );
        updateFolders(next);
        onRenameFolder?.(folderId, value);
        break;
      }

      case 'rename_doc': {
        const docId = alertPrompt.targetId!;
        onRenameDocument?.(docId, value);
        break;
      }
    }

    setAlertPrompt(null);
  };

  // ── Document Menu Actions ────────────────────────────────────────────────
  const handleDocAddtoNewFolder = () => {
    if (!selectedDocForMenu) return;
    const doc = selectedDocForMenu;
    setSelectedDocForMenu(null);
    openAlert({
      mode: 'create_folder_for_doc',
      title: 'New Folder',
      subtitle: 'Enter name for new folder.',
      defaultValue: 'New Folder',
      targetId: doc.id,
      parentId: doc.folderId ?? null,
    });
  };

  const handleDocMoveToFolder = () => {
    if (!selectedDocForMenu) return;
    const doc = selectedDocForMenu;
    setSelectedDocForMenu(null);
    if (currentFolders.length === 0) {
      Alert.alert(
        'No Folders',
        'No folders have been created yet. Tap "Add to New Folder" first.'
      );
      return;
    }
    setIsMovingDoc(doc);
  };

  const handleDocRename = () => {
    if (!selectedDocForMenu) return;
    const doc = selectedDocForMenu;
    setSelectedDocForMenu(null);
    openAlert({
      mode: 'rename_doc',
      title: 'Rename Document',
      subtitle: 'Enter name for document.',
      defaultValue: doc.title,
      targetId: doc.id,
    });
  };

  const handleDocDelete = () => {
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

  // ── Folder Menu Actions ──────────────────────────────────────────────────
  const handleFolderRename = () => {
    if (!selectedFolderForMenu) return;
    const folder = selectedFolderForMenu;
    setSelectedFolderForMenu(null);
    openAlert({
      mode: 'rename_folder',
      title: 'Rename Folder',
      subtitle: 'Enter name for folder.',
      defaultValue: folder.name,
      targetId: folder.id,
    });
  };

  const handleFolderAddSubfolder = () => {
    if (!selectedFolderForMenu) return;
    const folder = selectedFolderForMenu;
    setSelectedFolderForMenu(null);
    openAlert({
      mode: 'create_subfolder',
      title: 'New Folder',
      subtitle: 'Enter name for new folder.',
      defaultValue: 'New Folder',
      parentId: folder.id,
    });
  };

  const handleFolderDelete = () => {
    if (!selectedFolderForMenu) return;
    const folderToDelete = selectedFolderForMenu;
    Alert.alert(
      'Delete Folder',
      `Are you sure you want to delete "${folderToDelete.name}"? Documents inside will be moved to the parent level.`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            // Collect all descendant folder IDs recursively
            const getDescendantIds = (id: string): string[] => {
              const children = currentFolders.filter((f) => f.parentId === id);
              return [id, ...children.flatMap((c) => getDescendantIds(c.id))];
            };
            const toRemove = new Set(getDescendantIds(folderToDelete.id));

            // Move documents inside deleted folders up to parentId
            documents.forEach((d) => {
              if (d.folderId && toRemove.has(d.folderId)) {
                onMoveDocumentToFolder?.(d.id, folderToDelete.parentId ?? null);
              }
            });

            const next = currentFolders.filter((f) => !toRemove.has(f.id));
            updateFolders(next);
            onDeleteFolder?.(folderToDelete.id);
            setSelectedFolderForMenu(null);
          },
        },
      ]
    );
  };

  // ── Hierarchical Folder List for "Move to Folder" ────────────────────────
  const getFolderOptions = () => {
    const list: { id: string; name: string; depth: number }[] = [];
    const traverse = (parentId: string | null = null, depth = 0) => {
      const children = currentFolders.filter(
        (f) => (f.parentId ?? null) === parentId
      );
      children.sort((a, b) => a.name.localeCompare(b.name));
      for (const child of children) {
        list.push({ id: child.id, name: child.name, depth });
        traverse(child.id, depth + 1);
      }
    };
    traverse(null, 0);
    return list;
  };

  // ── Recursive Accordion Tree Rendering ────────────────────────────────────
  const renderTree = (parentId: string | null = null, depth = 0) => {
    // 1. Folders at this level
    let childFolders = currentFolders.filter(
      (f) => (f.parentId ?? null) === parentId
    );
    childFolders.sort((a, b) => {
      const cmp = a.name.localeCompare(b.name);
      return sortAscending ? cmp : -cmp;
    });

    // 2. Documents at this level
    let childDocs = documents.filter((d) => (d.folderId ?? null) === parentId);
    if (filterQuery.trim()) {
      const q = filterQuery.toLowerCase();
      childDocs = childDocs.filter((d) => d.title.toLowerCase().includes(q));
    }
    childDocs.sort((a, b) => {
      const cmp = a.title.localeCompare(b.title);
      return sortAscending ? cmp : -cmp;
    });

    return (
      <React.Fragment key={`tree-${parentId ?? 'root'}-${depth}`}>
        {childFolders.map((folder) => {
          const isExpanded = expandedFolderIds.has(folder.id);
          const indentPadding = 16 + depth * 22;

          return (
            <React.Fragment key={folder.id}>
              {/* Folder Row */}
              <TouchableOpacity
                style={[styles.treeRow, { paddingLeft: indentPadding }]}
                activeOpacity={0.7}
                onPress={() => toggleFolderExpand(folder.id)}
              >
                <View style={styles.treeRowLeft}>
                  {isExpanded ? <OpenFolderIcon /> : <ClosedFolderIcon />}
                  <Text style={styles.treeRowTitle} numberOfLines={1}>
                    {folder.name}
                  </Text>
                </View>

                <TouchableOpacity
                  style={styles.threeDotsBtn}
                  activeOpacity={0.65}
                  onPress={() => setSelectedFolderForMenu(folder)}
                  hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                >
                  <Text style={styles.threeDotsText}>⋮</Text>
                </TouchableOpacity>
              </TouchableOpacity>

              {/* Render children inline if folder is expanded */}
              {isExpanded && renderTree(folder.id, depth + 1)}
            </React.Fragment>
          );
        })}

        {childDocs.map((doc) => {
          const isActive = doc.id === activeDocumentId;
          const indentPadding = 16 + depth * 22;

          return (
            <TouchableOpacity
              key={doc.id}
              style={[
                styles.treeRow,
                { paddingLeft: indentPadding },
                isActive && styles.treeRowActive,
              ]}
              activeOpacity={0.8}
              onPress={() => {
                onSelectDocument(doc.id);
                onClose();
              }}
            >
              <View style={styles.treeRowLeft}>
                <DocOutlineIcon />
                <Text
                  style={[
                    styles.treeRowTitle,
                    isActive && styles.activeDocTitle,
                  ]}
                  numberOfLines={1}
                  ellipsizeMode="tail"
                >
                  {doc.title}
                </Text>
              </View>

              <TouchableOpacity
                style={styles.threeDotsBtn}
                activeOpacity={0.65}
                onPress={() => setSelectedDocForMenu(doc)}
                hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
              >
                <Text style={styles.threeDotsText}>⋮</Text>
              </TouchableOpacity>
            </TouchableOpacity>
          );
        })}
      </React.Fragment>
    );
  };

  const isTreeEmpty = documents.length === 0 && currentFolders.length === 0;

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
          {/* VIEW: Folder Action Sheet (LiquidText Video frame_015)           */}
          {/* ═════════════════════════════════════════════════════════════════ */}
          {selectedFolderForMenu ? (
            <Animated.View
              style={[
                styles.menuContainer,
                {
                  opacity: folderMenuAnim,
                  transform: [
                    {
                      translateY: folderMenuAnim.interpolate({
                        inputRange: [0, 1],
                        outputRange: [40, 0],
                      }),
                    },
                  ],
                },
              ]}
            >
              <View style={styles.menuTopBar}>
                <View style={styles.menuTitleBox}>
                  <Text style={styles.menuTitleText} numberOfLines={1}>
                    {selectedFolderForMenu.name}
                  </Text>
                </View>
                <TouchableOpacity
                  style={styles.circleCloseBtn}
                  onPress={() => setSelectedFolderForMenu(null)}
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
                {/* 1. Rename */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={handleFolderRename}
                >
                  <Text style={styles.actionIcon}>✎</Text>
                  <Text style={styles.actionText}>Rename</Text>
                </TouchableOpacity>

                {/* 2. Add Subfolder */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={handleFolderAddSubfolder}
                >
                  <Text style={styles.actionIcon}>📁⁺</Text>
                  <Text style={styles.actionText}>Add Subfolder</Text>
                </TouchableOpacity>

                {/* 3. Delete */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={handleFolderDelete}
                >
                  <Text style={styles.actionIcon}>🗑</Text>
                  <Text style={styles.actionText}>Delete</Text>
                </TouchableOpacity>
              </ScrollView>
            </Animated.View>
          ) : selectedDocForMenu ? (
            /* ═════════════════════════════════════════════════════════════════ */
            /* VIEW: Document Options / Actions Menu (Cleaned to user spec)     */
            /* ═════════════════════════════════════════════════════════════════ */
            <Animated.View
              style={[
                styles.menuContainer,
                {
                  opacity: docMenuAnim,
                  transform: [
                    {
                      translateY: docMenuAnim.interpolate({
                        inputRange: [0, 1],
                        outputRange: [40, 0],
                      }),
                    },
                  ],
                },
              ]}
            >
              {/* Header with Close Button */}
              <View style={styles.menuTopBar}>
                <View style={styles.menuTitleBox}>
                  <Text style={styles.menuTitleText} numberOfLines={1}>
                    {selectedDocForMenu.title}
                  </Text>
                </View>
                <TouchableOpacity
                  style={styles.circleCloseBtn}
                  onPress={() => setSelectedDocForMenu(null)}
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
                {/* Rename */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={handleDocRename}
                >
                  <Text style={styles.actionIcon}>✎</Text>
                  <Text style={styles.actionText}>Rename</Text>
                </TouchableOpacity>

                {/* Add to New Folder */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={handleDocAddtoNewFolder}
                >
                  <Text style={styles.actionIcon}>📁</Text>
                  <Text style={styles.actionText}>Add to New Folder</Text>
                </TouchableOpacity>

                {/* Move to Folder */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={handleDocMoveToFolder}
                >
                  <Text style={styles.actionIcon}>📁⇄</Text>
                  <Text style={styles.actionText}>Move to Folder...</Text>
                </TouchableOpacity>

                {/* Delete */}
                <TouchableOpacity
                  style={styles.actionRow}
                  activeOpacity={0.7}
                  onPress={handleDocDelete}
                >
                  <Text style={styles.actionIcon}>🗑</Text>
                  <Text style={styles.actionText}>Delete</Text>
                </TouchableOpacity>

                {/* Horizontal Divider */}
                <View style={styles.dividerLine} />

                {/* Replace Document */}
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

                {/* Tags and Metadata */}
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
              </ScrollView>
            </Animated.View>
          ) : (
            /* ═════════════════════════════════════════════════════════════════ */
            /* VIEW: Main Documents Accordion Tree List (Video frame_001/012)   */
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

              {/* ── 3. Hierarchical Accordion Tree List ─────────────────────── */}
              <ScrollView
                style={styles.docsList}
                contentContainerStyle={styles.docsListContent}
              >
                {isTreeEmpty ? (
                  <View style={styles.emptyContainer}>
                    <Text style={styles.emptyText}>
                      {filterQuery
                        ? 'No matching documents found'
                        : 'No documents in workspace'}
                    </Text>
                  </View>
                ) : (
                  renderTree(null, 0)
                )}
              </ScrollView>
            </View>
          )}

          {/* ═════════════════════════════════════════════════════════════════ */}
          {/* NATIVE iOS-STYLE ALERT DIALOG (LiquidText Video frame_006/025)   */}
          {/* ═════════════════════════════════════════════════════════════════ */}
          {alertPrompt && (
            <View style={styles.alertOverlay}>
              <View style={styles.alertCard}>
                <Text style={styles.alertTitle}>{alertPrompt.title}</Text>
                {alertPrompt.subtitle ? (
                  <Text style={styles.alertSubtitle}>
                    {alertPrompt.subtitle}
                  </Text>
                ) : null}

                <View style={styles.alertInputWrapper}>
                  <TextInput
                    style={styles.alertInput}
                    value={alertInputText}
                    onChangeText={setAlertInputText}
                    autoFocus
                    selectTextOnFocus
                    returnKeyType="done"
                    onSubmitEditing={handleAlertSubmit}
                  />
                  {alertInputText.length > 0 && (
                    <TouchableOpacity
                      onPress={() => setAlertInputText('')}
                      hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                      style={styles.alertClearBtn}
                    >
                      <Text style={styles.alertClearText}>✕</Text>
                    </TouchableOpacity>
                  )}
                </View>

                <View style={styles.alertButtonsRow}>
                  <TouchableOpacity
                    style={styles.alertBtn}
                    activeOpacity={0.6}
                    onPress={handleAlertSubmit}
                  >
                    <Text style={styles.alertBtnOkayText}>Okay</Text>
                  </TouchableOpacity>

                  <View style={styles.alertBtnDivider} />

                  <TouchableOpacity
                    style={styles.alertBtn}
                    activeOpacity={0.6}
                    onPress={() => setAlertPrompt(null)}
                  >
                    <Text style={styles.alertBtnCancelText}>Cancel</Text>
                  </TouchableOpacity>
                </View>
              </View>
            </View>
          )}

          {/* ═════════════════════════════════════════════════════════════════ */}
          {/* MOVE TO FOLDER DIALOG                                             */}
          {/* ═════════════════════════════════════════════════════════════════ */}
          {isMovingDoc && (
            <View style={styles.alertOverlay}>
              <View style={styles.moveDialogCard}>
                <Text style={styles.moveDialogTitle}>Move to Folder</Text>
                <Text style={styles.moveDialogSubtitle} numberOfLines={1}>
                  Select destination for "{isMovingDoc.title}"
                </Text>

                <ScrollView
                  style={styles.moveListScroll}
                  contentContainerStyle={styles.moveListContent}
                >
                  {/* Option: Remove from folder (Move to Root) */}
                  {isMovingDoc.folderId != null && (
                    <TouchableOpacity
                      style={styles.moveItemRow}
                      activeOpacity={0.7}
                      onPress={() => {
                        onMoveDocumentToFolder?.(isMovingDoc.id, null);
                        setIsMovingDoc(null);
                      }}
                    >
                      <Text style={styles.moveItemIcon}>🏠</Text>
                      <Text style={styles.moveItemText}>
                        Root Level (Remove from Folder)
                      </Text>
                    </TouchableOpacity>
                  )}

                  {/* List of existing folders with depth */}
                  {getFolderOptions().map((f) => {
                    const isCurrent = f.id === isMovingDoc.folderId;
                    return (
                      <TouchableOpacity
                        key={f.id}
                        style={[
                          styles.moveItemRow,
                          { paddingLeft: 16 + f.depth * 20 },
                          isCurrent && styles.moveItemRowCurrent,
                        ]}
                        activeOpacity={isCurrent ? 1 : 0.7}
                        disabled={isCurrent}
                        onPress={() => {
                          onMoveDocumentToFolder?.(isMovingDoc.id, f.id);
                          setExpandedFolderIds((prev) =>
                            new Set(prev).add(f.id)
                          );
                          setIsMovingDoc(null);
                        }}
                      >
                        <Text style={styles.moveItemIcon}>📁</Text>
                        <Text
                          style={[
                            styles.moveItemText,
                            isCurrent && styles.moveItemTextCurrent,
                          ]}
                          numberOfLines={1}
                        >
                          {f.name}
                        </Text>
                        {isCurrent && (
                          <Text style={styles.moveCurrentBadge}>Current</Text>
                        )}
                      </TouchableOpacity>
                    );
                  })}
                </ScrollView>

                <View style={styles.moveCancelRow}>
                  <TouchableOpacity
                    style={styles.moveCancelBtn}
                    activeOpacity={0.7}
                    onPress={() => setIsMovingDoc(null)}
                  >
                    <Text style={styles.moveCancelText}>Cancel</Text>
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
    marginTop: Platform.OS === 'ios' ? 54 : 46, // Starts right below iOS Dynamic Island / notch and Android status bar
    paddingBottom: Platform.OS === 'ios' ? 28 : 12,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    backgroundColor: 'rgba(142, 158, 175, 0.98)', // Slate-blue matching LiquidText video
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

  // ── Hierarchical Tree Rows ──────────────────────────────────────────────
  treeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingRight: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(255, 255, 255, 0.1)',
  },
  treeRowActive: {
    backgroundColor: 'rgba(0, 0, 0, 0.12)',
  },
  treeRowLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    flex: 1,
    paddingRight: 12,
  },
  treeRowTitle: {
    fontSize: 14.5,
    fontWeight: '600',
    color: '#FFFFFF',
    flex: 1,
  },
  activeDocTitle: {
    color: '#FFFFFF',
    fontWeight: '700',
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

  // ── Icons ────────────────────────────────────────────────────────────────
  folderIconContainer: {
    width: 22,
    height: 18,
    position: 'relative',
    justifyContent: 'flex-end',
  },
  closedFolderTab: {
    position: 'absolute',
    top: 0,
    left: 0,
    width: 9,
    height: 4,
    borderTopWidth: 1.8,
    borderLeftWidth: 1.8,
    borderRightWidth: 1.8,
    borderColor: '#FFFFFF',
    borderTopLeftRadius: 2,
    borderTopRightRadius: 2,
    backgroundColor: 'transparent',
  },
  closedFolderBody: {
    width: 22,
    height: 15,
    borderWidth: 1.8,
    borderColor: '#FFFFFF',
    borderRadius: 2,
    backgroundColor: 'transparent',
  },
  openFolderBackTab: {
    position: 'absolute',
    top: 0,
    left: 0,
    width: 9,
    height: 4,
    borderTopWidth: 1.8,
    borderLeftWidth: 1.8,
    borderRightWidth: 1.8,
    borderColor: '#FFFFFF',
    borderTopLeftRadius: 2,
    borderTopRightRadius: 2,
  },
  openFolderBackPlate: {
    position: 'absolute',
    top: 3,
    left: 0,
    width: 22,
    height: 14,
    borderWidth: 1.8,
    borderColor: 'rgba(255, 255, 255, 0.75)',
    borderRadius: 2,
  },
  openFolderFrontFlap: {
    position: 'absolute',
    bottom: -1,
    left: -1,
    width: 24,
    height: 12,
    borderWidth: 1.8,
    borderColor: '#FFFFFF',
    borderRadius: 2,
    backgroundColor: 'rgba(142, 158, 175, 0.98)',
    transform: [{ skewX: '-12deg' }],
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

  // ── Menu View ────────────────────────────────────────────────────────────
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
    width: 26,
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

  // ── Native iOS Alert Dialog ──────────────────────────────────────────────
  alertOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.4)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
    zIndex: 9999,
  },
  alertCard: {
    width: '100%',
    maxWidth: 290,
    backgroundColor: 'rgba(242, 242, 247, 0.98)',
    borderRadius: 14,
    overflow: 'hidden',
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.3,
    shadowRadius: 16,
    elevation: 24,
  },
  alertTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: '#000000',
    textAlign: 'center',
    marginTop: 18,
    marginBottom: 4,
    paddingHorizontal: 16,
  },
  alertSubtitle: {
    fontSize: 13,
    color: '#3C3C43',
    textAlign: 'center',
    marginBottom: 14,
    paddingHorizontal: 16,
  },
  alertInputWrapper: {
    backgroundColor: '#FFFFFF',
    borderWidth: 0.5,
    borderColor: '#C6C6C8',
    borderRadius: 7,
    height: 32,
    marginHorizontal: 16,
    marginBottom: 18,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
  },
  alertInput: {
    flex: 1,
    fontSize: 13,
    color: '#000000',
    paddingVertical: 0,
  },
  alertClearBtn: {
    width: 16,
    height: 16,
    borderRadius: 8,
    backgroundColor: '#8E8E93',
    alignItems: 'center',
    justifyContent: 'center',
    marginLeft: 4,
  },
  alertClearText: {
    fontSize: 10,
    color: '#FFFFFF',
    fontWeight: '700',
    lineHeight: 11,
  },
  alertButtonsRow: {
    borderTopWidth: 0.5,
    borderTopColor: 'rgba(60, 60, 67, 0.29)',
    flexDirection: 'row',
    height: 44,
  },
  alertBtn: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  alertBtnDivider: {
    width: 0.5,
    backgroundColor: 'rgba(60, 60, 67, 0.29)',
    height: '100%',
  },
  alertBtnOkayText: {
    fontSize: 17,
    fontWeight: '600',
    color: '#007AFF',
  },
  alertBtnCancelText: {
    fontSize: 17,
    fontWeight: '400',
    color: '#007AFF',
  },

  // ── Move To Folder Dialog ────────────────────────────────────────────────
  moveDialogCard: {
    width: '100%',
    maxWidth: 320,
    maxHeight: '80%',
    backgroundColor: 'rgba(242, 242, 247, 0.98)',
    borderRadius: 14,
    overflow: 'hidden',
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.3,
    shadowRadius: 16,
    elevation: 24,
  },
  moveDialogTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: '#000000',
    textAlign: 'center',
    marginTop: 18,
    marginBottom: 4,
    paddingHorizontal: 16,
  },
  moveDialogSubtitle: {
    fontSize: 13,
    color: '#3C3C43',
    textAlign: 'center',
    marginBottom: 12,
    paddingHorizontal: 16,
  },
  moveListScroll: {
    maxHeight: 280,
  },
  moveListContent: {
    paddingVertical: 4,
  },
  moveItemRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(60, 60, 67, 0.15)',
    gap: 12,
  },
  moveItemRowCurrent: {
    backgroundColor: 'rgba(0, 122, 255, 0.08)',
  },
  moveItemIcon: {
    fontSize: 18,
  },
  moveItemText: {
    fontSize: 15,
    color: '#000000',
    fontWeight: '500',
    flex: 1,
  },
  moveItemTextCurrent: {
    color: '#007AFF',
    fontWeight: '600',
  },
  moveCurrentBadge: {
    fontSize: 12,
    color: '#007AFF',
    fontWeight: '600',
  },
  moveCancelRow: {
    borderTopWidth: 0.5,
    borderTopColor: 'rgba(60, 60, 67, 0.29)',
    height: 44,
  },
  moveCancelBtn: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  moveCancelText: {
    fontSize: 17,
    fontWeight: '600',
    color: '#007AFF',
  },
});
