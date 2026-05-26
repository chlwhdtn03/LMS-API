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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.2.6/LmsApi.xcframework.zip",
            checksum: "26eddb4e30d199b11751e8e62617b27d2dcb98496a0eccaaa4c41d4b7625b5fe"
        )
    ]
)
