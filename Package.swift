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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.5.3/LmsApi.xcframework.zip",
            checksum: "bac67c6a1c4e028d854c2d0bc261854e51ccb24d0a03edc8d4b3b01d32b72da3"
        )
    ]
)
