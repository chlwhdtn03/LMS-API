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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.3.2/LmsApi.xcframework.zip",
            checksum: "eaba415a0ab679a259e9a05264a2b83cd2216998ca72adc95fc939d72dc1cdd0"
        )
    ]
)
