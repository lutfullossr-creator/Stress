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
    val bright: Float, val mem: Boolean, val battLimit: Double, val socLimit: Double
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
        Level("O'rtacha", 10, 0.5, 50, 45.0, 0.6f, false, 46.0, 70.0),
        Level("O'g'ir", 20, 1.0, 100, 55.0, 1f, false, 47.0, 78.0),
        Level("Max", 30, 1.0, 100, 90.0, 1f, true, 50.0, 80.0)
    )
    private val lv get() = levels[levelIdx]

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
            text = "Tayyor.\n\nDaraja va rejimni tanlang.\n\nO'rtacha: 10 daq, yarim yuklama\nO'g'ir: 20 daq, to'liq yuklama\nMax: 30 daq, eng yuqori yuklama (5 soniya ogohlantirish)\n\nZaryadkadan uzing, g'ilofni yeching."
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
                    lv.name == "Max" -> beginCountdown()
                    else -> start()
                }
            }
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
                info.text = "⚠ MAX REJIM OGOHLANTIRISHI\n\n" +
                    "• 30 daqiqagacha CPU va GPU to'liq yuklanadi\n" +
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
        running.set(true); setLocked(true)
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
            val heavyMem = lv.mem
            repeat(n) {
                Thread {
                    var x = 0.5
                    val mem = if (heavyMem) LongArray(1 shl 20) else null   // 8 MB: kesh/xotira yuklamasi
                    var j = 1
                    while (running.get()) {
                        for (i in 1..50000) x = sin(x) * cos(x) + sqrt(i.toDouble())
                        var cnt = 50000L
                        if (mem != null) {
                            for (i in 0 until 30000) { j = (j * 1103515245 + 12345) and (mem.size - 1); mem[j] += j.toLong() }
                            cnt += 30000
                        }
                        sink = x
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
                temp >= lv.battLimit -> stop("Xavfsizlik: batareya ${temp}°C ga yetdi")
                soc != null && soc >= lv.socLimit -> stop("Chip harorati chegaraga yetdi: ${soc}°C")
                ts >= 4 -> stop("Android termal holati kritik")
                sec >= total -> stop("${lv.minutes} daqiqa tugadi")
                else -> handler.postDelayed(this, 1000)
            }
        }
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
        renderer.active = false
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
        if (useGpu) sb.append(String.format(Locale.US, "GPU cho'qqi: %.1f G-iter/s | barqarorlik %.0f%%\nGPU: %s\n", peakGpu, gpuStab, renderer.gpuName))
        sb.append(String.format(Locale.US, "=> %s\n\nChip maks harorati: %s\nBatareya harorati: %.1f → maks %.1f°C (hozir %.1f°C)\nBatareya sarfi: %d%% (%d%% → %d%%)\nEng yuqori termal holat: %s\nMaks CPU chastota: %s",
            verdict, if (maxSoc > 0) String.format(Locale.US, "%.1f°C", maxSoc) else "o'qib bo'lmadi",
            tempStart, maxTemp, temp, batStart - bl, batStart, bl,
            thermalName(maxThermal), if (maxFreq > 0) "$maxFreq MHz" else "noma'lum"))
        info.text = sb.toString()
    }

    override fun onPause() {
        super.onPause()
        if (counting) cancelCountdown()
        stop("Ilova yopildi"); glView.onPause()
    }
    override fun onResume() { super.onResume(); glView.onResume() }
}

/**
 * Haqiqiy GPU yuklama: shaderda erta chiqish yo'q, tasodifiy tekstura o'qish, additiv blending (overdraw kesilmaydi),
 * o'tishlar soni dastlabki 4 soniyada moslanadi va keyin qotiriladi (throttling aniq ko'rinsin).
 */
class StressRenderer : GLSurfaceView.Renderer {
    @Volatile var active = false
    @Volatile var frames = 0L
    @Volatile var work = 0.0            // jami bajarilgan: piksel x sikl x o'tish
    @Volatile var gpuName = ""
    @Volatile var error: String? = null
    @Volatile var frameMs = 0.0
    @Volatile var passesNow = 1
    @Volatile var pixels = 0
    @Volatile var targetMs = 55.0
    @Volatile var duty = 100            // GPU band bo'lish ulushi, %

    private var prog = 0
    private var w = 1
    private var h = 1
    private var tLoc = 0
    private var rLoc = 0
    private var gLoc = 0
    private var texLoc = 0
    private var tex = 0
    private var passes = 1.0
    private var calibUntil = 0L
    private var wasActive = false

    private val verts = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    companion object { const val LOOP = 160 }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) error = "Shader xatosi: " + GLES20.glGetShaderInfoLog(s)
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        gpuName = (GLES20.glGetString(GLES20.GL_RENDERER) ?: "?") + " / " + (GLES20.glGetString(GLES20.GL_VENDOR) ?: "")
        val vs = "attribute vec2 p; void main(){ gl_Position = vec4(p,0.0,1.0); }"
        val fs = """
            precision highp float;
            uniform float t; uniform float gain; uniform vec2 r; uniform sampler2D tex;
            void main(){
                vec2 uv = gl_FragCoord.xy / r;
                vec2 z = uv * 2.0 - 1.0;
                float a = 0.0;
                vec3 c = vec3(0.0);
                for(int i = 0; i < $LOOP; i++){
                    z = vec2(sin(z.x*1.7 + t) + cos(z.y*2.3), cos(z.x*2.1 - t) + sin(z.y*1.3));
                    a += sin(length(z)*3.0 + float(i)) * 0.5 + 0.5;
                    if (mod(float(i), 8.0) < 0.5) c += texture2D(tex, fract(z*0.31 + uv)).rgb;
                }
                vec3 col = vec3(fract(a*0.02), fract(c.g*0.1), fract(c.b*0.1 + a*0.01));
                gl_FragColor = vec4(col * gain, 1.0);
            }
        """.trimIndent()
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0 && error == null) error = "Link xatosi: " + GLES20.glGetProgramInfoLog(prog)
        tLoc = GLES20.glGetUniformLocation(prog, "t")
        rLoc = GLES20.glGetUniformLocation(prog, "r")
        gLoc = GLES20.glGetUniformLocation(prog, "gain")
        texLoc = GLES20.glGetUniformLocation(prog, "tex")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        val arr = ByteArray(512 * 512 * 4); java.util.Random(1).nextBytes(arr)
        val buf = ByteBuffer.allocateDirect(arr.size).order(ByteOrder.nativeOrder()); buf.put(arr); buf.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 512, 512, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height; pixels = width * height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!active || prog == 0 || error != null) {
            wasActive = false
            GLES20.glClearColor(0.05f, 0.07f, 0.1f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        if (!wasActive) {
            wasActive = true; passes = 1.0
            calibUntil = System.nanoTime() + 4_000_000_000L
        }
        val t0 = System.nanoTime()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        val pl = GLES20.glGetAttribLocation(prog, "p")
        GLES20.glEnableVertexAttribArray(pl)
        GLES20.glVertexAttribPointer(pl, 2, GLES20.GL_FLOAT, false, 0, verts)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(texLoc, 0)
        GLES20.glUniform2f(rLoc, w.toFloat(), h.toFloat())
        val n = passes.roundToInt().coerceIn(1, 128)
        GLES20.glUniform1f(gLoc, 1f / n)
        for (k in 0 until n) {
            GLES20.glUniform1f(tLoc, (frames % 1000) * 0.05f + k * 0.37f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glFinish()
        val ms = (System.nanoTime() - t0) / 1e6
        frameMs = ms; passesNow = n
        work += n.toDouble() * w * h * LOOP
        frames++
        if (System.nanoTime() < calibUntil) {
            passes = (passes * 0.6 + passes * targetMs / max(ms, 0.5) * 0.4).coerceIn(1.0, 128.0)
        } else if (ms > 250.0) {
            passes = max(1.0, passes * 0.7)
        }
        val d = duty
        if (d < 100) { try { Thread.sleep((ms * (100 - d) / d).toLong().coerceAtMost(500)) } catch (_: InterruptedException) {} }
    }
}

class ChartView(ctx: Context) : View(ctx) {
    var cpu: List<Float> = emptyList()
    var gpu: List<Float> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; style = Paint.Style.STROKE }
    private val grid = Paint().apply { color = Color.parseColor("#2a3340"); strokeWidth = 2f }

    private fun line(c: Canvas, a: List<Float>, color: Int) {
        if (a.size < 2) return
        p.color = color
        val w = width.toFloat(); val h = height.toFloat()
        var px = 0f; var py = 0f
        a.forEachIndexed { i, v ->
            val x = i.toFloat() / (a.size - 1) * w
            val y = h - 6f - (v.coerceIn(0f, 110f) / 110f) * (h - 12f)
            if (i > 0) c.drawLine(px, py, x, y, p)
            px = x; py = y
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#0c1015"))
        val y100 = height - 6f - (100f / 110f) * (height - 12f)
        c.drawLine(0f, y100, width.toFloat(), y100, grid)
        line(c, cpu, Color.parseColor("#ff5a36"))
        line(c, gpu, Color.parseColor("#5aa9ff"))
    }
}
