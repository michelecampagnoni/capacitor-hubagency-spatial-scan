import { WebPlugin } from '@capacitor/core';
import type { SpatialScanPlugin, ScanOptions, ScanResult, ScanStatus, SpatialScanPermissionStatus } from './definitions';

export class SpatialScanWeb extends WebPlugin implements SpatialScanPlugin {
  async isSupported(): Promise<{ supported: boolean }> {
    return { supported: false };
  }
  async requestPermissions(): Promise<SpatialScanPermissionStatus> {
    throw this.unavailable('SpatialScan non disponibile su web.');
  }
  async startScan(_options?: ScanOptions): Promise<void> {
    throw this.unavailable('SpatialScan non disponibile su web.');
  }
  async stopScan(): Promise<ScanResult> {
    throw this.unavailable('SpatialScan non disponibile su web.');
  }
  async cancelScan(): Promise<void> {
    throw this.unavailable('SpatialScan non disponibile su web.');
  }
  async getScanStatus(): Promise<ScanStatus> {
    throw this.unavailable('SpatialScan non disponibile su web.');
  }
  async exportPdf(): Promise<{ pdfPath: string }> {
    throw this.unavailable('SpatialScan non disponibile su web.');
  }
}
