// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorHubagencySpatialScan",
    platforms: [.iOS(.v14)],
    products: [
        .library(
            name: "CapacitorHubagencySpatialScan",
            targets: ["Plugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "Plugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Plugin",
            exclude: ["SpatialScanPlugin.m"],
            linkerSettings: [
                .linkedFramework("ARKit"),
                .linkedFramework("GLKit"),
                .linkedFramework("OpenGLES"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("AVFoundation")
            ]
        )
    ]
)
