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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.5.0/LmsApi.xcframework.zip",
            checksum: "f506f331bcfe2a9259622ac11e8796eea5785eb91df43592b77b52fdd0ccf378"
        )
    ]
)
