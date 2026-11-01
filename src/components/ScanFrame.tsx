import React, { useEffect, useRef } from 'react';
import { Animated, Easing, StyleSheet, View } from 'react-native';

const SIZE = 240;
const CORNER = 36;
const THICKNESS = 4;

/** Khung ngắm 4 góc, "thở" nhẹ để người dùng biết app đang chờ quét. */
const ScanFrame: React.FC = () => {
  const pulse = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 1, duration: 1100, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 0, duration: 1100, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [pulse]);

  const scale = pulse.interpolate({ inputRange: [0, 1], outputRange: [1, 1.05] });
  const opacity = pulse.interpolate({ inputRange: [0, 1], outputRange: [0.65, 1] });

  return (
    <Animated.View style={[styles.frame, { opacity, transform: [{ scale }] }]}>
      <View style={[styles.corner, styles.topLeft]} />
      <View style={[styles.corner, styles.topRight]} />
      <View style={[styles.corner, styles.bottomLeft]} />
      <View style={[styles.corner, styles.bottomRight]} />
    </Animated.View>
  );
};

const styles = StyleSheet.create({
  frame: { width: SIZE, height: SIZE },
  corner: { position: 'absolute', width: CORNER, height: CORNER, borderColor: '#fff' },
  topLeft: { top: 0, left: 0, borderTopWidth: THICKNESS, borderLeftWidth: THICKNESS, borderTopLeftRadius: 16 },
  topRight: { top: 0, right: 0, borderTopWidth: THICKNESS, borderRightWidth: THICKNESS, borderTopRightRadius: 16 },
  bottomLeft: {
    bottom: 0,
    left: 0,
    borderBottomWidth: THICKNESS,
    borderLeftWidth: THICKNESS,
    borderBottomLeftRadius: 16,
  },
  bottomRight: {
    bottom: 0,
    right: 0,
    borderBottomWidth: THICKNESS,
    borderRightWidth: THICKNESS,
    borderBottomRightRadius: 16,
  },
});

export default ScanFrame;
