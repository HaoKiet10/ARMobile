import React, { useEffect, useState } from 'react';
import { StyleSheet, View, Text, PermissionsAndroid, Platform } from 'react-native';
import {
  ArCameraView,
  addAnchorFoundListener,
  addAnchorUpdatedListener,
  addAnchorRemovedListener,
} from './ArTracking';

export default function App() {
  const [hasPermission, setHasPermission] = useState(false);

  useEffect(() => {
    (async () => {
      if (Platform.OS === 'android') {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: 'Cần quyền Camera',
            message: 'App cần camera để nhận diện marker AR',
            buttonPositive: 'Đồng ý',
          },
        );
        setHasPermission(granted === PermissionsAndroid.RESULTS.GRANTED);
      } else {
        setHasPermission(true);
      }
    })();
  }, []);

  useEffect(() => {
    const s1 = addAnchorFoundListener((e) => console.log('[AR] found', e.name));
    const s2 = addAnchorUpdatedListener((e) =>
      console.log(
        '[AR] pose',
        'pos=', e.tx.toFixed(4), e.ty.toFixed(4), e.tz.toFixed(4),
        'quat=', e.qx.toFixed(3), e.qy.toFixed(3), e.qz.toFixed(3), e.qw.toFixed(3),
      ),
    );
    const s3 = addAnchorRemovedListener((e) => console.log('[AR] removed', e.name));
    return () => {
      s1.remove();
      s2.remove();
      s3.remove();
    };
  }, []);

  if (!hasPermission) {
    return (
      <View style={styles.center}>
        <Text>Đang chờ quyền camera...</Text>
      </View>
    );
  }

  return (
    <View style={styles.flex}>
      <ArCameraView
        style={styles.flex}
        targetAssetName="target.jpg"
        targetName="target"
        physicalWidth={0.15}
        overlayBoxSize={0.05}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});