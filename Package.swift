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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.4.2/LmsApi.xcframework.zip",
            checksum: "15321ca1847ac72fe728e90b04b8384dfd01b1d16dcd8209807c8585dd6df0bc"
        )
    ]
)
