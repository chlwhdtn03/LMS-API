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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.6.2/LmsApi.xcframework.zip",
            checksum: "552ef87d6b54513c1b07e785ea2dc36d3ac48d8715075eb370c7b39b475911b7"
        )
    ]
)
