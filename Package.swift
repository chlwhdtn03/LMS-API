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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.2.7/LmsApi.xcframework.zip",
            checksum: "094926b9cb036f1e306bbc734e98c441e3ee226fbfc620bd2ddc33e9ad240a05"
        )
    ]
)
