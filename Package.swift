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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.12/LmsApi.xcframework.zip",
            checksum: "1c32d43eaa1c21e47937e475934340fc2217893f4fd2fc35e0e1364e331a35a6"
        )
    ]
)
