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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.5.1/LmsApi.xcframework.zip",
            checksum: "d3a9cb193ae7f075f559166564ec03c7c783bf926736315ec33f9e3dd3f3b34f"
        )
    ]
)
