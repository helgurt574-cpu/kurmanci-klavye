// swift-tools-version:5.9
// Kurmancî Klavye for macOS: an InputMethodKit input method. The lookup logic lives in KurmanciCore (pure Swift,
// builds and tests on Linux too); the input method app itself only builds on macOS. Use ./build.sh to make the
// installable .app bundle.
import PackageDescription

var targets: [Target] = [
    .target(name: "KurmanciCore"),
    .testTarget(name: "KurmanciCoreTests", dependencies: ["KurmanciCore"]),
]
var products: [Product] = [.library(name: "KurmanciCore", targets: ["KurmanciCore"])]

#if os(macOS)
targets.append(.executableTarget(
    name: "KurmanciKlavye",
    dependencies: ["KurmanciCore"],
    linkerSettings: [.linkedFramework("InputMethodKit"), .linkedFramework("Carbon")]
))
products.append(.executable(name: "KurmanciKlavye", targets: ["KurmanciKlavye"]))
#endif

let package = Package(
    name: "KurmanciKlavye",
    platforms: [.macOS(.v13)],
    products: products,
    targets: targets
)
