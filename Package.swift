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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.4.3/LmsApi.xcframework.zip",
            checksum: "89853fa50c7640b286f674a8c19e210a3d39b71e406a43430cffaa4ebf0675b7"
        )
    ]
)
