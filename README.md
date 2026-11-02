# PD2 Installer & Updater for Android

**[English](#english)** | **[Tiếng Việt](#tiếng-việt)**

---

## English

An Android app that installs and updates [Project Diablo 2](https://www.projectdiablo2.com) on your phone, with no PC, so you can play it through [Winlator](https://github.com/brunodev85/winlator). The game files stay in a normal folder on your storage, where Winlator can reach them.

> **Project status:** The install mode has passed automated tests on synthetic data only. It has **not yet been tested end to end** on a real phone with real CD keys, Blizzard's servers or Winlator. See [Known limitations](#known-limitations).

> **Disclaimer:** This is an unofficial personal project, not affiliated with or endorsed by Blizzard Entertainment or the Project Diablo 2 team. You need your own legitimate Diablo II and Lord of Destruction CD keys. You are responsible for following the terms that apply to you.

### Features

- Installs Diablo II, Lord of Destruction (1.14b, English) and Project Diablo 2 from the phone
- Updates PD2 and keeps your own files
- Chooses a loot filter by author, as in the PD2 launcher
- Writes `PD2.bat`, which starts the game with the renderer you choose (Glide or DirectDraw)
- Stops and resumes downloads, and checks every downloaded file
- English and Vietnamese interface
- Includes a ready-made Winlator on-screen keyboard profile

### Requirements

- Android 8.0 or newer
- Winlator installed
- About 6 GB of free storage
- A Diablo II CD key and a Lord of Destruction CD key, 26 characters each (old 16-character keys do not work)
- A stable internet connection (the download is about 2.1 GB, plus PD2)

### Step 1: Install the app

1. Get the APK from this repository's **Releases** page if one is published, or [build it yourself](#build-from-source).
2. Open the APK on your phone and allow installing from this source if Android asks.
3. Open **PD2 Updater**.
4. Tap **Grant access** and turn on **All files access**, then go back to the app.
5. To change the language, tap the button at the top right.

### Step 2: Install the game

1. Tap **Choose folder…**.
2. Create a new **empty** folder, such as `Download/Game/PD2/Diablo II`, and select it. The app refuses a folder that already has files, so nothing of yours is overwritten.
3. Scroll to **New install** and enter your two CD keys.
4. Tap **Install**.
5. **Keep the app open** until it finishes. The screen stays on by itself. Android may stop the download if you switch to another app.
6. If the install stops, tap **Continue install** (enter the keys again if the boxes are empty). What was already downloaded is kept.
7. When asked, choose whether to delete the downloaded installers. This frees about 2 GB and does not affect the game.

The keys are used only during the install. The app does not save them; only the game keeps them, in its own files.

If the app already shows `✓ ProjectD2` for your folder, skip this step and use **Check** and **Update** instead.

### Step 3: Choose the graphics mode and loot filter

In PD2 Updater:

1. Under **Starting the game**, tap **DirectDraw**.
2. Under **Loot filter**, tap **Choose author** and pick an author.
3. Tap **Choose filter** and pick a filter. The app saves it as `loot.filter`, and the card shows `✓ In use as loot.filter`.
4. Optional: turn on **Always update default.filter** to keep PD2's default filter up to date. The game uses that filter when no filter is chosen.

To use your own filters, put them in `ProjectD2/filters/local`. They appear in the author list.

### Step 4: Set up Winlator

These are the settings we usually prefer, but results can differ between phones. Menu names can also differ slightly between Winlator builds.

1. Open Winlator and create a container, or use an existing one.
2. In the container's settings, set **Graphics Driver** to **VirGL** and **DX Wrapper** to **WineD3D**.
3. Under **Drives**, map a drive letter to a folder that contains the game. For example, map `D:` to `/storage/emulated/0/Download`.
4. Save the container and start it.
5. In the container's file manager, go to `Game.exe` on the mapped drive. With the example above, it is `D:\Game\PD2\Diablo II\ProjectD2\Game.exe`, which is `Download/Game/PD2/Diablo II/ProjectD2/Game.exe` on the phone.
6. Long-press `Game.exe` and choose **Create Shortcut**.

### Step 5: Import the keyboard profile (optional)

1. Download [`winlator/D2.icp`](winlator/D2.icp) to your phone.
2. In Winlator, open **Input Controls** from the side menu.
3. Use **Import Profile** and select `D2.icp`. A profile named **D2** appears.
4. While in the game, open Winlator's in-game menu, go to **Input Controls** and select **D2**.

What the profile's buttons send:

| Buttons | Keys |
|---|---|
| Left stick | `W` `A` `S` `D` |
| Mouse | Left click (2 buttons), right click |
| Function keys | `F1` to `F9` |
| Number keys | `1` `2` `3` `4` `8` |
| Letter keys | `Q` `B` `C` `T` `O` `P` `J` |
| Modifiers | Left and right `Shift`, left and right `Ctrl`, `Alt` (stays on until tapped again) |
| Others | `Tab`, `Enter`, `Esc` |

What each key does depends on your in-game key settings. One round button at the top left has no key assigned; you can edit it in Winlator.

### Step 6: Play

1. In PD2 Updater, tap **Open Winlator to play**.
2. On Winlator's **Shortcuts** screen, open the shortcut you created for `Game.exe`.

### Updating

1. Open PD2 Updater and tap **Check**.
2. If something is new, tap **Update**. This also applies a newer copy of the filter you chose.

### Known limitations

- **Not tested end to end**, as stated above. Reports from real devices are welcome.
- **PD2 files:** the app installs PD2 from its update manifest, while the official route uses `ProjectD2Installer.exe`. The manifest may not be enough for a new install. If the app reports missing files, or the game fails to start, run the [official installer](https://storage.googleapis.com/project-diablo-2-website-public/installer/ProjectD2Installer.exe) once inside Winlator.
- **Winlator compatibility:** whether a game installed this way runs and connects online through Winlator is unconfirmed.
- **Background use:** there is no background service, so the app must stay open during the install.
- **Language of the game:** only the English game is installed.

### Build from source

You need JDK 21, the Android SDK and Gradle 9.4.1. The repository has no Gradle wrapper script, so use an installed Gradle:

```sh
gradle :app:testDebugUnitTest
gradle :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Those commands sign it with the debug key, which is for testing only.

To sign with your own release key, run this in a PowerShell window. It asks for the passwords without showing them, then builds and verifies the signature:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
```

The script takes `-Keystore`, `-KeyAlias`, `-Jdk`, `-Sdk` and `-GradleHome` if your paths differ from its defaults. On other systems, set `PD2_KEYSTORE_PATH`, `PD2_KEY_ALIAS`, `PD2_KEYSTORE_PASSWORD` and `PD2_KEY_PASSWORD` before running Gradle.

**Keep the keystore and its passwords backed up.** Updates must be signed with the same key. Android refuses to update an app signed with a different key, so moving from a debug-signed build means uninstalling the old app first. That clears the app's own settings, but not the game folder on the SD card.

---

## Tiếng Việt

Ứng dụng Android cài đặt và cập nhật [Project Diablo 2](https://www.projectdiablo2.com) ngay trên điện thoại, không cần máy tính, để chơi qua [Winlator](https://github.com/brunodev85/winlator). File game nằm trong một thư mục bình thường trên bộ nhớ máy, nên Winlator truy cập được.

> **Tình trạng dự án:** Chế độ cài đặt mới chỉ đạt các bài kiểm thử tự động trên dữ liệu giả lập. Toàn bộ quy trình **chưa được thử** trên điện thoại thật với CD key thật, máy chủ Blizzard hay Winlator. Xem [Hạn chế đã biết](#hạn-chế-đã-biết).

> **Lưu ý:** Đây là dự án cá nhân không chính thức, không liên kết với và không được Blizzard Entertainment hay nhóm Project Diablo 2 chứng thực. Bạn cần có CD key Diablo II và Lord of Destruction hợp lệ của riêng mình, và tự chịu trách nhiệm tuân thủ các điều khoản áp dụng cho bạn.

### Tính năng

- Cài Diablo II, Lord of Destruction (1.14b, tiếng Anh) và Project Diablo 2 từ điện thoại
- Cập nhật PD2 và giữ nguyên file của bạn
- Chọn loot filter theo tác giả, giống launcher PD2
- Ghi file `PD2.bat` để mở game với renderer bạn chọn (Glide hoặc DirectDraw)
- Dừng và tải tiếp được, kiểm tra mọi file tải về
- Giao diện tiếng Anh và tiếng Việt
- Có sẵn cấu hình bàn phím ảo cho Winlator

### Yêu cầu

- Android 8.0 trở lên
- Đã cài Winlator
- Trống khoảng 6 GB bộ nhớ
- Một CD key Diablo II và một CD key Lord of Destruction, mỗi key 26 ký tự (key cũ 16 ký tự không dùng được)
- Mạng ổn định (tải khoảng 2,1 GB, chưa tính PD2)

### Bước 1: Cài app

1. Lấy APK từ trang **Releases** của repo này nếu đã có, hoặc [tự build](#build-từ-mã-nguồn).
2. Mở APK trên điện thoại, cho phép cài từ nguồn này nếu Android hỏi.
3. Mở **PD2 Updater**.
4. Bấm **Cấp quyền**, bật **Truy cập tất cả file**, rồi quay lại app.
5. Muốn đổi ngôn ngữ, bấm nút ở góc trên bên phải.

### Bước 2: Cài game

1. Bấm **Chọn thư mục…**.
2. Tạo một thư mục **trống** mới, ví dụ `Download/Game/PD2/Diablo II`, rồi chọn nó. App từ chối thư mục đã có file, nên dữ liệu của bạn không bị ghi đè.
3. Kéo xuống **Cài đặt mới** và nhập 2 CD key.
4. Bấm **Cài đặt**.
5. **Giữ app mở** đến khi xong. Màn hình sẽ tự sáng. Android có thể dừng việc tải nếu bạn chuyển sang app khác.
6. Nếu quá trình cài bị dừng, bấm **Tiếp tục cài** (nhập lại key nếu ô đã trống). Phần đã tải được giữ lại.
7. Khi được hỏi, chọn có xóa bộ cài đã tải hay không. Việc xóa giải phóng khoảng 2 GB và không ảnh hưởng đến game.

Key chỉ được dùng khi cài. App không lưu key; chỉ game giữ key trong file của nó.

Nếu app đã hiện `✓ ProjectD2` với thư mục của bạn, hãy bỏ qua bước này và dùng **Kiểm tra** rồi **Cập nhật**.

### Bước 3: Chọn chế độ đồ họa và loot filter

Trong PD2 Updater:

1. Ở mục **Khởi động game**, bấm **DirectDraw**.
2. Ở mục **Bộ lọc đồ (loot filter)**, bấm **Chọn tác giả** rồi chọn một tác giả.
3. Bấm **Chọn filter** rồi chọn một filter. App lưu filter thành `loot.filter`, và thẻ này hiện `✓ Đang dùng làm loot.filter`.
4. Không bắt buộc: bật **Luôn cập nhật default.filter** để giữ bộ lọc mặc định của PD2 luôn mới. Game dùng bộ lọc này khi không chọn filter nào.

Muốn dùng filter của riêng bạn, hãy đặt vào `ProjectD2/filters/local`. Các filter đó sẽ xuất hiện trong danh sách tác giả.

### Bước 4: Thiết lập Winlator

Đây là các thiết lập chúng tôi thường ưu tiên, nhưng kết quả có thể khác nhau tùy điện thoại. Tên menu cũng có thể hơi khác giữa các bản Winlator.

1. Mở Winlator, tạo container mới hoặc dùng container có sẵn.
2. Trong cài đặt container, đặt **Graphics Driver** là **VirGL** và **DX Wrapper** là **WineD3D**.
3. Ở mục **Drives**, gán một ký tự ổ đĩa cho thư mục có chứa game. Ví dụ gán `D:` cho `/storage/emulated/0/Download`.
4. Lưu container rồi khởi động nó.
5. Trong trình quản lý file của container, tìm tới `Game.exe` trên ổ đã gán. Với ví dụ trên, đường dẫn là `D:\Game\PD2\Diablo II\ProjectD2\Game.exe`, tức `Download/Game/PD2/Diablo II/ProjectD2/Game.exe` trên điện thoại.
6. Nhấn giữ `Game.exe` và chọn **Create Shortcut**.

### Bước 5: Nhập cấu hình bàn phím (không bắt buộc)

1. Tải [`winlator/D2.icp`](winlator/D2.icp) về điện thoại.
2. Trong Winlator, mở **Input Controls** ở menu bên.
3. Dùng **Import Profile** và chọn `D2.icp`. Cấu hình tên **D2** sẽ xuất hiện.
4. Khi đang trong game, mở menu của Winlator, vào **Input Controls** và chọn **D2**.

Các nút trong cấu hình gửi những phím sau:

| Nút | Phím |
|---|---|
| Cần điều hướng bên trái | `W` `A` `S` `D` |
| Chuột | Chuột trái (2 nút), chuột phải |
| Phím chức năng | `F1` đến `F9` |
| Phím số | `1` `2` `3` `4` `8` |
| Phím chữ | `Q` `B` `C` `T` `O` `P` `J` |
| Phím bổ trợ | `Shift` trái và phải, `Ctrl` trái và phải, `Alt` (giữ trạng thái đến khi bấm lại) |
| Khác | `Tab`, `Enter`, `Esc` |

Tác dụng của mỗi phím tùy vào cài đặt phím trong game. Một nút tròn ở góc trên bên trái chưa gán phím; bạn có thể sửa trong Winlator.

### Bước 6: Chơi game

1. Trong PD2 Updater, bấm **Mở Winlator để chơi**.
2. Ở màn hình **Shortcuts** của Winlator, mở shortcut bạn đã tạo cho `Game.exe`.

### Cập nhật

1. Mở PD2 Updater và bấm **Kiểm tra**.
2. Nếu có bản mới, bấm **Cập nhật**. Thao tác này cũng áp dụng bản mới hơn của filter bạn đã chọn.

### Hạn chế đã biết

- **Chưa thử trọn quy trình**, như đã nêu ở trên. Rất mong nhận được phản hồi từ thiết bị thật.
- **File PD2:** app cài PD2 từ manifest cập nhật, còn cách chính thức dùng `ProjectD2Installer.exe`. Manifest có thể chưa đủ cho một bản cài mới. Nếu app báo thiếu file hoặc game không mở được, hãy chạy [trình cài chính thức](https://storage.googleapis.com/project-diablo-2-website-public/installer/ProjectD2Installer.exe) một lần trong Winlator.
- **Tương thích Winlator:** chưa xác nhận game cài theo cách này có chạy và vào online được qua Winlator không.
- **Chạy nền:** app chưa có dịch vụ chạy nền, nên phải giữ app mở trong lúc cài.
- **Ngôn ngữ game:** chỉ cài bản game tiếng Anh.

### Build từ mã nguồn

Cần JDK 21, Android SDK và Gradle 9.4.1. Repo không có script Gradle wrapper, nên hãy dùng Gradle đã cài sẵn:

```sh
gradle :app:testDebugUnitTest
gradle :app:assembleRelease
```

APK nằm tại `app/build/outputs/apk/release/app-release.apk`. Các lệnh trên ký APK bằng debug key, chỉ dùng để thử nghiệm.

Để ký bằng release key của bạn, hãy chạy lệnh sau trong cửa sổ PowerShell. Script sẽ hỏi mật khẩu (không hiện ra màn hình), rồi build và kiểm tra chữ ký:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
```

Nếu đường dẫn của bạn khác mặc định, script nhận các tham số `-Keystore`, `-KeyAlias`, `-Jdk`, `-Sdk` và `-GradleHome`. Trên hệ điều hành khác, hãy đặt `PD2_KEYSTORE_PATH`, `PD2_KEY_ALIAS`, `PD2_KEYSTORE_PASSWORD` và `PD2_KEY_PASSWORD` trước khi chạy Gradle.

**Hãy sao lưu keystore và mật khẩu.** Các bản cập nhật phải được ký bằng cùng một key. Android từ chối cập nhật app đã ký bằng key khác, nên khi chuyển từ bản ký bằng debug key, bạn cần gỡ app cũ trước. Việc này xóa cài đặt của app, nhưng không xóa thư mục game trên thẻ SD.
