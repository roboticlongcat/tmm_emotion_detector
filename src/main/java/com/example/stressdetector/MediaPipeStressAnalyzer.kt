package com.example.stressdetector

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.components.containers.Category

class MediaPipeStressAnalyzer(context: Context) {

    private val landmarker: FaceLandmarker by lazy {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(false)
            .build()
            .let { FaceLandmarker.createFromOptions(context, it) }
    }

    /**
     * Возвращает напряжение бровей: 0.0 (расслаблены) → 1.0 (нахмурены)
     * Работает мгновенно, использует прямые ML-метрики мимики (Blendshapes)
     */
    fun detectEyebrowTension(bitmap: Bitmap): Float {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detect(mpImage)

        // 🔑 MediaPipe Java API возвращает java.util.Optional<List<List<Category>>>
        val blendshapesOpt = result.faceBlendshapes()
        if (!blendshapesOpt.isPresent) return 0f

        val facesBlendshapes = blendshapesOpt.get()
        if (facesBlendshapes.isEmpty()) return 0f

        // Берем категории для первого обнаруженного лица
        val categories: List<Category> = facesBlendshapes[0]

        // Вручную ищем по имени (избегаем проблем с Kotlin-лямбдами и Java List)
        fun getScore(name: String): Float {
            for (cat in categories) {
                if (cat.categoryName() == name) {
                    return cat.score()
                }
            }
            return 0f
        }

        // Blendshapes: прямые вероятности мышечных действий
        val browDownL = getScore("browDownLeft")   // опускание левой брови
        val browDownR = getScore("browDownRight")  // опускание правой брови
        val browInnerUp = getScore("browInnerUp")  // подъем внутренних уголков
        val eyeSquintL = getScore("eyeSquintLeft") // прищур левого глаза
        val eyeSquintR = getScore("eyeSquintRight")// прищур правого глаза

        // Формула напряжения: хмурение (70%) + подъем внутренних уголков (15%) + прищур (15%)
        val tension = ((browDownL + browDownR) / 2f) * 0.7f +
                browInnerUp * 0.15f +
                ((eyeSquintL + eyeSquintR) / 2f) * 0.15f

        return tension.coerceIn(0f, 1f)
    }

    fun close() = landmarker.close()

    // В конец класса MediaPipeStressAnalyzer:

    /**
     * Возвращает вероятность улыбки: 0.0 (нет улыбки) → 1.0 (явная улыбка)
     * Использует прямые ML-метрики MediaPipe (mouthSmileLeft/Right)
     */
    fun detectSmileProbability(bitmap: Bitmap): Float {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detect(mpImage)

        val blendshapesOpt = result.faceBlendshapes()
        if (!blendshapesOpt.isPresent) return 0f

        val facesBlendshapes = blendshapesOpt.get()
        if (facesBlendshapes.isEmpty()) return 0f

        val categories: List<Category> = facesBlendshapes[0]

        // Вручную ищем по имени (Java-Kotlin интероп)
        fun getScore(name: String): Float {
            for (cat in categories) {
                if (cat.categoryName() == name) {
                    return cat.score()
                }
            }
            return 0f
        }

        // Blendshapes для улыбки
        val smileL = getScore("mouthSmileLeft")
        val smileR = getScore("mouthSmileRight")

        // Анти-улыбка: опущенные уголки рта (печаль/напряжение)
        val frownL = getScore("mouthFrownLeft")
        val frownR = getScore("mouthFrownRight")

        // Чистая улыбка = (смайл) - (нахмуренный рот)
        val rawSmile = ((smileL + smileR) / 2f) - ((frownL + frownR) / 2f)

        return rawSmile.coerceIn(0f, 1f)
    }

    /**
     * Комбинированная метрика: стресс от выражения лица
     * Возвращает: 0.0 (расслаблен/улыбается) → 1.0 (напряжён/нахмурен)
     */
    fun detectFacialStress(bitmap: Bitmap): Float {
        val eyebrowTension = detectEyebrowTension(bitmap)
        val smileProb = detectSmileProbability(bitmap)

        // Формула: брови (60%) + отсутствие улыбки (40%)
        // Улыбка снижает стресс, поэтому используем (1 - smileProb)
        val stress = eyebrowTension * 0.6f + (1f - smileProb) * 0.4f

        return stress.coerceIn(0f, 1f)
    }
}