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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.7/LmsApi.xcframework.zip",
            checksum: "caa662d7bf7b6341c5bf74377a3c43ff913ba3f97e0dc19dec880cf4afaf06e4"
        )
    ]
)
