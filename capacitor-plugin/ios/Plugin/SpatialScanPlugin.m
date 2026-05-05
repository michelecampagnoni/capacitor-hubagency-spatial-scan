#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(SpatialScanPlugin, "SpatialScan",
    CAP_PLUGIN_METHOD(isSupported,        CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(requestPermissions, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(startScan,          CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(stopScan,           CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(cancelScan,         CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(exportPdf,          CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getScanStatus,      CAPPluginReturnPromise);
)
