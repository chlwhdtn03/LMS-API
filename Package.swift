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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.3.0/LmsApi.xcframework.zip",
            checksum: "a5e10ae2287c0ea36af299944c75ea118e8bf10661aade45ec0eaa2b9b02e332"
        )
    ]
)
