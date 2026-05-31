// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "LmsApi",
    platforms: [
        .iOS(.v14),
    ],
    products: [
        .library(name: "LmsApi", targets: ["LmsApi"])
    ],
    targets: [
        .binaryTarget(
            name: "LmsApi",
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.3.7/LmsApi.xcframework.zip",
            checksum: "f9f6a177e4c63098b4e311f36bfd35095b7fa530929e001c42d794dae602306c"
        )
    ]
)
