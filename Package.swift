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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.8/LmsApi.xcframework.zip",
            checksum: "ea01fce04ea5a505d9b79ec7366845a27ab514b8cc371090b14cb10b80704da2"
        )
    ]
)
