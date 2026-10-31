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

    override fun onSensorChanged(e: SensorEvent) {
        val lv = level
        if (!lv.software) { correction = floatArrayOf(0f, 0f, 0f, 1f); lastTs = 0L; return }
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

        // два каскада сглаживания: плавный старт и плавная остановка движения
        val k = dt / (tau / 2 + dt)
        s1x += (ax - s1x) * k; s1y += (ay - s1y) * k; s1z += (az - s1z) * k
        s2x += (s1x - s2x) * k; s2y += (s1y - s2y) * k; s2z += (s1z - s2z) * k

        // ограничение запасом кадра: если поворот больше запаса — траектория догоняет
        val maxU = atan(margin * 0.85 * 2 * tanHalfFovU)
        val maxV = atan(margin * 0.85 * 2 * tanHalfFovV)
        val maxRoll = Math.toRadians(3.0 + margin * 20)
        var dYaw = ay - s2y; var dPitch = ax - s2x; var dRoll = az - s2z
        if (abs(dYaw) > maxU) { s2y = ay - maxU * sign(dYaw); s1y = s2y; dYaw = ay - s2y }
        if (abs(dPitch) > maxV) { s2x = ax - maxV * sign(dPitch); s1x = s2x; dPitch = ax - s2x }
        if (abs(dRoll) > maxRoll) { s2z = az - maxRoll * sign(dRoll); s1z = s2z; dRoll = az - s2z }

        // перевод углов в сдвиг кадра (доли ширины/высоты вертикального кадра)
        var du = tan(dYaw) / (2 * tanHalfFovU)
        var dv = tan(dPitch) / (2 * tanHalfFovV)
        if (front) dv = -dv
        if (invertX) du = -du
        if (invertY) dv = -dv
        val zoom = 1.0 / (1.0 - 2 * margin)
        correction = floatArrayOf(du.toFloat(), dv.toFloat(), Math.toDegrees(dRoll).toFloat(), zoom.toFloat())

        // периодически «обнуляем» большие числа, чтобы не терять точность
        if (abs(ax) > 100 || abs(ay) > 100 || abs(az) > 100) {
            s1x -= ax; s2x -= ax; ax = 0.0
            s1y -= ay; s2y -= ay; ay = 0.0
            s1z -= az; s2z -= az; az = 0.0
        }
    }

    private fun sign(v: Double) = if (v >= 0) 1.0 else -1.0
}
