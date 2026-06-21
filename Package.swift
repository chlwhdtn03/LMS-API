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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.5.4/LmsApi.xcframework.zip",
            checksum: "ec1f57bd08f3d8c749d93480fbb253949fbb848b704d2c40ece16a86a3122e0f"
        )
    ]
)
