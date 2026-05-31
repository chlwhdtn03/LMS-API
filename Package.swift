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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.3.4/LmsApi.xcframework.zip",
            checksum: "ee2ab1b6eb611c625b5759b67fea7b3e276c5fef2d7df85ff82a880c4472ceff"
        )
    ]
)
