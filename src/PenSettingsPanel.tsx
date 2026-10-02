import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Modal,
  Animated,
  Easing,
} from 'react-native';
import {
  DEFAULT_PEN_FAVORITES,
  EXPANDED_PEN_PALETTE,
  PEN_THICKNESS_PRESETS,
  type PenDrawingMode,
} from './types';

export interface PenSettingsPanelProps {
  visible: boolean;
  onClose: () => void;
  drawingMode: PenDrawingMode;
  onSelectDrawingMode: (mode: PenDrawingMode) => void;
  selectedColor: string;
  onSelectColor: (color: string) => void;
  selectedThickness: number;
  onSelectThickness: (thickness: number) => void;
  favoriteColors?: string[];
  onChangeFavorites?: (favorites: string[]) => void;
}

export const PenSettingsPanel: React.FC<PenSettingsPanelProps> = ({
  visible,
  onClose,
  drawingMode,
  onSelectDrawingMode,
  selectedColor,
  onSelectColor,
  selectedThickness,
  onSelectThickness,
  favoriteColors = DEFAULT_PEN_FAVORITES,
  onChangeFavorites,
}) => {
  const [favorites, setFavorites] = useState<string[]>(favoriteColors);
  const [isPaletteOpen, setIsPaletteOpen] = useState(false);
  const [dragCandidateColor, setDragCandidateColor] = useState<string | null>(
    null
  );

  // Sync favorites if prop changes
  useEffect(() => {
    if (favoriteColors && favoriteColors.length > 0) {
      setFavorites(favoriteColors);
    }
  }, [favoriteColors]);

  const slideAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(slideAnim, {
      toValue: visible ? 1 : 0,
      duration: 220,
      easing: Easing.bezier(0.25, 0.1, 0.25, 1),
      useNativeDriver: true,
    }).start();
  }, [visible, slideAnim]);

  if (!visible) return null;

  const handleSelectFavorite = (color: string, index: number) => {
    if (dragCandidateColor) {
      // User tapped a favorite slot while a palette color was selected for replacement
      const nextFavorites = [...favorites];
      nextFavorites[index] = dragCandidateColor;
      setFavorites(nextFavorites);
      onChangeFavorites?.(nextFavorites);
      onSelectColor(dragCandidateColor);
      setDragCandidateColor(null);
    } else {
      onSelectColor(color);
    }
  };

  const handleSelectPaletteColor = (color: string) => {
    onSelectColor(color);
    setDragCandidateColor(color);
  };

  const handleRestoreDefaults = () => {
    setFavorites(DEFAULT_PEN_FAVORITES);
    onChangeFavorites?.(DEFAULT_PEN_FAVORITES);
    onSelectColor(DEFAULT_PEN_FAVORITES[7] || '#FACC15');
    setDragCandidateColor(null);
  };

  const translateY = slideAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [30, 0],
  });

  const opacity = slideAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [0, 1],
  });

  return (
    <>
      <Animated.View
        style={[
          styles.container,
          {
            transform: [{ translateY }],
            opacity,
          },
        ]}
      >
        {/* ── 1. Drawing Style Selector & Close button ──────── */}
        <View style={styles.topHeaderRow}>
          <View style={styles.styleSelectorRow}>
            <TouchableOpacity
              style={[
                styles.styleOptionBtn,
                drawingMode === 'straight' && styles.styleOptionBtnActive,
              ]}
              activeOpacity={0.75}
              onPress={() => onSelectDrawingMode('straight')}
            >
              <View style={styles.straightIcon}>
                <View
                  style={[styles.iconDot, { backgroundColor: selectedColor }]}
                />
                <View
                  style={[styles.iconLine, { backgroundColor: selectedColor }]}
                />
                <View
                  style={[styles.iconDot, { backgroundColor: selectedColor }]}
                />
              </View>
              <Text
                style={[
                  styles.styleOptionText,
                  drawingMode === 'straight' && styles.styleOptionTextActive,
                ]}
              >
                Straight
              </Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={[
                styles.styleOptionBtn,
                drawingMode === 'freehand' && styles.styleOptionBtnActive,
              ]}
              activeOpacity={0.75}
              onPress={() => onSelectDrawingMode('freehand')}
            >
              <View style={styles.freehandIcon}>
                <View
                  style={[styles.iconDot, { backgroundColor: selectedColor }]}
                />
                <View
                  style={[styles.iconCurve, { borderColor: selectedColor }]}
                />
              </View>
              <Text
                style={[
                  styles.styleOptionText,
                  drawingMode === 'freehand' && styles.styleOptionTextActive,
                ]}
              >
                Freehand
              </Text>
            </TouchableOpacity>
          </View>

          <TouchableOpacity
            style={styles.closePanelBtn}
            activeOpacity={0.7}
            onPress={onClose}
            hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
          >
            <Text style={styles.closePanelText}>✕</Text>
          </TouchableOpacity>
        </View>

        {/* ── 2. Favorite Colors Grid (3 rows x 6 columns) ──────────────── */}
        <View style={styles.favoritesGrid}>
          {favorites.slice(0, 17).map((color, index) => {
            const isSelected =
              selectedColor.toLowerCase() === color.toLowerCase();
            return (
              <TouchableOpacity
                key={`fav-${index}-${color}`}
                style={[
                  styles.colorCircleWrapper,
                  isSelected && styles.colorCircleSelected,
                ]}
                activeOpacity={0.8}
                onPress={() => handleSelectFavorite(color, index)}
              >
                <View
                  style={[styles.colorCircle, { backgroundColor: color }]}
                />
              </TouchableOpacity>
            );
          })}

          {/* Slot 18: Multicolor Rainbow Circle to open expanded palette */}
          <TouchableOpacity
            style={styles.colorCircleWrapper}
            activeOpacity={0.8}
            onPress={() => setIsPaletteOpen(true)}
          >
            <View style={[styles.colorCircle, styles.rainbowCircle]}>
              <View style={[styles.rainbowQuarter, styles.rq1]} />
              <View style={[styles.rainbowQuarter, styles.rq2]} />
              <View style={[styles.rainbowQuarter, styles.rq3]} />
              <View style={[styles.rainbowQuarter, styles.rq4]} />
            </View>
          </TouchableOpacity>
        </View>

        {/* ── 3. Stroke Thickness Presets (7 vertical samples) ─────────── */}
        <View style={styles.thicknessRow}>
          {PEN_THICKNESS_PRESETS.map((preset, index) => {
            const isSelected = Math.abs(selectedThickness - preset) < 0.3;
            // Visual height is 34px, width corresponds to preset width
            const barW = Math.max(1.5, Math.min(preset * 0.9, 10));
            return (
              <TouchableOpacity
                key={`thickness-${index}-${preset}`}
                style={[
                  styles.thicknessBtn,
                  isSelected && styles.thicknessBtnSelected,
                ]}
                activeOpacity={0.7}
                onPress={() => onSelectThickness(preset)}
              >
                <View
                  style={[
                    styles.thicknessBar,
                    {
                      width: barW,
                      backgroundColor: selectedColor,
                    },
                  ]}
                />
              </TouchableOpacity>
            );
          })}
        </View>
      </Animated.View>

      {/* ── 4. Expanded Color Palette Modal matching LiquidText Screenshot ── */}
      <Modal
        visible={isPaletteOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setIsPaletteOpen(false)}
      >
        <TouchableOpacity
          style={styles.modalBackdrop}
          activeOpacity={1}
          onPress={() => setIsPaletteOpen(false)}
        >
          <TouchableOpacity
            style={styles.paletteCard}
            activeOpacity={1}
            onPress={(e) => e.stopPropagation()}
          >
            {/* Header */}
            <View style={styles.paletteHeader}>
              <View style={styles.paletteTitleContainer}>
                <Text style={styles.paletteTitle}>Tap color</Text>
                <Text style={styles.paletteSubtitle}>
                  (Or drag color to favorite)
                </Text>
              </View>
              <TouchableOpacity
                style={styles.paletteCloseBtn}
                onPress={() => setIsPaletteOpen(false)}
                activeOpacity={0.7}
              >
                <Text style={styles.paletteCloseText}>✕</Text>
              </TouchableOpacity>
            </View>

            {/* 32-Color Grid (4 rows x 8 columns) */}
            <View style={styles.paletteGrid}>
              {EXPANDED_PEN_PALETTE.map((color, idx) => {
                const isSelected =
                  selectedColor.toLowerCase() === color.toLowerCase();
                return (
                  <TouchableOpacity
                    key={`palette-${idx}-${color}`}
                    style={[
                      styles.paletteSwatchWrapper,
                      isSelected && styles.paletteSwatchSelected,
                    ]}
                    activeOpacity={0.8}
                    onPress={() => handleSelectPaletteColor(color)}
                  >
                    <View
                      style={[
                        styles.paletteSwatch,
                        { backgroundColor: color },
                        color.toLowerCase() === '#ffffff' &&
                          styles.whiteSwatchBorder,
                      ]}
                    />
                  </TouchableOpacity>
                );
              })}
            </View>

            {/* Restore Default Favorites Action */}
            <TouchableOpacity
              style={styles.restoreBtn}
              activeOpacity={0.7}
              onPress={handleRestoreDefaults}
            >
              <Text style={styles.restoreBtnText}>
                Restore Default Favorites
              </Text>
            </TouchableOpacity>
          </TouchableOpacity>
        </TouchableOpacity>
      </Modal>
    </>
  );
};

const styles = StyleSheet.create({
  container: {
    backgroundColor: '#FFFFFF',
    borderRadius: 18,
    paddingTop: 14,
    paddingBottom: 16,
    paddingHorizontal: 18,
    width: 320,
    alignSelf: 'center',
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.16,
    shadowRadius: 18,
    elevation: 16,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#E2E8F0',
  },
  topHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 16,
  },
  styleSelectorRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    flex: 1,
  },
  closePanelBtn: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: '#F1F5F9',
    alignItems: 'center',
    justifyContent: 'center',
    marginLeft: 6,
  },
  closePanelText: {
    fontSize: 13,
    color: '#64748B',
    fontWeight: '700',
  },
  styleOptionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 10,
    borderWidth: 1.5,
    borderColor: 'transparent',
    backgroundColor: '#F8FAFC',
  },
  styleOptionBtnActive: {
    borderColor: '#FACC15',
    backgroundColor: '#FEFCE8',
  },
  styleOptionText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#475569',
  },
  styleOptionTextActive: {
    color: '#1E293B',
    fontWeight: '700',
  },
  straightIcon: {
    width: 24,
    height: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  iconDot: {
    width: 5,
    height: 5,
    borderRadius: 2.5,
  },
  iconLine: {
    flex: 1,
    height: 2,
    marginHorizontal: 1,
  },
  freehandIcon: {
    width: 22,
    height: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconCurve: {
    width: 14,
    height: 8,
    borderBottomWidth: 2,
    borderRadius: 4,
    marginLeft: 2,
  },
  favoritesGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    rowGap: 12,
    marginBottom: 16,
    paddingHorizontal: 2,
  },
  colorCircleWrapper: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 20,
  },
  colorCircleSelected: {
    borderWidth: 2.5,
    borderColor: '#64748B',
    backgroundColor: 'rgba(255, 255, 255, 0.9)',
  },
  colorCircle: {
    width: 28,
    height: 28,
    borderRadius: 14,
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.12,
    shadowRadius: 3,
    elevation: 3,
  },
  rainbowCircle: {
    overflow: 'hidden',
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  rainbowQuarter: {
    width: 14,
    height: 14,
  },
  rq1: { backgroundColor: '#EC4899' },
  rq2: { backgroundColor: '#3B82F6' },
  rq3: { backgroundColor: '#10B981' },
  rq4: { backgroundColor: '#F59E0B' },
  thicknessRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 6,
    height: 48,
  },
  thicknessBtn: {
    width: 34,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 8,
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  thicknessBtnSelected: {
    borderColor: '#94A3B8',
    backgroundColor: '#F8FAFC',
  },
  thicknessBar: {
    height: 30,
    borderRadius: 4,
  },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(15, 23, 42, 0.55)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  paletteCard: {
    width: 330,
    backgroundColor: '#1E293B',
    borderRadius: 20,
    padding: 20,
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 12 },
    shadowOpacity: 0.45,
    shadowRadius: 24,
    elevation: 24,
    borderWidth: 1,
    borderColor: '#334155',
  },
  paletteHeader: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    marginBottom: 16,
  },
  paletteTitleContainer: {
    flex: 1,
    alignItems: 'center',
  },
  paletteTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: '#F8FAFC',
  },
  paletteSubtitle: {
    fontSize: 12,
    fontWeight: '500',
    color: '#94A3B8',
    marginTop: 2,
  },
  paletteCloseBtn: {
    padding: 4,
    position: 'absolute',
    right: 0,
    top: 0,
  },
  paletteCloseText: {
    fontSize: 16,
    fontWeight: '700',
    color: '#94A3B8',
  },
  paletteGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    rowGap: 12,
    marginBottom: 18,
  },
  paletteSwatchWrapper: {
    width: 32,
    height: 32,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 16,
  },
  paletteSwatchSelected: {
    borderWidth: 2,
    borderColor: '#38BDF8',
  },
  paletteSwatch: {
    width: 24,
    height: 24,
    borderRadius: 12,
  },
  whiteSwatchBorder: {
    borderWidth: 1,
    borderColor: '#CBD5E1',
  },
  restoreBtn: {
    alignItems: 'center',
    paddingVertical: 6,
  },
  restoreBtnText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#38BDF8',
  },
});
