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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.2.4/LmsApi.xcframework.zip",
            checksum: "b9a6245a53bf5d1a27397a6e1c9fadc15d1a33739730301f74b82a90839ba3c4"
        )
    ]
)
