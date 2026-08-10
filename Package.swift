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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.5.2/LmsApi.xcframework.zip",
            checksum: "972cc72a46f4c974d8738350a6159ff61eacfd4591de97049cf3b18e238c9e7a"
        )
    ]
)
