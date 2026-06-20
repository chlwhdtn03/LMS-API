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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.5.2/LmsApi.xcframework.zip",
            checksum: "59626840d531a4bc3f9cea7f3c46952f11d00cc93315dbcbaa64f871a6df795e"
        )
    ]
)
