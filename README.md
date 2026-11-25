
# MINHDUC ANDROID ELITE 4.0

<p align="center">
  <strong>MINHDUC IOS</strong>
</p>

Ứng dụng quản lý cấu hình và hồ sơ trò chơi trên Android, sử dụng Kotlin và Jetpack Compose.

## Thông tin dự án

| Thuộc tính | Giá trị |
|---|---|
| Tên ứng dụng | MINHDUC ANDROID ELITE |
| Phiên bản | 4.0 |
| Ngôn ngữ | Kotlin |
| Giao diện | Jetpack Compose |
| Minimum Android | Android 8.0 (API 26) |
| Compile SDK | 35 |
| Target SDK | 35 |
| Gradle | 8.9 |

## Tính năng

### 1. HOME
- Hiển thị thông tin thiết bị.
- Hiển thị phiên bản Android.
- Hiển thị thông tin màn hình.
- Làm mới thông tin thiết bị.

### 2. GAME MANAGER
- Tìm ứng dụng có thể khởi chạy trên thiết bị.
- Chọn ứng dụng để quản lý.
- Lưu cấu hình riêng cho từng ứng dụng.
- Điều chỉnh thông số preset cục bộ.
- Khởi chạy ứng dụng đã chọn.

### 3. XML MANAGER
- Nhập cấu hình XML.
- Xuất cấu hình XML.
- Quản lý hồ sơ đã lưu.
- Xóa hồ sơ không cần thiết.

### 4. SETTINGS
- Mở cài đặt màn hình Android.
- Mở cài đặt trợ năng Android.
- Quản lý các tùy chọn ứng dụng.

## Yêu cầu

- Android Studio.
- JDK 17.
- Android SDK 35.
- Gradle 8.9.

## Cấu trúc dự án

```text
MINHDUC-ANDROID-ELITE/
├── .github/
│   └── workflows/
│       └── android.yml
├── app/
│   ├── build.gradle.kts
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           ├── java/
│           │   └── com/minhduc/elite/
│           │       ├── MainActivity.kt
│           │       └── ProfileStore.kt
│           └── res/
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── README.md
```

## Build APK bằng GitHub Actions

1. Tải toàn bộ mã nguồn lên repository GitHub.
2. Mở tab **Actions**.
3. Chọn workflow `Build MINHDUC Android APK`.
4. Nhấn `Run workflow`.
5. Chờ quá trình build hoàn tất.
6. Tải artifact `MINHDUC-ANDROID-ELITE-4.0-APK`.
7. Giải nén để lấy file APK.

## Giới hạn hoạt động

Ứng dụng quản lý hồ sơ và preset cục bộ. Các thông số sensitivity và smoothness chỉ được lưu làm cấu hình tham khảo.

Ứng dụng không tự thay đổi độ nhạy, thao tác cảm ứng hoặc cơ chế ngắm bên trong Free Fire hay ứng dụng khác.

Không yêu cầu Root hoặc Shizuku.

## Nhà phát triển

**MINHDUC IOS**

Version: 4.0

## Game selector update

- GAMES tab now presents only Free Fire TH (`com.dts.freefireth`) and Free Fire VN (`com.garena.ffvn`).
- Selecting a card chooses that package; `OPEN SELECTED GAME` launches it only when installed.
- Each package can have its own locally saved profile.
- The optimization controls are reference presets stored inside this app. Android does not provide a general API for changing another game's FPS, aim, touch sensitivity, or internal settings.
- The virtual crosshair in this build is an in-app preview/customizer only; it does not overlay or inject into Free Fire.

## License binding

The existing GitHub license flow binds an unbound key to `Settings.Secure.ANDROID_ID` in `keys.json` and rejects activation from a different device ID. A token embedded in an APK can be extracted; use a narrowly scoped, replaceable token and do not publish it in a public repository.
