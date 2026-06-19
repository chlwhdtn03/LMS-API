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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.5.1/LmsApi.xcframework.zip",
            checksum: "f5d87c49f7eb05522ae34d2d7a7bf9ed1f44d9ad3fb9c6b09342673edeaec535"
        )
    ]
)
