import React, { useRef, useState } from 'react';
import { View, StyleSheet, PanResponder } from 'react-native';
import type { SplitDividerProps } from './types';

export const SplitDivider: React.FC<SplitDividerProps> = ({
  splitRatio,
  onRatioChange,
  minRatio = 0.15,
  maxRatio = 0.85,
}) => {
  const [isDragging, setIsDragging] = useState(false);
  const containerHeightRef = useRef(700);

  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: () => {
        setIsDragging(true);
      },
      onPanResponderMove: (_, gestureState) => {
        const totalH = containerHeightRef.current || 700;
        const delta = gestureState.dy / totalH;
        const newRatio = Math.max(
          minRatio,
          Math.min(maxRatio, splitRatio + delta)
        );
        onRatioChange(newRatio);
      },
      onPanResponderRelease: () => {
        setIsDragging(false);
      },
      onPanResponderTerminate: () => {
        setIsDragging(false);
      },
    })
  ).current;

  return (
    <View
      style={styles.touchZone}
      onLayout={(e) => {
        // Measure parent/screen height
        containerHeightRef.current = e.nativeEvent.layout.height * 25;
      }}
      {...panResponder.panHandlers}
    >
      {/* Hairline */}
      <View style={[styles.hairline, isDragging && styles.activeHairline]} />

      {/* Pill Handle */}
      <View style={[styles.handle, isDragging && styles.activeHandle]}>
        <View style={styles.gripLine} />
        <View style={styles.gripLine} />
        <View style={styles.gripLine} />
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  touchZone: {
    height: 24,
    width: '100%',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#181A20',
    zIndex: 30,
    cursor: 'row-resize',
  } as any,
  hairline: {
    position: 'absolute',
    left: 0,
    right: 0,
    top: '50%',
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.1)',
  },
  activeHairline: {
    backgroundColor: '#00ADB5',
    height: 2,
  },
  handle: {
    width: 48,
    height: 16,
    borderRadius: 8,
    backgroundColor: '#222831',
    borderWidth: 1.5,
    borderColor: '#393E46',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
    elevation: 4,
  },
  activeHandle: {
    backgroundColor: '#00ADB5',
    borderColor: '#FFFFFF',
    transform: [{ scale: 1.15 }],
  },
  gripLine: {
    width: 18,
    height: 1.5,
    borderRadius: 1,
    backgroundColor: '#EEEEEE',
    opacity: 0.7,
  },
});

export default SplitDivider;
