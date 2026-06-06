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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.4.5/LmsApi.xcframework.zip",
            checksum: "9f47ef965c33b0d0d03d1ffd538771cc052daf2b17eac1de5cb24a511abff489"
        )
    ]
)
