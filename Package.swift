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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.3/LmsApi.xcframework.zip",
            checksum: "3d6971a890bb2439d3a8f5b430b3a05c25f9074844da5b17fc11f34281ed571a"
        )
    ]
)
