import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

export interface SpatialScanPlugin {
  isSupported(): Promise<{ supported: boolean; reason?: UnsupportedReason }>;
  requestPermissions(): Promise<SpatialScanPermissionStatus>;
  startScan(options?: ScanOptions): Promise<void>;
  stopScan(): Promise<ScanResult>;
  cancelScan(): Promise<void>;
  getScanStatus(): Promise<ScanStatus>;
  addListener(eventName: 'onFrameUpdate', listenerFunc: (data: FrameUpdateEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'onTrackingStateChanged', listenerFunc: (data: TrackingStateEvent) => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

export interface ScanOptions {
  enableDepth?: boolean;
  minScanDurationSeconds?: number;
}

export interface ScanResult {
  success: boolean;
  error?: ScanError;
  walls: Wall[];
  floor: FloorPolygon | null;
  roomDimensions: RoomDimensions;
  scanMetadata: ScanMetadata;
}

export interface Wall {
  id: string;
  startPoint: Point3D;
  endPoint: Point3D;
  length: number;
  height: number;
  normal: Vector3D;
  confidence: number;
}

export interface FloorPolygon {
  vertices: Point3D[];
  area: number;
}

export interface RoomDimensions {
  width: number;
  length: number;
  height: number;
  area: number;
  perimeter: number;
}

export interface ScanMetadata {
  scanDurationSeconds: number;
  planesDetected: number;
  wallsInResult: number;
  depthApiUsed: boolean;
  arcoreVersion: string;
}

export interface Point3D { x: number; y: number; z: number; }
export interface Vector3D { x: number; y: number; z: number; }

export interface ScanStatus {
  isScanning: boolean;
  trackingState: ARTrackingState;
  planesDetected: number;
  scanDurationSeconds: number;
}

export interface FrameUpdateEvent {
  trackingState: ARTrackingState;
  planesDetected: number;
  wallsDetected: number;
  coverageEstimate: number;
  scanDurationSeconds: number;
}

export interface TrackingStateEvent {
  trackingState: ARTrackingState;
  pauseReason?: TrackingPauseReason;
}

export interface SpatialScanPermissionStatus {
  camera: PermissionState;
}

export type ARTrackingState = 'TRACKING' | 'PAUSED' | 'STOPPED';
export type ScanError = 'DEVICE_NOT_SUPPORTED' | 'ARCORE_NOT_INSTALLED' | 'PERMISSION_DENIED' | 'SESSION_FAILED' | 'TRACKING_INSUFFICIENT' | 'SCAN_TOO_SHORT' | 'INTERNAL_ERROR';
export type UnsupportedReason = 'ARCORE_NOT_AVAILABLE' | 'SDK_TOO_OLD' | 'DEVICE_NOT_CERTIFIED';
export type TrackingPauseReason = 'INITIALIZING' | 'EXCESSIVE_MOTION' | 'INSUFFICIENT_LIGHT' | 'INSUFFICIENT_FEATURES' | 'CAMERA_UNAVAILABLE';
