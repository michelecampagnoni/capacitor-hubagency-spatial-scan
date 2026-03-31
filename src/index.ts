import { registerPlugin } from '@capacitor/core';
import type { SpatialScanPlugin } from './definitions';

const SpatialScan = registerPlugin<SpatialScanPlugin>('SpatialScan', {
  web: () => import('./web').then((m) => new m.SpatialScanWeb()),
});

export * from './definitions';
export { SpatialScan };
