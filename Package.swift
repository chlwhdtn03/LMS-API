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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.6/LmsApi.xcframework.zip",
            checksum: "30e41d71a725c47da5022be2429c3d5736e2972a824ecfe53b41a5b0ea40c5b3"
        )
    ]
)
