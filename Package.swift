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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.4.4/LmsApi.xcframework.zip",
            checksum: "92ddcd0e6a40edc5df3ddb1bdb593e7da0afe2861b94f12aacf3d3be1041ce81"
        )
    ]
)
