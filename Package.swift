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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.6.3/LmsApi.xcframework.zip",
            checksum: "c01881307c265959d717c4efac087c097060c999cc7295638fc93cdc6fa38471"
        )
    ]
)
