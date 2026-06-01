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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.3.9/LmsApi.xcframework.zip",
            checksum: "2fe63ff895efad6d6963cfc2d3718f6ca87c0d3259971da4bc080ecfbb4a568e"
        )
    ]
)
