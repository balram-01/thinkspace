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
    onSelectColor(DEFAULT_PEN_FAVORITES[16] || '#1E3A8A');
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

  // Calibrated bar widths for the 6 thickness bars
  const visualBarWidths = [2.5, 4.5, 7.0, 10.0, 13.5, 17.5];

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
        {/* ── 1. Drawing Style Selector (Straight | Freehand) ──────── */}
        <View style={styles.topHeaderRow}>
          <View style={styles.styleSelectorRow}>
            {/* Straight Option */}
            <TouchableOpacity
              style={[
                styles.styleOptionBtn,
                drawingMode === 'straight' && styles.styleOptionBtnActive,
              ]}
              activeOpacity={0.75}
              onPress={() => onSelectDrawingMode('straight')}
            >
              <View style={styles.straightIcon}>
                <View style={styles.iconDot} />
                <View style={styles.iconLine} />
                <View style={styles.iconDot} />
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

            {/* Freehand Option */}
            <TouchableOpacity
              style={[
                styles.styleOptionBtn,
                drawingMode === 'freehand' && styles.styleOptionBtnActive,
              ]}
              activeOpacity={0.75}
              onPress={() => onSelectDrawingMode('freehand')}
            >
              <View style={styles.freehandIcon}>
                <View style={styles.iconDot} />
                <View style={styles.iconCurve} />
                <View style={styles.iconDot} />
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
        </View>

        {/* ── 2. Favorite Colors Grid (3 rows x 6 columns = 18 slots) ── */}
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
                  style={[
                    styles.colorCircle,
                    isSelected && styles.colorCircleInnerSelected,
                    { backgroundColor: color },
                  ]}
                />
              </TouchableOpacity>
            );
          })}

          {/* Slot 18: Multicolor Rainbow Swatch opens expanded palette */}
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
              <View style={styles.rainbowCenterGlow} />
            </View>
          </TouchableOpacity>
        </View>

        {/* ── 3. Stroke Thickness Presets (6 vertical bars) ─────────── */}
        <View style={styles.thicknessRow}>
          {PEN_THICKNESS_PRESETS.map((preset, index) => {
            const isSelected = Math.abs(selectedThickness - preset) < 0.6;
            const barW = visualBarWidths[index] ?? preset;
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

      {/* ── 4. Expanded Color Palette Modal matching Screenshot 2 ── */}
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
            {/* Header with Title, Subtitle, and ✕ Close Button */}
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
                hitSlop={{ top: 12, bottom: 12, left: 12, right: 12 }}
              >
                <Text style={styles.paletteCloseText}>✕</Text>
              </TouchableOpacity>
            </View>

            {/* 32-Color Grid (4 rows x 8 columns) */}
            <View style={styles.paletteGrid}>
              {EXPANDED_PEN_PALETTE.map((color, idx) => {
                const isDivider = color === '#FFFFFF_DIVIDER';
                const actualColor = isDivider ? '#FFFFFF' : color;
                const isSelected =
                  selectedColor.toLowerCase() === actualColor.toLowerCase();
                return (
                  <TouchableOpacity
                    key={`palette-${idx}-${color}`}
                    style={[
                      styles.paletteSwatchWrapper,
                      isSelected && styles.paletteSwatchSelected,
                    ]}
                    activeOpacity={0.8}
                    onPress={() => handleSelectPaletteColor(actualColor)}
                  >
                    <View
                      style={[
                        styles.paletteSwatch,
                        { backgroundColor: actualColor },
                        actualColor.toLowerCase() === '#ffffff' &&
                          styles.whiteSwatchBorder,
                      ]}
                    >
                      {isDivider && <View style={styles.dividerLine} />}
                    </View>
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
    paddingHorizontal: 16,
    width: 326,
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
    justifyContent: 'center',
    marginBottom: 14,
  },
  styleSelectorRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
  },
  styleOptionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 8,
    borderWidth: 1.5,
    borderColor: 'transparent',
    backgroundColor: 'transparent',
  },
  styleOptionBtnActive: {
    borderColor: '#374151',
    backgroundColor: '#FFFFFF',
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.08,
    shadowRadius: 2,
    elevation: 2,
  },
  styleOptionText: {
    fontSize: 13.5,
    fontWeight: '500',
    color: '#64748B',
  },
  styleOptionTextActive: {
    color: '#1E293B',
    fontWeight: '700',
  },
  straightIcon: {
    width: 22,
    height: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  iconDot: {
    width: 4.5,
    height: 4.5,
    borderRadius: 2.25,
    backgroundColor: '#5C7CFA',
  },
  iconLine: {
    flex: 1,
    height: 1.8,
    backgroundColor: '#5C7CFA',
    marginHorizontal: 1,
  },
  freehandIcon: {
    width: 22,
    height: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  iconCurve: {
    flex: 1,
    height: 8,
    borderBottomWidth: 1.8,
    borderColor: '#5C7CFA',
    borderRadius: 4,
    marginHorizontal: 1,
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
    width: 38,
    height: 38,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 19,
    borderWidth: 2,
    borderColor: 'transparent',
    backgroundColor: 'transparent',
  },
  colorCircleSelected: {
    borderColor: '#374151',
    backgroundColor: '#FFFFFF',
  },
  colorCircle: {
    width: 28,
    height: 28,
    borderRadius: 14,
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.18,
    shadowRadius: 3,
    elevation: 3,
  },
  colorCircleInnerSelected: {
    width: 24,
    height: 24,
    borderRadius: 12,
  },
  rainbowCircle: {
    overflow: 'hidden',
    flexDirection: 'row',
    flexWrap: 'wrap',
    position: 'relative',
  },
  rainbowQuarter: {
    width: 14,
    height: 14,
  },
  rq1: { backgroundColor: '#EF4444' }, // Red top-left
  rq2: { backgroundColor: '#3B82F6' }, // Blue top-right
  rq3: { backgroundColor: '#22C55E' }, // Green bottom-left
  rq4: { backgroundColor: '#EC4899' }, // Magenta bottom-right
  rainbowCenterGlow: {
    position: 'absolute',
    top: 4,
    left: 4,
    right: 4,
    bottom: 4,
    borderRadius: 10,
    backgroundColor: '#00F0FF',
    opacity: 0.45,
  },
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
    borderRadius: 6,
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  thicknessBtnSelected: {
    borderColor: '#374151',
    backgroundColor: 'rgba(0, 0, 0, 0.02)',
  },
  thicknessBar: {
    height: 30,
    borderRadius: 2,
  },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(15, 23, 42, 0.45)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  paletteCard: {
    width: 334,
    backgroundColor: 'rgba(142, 158, 175, 0.96)',
    borderRadius: 18,
    paddingTop: 16,
    paddingBottom: 16,
    paddingHorizontal: 16,
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.35,
    shadowRadius: 20,
    elevation: 20,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.25)',
  },
  paletteHeader: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'center',
    marginBottom: 16,
    position: 'relative',
  },
  paletteTitleContainer: {
    alignItems: 'center',
  },
  paletteTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: '#FFFFFF',
    letterSpacing: 0.2,
  },
  paletteSubtitle: {
    fontSize: 12.5,
    fontWeight: '500',
    color: '#F1F5F9',
    marginTop: 2,
  },
  paletteCloseBtn: {
    position: 'absolute',
    right: 0,
    top: -2,
    padding: 4,
  },
  paletteCloseText: {
    fontSize: 18,
    fontWeight: '900',
    color: '#000000',
  },
  paletteGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    rowGap: 10,
    marginBottom: 16,
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
    borderColor: '#FFFFFF',
  },
  paletteSwatch: {
    width: 25,
    height: 25,
    borderRadius: 12.5,
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.25,
    shadowRadius: 2.5,
    elevation: 3,
    alignItems: 'center',
    justifyContent: 'center',
  },
  whiteSwatchBorder: {
    borderWidth: 1,
    borderColor: '#CBD5E1',
  },
  dividerLine: {
    width: '100%',
    height: 1.5,
    backgroundColor: '#475569',
  },
  restoreBtn: {
    alignItems: 'center',
    paddingVertical: 4,
  },
  restoreBtnText: {
    fontSize: 13.5,
    fontWeight: '600',
    color: '#00F0FF',
  },
});
