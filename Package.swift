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
            checksum: "97efd03b52648bd809bdf477b03431e575d7e3965ba3bc6241c9ac462baa9dc5"
        )
    ]
)
