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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.5.3/LmsApi.xcframework.zip",
            checksum: "770121462dc2301ba2dd0ba8493aef5de518a398e6ecc913a181f284a9198075"
        )
    ]
)
