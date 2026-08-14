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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.5.4/LmsApi.xcframework.zip",
            checksum: "f732c90bc9508b12abb757c4b84fc71b7b6a792a04d1f7175fc1f7a61f6394ae"
        )
    ]
)
