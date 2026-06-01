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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.4.0/LmsApi.xcframework.zip",
            checksum: "0d6482715d4ce586348260e0ee7fc711dd843353d11ac0c2cf6e485111136afa"
        )
    ]
)
