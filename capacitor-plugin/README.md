# capacitor-hubagency-spatial-scan

Capacitor Android plugin for ARCore spatial scanning and floor plan generation. Detects walls, floors and room dimensions using Google ARCore Plane Detection and Depth API.

> **Android only.** The plugin returns `isSupported: false` on iOS and web without crashing.

---

## Requirements

| Requirement | Minimum |
|-------------|---------|
| Android API level | 24 (Android 7.0) |
| ARCore-certified device | Required ([device list](https://developers.google.com/ar/devices)) |
| ARCore Depth API | Optional — improves wall accuracy if supported |
| Capacitor | >= 8.0.0 |
| Google Play Services for AR | Installed automatically on first launch |

Tested on: **Xiaomi Redmi Note 12 Pro+ 5G** (ARCore + Depth API supported).

---

## Installation

### From GitHub (production)

```bash
npm install github:michelecampagnoni/capacitor-hubagency-spatial-scan#v1.0.0
npx cap sync android
```

### From local path (development)

```bash
npm install file:../../hubagencynative/capacitor-plugin
npx cap sync android
```

### Android permissions

Add to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />

<meta-data
    android:name="com.google.ar.core"
    android:value="required" />
```

---

## Usage

```typescript
import { SpatialScan } from 'capacitor-hubagency-spatial-scan';
import { Capacitor } from '@capacitor/core';

// Check device support
const { supported, reason } = await SpatialScan.isSupported();
if (!supported) {
  console.warn('ARCore not available:', reason);
  return;
}

// Request camera permission
const { camera } = await SpatialScan.requestPermissions();
if (camera !== 'granted') return;

// Listen to frame updates
const listener = await SpatialScan.addListener('onFrameUpdate', (data) => {
  console.log('Tracking:', data.trackingState);
  console.log('Walls detected:', data.wallsDetected);
  console.log('Coverage:', Math.round(data.coverageEstimate * 100) + '%');
});

// Start scan — opens fullscreen ARCore Activity with camera preview
await SpatialScan.startScan({ enableDepth: true, minScanDurationSeconds: 20 });

// After at least 20 seconds, stop and get results
const result = await SpatialScan.stopScan();
listener.remove();

if (result.success) {
  console.log('Walls:', result.walls.length);
  console.log('Room area:', result.roomDimensions.area, 'm²');
  console.log('Depth API used:', result.scanMetadata.depthApiUsed);
}
```

### Safe conditional import (web/iOS safe)

```typescript
let SpatialScan: any = null;
if (Capacitor.isNativePlatform()) {
  import('capacitor-hubagency-spatial-scan').then(m => {
    SpatialScan = m.SpatialScan;
  });
}
```

---

## API

### `isSupported()`

```typescript
isSupported(): Promise<{ supported: boolean; reason?: UnsupportedReason }>
```

Checks if the device supports ARCore. Does not open camera.

| `reason` value | Meaning |
|----------------|---------|
| `ARCORE_NOT_AVAILABLE` | Google Play Services for AR not installed |
| `SDK_TOO_OLD` | ARCore SDK version insufficient |
| `DEVICE_NOT_CERTIFIED` | Device not on ARCore certified list |

---

### `requestPermissions()`

```typescript
requestPermissions(): Promise<{ camera: PermissionState }>
```

Requests camera permission. `camera` is `'granted'`, `'denied'`, or `'prompt'`.

---

### `startScan(options?)`

```typescript
startScan(options?: ScanOptions): Promise<void>
```

Opens a fullscreen `ScanningActivity` with ARCore camera preview. The user points the device at walls to build the room model. Returns immediately — scan runs until `stopScan()` or `cancelScan()` is called.

```typescript
interface ScanOptions {
  enableDepth?: boolean;          // Use Depth API for better wall accuracy (default: true)
  minScanDurationSeconds?: number; // Minimum seconds before stopScan is allowed (default: 0)
}
```

---

### `stopScan()`

```typescript
stopScan(): Promise<ScanResult>
```

Stops the scan and returns the room data.

```typescript
interface ScanResult {
  success: boolean;
  error?: ScanError;
  walls: Wall[];
  floor: FloorPolygon | null;
  roomDimensions: RoomDimensions;
  scanMetadata: ScanMetadata;
}

interface Wall {
  id: string;
  startPoint: Point3D;  // meters
  endPoint: Point3D;    // meters
  length: number;       // meters
  height: number;       // meters
  normal: Vector3D;
  confidence: number;   // 0.0 – 1.0
}

interface RoomDimensions {
  width: number;      // meters
  length: number;     // meters
  height: number;     // meters
  area: number;       // square meters
  perimeter: number;  // meters
}

interface ScanMetadata {
  scanDurationSeconds: number;
  planesDetected: number;
  wallsInResult: number;
  depthApiUsed: boolean;
  arcoreVersion: string;
}
```

---

### `cancelScan()`

```typescript
cancelScan(): Promise<void>
```

Cancels the scan without returning results. Closes the ARCore Activity.

---

### `getScanStatus()`

```typescript
getScanStatus(): Promise<ScanStatus>
```

Polls current scan state without using event listeners.

```typescript
interface ScanStatus {
  isScanning: boolean;
  trackingState: ARTrackingState;
  planesDetected: number;
  scanDurationSeconds: number;
}
```

---

### Events

#### `onFrameUpdate`

Fired every ARCore frame (up to 30fps). Use to update UI progress.

```typescript
interface FrameUpdateEvent {
  trackingState: ARTrackingState;   // 'TRACKING' | 'PAUSED' | 'STOPPED'
  planesDetected: number;
  wallsDetected: number;
  coverageEstimate: number;         // 0.0 – 1.0
  scanDurationSeconds: number;
}
```

#### `onTrackingStateChanged`

Fired when ARCore tracking state changes.

```typescript
interface TrackingStateEvent {
  trackingState: ARTrackingState;
  pauseReason?: TrackingPauseReason;
  // 'INITIALIZING' | 'EXCESSIVE_MOTION' | 'INSUFFICIENT_LIGHT'
  // | 'INSUFFICIENT_FEATURES' | 'CAMERA_UNAVAILABLE'
}
```

---

## Error codes

| Code | When |
|------|------|
| `DEVICE_NOT_SUPPORTED` | `isSupported()` returned false |
| `ARCORE_NOT_INSTALLED` | Google Play Services for AR missing |
| `PERMISSION_DENIED` | Camera permission not granted |
| `SESSION_FAILED` | ARCore session failed to initialize |
| `TRACKING_INSUFFICIENT` | Scan stopped with too few planes/walls |
| `SCAN_TOO_SHORT` | Stopped before `minScanDurationSeconds` |
| `INTERNAL_ERROR` | Unexpected native exception |

---

## Architecture

```
SpatialScanPlugin.kt      — Capacitor bridge (JS ↔ Kotlin)
ScanningActivity.kt       — Fullscreen ARCore Activity (GLSurfaceView)
BackgroundRenderer.kt     — GLSL shader for camera feed preview
PlaneProcessor.kt         — ARCore plane tracking → wall candidates
WallDetector.kt           — Extracts wall geometry from vertical planes
DepthProcessor.kt         — Refines walls using ARCore Depth API (every 2s)
SpatialScanManager.kt     — Device support check (isSupported)
```

**Key implementation detail**: ARCore requires `session.setCameraTextureName(textureId)` to be called from within an active GL context (inside `GLSurfaceView.Renderer.onSurfaceCreated()`) before `session.update()` will produce valid frames. Omitting this call causes the camera LED to activate but no tracking to occur.

---

## Building the plugin

```bash
cd capacitor-plugin
npm install
npm run build
```

## Building the test app

```bash
cd capacitor-test-app
npm run build && npx cap sync android
cd android
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleDebug
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
adb -s <DEVICE_ID> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Logcat

```bash
adb -s <DEVICE_ID> logcat --pid=$(adb -s <DEVICE_ID> shell pidof it.hubagency.test | tr -d '\r')
```

---

## License

Private — HubAgency internal use.
