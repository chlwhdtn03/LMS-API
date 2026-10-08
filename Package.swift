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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.13/LmsApi.xcframework.zip",
            checksum: "ae46a34b383377dc3d47cdb9b100c6c4aee4dc4b08f504a3ac9943a9e81bcfee"
        )
    ]
)
