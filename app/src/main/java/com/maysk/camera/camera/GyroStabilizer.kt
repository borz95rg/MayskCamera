package com.maysk.camera.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.maysk.camera.data.Stabilization
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Электронная стабилизация Maysk (EIS) по гироскопу.
 *
 * Гироскоп 200+ раз в секунду сообщает поворот телефона. Мы считаем «реальную» траекторию
 * (сумма поворотов) и «желаемую» — плавную (сглаженную). Разница = дрожание рук/дороги.
 * Кадр немного увеличивается (запас [Stabilization.margin]) и сдвигается/поворачивается навстречу
 * дрожанию — картинка остаётся неподвижной, а плавные повороты оператора сохраняются.
 *
 * Результат — матрица выборки в «вертикальных» координатах экрана (u вправо, v вниз),
 * которую GL-процессор применяет к каждому кадру превью и видео.
 */
class GyroStabilizer(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var thread: HandlerThread? = null

    val available: Boolean get() = gyro != null

    @Volatile var level: Stabilization = Stabilization.OFF
    @Volatile var front: Boolean = false
    @Volatile var invertX: Boolean = false
    @Volatile var invertY: Boolean = false
    /** tan(половины угла обзора) по горизонтали/вертикали вертикального кадра (с учётом зума). */
    @Volatile var tanHalfFovU: Float = 0.45f
    @Volatile var tanHalfFovV: Float = 0.6f

    // интегрированные углы (рад): x — наклон вверх/вниз, y — поворот влево/вправо, z — завал горизонта
    private var ax = 0.0; private var ay = 0.0; private var az = 0.0
    // сглаженная траектория (два каскада — для Gimbal-плавности)
    private var s1x = 0.0; private var s1y = 0.0; private var s1z = 0.0
    private var s2x = 0.0; private var s2y = 0.0; private var s2z = 0.0
    private var lastTs = 0L
    private var energy = 0.0   // средняя скорость вращения — для режима «Авто»

    // «Сырые» углы для панорамы (рад) и скорость поворота (рад/с) — считаются всегда, пока датчик включён
    @Volatile var rawYaw: Float = 0f; private set
    @Volatile var rawPitch: Float = 0f; private set
    @Volatile var rate: Float = 0f; private set
    private var rawTs = 0L
    private var rYaw = 0.0; private var rPitch = 0.0

    /** История компенсаций (время датчика → поправка), чтобы брать поправку на момент съёмки кадра. */
    private val histT = LongArray(256)
    private val histC = Array(256) { FloatArray(4) }
    private var histN = 0
    private var histPos = 0

    /** Текущая компенсация: du, dv (доли кадра), поворот (градусы), увеличение. */
    @Volatile var correction: FloatArray = floatArrayOf(0f, 0f, 0f, 1f)
        private set

    /** Текущий уровень, выбранный в режиме «Авто» (для подсказки в интерфейсе). */
    @Volatile var autoLabel: String = ""
        private set

    fun start() {
        if (gyro == null || thread != null) return
        val t = HandlerThread("MayskGyro").apply { start() }
        thread = t
        reset()
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        correction = floatArrayOf(0f, 0f, 0f, 1f)
    }

    fun reset() {
        ax = 0.0; ay = 0.0; az = 0.0
        s1x = 0.0; s1y = 0.0; s1z = 0.0; s2x = 0.0; s2y = 0.0; s2z = 0.0
        lastTs = 0L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Поправка на момент экспозиции кадра [frameTsNs] (время камеры). Устраняет «опоздание»
     * стабилизации на 30–60 мс, из-за которого при ходьбе картинка подрагивала.
     * Если часы камеры и датчика не совпадают — возвращает последнюю поправку.
     */
    fun correctionAt(frameTsNs: Long): FloatArray {
        val n = histN
        if (n == 0 || frameTsNs <= 0L) return correction
        val boot = android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        var ts0 = 0L; var tsArr = LongArray(0); var cArr: Array<FloatArray> = emptyArray()
        synchronized(histT) { tsArr = histT.copyOf(); cArr = Array(256) { histC[it].copyOf() }; ts0 = histT[(histPos - 1 + 256) % 256] }
        // середина экспозиции ≈ +8 мс от начала кадра
        var ts = frameTsNs + 8_000_000L
        if (abs(ts0 - ts) > 300_000_000L) ts += boot     // камера в «монотонных» часах
        if (abs(ts0 - ts) > 300_000_000L) return correction
        var best = -1; var bestD = Long.MAX_VALUE
        for (i in 0 until n) {
            val d = abs(tsArr[i] - ts)
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD < 40_000_000L) cArr[best] else correction
    }

    private fun pushHistory(ts: Long, c: FloatArray) {
        synchronized(histT) {
            histT[histPos] = ts
            System.arraycopy(c, 0, histC[histPos], 0, 4)
            histPos = (histPos + 1) % 256
            if (histN < 256) histN++
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        // сырые углы для панорамы
        if (rawTs != 0L) {
            val rdt = ((e.timestamp - rawTs) / 1e9).coerceIn(0.0, 0.05)
            rYaw += e.values[1] * rdt; rPitch += e.values[0] * rdt
            rawYaw = rYaw.toFloat(); rawPitch = rPitch.toFloat()
            rate = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        }
        rawTs = e.timestamp
        val lv = level
        if (!lv.software) { correction = floatArrayOf(0f, 0f, 0f, 1f); lastTs = 0L; histN = 0; return }
        if (lastTs == 0L) { lastTs = e.timestamp; return }
        val dt = ((e.timestamp - lastTs) / 1e9).coerceIn(0.0, 0.05)
        lastTs = e.timestamp
        val wx = e.values[0].toDouble(); val wy = e.values[1].toDouble(); val wz = e.values[2].toDouble()
        ax += wx * dt; ay += wy * dt; az += wz * dt

        // «Энергия» движения за ~1 с — для автоматического выбора уровня
        val speed = sqrt(wx * wx + wy * wy + wz * wz)
        energy += (speed - energy) * (dt / (1.0 + dt))

        val (tau, margin) = if (lv == Stabilization.AUTO) {
            when {
                energy < 0.12 -> { autoLabel = "Обычная"; 0.3 to 0.07 }
                energy < 0.45 -> { autoLabel = "Ходьба"; 0.5 to 0.09 }
                energy < 1.0 -> { autoLabel = "Машина/бег"; 0.8 to 0.12 }
                else -> { autoLabel = "Ультра"; 1.1 to 0.13 }
            }
        } else lv.tau.toDouble() to lv.margin.toDouble()

        // запас кадра (в радианах) по осям
        val maxU = atan(margin * 0.85 * 2 * tanHalfFovU)
        val maxV = atan(margin * 0.85 * 2 * tanHalfFovV)
        val maxRoll = Math.toRadians(3.0 + margin * 20)

        // Два каскада сглаживания. Вместо жёсткого упора в край запаса (давал «прыжки» при ходьбе)
        // траектория плавно ускоряется, когда поправка приближается к краю.
        val k = dt / (tau / 2 + dt)
        fun boost(d: Double, m: Double): Double {
            val ex = ((abs(d) / m) - 0.5).coerceAtLeast(0.0) / 0.5
            return (1.0 + 6.0 * ex * ex).coerceAtMost(1.0 / k.coerceAtLeast(1e-6))
        }
        val kx = (k * boost(ax - s2x, maxV)).coerceAtMost(1.0)
        val ky = (k * boost(ay - s2y, maxU)).coerceAtMost(1.0)
        val kz = (k * boost(az - s2z, maxRoll)).coerceAtMost(1.0)
        s1x += (ax - s1x) * kx; s1y += (ay - s1y) * ky; s1z += (az - s1z) * kz
        s2x += (s1x - s2x) * kx; s2y += (s1y - s2y) * ky; s2z += (s1z - s2z) * kz

        // мягкое ограничение (tanh): поправка никогда не выходит за запас, но и не «упирается» резко
        val dYaw = maxU * tanh((ay - s2y) / maxU)
        val dPitch = maxV * tanh((ax - s2x) / maxV)
        val dRoll = maxRoll * tanh((az - s2z) / maxRoll)

        // перевод углов в сдвиг кадра (доли ширины/высоты вертикального кадра)
        var du = tan(dYaw) / (2 * tanHalfFovU)
        var dv = tan(dPitch) / (2 * tanHalfFovV)
        if (front) dv = -dv
        if (invertX) du = -du
        if (invertY) dv = -dv
        val zoom = 1.0 / (1.0 - 2 * margin)
        correction = floatArrayOf(du.toFloat(), dv.toFloat(), Math.toDegrees(dRoll).toFloat(), zoom.toFloat())
        pushHistory(e.timestamp, correction)

        // периодически «обнуляем» большие числа, чтобы не терять точность
        if (abs(ax) > 100 || abs(ay) > 100 || abs(az) > 100) {
            s1x -= ax; s2x -= ax; ax = 0.0
            s1y -= ay; s2y -= ay; ay = 0.0
            s1z -= az; s2z -= az; az = 0.0
        }
    }

    private fun sign(v: Double) = if (v >= 0) 1.0 else -1.0
}
