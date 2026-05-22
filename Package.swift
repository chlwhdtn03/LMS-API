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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.2.5/LmsApi.xcframework.zip",
            checksum: "a9d85dca3b455d5b41460f1dfe653ccff67138abe0d6973a538c28cf24d2296b"
        )
    ]
)
