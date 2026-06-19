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
            checksum: "90e52158eed51e418321b6f4eca86990a01283f0a23435c0aa70e0ff58f44cc2"
        )
    ]
)
