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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.10/LmsApi.xcframework.zip",
            checksum: "06f103166a89ed5a6d0d1340ff8076d0b41ba9211509766b2ee5b6eb06a78e8f"
        )
    ]
)
