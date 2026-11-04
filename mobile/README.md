# SMC Trader — Android demo terminal

Ứng dụng Android mô phỏng một trading terminal theo phong cách MT5, tập trung vào
Smart Money Concepts (SMC) / ICT. Toàn bộ giá là **dữ liệu tổng hợp cục bộ**, không
kết nối broker, không có tài khoản MT5 và không định tuyến lệnh thật.

> **Cảnh báo:** Đây là công cụ học tập/mô phỏng. Giá, kết quả backtest và P/L đều
> được tạo từ dữ liệu giả lập. Không có kết quả nào đại diện cho giao dịch thật và
> không có cam kết lợi nhuận. Không dùng ứng dụng để đưa ra quyết định đầu tư.

## Tải và cài đặt

APK đã build sẵn nằm trong `mobile/dist/`:

| File | Mục đích |
| --- | --- |
| `smc-trader-1.0.0-release.apk` | Bản cài đặt chính (ký bằng debug keystore) |
| `smc-trader-1.0.0-debug.apk` | Bản debug, package `com.smclab.autotrade.debug` |

Yêu cầu: Android 8.0 (API 26) trở lên, kiến trúc ARM/ARM64/x86 tùy thiết bị.

Cài qua ADB:

```powershell
adb install -r E:\AutoTrade\mobile\dist\smc-trader-1.0.0-release.apk
```

Hoặc copy APK sang điện thoại và mở trực tiếp. Bản release dùng debug keystore để
tiện cài đặt nội bộ; khi phát hành chính thức cần thay bằng keystore riêng.

## Tính năng

### Thị trường
- 10 mã: XAUUSD, EURUSD, GBPUSD, USDJPY, AUDUSD, EURJPY, GBPJPY, BTCUSD, US30,
  NAS100.
- Bid/Ask, spread, thay đổi %, sparkline và phiên giao dịch (Asia/London/New York).
- Chạm một dòng để mở biểu đồ của mã đó.

### Biểu đồ
- Nến Nhật + overlay SMC: Order Block, FVG, BOS/CHoCH, Liquidity, OTE.
- Chọn khung thời gian M5 / M15 / H1 / H4 / D1.
- Chọn chiến lược riêng cho biểu đồ (`AUTO` = chiến lược đang bật có điểm cao nhất).
- Thẻ tín hiệu hiển thị hướng, điểm confluence, yếu tố, Entry / SL / TP.
- Nút BUY / SELL mở order ticket với SL/TP gợi ý từ tín hiệu.

### Đa khung thời gian (MTF)
- Khung xác nhận tự động: **M5 → M15, M15 → H1, H1 → H4, H4 → D1**; D1 không có
  khung cao hơn.
- Bật/tắt bằng switch **Xác nhận đa khung (HTF)** trên tab Biểu đồ hoặc tab
  Chiến lược. Trạng thái được lưu lại.
- Khi bật, tín hiệu chỉ được tạo nếu xu hướng khung cao không ngược hướng lệnh.
  Khung cao đang đi ngang (`RANGING`) **không chặn** tín hiệu.
- Mỗi chiến lược có switch HTF riêng trong hộp cấu hình; tắt switch này thì chiến
  lược đó bỏ qua bộ lọc HTF.

### Chiến lược
- 10 bộ quy tắc SMC/ICT dựa trên cùng bộ detector: BOS/CHoCH, Order Block, FVG,
  Liquidity sweep, OTE, kill zone, breaker.
- Bật/tắt từng chiến lược, chỉnh tham số, risk %, R:R và HTF.
- Tự động giao dịch demo: kiểm tra nến đã đóng, tối đa một lệnh cho mỗi cặp/chiến
  lược, tôn trọng bộ lọc HTF và VIX.

### Backtest
- Chọn cặp, khung thời gian, chiến lược, số nến, balance, risk %, R:R.
- Chọn khung xác nhận HTF hoặc **Không lọc HTF**.
- Kết quả: net profit, return %, win rate, profit factor, max drawdown, expectancy,
  avg R, Sharpe, chuỗi thua, equity curve và danh sách lệnh gần nhất.
- Quy tắc mô phỏng: tín hiệu trên nến đóng, khớp ở open nến kế tiếp, một vị thế tại
  một thời điểm, ưu tiên SL trong nến, có phí commission.

### Giao dịch
- Tài khoản demo persist qua SharedPreferences.
- Danh sách vị thế đang mở, sửa SL/TP, đóng lệnh, lịch sử giao dịch.
- Margin, equity, free margin, margin level cập nhật mỗi giây.

## Kiến trúc

```
app/src/main/java/com/smclab/autotrade/
  MainActivity.kt              # khung app + tick loop 1 giây
  model/Models.kt              # model, Timeframe.higher(), settings
  engine/MarketData.kt         # dữ liệu OHLC tổng hợp + tick
  engine/SmcEngine.kt          # detector SMC
  engine/StrategyCatalog.kt    # 10 chiến lược + confluence + HTF gate
  engine/AnalysisService.kt    # phân tích chart + HtfContext
  engine/BacktestEngine.kt     # backtest theo nến, HTF alignment
  data/AppRepository.kt        # state, persistence, auto-trade
  ui/...                       # fragment, dialog, adapter, custom view
```

- Kotlin + XML + Material 3 + ViewBinding.
- `minSdk 26`, `compileSdk/targetSdk 34`, AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7.
- Không có hosted workflow, không gọi mạng.

## Build từ source

Yêu cầu JDK 17 và Android SDK 34. Trong thư mục `mobile/`:

```powershell
$env:JAVA_HOME="C:\đường\dẫn\jdk"
$env:ANDROID_HOME="C:\đường\dẫn\sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME

gradle :app:assembleDebug
gradle :app:assembleRelease
gradle :app:testDebugUnitTest
```

APK đầu ra:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## Kiểm tra đã chạy

- `:app:compileDebugKotlin` — thành công.
- `:app:assembleDebug` và `:app:assembleRelease` — thành công.
- `:app:testDebugUnitTest` — 6 test engine/MTF/backtest thành công
  (`EngineSmokeTest`).
- `apksigner verify` — cả hai APK có chữ ký hợp lệ.
- `aapt dump badging` — `com.smclab.autotrade`, versionName `1.0.0`,
  minSdk 26, targetSdk 34, launcher `MainActivity`.

Các kiểm tra trên là kiểm tra tĩnh/unit test trên JVM, **không thay thế** việc chạy
ứng dụng trên thiết bị/emulator thật. Chưa có kiểm thử tự động UI hoặc kiểm thử
hành vi trên thiết bị.

## Giới hạn đã biết

- Không kết nối MT5/broker; không có dữ liệu thị trường thật.
- Không có đăng nhập, đồng bộ đám mây hay thông báo push.
- Dữ liệu tổng hợp có thể tạo tín hiệu đẹp hơn thị trường thật; chỉ dùng để kiểm
  tra logic và luồng UI.
- Bản release ký bằng debug key, không phù hợp để phát hành lên store.
