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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.2.8/LmsApi.xcframework.zip",
            checksum: "417d89054f44f520c57a7bde8143f0680ec2127bdaed3e7b853a33c0ff82c57a"
        )
    ]
)
