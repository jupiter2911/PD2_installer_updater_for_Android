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
2. Create a new **empty** folder, such as `Games/Diablo II`, and select it. The app refuses a folder that already has files, so nothing of yours is overwritten.
3. Scroll to **New install** and enter your two CD keys.
4. Tap **Install**.
5. **Keep the app open** until it finishes. The screen stays on by itself. Android may stop the download if you switch to another app.
6. If the install stops, tap **Continue install** (enter the keys again if the boxes are empty). What was already downloaded is kept.
7. When asked, choose whether to delete the downloaded installers. This frees about 2 GB and does not affect the game.

The keys are used only during the install. The app does not save them; only the game keeps them, in its own files.

If the app already shows `✓ ProjectD2` for your folder, skip this step and use **Check** and **Update** instead.

### Step 3: Set up Winlator

Menu names can differ slightly between Winlator builds.

1. Open Winlator and create a container, or use an existing one.
2. In the container's settings, under **Drives**, map a drive letter to the folder that holds the game. For example, map `D:` to `/storage/emulated/0/Games`.
3. Save the container.

### Step 4: Import the keyboard profile (optional)

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

### Step 5: Play

1. In PD2 Updater, choose a renderer under **Starting the game**.
2. Tap **Open Winlator to play**.
3. Start the container and open `ProjectD2\PD2.bat` on the mapped drive, for example `D:\Diablo II\ProjectD2\PD2.bat`.
4. To start faster next time, create a shortcut to `PD2.bat` in Winlator.

### Updating

1. Open PD2 Updater and tap **Check**.
2. If something is new, tap **Update**.

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

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are signed with the debug key, so they suit personal use only.

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
2. Tạo một thư mục **trống** mới, ví dụ `Games/Diablo II`, rồi chọn nó. App từ chối thư mục đã có file, nên dữ liệu của bạn không bị ghi đè.
3. Kéo xuống **Cài đặt mới** và nhập 2 CD key.
4. Bấm **Cài đặt**.
5. **Giữ app mở** đến khi xong. Màn hình sẽ tự sáng. Android có thể dừng việc tải nếu bạn chuyển sang app khác.
6. Nếu quá trình cài bị dừng, bấm **Tiếp tục cài** (nhập lại key nếu ô đã trống). Phần đã tải được giữ lại.
7. Khi được hỏi, chọn có xóa bộ cài đã tải hay không. Việc xóa giải phóng khoảng 2 GB và không ảnh hưởng đến game.

Key chỉ được dùng khi cài. App không lưu key; chỉ game giữ key trong file của nó.

Nếu app đã hiện `✓ ProjectD2` với thư mục của bạn, hãy bỏ qua bước này và dùng **Kiểm tra** rồi **Cập nhật**.

### Bước 3: Thiết lập Winlator

Tên menu có thể hơi khác giữa các bản Winlator.

1. Mở Winlator, tạo container mới hoặc dùng container có sẵn.
2. Trong cài đặt container, mục **Drives**, gán một ký tự ổ đĩa cho thư mục chứa game. Ví dụ gán `D:` cho `/storage/emulated/0/Games`.
3. Lưu container.

### Bước 4: Nhập cấu hình bàn phím (không bắt buộc)

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

### Bước 5: Chơi game

1. Trong PD2 Updater, chọn renderer ở mục **Khởi động game**.
2. Bấm **Mở Winlator để chơi**.
3. Khởi động container và mở `ProjectD2\PD2.bat` trên ổ đã gán, ví dụ `D:\Diablo II\ProjectD2\PD2.bat`.
4. Để lần sau mở nhanh hơn, tạo shortcut tới `PD2.bat` trong Winlator.

### Cập nhật

1. Mở PD2 Updater và bấm **Kiểm tra**.
2. Nếu có bản mới, bấm **Cập nhật**.

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

APK nằm tại `app/build/outputs/apk/release/app-release.apk`. Bản release được ký bằng debug key, nên chỉ phù hợp để dùng cá nhân.
