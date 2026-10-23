package com.edi.stress

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Sinov darajasi */
data class Level(
    val name: String, val minutes: Int, val cpuFrac: Double, val gpuDuty: Int, val targetMs: Double,
    val bright: Float, val mem: Boolean, val battLimit: Double, val socLimit: Double,
    val mult: Int = 1, val warnBatt: Double = 0.0
)

class MainActivity : Activity() {
    private val running = AtomicBoolean(false)
    private val ops = AtomicLong(0)
    @Volatile private var sink = 0.0
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: StressRenderer
    private lateinit var info: TextView
    private lateinit var btn: Button
    private lateinit var modeBtn: Button
    private lateinit var levelBtn: Button
    private lateinit var chart: ChartView

    private val cpuHist = ArrayList<Double>()
    private val gpuHist = ArrayList<Double>()
    private var lastWork = 0.0
    private var t0 = 0L
    private var batStart = 0
    private var tempStart = 0.0
    private var maxTemp = 0.0
    private var maxSoc = 0.0
    private var maxThermal = 0
    private var maxFreq = 0
    private var socPaths: List<File> = emptyList()

    private var mode = 0                       // 0 = CPU+GPU, 1 = faqat GPU, 2 = faqat CPU
    private val modeNames = arrayOf("CPU + GPU", "Faqat GPU", "Faqat CPU")
    private val useCpu get() = mode != 1
    private val useGpu get() = mode != 2

    private var levelIdx = 1
    private val levels = listOf(
        //    nom         daq  CPU   GPUduty  kadrMs  yorqinlik mem    batareya  SoC chegara
        Level("O'rtacha", 10, 0.75, 75, 50.0, 0.7f, false, 46.0, 70.0),
        Level("O'g'ir", 20, 1.0, 100, 70.0, 1f, true, 47.0, 78.0),
        Level("Max", 30, 1.0, 100, 120.0, 1f, true, 50.0, 80.0),
        Level("Ultra", 40, 1.0, 100, 200.0, 1f, true, 55.0, 90.0, 2, 50.0)
    )
    private val lv get() = levels[levelIdx]

    @Volatile private var paused = false
    private var warned = false
    private lateinit var contBtn: Button
    private var counting = false
    private var cdRunnable: Runnable? = null

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#10141a"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val title = TextView(this).apply {
            text = "Telefon stres test"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        }
        renderer = StressRenderer()
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        chart = ChartView(this)
        info = TextView(this).apply {
            textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#e8eef5"))
            text = "Tayyor.\n\nDaraja va rejimni tanlang.\n\nO'rtacha: 10 daq, yarim yuklama\nO'g'ir: 20 daq, to'liq yuklama\nMax: 30 daq, eng yuqori yuklama (5 soniya ogohlantirish)\nUltra: 40 daq, Max'dan ham qattiq (batareya 50°C da ogohlantirish, chip 90°C da to'xtaydi)\n\nZaryadkadan uzing, g'ilofni yeching."
        }
        levelBtn = Button(this).apply {
            text = "Daraja: ${lv.name}"; textSize = 14f
            setOnClickListener { levelIdx = (levelIdx + 1) % levels.size; text = "Daraja: ${lv.name}" }
        }
        modeBtn = Button(this).apply {
            text = "Rejim: ${modeNames[mode]}"; textSize = 14f
            setOnClickListener { mode = (mode + 1) % 3; text = "Rejim: ${modeNames[mode]}" }
        }
        btn = Button(this).apply {
            text = "Testni boshlash"; textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#ff5a36"))
            setOnClickListener {
                when {
                    running.get() -> stop("Qo'lda to'xtatildi")
                    counting -> cancelCountdown()
                    lv.name == "Max" || lv.name == "Ultra" -> beginCountdown()
                    else -> start()
                }
            }
        }
        contBtn = Button(this).apply {
            text = "Davom etish (xavfni tushunaman)"; textSize = 15f
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#b36b00")); visibility = View.GONE
            setOnClickListener { resumeAfterWarning() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(levelBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(modeBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(title)
        root.addView(glView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        root.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)).apply { topMargin = dp(6) })
        val sv = ScrollView(this); sv.addView(info)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(215)).apply { topMargin = dp(6) })
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(contBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        setContentView(root)
    }

    private fun setLocked(locked: Boolean) { modeBtn.isEnabled = !locked; levelBtn.isEnabled = !locked }

    // ---- Max rejim: 5 soniyalik ogohlantirish ----
    private fun beginCountdown() {
        counting = true; setLocked(true)
        btn.text = "Bekor qilish"; btn.setBackgroundColor(Color.parseColor("#d6242c"))
        var left = 5
        val r = object : Runnable {
            override fun run() {
                if (!counting) return
                if (left == 0) { counting = false; start(); return }
                info.text = "⚠ ${lv.name.uppercase()} REJIM OGOHLANTIRISHI\n\n" +
                    "• ${lv.minutes} daqiqagacha CPU va GPU to'liq yuklanadi" + (if (lv.mult > 1) " (Max'dan ham qattiq)" else "") + "\n" +
                    (if (lv.warnBatt > 0) "• Batareya ${lv.warnBatt.toInt()}°C dan oshsa yana ogohlantirish chiqadi, davom etish o'zingizning qaroringiz\n" else "") +
                    "• Telefon juda qattiq qiziydi, ushlab bo'lmasligi mumkin\n" +
                    "• Zaryadkadan uzing, g'ilofni yeching, quyoshda qoldirmang\n" +
                    "• SoC ${lv.socLimit.toInt()}°C yoki batareya ${lv.battLimit.toInt()}°C ga yetsa test o'zi to'xtaydi\n\n" +
                    "Boshlanishiga: $left soniya\nBekor qilish uchun tugmani bosing."
                left--
                handler.postDelayed(this, 1000)
            }
        }
        cdRunnable = r
        handler.post(r)
    }

    private fun cancelCountdown() {
        counting = false
        cdRunnable?.let { handler.removeCallbacks(it) }
        setLocked(false)
        btn.text = "Testni boshlash"; btn.setBackgroundColor(Color.parseColor("#ff5a36"))
        info.text = "Bekor qilindi."
    }

    private fun battery(): Triple<Int, Double, Boolean> {
        val i: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val t = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val pl = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return Triple(l * 100 / sc, t, pl)
    }

    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= 29) (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0

    private fun thermalName(s: Int) = when (s) {
        0 -> "Normal"; 1 -> "Yengil"; 2 -> "O'rta"; 3 -> "Kuchli (throttling)"
        4 -> "Kritik"; 5 -> "Favqulodda"; else -> "O'chish"
    }

    private fun cpuFreqMhz(): Int {
        var m = 0
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            try {
                val v = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000
                if (v > m) m = v
            } catch (_: Exception) {}
        }
        return m
    }

    private fun readTemp(f: File): Double? = try {
        var v = f.readText().trim().toDouble()
        if (v > 1000) v /= 1000.0
        if (v in 20.0..125.0) v else null
    } catch (_: Exception) { null }

    /** Chip (SoC) harorati: Android ruxsat bergan termal zonalarning eng issig'i. Ruxsat bo'lmasa null. */
    private fun socTemp(): Double? {
        var best: Double? = null
        for (f in socPaths) { val t = readTemp(f); if (t != null && (best == null || t > best)) best = t }
        return best
    }

    private fun start() {
        if (useGpu && renderer.error != null) { info.text = renderer.error; setLocked(false); btn.text = "Testni boshlash"; return }
        running.set(true); setLocked(true); paused = false; warned = false; contBtn.visibility = View.GONE
        ops.set(0); renderer.frames = 0; renderer.work = 0.0; lastWork = 0.0
        renderer.targetMs = lv.targetMs; renderer.duty = lv.gpuDuty
        renderer.active = useGpu
        cpuHist.clear(); gpuHist.clear(); maxTemp = 0.0; maxSoc = 0.0; maxThermal = 0; maxFreq = 0
        socPaths = (File("/sys/class/thermal").listFiles() ?: emptyArray())
            .filter { it.name.startsWith("thermal_zone") }.map { File(it, "temp") }.filter { readTemp(it) != null }
        val bt = battery(); batStart = bt.first; tempStart = bt.second
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = lv.bright }
        btn.text = "To'xtatish"; btn.setBackgroundColor(Color.parseColor("#d6242c"))
        t0 = SystemClock.elapsedRealtime()
        if (useCpu) {
            val n = max(1, (Runtime.getRuntime().availableProcessors() * lv.cpuFrac).roundToInt())
            val m = lv.mult
            val shared = if (lv.mem) LongArray(if (m > 1) 1 shl 23 else 1 shl 22) else null   // 32/64 MB: DRAM / kesh yuklamasi
            repeat(n) { tid ->
                Thread {
                    // NEON (SIMD) ga mos float massiv + 8 ta mustaqil double zanjir: yadroni haqiqatan qizdiradi
                    val fa = FloatArray(4096) { 1f + it * 0.0001f }
                    val fb = FloatArray(4096) { 0.9999f }
                    val fc = FloatArray(4096) { 0.0001f }
                    var x0 = 0.1; var x1 = 0.2; var x2 = 0.3; var x3 = 0.4
                    var x4 = 0.5; var x5 = 0.6; var x6 = 0.7; var x7 = 0.8
                    var j = tid * 7919 + 1
                    var sc = 0.5
                    while (running.get()) {
                        if (paused) { try { Thread.sleep(100) } catch (_: InterruptedException) {}; continue }
                        for (rep in 0 until 40 * m) for (i in 0 until 4096) fa[i] = fa[i] * fb[i] + fc[i]
                        for (i in 0 until 20000 * m) {
                            x0 = x0 * 0.9999999 + 0.1; x1 = x1 * 0.9999999 + 0.2
                            x2 = x2 * 0.9999999 + 0.3; x3 = x3 * 0.9999999 + 0.4
                            x4 = x4 * 0.9999999 + 0.5; x5 = x5 * 0.9999999 + 0.6
                            x6 = x6 * 0.9999999 + 0.7; x7 = x7 * 0.9999999 + 0.8
                        }
                        for (i in 1..20000) sc = sin(sc) * cos(sc) + sqrt(i.toDouble())
                        var cnt = (327_680L + 320_000L) * m + 20_000L
                        if (shared != null) {
                            for (i in 0 until 60000 * m) {
                                j = (j * 1103515245 + 12345) and (shared.size - 1)
                                shared[j] += j.toLong()
                            }
                            cnt += 60000L * m
                        }
                        sink = x0 + x1 + x2 + x3 + x4 + x5 + x6 + x7 + sc + fa[0]
                        ops.addAndGet(cnt)
                    }
                }.apply { priority = if (lv.cpuFrac < 1.0) Thread.NORM_PRIORITY else Thread.MAX_PRIORITY; start() }
            }
        }
        handler.postDelayed(tick, 1000)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val sec = (SystemClock.elapsedRealtime() - t0) / 1000
            val cpu = ops.getAndSet(0) / 1_000_000.0                    // million amal/s
            val wk = renderer.work; val gpu = (wk - lastWork) / 1e9; lastWork = wk   // G-iteratsiya/s
            cpuHist.add(cpu); gpuHist.add(gpu)
            val (bl, temp, plugged) = battery()
            val ts = thermalStatus(); val fr = cpuFreqMhz(); val soc = socTemp()
            if (temp > maxTemp) maxTemp = temp
            if (soc != null && soc > maxSoc) maxSoc = soc
            if (ts > maxThermal) maxThermal = ts
            if (fr > maxFreq) maxFreq = fr
            val ref = if (gpuHist.size > 5) 5 else 0
            val c0 = cpuHist[minOf(ref, cpuHist.size - 1)].coerceAtLeast(0.0001)
            val g0 = gpuHist[minOf(ref, gpuHist.size - 1)].coerceAtLeast(0.0001)
            chart.cpu = if (useCpu) cpuHist.map { (it / c0 * 100).toFloat() } else emptyList()
            chart.gpu = if (useGpu && gpuHist.size > 5) gpuHist.drop(5).map { (it / g0 * 100).toFloat() } else emptyList()
            chart.invalidate()
            var headroom = ""
            if (Build.VERSION.SDK_INT >= 30) {
                val hr = (getSystemService(Context.POWER_SERVICE) as PowerManager).getThermalHeadroom(5)
                if (!hr.isNaN()) headroom = String.format(Locale.US, "\nIssiqlik zaxirasi: %.0f%% (100%% = throttling)", hr * 100)
            }
            val total = lv.minutes * 60
            val sb = StringBuilder()
            sb.append(String.format(Locale.US, "Vaqt: %d:%02d / %d:00   [%s | %s]\n\n", sec / 60, sec % 60, lv.minutes, lv.name, modeNames[mode]))
            if (useCpu) sb.append(String.format(Locale.US, "CPU: %.0f M amal/s\nCPU chastota: %s\n", cpu, if (fr > 0) "$fr MHz" else "o'qib bo'lmadi"))
            if (useGpu) sb.append(String.format(Locale.US, "GPU: %.1f G-iter/s\nKadr: %.0f ms | o'tishlar: %d | %d px\n%s%s\n",
                gpu, renderer.frameMs, renderer.passesNow, renderer.pixels, renderer.gpuName,
                if (sec < 5) "\n(GPU yuklama moslanmoqda...)" else ""))
            sb.append(String.format(Locale.US, "\nChip (SoC) harorati: %s (chegara %.0f°C)\nBatareya: %d%% (boshida %d%%)\nBatareya harorati: %.1f°C (boshida %.1f°C, chegara %.0f°C)\nTermal holat: %s%s%s",
                if (soc != null) String.format(Locale.US, "%.1f°C", soc) else "o'qib bo'lmadi (Android ruxsat bermaydi)",
                lv.socLimit, bl, batStart, temp, tempStart, lv.battLimit, thermalName(ts), headroom,
                if (plugged) "\n(Zaryadkada — batareya sarfi aniq emas)" else ""))
            info.text = sb.toString()
            when {
                bl <= 8 && !plugged -> stop("Batareya juda kam (${bl}%)")
                temp >= lv.battLimit -> stop("Xavfsizlik: batareya ${temp}°C ga yetdi")
                soc != null && soc >= lv.socLimit -> stop("Chip harorati chegaraga yetdi: ${soc}°C")
                ts >= 4 -> stop("Android termal holati kritik")
                sec >= total -> stop("${lv.minutes} daqiqa tugadi")
                lv.warnBatt > 0 && !warned && temp >= lv.warnBatt -> pauseForWarning(temp)
                else -> handler.postDelayed(this, 1000)
            }
        }
    }

    private fun pauseForWarning(temp: Double) {
        warned = true; paused = true; renderer.active = false
        contBtn.visibility = View.VISIBLE
        info.text = String.format(Locale.US,
            "⚠ BATAREYA %.0f°C DAN OSHDI (hozir %.1f°C)\n\nYuklama vaqtincha to'xtatildi.\n\n" +
            "Davom etsangiz telefon yanada qattiq qiziydi, ushlab bo'lmasligi, sekinlashishi yoki o'chib qolishi mumkin.\n" +
            "Test batareya %.0f°C yoki chip %.0f°C da avtomatik to'xtaydi.\n\n" +
            "Telefonni sovutish uchun qattiq yuzaga qo'ying, g'ilofni yeching.\n\n" +
            "Davom etish yoki To'xtatish ni tanlang.",
            lv.warnBatt, temp, lv.battLimit, lv.socLimit)
    }

    private fun resumeAfterWarning() {
        if (!running.get()) return
        paused = false; renderer.active = useGpu; contBtn.visibility = View.GONE
        handler.postDelayed(tick, 1000)
    }

    private fun avg(a: List<Double>) = if (a.isEmpty()) 0.0 else a.average()

    private fun stability(h: List<Double>): Double {
        if (h.size < 10) return 100.0
        val k = maxOf(1, h.size / 5)
        val first = avg(h.take(k)).coerceAtLeast(0.0001)
        return (avg(h.takeLast(k)) / first * 100).coerceAtMost(100.0)
    }

    private fun stop(reason: String) {
        if (!running.getAndSet(false)) return
        renderer.active = false; paused = false; contBtn.visibility = View.GONE
        handler.removeCallbacks(tick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        btn.text = "Qayta boshlash"; btn.setBackgroundColor(Color.parseColor("#ff5a36")); setLocked(false)
        val (bl, temp, _) = battery()
        val gSteady = if (gpuHist.size > 5) gpuHist.drop(5) else gpuHist
        val cpuStab = stability(cpuHist); val gpuStab = stability(gSteady)
        val peakCpu = cpuHist.maxOrNull() ?: 0.0
        val peakGpu = gSteady.maxOrNull() ?: 0.0
        val score = ((if (useCpu) peakCpu * 2 else 0.0) + (if (useGpu) peakGpu * 10 else 0.0)).roundToInt()
        val worst = if (useCpu && useGpu) minOf(cpuStab, gpuStab) else if (useGpu) gpuStab else cpuStab
        val verdict = when {
            worst >= 90 -> "sovutish a'lo"
            worst >= 75 -> "sovutish yaxshi"
            worst >= 60 -> "qizib sezilarli sekinlashadi"
            else -> "qattiq throttling"
        }
        val el = (SystemClock.elapsedRealtime() - t0) / 1000
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "TUGADI: %s\nDaraja: %s | Rejim: %s | Davomiyligi: %d:%02d\n\nBALL: %d\n", reason, lv.name, modeNames[mode], el / 60, el % 60, score))
        if (useCpu) sb.append(String.format(Locale.US, "CPU cho'qqi: %.0f M amal/s | barqarorlik %.0f%%\n", peakCpu, cpuStab))
        if (useGpu) sb.append(String.format(
