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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.2.9/LmsApi.xcframework.zip",
            checksum: "1824dccae77dbea75b86aa536295bb69265b1cd251cb62d44422f25282a91691"
        )
    ]
)
