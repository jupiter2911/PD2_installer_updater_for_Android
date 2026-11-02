package com.pd2updater

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.pd2updater.install.CdKeys
import com.pd2updater.install.GameInstaller
import com.pd2updater.install.KeyField
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var permissionCard: View
    private lateinit var pathText: TextView
    private lateinit var folderState: TextView
    private lateinit var pickButton: Button
    private lateinit var filterAuthorText: TextView
    private lateinit var filterNameText: TextView
    private lateinit var filterDescText: TextView
    private lateinit var filterStateText: TextView
    private lateinit var authorButton: Button
    private lateinit var filterButton: Button
    private lateinit var filterSwitch: Switch
    private lateinit var rendererViews: Map<Renderer, TextView>
    private lateinit var launcherNote: TextView
    private lateinit var checkButton: Button
    private lateinit var updateButton: Button
    private lateinit var stopButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var installCard: View
    private lateinit var baseKeyField: EditText
    private lateinit var expansionKeyField: EditText
    private lateinit var installNote: TextView
    private lateinit var installButton: Button
    private lateinit var deleteDownloadsButton: Button

    private var cancel = AtomicBoolean(false)
    private var running = false
    @Volatile private var installing = false
    @Volatile private var plan: Plan? = null
    /** The chosen filter's newer copy that the last check found, applied by Update. */
    @Volatile private var pendingFilter: FilterCatalog.Filter? = null
    @Volatile private var authorsCache: List<FilterCatalog.Author>? = null
    private val logLines = ArrayDeque<String>()

    private val prefs by lazy { getSharedPreferences("pd2", MODE_PRIVATE) }
    private var path: String
        get() = prefs.getString("path", "") ?: ""
        set(value) = prefs.edit().putString("path", value).apply()
    private var renderer: Renderer
        get() = Renderer.of(prefs.getString("renderer", null))
        set(value) = prefs.edit().putString("renderer", value.name).apply()
    private var alwaysFilter: Boolean
        get() = prefs.getBoolean("always_default_filter", false)
        set(value) = prefs.edit().putBoolean("always_default_filter", value).apply()

    /** The chosen filter author, or null. */
    private var filterAuthor: FilterCatalog.Author?
        get() {
            val url = prefs.getString("filter_author_url", null) ?: return null
            return FilterCatalog.Author(prefs.getString("filter_author_name", url) ?: url, url, prefs.getString("filter_author", "") ?: "")
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove("filter_author_url"); remove("filter_author_name"); remove("filter_author")
                } else {
                    putString("filter_author_url", value.url); putString("filter_author_name", value.name); putString("filter_author", value.author)
                }
            }.apply()
        }

    private data class ChosenFilter(val name: String, val display: String, val description: String, val id: String)

    private var chosenFilter: ChosenFilter?
        get() {
            val name = prefs.getString("filter_name", null) ?: return null
            return ChosenFilter(
                name,
                prefs.getString("filter_display", name) ?: name,
                prefs.getString("filter_desc", "") ?: "",
                prefs.getString("filter_id", "") ?: "",
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove("filter_name"); remove("filter_display"); remove("filter_desc"); remove("filter_id")
                } else {
                    putString("filter_name", value.name); putString("filter_display", value.display)
                    putString("filter_desc", value.description); putString("filter_id", value.id)
                }
            }.apply()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Palette.background)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        column.addView(header())
        column.addView(permissionCard().also { permissionCard = it })
        column.addView(folderCard())
        column.addView(updateCard())
        column.addView(installCard().also { installCard = it })
        column.addView(filterCard())
        column.addView(launchCard())
        column.addView(actionButton("Mở Winlator để chơi", primary = true) { openWinlator() }.apply {
            (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = dp(12)
        })
        column.addView(logCard())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        // Android 15 draws the app under the system bars: keep the content clear of them.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        refresh()
    }

    // ---- Layout ----

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(16))
        addView(ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher)
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(body("PD2 Updater", size = 24f).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(body("Cài và cập nhật Project Diablo 2 để chơi qua Winlator", muted = true, size = 13f))
        })
    }

    private fun permissionCard(): View = card().apply {
        background = rounded(Palette.card, Palette.error, 16)
        addView(sectionTitle("Cần quyền truy cập file").apply { setTextColor(Palette.error) })
        addView(body("App cần quyền \"Truy cập tất cả file\" để đọc và ghi thư mục PD2 trong bộ nhớ điện thoại.", muted = true))
        addView(actionButton("Cấp quyền", primary = true) { requestFiles() })
    }

    private fun folderCard(): View = card().apply {
        addView(sectionTitle("Thư mục game"))
        pathText = body(size = 15f).apply { setTextIsSelectable(true) }
        addView(pathText)
        folderState = body(size = 13f).apply { setPadding(0, dp(4), 0, 0) }
        addView(folderState)
        pickButton = actionButton("Chọn thư mục…", primary = false) { pickFolder() }
        addView(pickButton)
    }

    private fun updateCard(): View = card().apply {
        addView(sectionTitle("Cập nhật"))
        statusText = body("Bấm Kiểm tra để so sánh với bản mới nhất của PD2.", size = 15f)
        addView(statusText)
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Palette.gold)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.stroke)
            setPadding(0, dp(10), 0, dp(2))
        }
        addView(progressBar)
        checkButton = actionButton("Kiểm tra", primary = false) { runCheck() }
        updateButton = actionButton("Cập nhật", primary = true) { runUpdate() }
        addView(row(checkButton, updateButton))
        stopButton = actionButton("Dừng", primary = false) { cancel.set(true) }
        addView(stopButton)
    }

    private fun installCard(): View = card().apply {
        addView(sectionTitle("Cài đặt mới"))
        addView(body("Tải Diablo II, Lord of Destruction và PD2 về thư mục trống đã chọn (cần khoảng 6 GB). Hai CD key chỉ dùng khi cài, app không lưu lại.", muted = true))
        baseKeyField = keyField("CD key Diablo II")
        addView(baseKeyField)
        expansionKeyField = keyField("CD key Lord of Destruction")
        addView(expansionKeyField)
        installNote = body(size = 13f).apply { setPadding(0, dp(6), 0, 0) }
        addView(installNote)
        installButton = actionButton("Cài đặt", primary = true) { runInstall() }
        addView(installButton)
        deleteDownloadsButton = actionButton("Xóa bộ cài đã tải", primary = false) { askDeleteDownloads() }
        addView(deleteDownloadsButton)
    }

    /** A masked key box: nothing typed is learned by the keyboard, autofilled or restored. */
    private fun keyField(label: String): EditText = EditText(this).apply {
        hint = label
        setHintTextColor(Palette.muted)
        setTextColor(Palette.text)
        textSize = 15f
        isSingleLine = true
        isSaveEnabled = false
        typeface = Typeface.MONOSPACE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        background = rounded(Palette.background, Palette.stroke, 12)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun filterCard(): View = card().apply {
        addView(sectionTitle("Bộ lọc đồ (loot filter)"))
        filterAuthorText = body(muted = true, size = 13f)
        addView(filterAuthorText)
        filterNameText = body(size = 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        }
        addView(filterNameText)
        filterDescText = body(muted = true, size = 12f)
        addView(filterDescText)
        filterStateText = body(size = 13f).apply { setPadding(0, dp(4), 0, 0) }
        addView(filterStateText)
        authorButton = actionButton("Chọn tác giả", primary = false) { chooseAuthor() }
        filterButton = actionButton("Chọn filter", primary = false) { chooseFilter() }
        addView(row(authorButton, filterButton))

        addView(View(context).apply { setBackgroundColor(Palette.stroke) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(8) })
        filterSwitch = Switch(context).apply {
            text = "Luôn cập nhật default.filter"
            setTextColor(Palette.text)
            textSize = 14f
            isChecked = alwaysFilter
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Palette.gold, Palette.muted))
            trackTintList = ColorStateList(states, intArrayOf(Palette.goldDim, Palette.stroke))
            setOnCheckedChangeListener { _, checked ->
                alwaysFilter = checked
                plan = null
                setStatus("Tùy chọn đã đổi. Bấm Kiểm tra lại.")
                refresh()
            }
        }
        addView(filterSwitch)
        addView(body("Bộ lọc mặc định của PD2, dùng khi không chọn filter nào.", muted = true, size = 12f))
    }

    private fun launchCard(): View = card().apply {
        addView(sectionTitle("Khởi động game"))
        addView(body("Renderer", size = 15f).apply { setPadding(0, 0, 0, dp(8)) })
        val segment = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Palette.background, Palette.stroke, 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        rendererViews = Renderer.entries.associateWith { option ->
            TextView(context).apply {
                text = option.label
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { chooseRenderer(option) }
                segment.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        addView(segment)
        launcherNote = body(muted = true, size = 12f).apply { setPadding(0, dp(8), 0, 0) }
        addView(launcherNote)
    }

    private fun logCard(): View = card().apply {
        addView(sectionTitle("Nhật ký"))
        logText = body(muted = true, size = 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "—"
        }
        addView(logText)
    }

    // ---- State ----

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun projectD2(): File? = if (path.isEmpty() || !hasFiles()) null else Updater.resolveProjectD2(path)

    private fun refresh() {
        val granted = hasFiles()
        permissionCard.visibility = if (granted) View.GONE else View.VISIBLE
        val folder = projectD2()
        pathText.text = path.ifEmpty { "Chưa chọn thư mục" }
        pathText.setTextColor(if (path.isEmpty()) Palette.muted else Palette.text)
        when {
            path.isEmpty() -> folderState.text = "Chọn thư mục Diablo II (có ProjectD2 bên trong) hoặc chính ProjectD2."
            !granted -> folderState.text = "Cần cấp quyền để đọc thư mục."
            folder == null -> folderState.text = "✗ Không thấy thư mục ProjectD2 ở đây. Chưa cài game thì chọn thư mục trống và dùng Cài đặt mới."
            else -> folderState.text = "✓ ProjectD2: ${folder.path}"
        }
        folderState.setTextColor(
            when {
                folder != null -> Palette.ok
                path.isNotEmpty() && granted -> Palette.error
                else -> Palette.muted
            },
        )

        val author = filterAuthor
        val chosen = chosenFilter
        filterAuthorText.text = "Tác giả: ${author?.name ?: "chưa chọn"}"
        filterNameText.text = chosen?.display ?: "Chưa chọn filter"
        filterNameText.setTextColor(if (chosen == null) Palette.muted else Palette.text)
        filterDescText.text = chosen?.description.orEmpty()
        filterDescText.visibility = if (chosen?.description.isNullOrBlank()) View.GONE else View.VISIBLE
        when {
            chosen == null -> {
                filterStateText.text = "Chọn như trong launcher PD2: tác giả, rồi filter. App ghi nó thành loot.filter."
                filterStateText.setTextColor(Palette.muted)
            }
            pendingFilter != null -> {
                filterStateText.text = "● Có bản mới của filter, bấm Cập nhật."
                filterStateText.setTextColor(Palette.gold)
            }
            else -> {
                filterStateText.text = "✓ Đang dùng làm loot.filter"
                filterStateText.setTextColor(Palette.ok)
            }
        }

        rendererViews.forEach { (option, view) ->
            val selected = option == renderer
            view.background = if (selected) rounded(Palette.gold, radius = 9) else null
            view.setTextColor(if (selected) Palette.onGold else Palette.muted)
            view.isEnabled = !running
        }
        launcherNote.text = "App ghi file ${GameLauncher.FILE_NAME} trong ProjectD2, chạy Game.exe ${renderer.argument}. " +
            "Trong Winlator, tạo shortcut tới file này để vào game."

        val target = installFolder()
        val begun = target != null && GameInstaller.hasDownloads(target)
        installCard.visibility = if (running && installing || folder == null || begun) View.VISIBLE else View.GONE
        installButton.text = if (begun) "Tiếp tục cài" else "Cài đặt"
        installButton.enable(!running && target != null)
        baseKeyField.enable(!running)
        expansionKeyField.enable(!running)
        deleteDownloadsButton.visibility = if (begun && folder != null && File(folder, "Game.exe").isFile) View.VISIBLE else View.GONE
        deleteDownloadsButton.enable(!running)

        pickButton.enable(!running)
        filterSwitch.enable(!running)
        authorButton.enable(!running && folder != null)
        filterButton.enable(!running && folder != null && author != null)
        checkButton.enable(!running && folder != null)
        updateButton.enable(!running && (plan?.toUpdate?.isNotEmpty() == true || pendingFilter != null))
        stopButton.visibility = if (running) View.VISIBLE else View.GONE
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setStatus(text: String, color: Int = Palette.text) = runOnUiThread {
        statusText.text = text
        statusText.setTextColor(color)
    }

    private fun addLog(text: String) = runOnUiThread {
        logLines.addLast(text)
        while (logLines.size > 300) logLines.removeFirst()
        logText.text = logLines.joinToString("\n")
    }

    private val progress = object : Progress {
        private var lastBar = -1
        override fun status(text: String) = setStatus(text)
        override fun log(text: String) = addLog(text)
        override fun bar(done: Long, total: Long) {
            val value = if (total > 0) (done * 1000 / total).toInt() else 0
            if (value == lastBar) return
            lastBar = value
            runOnUiThread {
                progressBar.isIndeterminate = total <= 0
                progressBar.progress = value
            }
        }
    }

    // ---- Actions ----

    private fun requestFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER)
        } catch (e: Exception) {
            toast("Máy không có trình chọn thư mục")
        }
    }

    @Deprecated("Activity result API of the platform")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val picked = try {
            FolderPath.fromTreeDocumentId(DocumentsContract.getTreeDocumentId(uri))
        } catch (e: Exception) {
            null
        }
        if (picked == null) {
            toast("Không đọc được thư mục này, hãy chọn thư mục trong bộ nhớ điện thoại")
            return
        }
        path = picked
        plan = null
        pendingFilter = null
        addLog("Thư mục: $picked")
        projectD2()?.let { writeLauncher(it) }
        setStatus("Bấm Kiểm tra để so sánh với bản mới nhất của PD2.")
        refresh()
    }

    private fun chooseRenderer(option: Renderer) {
        if (running) return
        renderer = option
        projectD2()?.let { writeLauncher(it) }
        refresh()
    }

    private fun writeLauncher(folder: File) {
        try {
            val file = GameLauncher.write(folder, renderer)
            addLog("Đã ghi ${file.name} (${renderer.label})")
        } catch (e: Exception) {
            addLog("Không ghi được ${GameLauncher.FILE_NAME}: ${e.message}")
        }
    }

    private fun alwaysUpdate(): Set<String> = if (alwaysFilter) setOf("default.filter") else emptySet()

    /** Runs [job] off the main thread with the busy state on; errors land in the status line. */
    private fun background(job: (File) -> Unit) {
        val folder = projectD2() ?: run {
            toast("Chọn thư mục game trước")
            return
        }
        work { job(folder) }
    }

    private fun work(job: () -> Unit) {
        cancel = AtomicBoolean(false)
        running = true
        refresh()
        thread(name = "pd2-work") {
            try {
                job()
            } catch (e: Cancelled) {
                setStatus("Đã dừng.", Palette.muted)
                addLog("Đã dừng theo yêu cầu.")
            } catch (e: Exception) {
                setStatus("Lỗi: ${e.message}", Palette.error)
                addLog("LỖI: $e")
            } finally {
                runOnUiThread {
                    running = false
                    refresh()
                }
            }
        }
    }

    private fun installFolder(): File? = if (path.isEmpty() || !hasFiles()) null else File(path)

    private fun runInstall() {
        val game = installFolder() ?: run {
            toast("Chọn thư mục trống và cấp quyền trước")
            return
        }
        val baseKey = baseKeyField.text.toString()
        val expansionKey = expansionKeyField.text.toString()
        val problem = CdKeys.problem(KeyField.BASE_GAME, baseKey)?.let { GameInstaller.describe(KeyField.BASE_GAME, it) }
            ?: CdKeys.problem(KeyField.EXPANSION, expansionKey)?.let { GameInstaller.describe(KeyField.EXPANSION, it) }
        installNote.text = problem.orEmpty()
        installNote.setTextColor(Palette.error)
        if (problem != null) return
        plan = null
        pendingFilter = null
        installing = true
        work {
            val lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pd2updater:install")
            lock.acquire(INSTALL_WAKE_MILLIS)
            try {
                GameInstaller(game, baseKey, expansionKey, cancel, progress).run()
                projectD2()?.let { writeLauncher(it) }
                setStatus("✓ Cài đặt xong. Mở Winlator để chơi.", Palette.ok)
                addLog("Cài đặt xong: $game")
                runOnUiThread {
                    baseKeyField.text.clear()
                    expansionKeyField.text.clear()
                    askDeleteDownloads()
                }
            } finally {
                installing = false
                if (lock.isHeld) lock.release()
            }
        }
    }

    private fun askDeleteDownloads() {
        val game = installFolder() ?: return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Xóa bộ cài đã tải?")
            .setMessage("Giải phóng khoảng 2 GB. Game đã cài không bị ảnh hưởng.")
            .setPositiveButton("Xóa") { _, _ ->
                work {
                    GameInstaller.deleteDownloads(game)
                    addLog("Đã xóa bộ cài đã tải.")
                }
            }
            .setNegativeButton("Giữ lại", null)
            .show()
    }

    private fun runCheck() {
        plan = null
        pendingFilter = null
        logLines.clear()
        background { folder ->
            val updater = Updater(folder, cancel)
            val result = updater.check(progress, alwaysUpdate())
            plan = result
            writeLauncher(folder)
            val todo = result.toUpdate
            addLog("Giữ nguyên ${result.kept} file của bạn/launcher.")
            todo.forEach { addLog("Cần cập nhật: ${it.name}") }
            val filterNews = checkFilter(folder)
            val parts = mutableListOf<String>()
            if (todo.isNotEmpty()) parts += "${todo.size} file game cần cập nhật"
            if (filterNews) parts += "filter có bản mới"
            if (parts.isEmpty()) setStatus("✓ PD2 và filter đều là bản mới nhất.", Palette.ok)
            else setStatus("Có ${parts.joinToString(", ")}.", Palette.gold)
        }
    }

    /** Whether the chosen filter has a newer copy than loot.filter; it is kept for Update. */
    private fun checkFilter(folder: File): Boolean {
        val author = filterAuthor ?: return false
        val chosen = chosenFilter ?: return false
        return try {
            progress.status("Đang kiểm tra filter…")
            val service = FilterService(folder)
            val current = service.resolve(author, chosen.name, chosen.id)
            if (current == null) {
                addLog("Filter ${chosen.display} không còn trong danh sách của ${author.name}.")
                false
            } else if (service.isCurrent(current)) {
                addLog("Filter ${chosen.display}: đã là bản mới nhất.")
                false
            } else {
                addLog("Filter ${chosen.display}: có bản mới.")
                pendingFilter = current
                true
            }
        } catch (e: Exception) {
            addLog("Không kiểm tra được filter: ${e.message}")
            false
        }
    }

    private fun runUpdate() {
        val current = plan
        val filter = pendingFilter
        background { folder ->
            val updater = Updater(folder, cancel)
            if (current != null && current.toUpdate.isNotEmpty()) {
                updater.apply(current, progress)
                addLog("Đã cập nhật ${current.toUpdate.size} file game.")
            }
            val author = filterAuthor
            if (filter != null && author != null) {
                progress.status("Đang cập nhật filter…")
                FilterService(folder).apply(author, filter)
                pendingFilter = null
                addLog("Đã cập nhật filter ${filter.displayName}.")
            }
            val after = updater.check(progress, alwaysUpdate())
            plan = after
            writeLauncher(folder)
            if (after.toUpdate.isEmpty()) {
                setStatus("✓ Cập nhật xong. Mở Winlator để chơi.", Palette.ok)
            } else {
                setStatus("Còn ${after.toUpdate.size} file chưa khớp. Thử cập nhật lại.", Palette.error)
            }
        }
    }

    private fun chooseAuthor() {
        background { folder ->
            progress.status("Đang tải danh sách tác giả filter…")
            val authors = authorsCache ?: FilterService(folder).authors().also { authorsCache = it }
            val all = authors + FilterCatalog.localAuthor
            setStatus("Chọn tác giả filter.")
            runOnUiThread {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Tác giả filter")
                    .setItems(all.map { twoLine(it.name, if (it.isLocal) "Filter trong ProjectD2/filters/local" else it.author) }.toTypedArray()) { _, which ->
                        val picked = all[which]
                        if (picked.url != filterAuthor?.url) {
                            filterAuthor = picked
                            chosenFilter = null
                            pendingFilter = null
                        }
                        refresh()
                        chooseFilter()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        }
    }

    private fun chooseFilter() {
        val author = filterAuthor ?: return
        background { folder ->
            progress.status("Đang tải các filter của ${author.name}…")
            val service = FilterService(folder)
            val filters = service.filters(author)
            if (filters.isEmpty()) {
                setStatus(if (author.isLocal) "Chưa có filter nào trong ProjectD2/filters/local." else "${author.name} chưa có filter nào.", Palette.error)
                return@background
            }
            setStatus("Chọn filter của ${author.name}.")
            runOnUiThread {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(author.name)
                    .setItems(filters.map { twoLine(it.displayName, it.description) }.toTypedArray()) { _, which ->
                        applyFilter(author, filters[which])
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        }
    }

    private fun applyFilter(author: FilterCatalog.Author, filter: FilterCatalog.Filter) {
        background { folder ->
            progress.status("Đang áp dụng ${filter.displayName}…")
            FilterService(folder).apply(author, filter)
            chosenFilter = ChosenFilter(filter.name, filter.displayName, filter.description, filter.filterId)
            pendingFilter = null
            addLog("Đã áp dụng filter ${filter.displayName} (${author.name}) thành loot.filter.")
            setStatus("✓ Đã áp dụng filter ${filter.displayName}.", Palette.ok)
        }
    }

    private fun twoLine(title: String, subtitle: String): CharSequence {
        if (subtitle.isBlank()) return title
        return SpannableStringBuilder(title).apply {
            append("\n")
            val start = length
            append(subtitle.trim().take(160))
            setSpan(ForegroundColorSpan(Palette.muted), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun openWinlator() {
        val intent = WINLATOR_PACKAGES.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (intent != null) startActivity(intent) else toast("Không tìm thấy Winlator trên máy")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_FOLDER = 10
        private const val REQUEST_PERMISSION = 11
        private const val INSTALL_WAKE_MILLIS = 6L * 60 * 60 * 1000
        private val WINLATOR_PACKAGES = listOf(
            "com.winlator", "com.winlator.cmod", "com.winlator.glibc", "com.ewt45.winlator", "com.winlator.frost",
        )
    }
}
