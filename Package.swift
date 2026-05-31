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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.3.6/LmsApi.xcframework.zip",
            checksum: "7fd5c156b84282b7663dd0a3800dc49a6ad0ba38170410e5323b34793821b924"
        )
    ]
)
