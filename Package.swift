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
            url: "https://github.com/chlwhdtn03/LMS-API/releases/download/1.6.4/LmsApi.xcframework.zip",
            checksum: "7fa201751552b4da741efe58a882e3df0162c262559491b67ef24a38de1cbe36"
        )
    ]
)
