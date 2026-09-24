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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.9/LmsApi.xcframework.zip",
            checksum: "20fdb91404720868e7ae9d6f94b3e96230716db484fe3cc3fc14fb807666e3a2"
        )
    ]
)
