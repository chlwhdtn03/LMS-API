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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.3.1/LmsApi.xcframework.zip",
            checksum: "c4194f36ea14d67e6888938df920f633633e4a7bbb693939e6175f09d1d1ca62"
        )
    ]
)
