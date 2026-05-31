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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.3.5/LmsApi.xcframework.zip",
            checksum: "4768c89b6d59e15490cc6909f79eb81481a9034b604c7821ece0d5a7f173bf28"
        )
    ]
)
