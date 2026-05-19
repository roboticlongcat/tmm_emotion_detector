package com.example.stressdetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.stressdetector.MediaPipeStressAnalyzer // Убедитесь, что пакет совпадает
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // 🔹 UI элементы
    private lateinit var previewView: PreviewView
    private lateinit var pbStress: ProgressBar
    private lateinit var tvStressLevel: TextView
    private lateinit var tvDetails: TextView

    // 🔹 CameraX & Threading
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null

    // 🔹 Анализатор и состояние
    private lateinit var stressAnalyzer: MediaPipeStressAnalyzer
    private var frameCounter = 0
    private var lastStressValue = 0.35f // Стартовое значение (умеренный фон)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()

        // Инициализация пула потоков и анализатора
        cameraExecutor = Executors.newSingleThreadExecutor()
        stressAnalyzer = MediaPipeStressAnalyzer(this)

        // Запрос разрешения камеры
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            startCamera()
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        pbStress = findViewById(R.id.pbStress)
        tvStressLevel = findViewById(R.id.tvStressLevel)
        tvDetails = findViewById(R.id.tvDetails)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        // Превью камеры
        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // Анализ кадров
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480)) // Оптимально для MediaPipe
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }

        try {
            provider.unbindAll()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            provider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        } catch (e: Exception) {
            Log.e("CAMERA", "Ошибка привязки камеры", e)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        frameCounter++

        // Анализируем каждый 3-й кадр (~10 FPS при 30 FPS камеры).
        // Это убирает лишнюю нагрузку без видимого замедления реакции.
        if (frameCounter % 3 == 0) {
            val bitmap = imageProxy.toBitmap()
            if (bitmap != null) {
                try {
                    // 🧠 Получаем метрики
                    val eyebrowTension = stressAnalyzer.detectEyebrowTension(bitmap)
                    val smileProb = stressAnalyzer.detectSmileProbability(bitmap)

                    // 📊 Рассчитываем итоговый стресс
                    val stress = calculateStress(eyebrowTension, smileProb)
                    lastStressValue = stress

                    // 🖥️ Обновляем UI в главном потоке
                    runOnUiThread {
                        updateUI(stress, eyebrowTension, smileProb)
                    }
                } catch (e: Exception) {
                    Log.e("ANALYZER", "Ошибка анализа кадра", e)
                } finally {
                    bitmap.recycle() // Освобождаем память
                }
            }
        }
        imageProxy.close() // Обязательно закрываем прокси
    }

    /**
     * Формула расчёта стресса:
     * Напряжение бровей (60%) + Отсутствие улыбки (40%)
     */
    private fun calculateStress(eyebrowTension: Float, smileProb: Float): Float {
        val browFactor = eyebrowTension * 0.6f
        val smileFactor = (1f - smileProb) * 0.4f
        return (browFactor + smileFactor).coerceIn(0.1f, 0.95f)
    }

    private fun updateUI(stress: Float, eyebrowTension: Float, smileProb: Float) {
        val percent = (stress * 100).toInt()
        pbStress.progress = percent

        // Динамический цвет прогресс-бара
        val colorRes = when {
            percent < 30 -> R.color.green
            percent < 60 -> R.color.orange
            else -> R.color.red
        }
        pbStress.progressTintList = ContextCompat.getColorStateList(this, colorRes)

        // Текст статуса
        tvStressLevel.text = when {
            percent < 30 -> "Расслаблен 😌"
            percent < 50 -> "Лёгкое напряжение 😐"
            percent < 75 -> "Стресс 😟"
            else -> "Высокий стресс 🚨"
        }

        // Детали для отладки/визуализации
        tvDetails.text = "Брови: ${(eyebrowTension * 100).toInt()}% | Улыбка: ${(smileProb * 100).toInt()}%"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::stressAnalyzer.isInitialized) {
            stressAnalyzer.close()
        }
    }

    /**
     * Конвертация ImageProxy (YUV_420_888) → Bitmap
     * Оптимизировано для Android CameraX
     */
    private fun ImageProxy.toBitmap(): Bitmap? {
        if (format != ImageFormat.YUV_420_888) return null

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val nv21 = ByteArray(width * height + 2 * (width / 2) * (height / 2))
        yBuffer.get(nv21, 0, yBuffer.remaining())
        vBuffer.get(nv21, yBuffer.remaining(), vBuffer.remaining())
        uBuffer.get(nv21, yBuffer.remaining() + vBuffer.remaining(), uBuffer.remaining())

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)

        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }
}