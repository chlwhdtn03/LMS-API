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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.4.1/LmsApi.xcframework.zip",
            checksum: "848970c3c900943ef40b19004e9363ef46fec6a3cbafa3f9458e6f2d413fb2b0"
        )
    ]
)
