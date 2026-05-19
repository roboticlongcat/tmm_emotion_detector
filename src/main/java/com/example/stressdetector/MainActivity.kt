package com.example.stressdetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.stressdetector.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null

    private var isDetectionRunning = true
    private var currentStressLevel = 25f
    private var frameCounter = 0

    private lateinit var faceDetector: com.google.mlkit.vision.face.FaceDetector

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera() else {
            Toast.makeText(this, "Камера необходима", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        initializeFaceDetector()

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCameraIfPermissionGranted()
    }

    private fun initializeFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()

        faceDetector = FaceDetection.getClient(options)
        Log.d("MLKit", "✅ Face Detector инициализирован")
    }

    private fun setupUI() {
        binding.btnToggleDetection.setOnClickListener {
            isDetectionRunning = !isDetectionRunning
            binding.btnToggleDetection.text = if (isDetectionRunning) "Остановить" else "Запустить"
            Toast.makeText(this, if (isDetectionRunning) "Анализ запущен" else "Анализ остановлен", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetHistory.setOnClickListener {
            currentStressLevel = 25f
            updateUI(currentStressLevel)
            Toast.makeText(this, "Сброшено к норме", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCameraIfPermissionGranted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }

            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
            Log.d("Camera", "✅ Камера запущена")

        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        frameCounter++

        if (!isDetectionRunning) {
            imageProxy.close()
            return
        }

        // Обрабатываем каждый 3-й кадр
        if (frameCounter % 3 != 0) {
            imageProxy.close()
            return
        }

        val bitmap = yuvToBitmap(imageProxy)

        if (bitmap != null) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)

                faceDetector.process(image)
                    .addOnSuccessListener { faces ->
                        if (faces.isNotEmpty()) {
                            val face = faces[0]
                            val stressLevel = calculateStressFromFace(face)
                            currentStressLevel = currentStressLevel * 0.7f + stressLevel * 0.3f

                            runOnUiThread {
                                updateUI(currentStressLevel)
                                binding.tvConfidence.text = "Лицо обнаружено"
                            }
                            Log.d("Stress", "Стресс: ${String.format("%.0f", currentStressLevel)}%")
                        } else {
                            runOnUiThread {
                                binding.tvEmotion.text = "😐 Лицо не обнаружено"
                                binding.tvConfidence.text = "Посмотрите в камеру"
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("Analysis", "Ошибка ML Kit: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e("Analysis", "Ошибка: ${e.message}")
            }
        }

        imageProxy.close()
    }

    private fun yuvToBitmap(imageProxy: ImageProxy): Bitmap? {
        try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val yBytes = ByteArray(ySize)
            val uBytes = ByteArray(uSize)
            val vBytes = ByteArray(vSize)

            yBuffer.get(yBytes)
            uBuffer.get(uBytes)
            vBuffer.get(vBytes)

            val width = imageProxy.width
            val height = imageProxy.height
            val pixels = IntArray(width * height)

            var index = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val yIndex = y * width + x
                    val uvIndex = (y / 2) * (width / 2) + (x / 2)

                    val Y = yBytes[yIndex].toInt() and 0xFF
                    val U = uBytes[uvIndex].toInt() and 0xFF
                    val V = vBytes[uvIndex].toInt() and 0xFF

                    // YUV to RGB conversion
                    var R = (Y + 1.402 * (V - 128)).toInt()
                    var G = (Y - 0.344 * (U - 128) - 0.714 * (V - 128)).toInt()
                    var B = (Y + 1.772 * (U - 128)).toInt()

                    R = R.coerceIn(0, 255)
                    G = G.coerceIn(0, 255)
                    B = B.coerceIn(0, 255)

                    pixels[index++] = (0xFF shl 24) or (R shl 16) or (G shl 8) or B
                }
            }

            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

        } catch (e: Exception) {
            Log.e("Analysis", "YUV to Bitmap error: ${e.message}")
            return null
        }
    }

    private fun calculateStressFromFace(face: com.google.mlkit.vision.face.Face): Float {
        var stress = 0.35f

        // Анализ улыбки
        val smiling = face.smilingProbability ?: 0.5f
        when {
            smiling > 0.7f -> stress -= 0.25f
            smiling > 0.5f -> stress -= 0.1f
            smiling < 0.3f -> stress += 0.2f
            smiling < 0.4f -> stress += 0.1f
        }

        // Анализ открытости глаз
        val leftEye = face.leftEyeOpenProbability ?: 0.5f
        val rightEye = face.rightEyeOpenProbability ?: 0.5f
        val eyesOpen = (leftEye + rightEye) / 2f

        when {
            eyesOpen < 0.3f -> stress += 0.3f
            eyesOpen < 0.45f -> stress += 0.15f
            eyesOpen > 0.7f -> stress -= 0.1f
        }

        // Анализ положения головы
        val headYaw = face.headEulerAngleY ?: 0f
        if (Math.abs(headYaw) > 15f) {
            stress += 0.1f
        }

        stress = stress.coerceIn(0.1f, 0.9f)

        return stress * 100
    }

    private fun updateUI(stressPercent: Float) {
        val percent = stressPercent.toInt()

        val (status, message, colorRes) = when (percent) {
            in 0..25 -> Triple("😊 Спокойствие", "Эмоциональный фон в норме", android.R.color.holo_green_dark)
            in 26..45 -> Triple("🙂 Лёгкое напряжение", "Небольшие признаки стресса", android.R.color.holo_orange_dark)
            in 46..65 -> Triple("😐 Умеренный стресс", "Рекомендуется перерыв", android.R.color.holo_orange_light)
            in 66..85 -> Triple("😟 Высокий стресс", "Требуется внимание", android.R.color.holo_red_dark)
            else -> Triple("😫 Критический стресс", "Срочно нужна поддержка!", android.R.color.holo_red_light)
        }

        binding.tvEmotion.text = status
        binding.tvConfidence.text = message
        binding.tvStressLevel.text = "$percent%"
        binding.progressStress.progress = percent

        val stressColor = ContextCompat.getColor(this, colorRes)
        binding.progressStress.progressTintList = android.content.res.ColorStateList.valueOf(stressColor)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceDetector.close()
    }
}