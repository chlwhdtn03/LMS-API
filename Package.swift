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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.3.8/LmsApi.xcframework.zip",
            checksum: "8aaeff6cf43be880c2be962791730ac8bfccb8c4e1406b0cab04a64e9f8adcc5"
        )
    ]
)
