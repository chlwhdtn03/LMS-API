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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.1/LmsApi.xcframework.zip",
            checksum: "0813bb7335e2e8cc8cb7b5477bd5a2db5c99884e0817ba4e3d456cf15e65b3b4"
        )
    ]
)
