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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.11/LmsApi.xcframework.zip",
            checksum: "6f195d7cffdc39e08ddc16374f27faf68e3a8aa7e7d4b16307e21997731b7912"
        )
    ]
)
