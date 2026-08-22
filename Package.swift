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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.6.1/LmsApi.xcframework.zip",
            checksum: "5e9ff34788838702d75f345bace53581511b52934570ab1eb106e4b29e66c78c"
        )
    ]
)
