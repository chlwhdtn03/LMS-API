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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.0/LmsApi.xcframework.zip",
            checksum: "8d64ff7ad5d1b79078b033b8b15c211e5572a6f61561820a3e44c2dad5a13947"
        )
    ]
)
