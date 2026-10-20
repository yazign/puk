package ru.semin.kakoyyaperdezh

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    // 01 — самое весёлое лицо, 10 — самое грустное
    private val sounds = intArrayOf(
        R.raw.fart_01, R.raw.fart_02, R.raw.fart_03, R.raw.fart_04, R.raw.fart_05,
        R.raw.fart_06, R.raw.fart_07, R.raw.fart_08, R.raw.fart_09, R.raw.fart_10
    )
    private val names = intArrayOf(
        R.string.name_01, R.string.name_02, R.string.name_03, R.string.name_04, R.string.name_05,
        R.string.name_06, R.string.name_07, R.string.name_08, R.string.name_09, R.string.name_10
    )
    private val descs = intArrayOf(
        R.string.desc_01, R.string.desc_02, R.string.desc_03, R.string.desc_04, R.string.desc_05,
        R.string.desc_06, R.string.desc_07, R.string.desc_08, R.string.desc_09, R.string.desc_10
    )
    private val blockColors = intArrayOf(
        0xFFA3E635.toInt(), 0xFFA3E635.toInt(), 0xFFA3E635.toInt(),
        0xFFFFD60A.toInt(), 0xFFFFD60A.toInt(), 0xFFFFD60A.toInt(),
        0xFFFF7A00.toInt(), 0xFFFF7A00.toInt(),
        0xFFFF3DA5.toInt(), 0xFFFF3DA5.toInt()
    )
    private val colorDark = 0xFF12002E.toInt()
    private val colorTrack = 0xFF3B2A5C.toInt()
    private val colorLight = 0xFFEDE7F6.toInt()

    private lateinit var screenStart: View
    private lateinit var screenLoading: View
    private lateinit var screenResult: View
    private lateinit var photoFrame: View
    private lateinit var photoView: ImageView
    private lateinit var photoHint: View
    private lateinit var btnPhoto: Button
    private lateinit var btnWhich: Button
    private lateinit var loadingBlocks: LinearLayout
    private lateinit var loadingPercent: TextView
    private lateinit var loadingMascot: View
    private lateinit var resultName: TextView
    private lateinit var resultDesc: TextView
    private lateinit var powerBlocks: LinearLayout
    private lateinit var powerLabel: TextView

    private var photoFile: File? = null
    private var photoBitmap: Bitmap? = null
    private var resultIndex = 1
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var session = 0
    private var animationDone = false
    private var detectedIndex: Int? = null
    private var detectionFailed = false
    private var noFace = false
    private var mascotAnimator: ObjectAnimator? = null

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
    }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) onPhotoTaken() else deletePhotoFile()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        screenStart = findViewById(R.id.screenStart)
        screenLoading = findViewById(R.id.screenLoading)
        screenResult = findViewById(R.id.screenResult)
        photoFrame = findViewById(R.id.photoFrame)
        photoView = findViewById(R.id.photo)
        photoHint = findViewById(R.id.photoHint)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnWhich = findViewById(R.id.btnWhich)
        loadingBlocks = findViewById(R.id.loadingBlocks)
        loadingPercent = findViewById(R.id.loadingPercent)
        loadingMascot = findViewById(R.id.loadingMascot)
        resultName = findViewById(R.id.resultName)
        resultDesc = findViewById(R.id.resultDesc)
        powerBlocks = findViewById(R.id.powerBlocks)
        powerLabel = findViewById(R.id.powerLabel)

        photoFrame.clipToOutline = true

        // Фото могло остаться, если Android закрыл приложение, пока была открыта камера
        savedInstanceState?.getString(KEY_PHOTO)?.let { photoFile = File(it) }
        if (savedInstanceState == null) {
            File(cacheDir, "share").deleteRecursively()
            File(cacheDir, "photos").deleteRecursively()
        }

        buildBlocks(loadingBlocks, 10)
        buildBlocks(powerBlocks, 10)

        btnPhoto.setOnClickListener { takePhoto() }
        btnWhich.setOnClickListener { startAnalysis() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { resetToStart() }
        findViewById<ImageButton>(R.id.btnPlay).setOnClickListener { playSound() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { share() }
        findViewById<Button>(R.id.btnAgain).setOnClickListener { resetToStart() }

        onBackPressedDispatcher.addCallback(this) {
            if (screenStart.visibility == View.VISIBLE) finish() else resetToStart()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        photoFile?.let { outState.putString(KEY_PHOTO, it.absolutePath) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---------- Фото ----------

    private fun takePhoto() {
        deletePhotoFile()
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        photoFile = file
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_camera)
        }
    }

    private fun onPhotoTaken() {
        val file = photoFile ?: return
        val bmp = try {
            decodeScaled(file, 1280)
        } catch (e: Exception) {
            null
        }
        if (bmp == null) {
            deletePhotoFile()
            toast(R.string.error)
            return
        }
        photoBitmap = bmp
        photoView.setImageBitmap(bmp)
        photoHint.visibility = View.GONE
        btnPhoto.visibility = View.GONE
        btnWhich.visibility = View.VISIBLE
        // Сам файл больше не нужен — удаляем сразу
        deletePhotoFile()
    }

    private fun decodeScaled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val orientation = ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bmp
        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun deletePhotoFile() {
        photoFile?.delete()
        photoFile = null
    }

    // ---------- Анализ ----------

    private fun startAnalysis() {
        val bmp = photoBitmap ?: return
        session++
        val mySession = session
        animationDone = false
        detectedIndex = null
        detectionFailed = false
        noFace = false
        show(screenLoading)
        setBlocks(loadingBlocks, 0, colorTrack, false)
        loadingPercent.text = "0%"
        startMascotAnimation()

        for (step in 1..10) {
            handler.postDelayed({
                if (mySession != session) return@postDelayed
                setBlocks(loadingBlocks, step, colorTrack, false)
                loadingPercent.text = "${step * 10}%"
                if (step == 10) {
                    animationDone = true
                    maybeFinish(mySession)
                }
            }, step * 230L)
        }

        detector.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { faces ->
                if (mySession != session) return@addOnSuccessListener
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) noFace = true else detectedIndex = pickSound(face)
                maybeFinish(mySession)
            }
            .addOnFailureListener {
                if (mySession != session) return@addOnFailureListener
                detectionFailed = true
                maybeFinish(mySession)
            }
    }

    private fun maybeFinish(mySession: Int) {
        if (mySession != session || !animationDone) return
        val index = detectedIndex
        when {
            noFace -> { resetToStart(keepPhoto = false); toast(R.string.no_face) }
            detectionFailed -> { resetToStart(keepPhoto = true); toast(R.string.error) }
            index != null -> showResult(index)
        }
    }

    /**
     * Улыбка выбирает группу: широкая улыбка → звуки 1–3,
     * обычное лицо → 4–7, грустное → 8–10.
     * Внутри группы звук зависит от глаз и наклона головы,
     * поэтому разные фото дают разные звуки.
     */
    private fun pickSound(face: Face): Int {
        val smile = face.smilingProbability ?: 0.5f
        val left = face.leftEyeOpenProbability
        val right = face.rightEyeOpenProbability
        val eyes = if (left != null && right != null) (left + right) / 2f else 0.8f
        val tilt = abs(face.headEulerAngleZ) + abs(face.headEulerAngleY)
        val from: Int
        val size: Int
        when {
            smile >= 0.7f -> { from = 1; size = 3 }
            smile >= 0.25f -> { from = 4; size = 4 }
            else -> { from = 8; size = 3 }
        }
        val k = ((eyes * 10f).toInt() + (tilt / 4f).toInt()) % size
        return (from + k).coerceIn(1, 10)
    }

    // ---------- Результат ----------

    private fun showResult(index: Int) {
        stopMascotAnimation()
        resultIndex = index
        resultName.setText(names[index - 1])
        resultDesc.setText(descs[index - 1])
        val power = 11 - index
        powerLabel.text = getString(R.string.power_value, power)
        setBlocks(powerBlocks, power, colorLight, true)
        show(screenResult)
        playSound()
    }

    private fun playSound() {
        player?.release()
        val p = MediaPlayer.create(this, sounds[resultIndex - 1]) ?: return
        player = p
        p.setOnCompletionListener {
            it.release()
            if (player === it) player = null
        }
        p.start()
    }

    private fun share() {
        try {
            val dir = File(cacheDir, "share")
            dir.deleteRecursively()
            dir.mkdirs()
            val card = makeCard()
            val cardFile = File(dir, "result.png")
            FileOutputStream(cardFile).use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val soundFile = File(dir, "sound_%02d.mp3".format(resultIndex))
            resources.openRawResource(sounds[resultIndex - 1]).use { input ->
                FileOutputStream(soundFile).use { input.copyTo(it) }
            }
            val auth = "$packageName.fileprovider"
            val uris = arrayListOf(
                FileProvider.getUriForFile(this, auth, cardFile),
                FileProvider.getUriForFile(this, auth, soundFile)
            )
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, getString(names[resultIndex - 1])))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_title)))
        } catch (e: Exception) {
            toast(R.string.error)
        }
    }

    /** Картинка 1080×1080 для отправки в мессенджеры */
    private fun makeCard(): Bitmap {
        val size = 1080
        val card = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(card)
        c.drawColor(0xFF3B0F8C.toInt())
        val black = Typeface.create("sans-serif-black", Typeface.NORMAL)
        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // Звёздочки-точки
        fill.color = 0xFFFFD60A.toInt(); c.drawCircle(990f, 110f, 14f, fill); c.drawCircle(70f, 1000f, 12f, fill)
        fill.color = 0xFF22D3EE.toInt(); c.drawCircle(1010f, 600f, 12f, fill)
        fill.color = 0xFFFF3DA5.toInt(); c.drawCircle(60f, 70f, 12f, fill)

        // Заголовок
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = black; textSize = 92f }
        val titleText = getString(R.string.card_brand)
        title.color = 0xFFFF3DA5.toInt(); c.drawText(titleText, 78f, 168f, title)
        title.color = 0xFFFFD60A.toInt(); c.drawText(titleText, 70f, 160f, title)

        // Фото
        val dst = RectF(70f, 230f, 520f, 800f)
        fill.color = colorDark
        c.drawRoundRect(RectF(dst.left, dst.top + 14f, dst.right, dst.bottom + 14f), 56f, 56f, fill)
        fill.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(dst, 56f, 56f, fill)
        photoBitmap?.let { bmp ->
            val scale = max(dst.width() / bmp.width, dst.height() / bmp.height)
            val sw = dst.width() / scale
            val sh = dst.height() / scale
            val sx = (bmp.width - sw) / 2f
            val sy = (bmp.height - sh) / 2f
            val src = Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
            c.save()
            c.clipPath(Path().apply { addRoundRect(dst, 56f, 56f, Path.Direction.CW) })
            c.drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10f; color = colorDark
        }
        c.drawRoundRect(dst, 56f, 56f, stroke)

        // Подпись и название
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 40f; color = 0xFF22D3EE.toInt() }
        c.drawText(getString(R.string.card_label), 575f, 290f, label)

        val nameText = getString(names[resultIndex - 1]).uppercase()
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = black; textSize = 66f; color = colorDark }
        val boxW = 440
        val layout = StaticLayout.Builder.obtain(nameText, 0, nameText.length, namePaint, boxW - 60)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 0.95f)
            .build()
        val box = RectF(570f, 330f, 570f + boxW, 330f + layout.height + 60f)
        fill.color = colorDark
        c.drawRoundRect(RectF(box.left, box.top + 12f, box.right, box.bottom + 12f), 36f, 36f, fill)
        fill.color = 0xFFFFD60A.toInt()
        c.drawRoundRect(box, 36f, 36f, fill)
        c.drawRoundRect(box, 36f, 36f, stroke)
        c.save()
        c.translate(box.left + 30f, box.top + 30f)
        layout.draw(c)
        c.restore()

        val power = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 38f; color = 0xFFFF3DA5.toInt() }
        c.drawText(getString(R.string.card_power, 11 - resultIndex), 575f, box.bottom + 90f, power)

        // «А ты?»
        val youText = getString(R.string.card_you)
        val you = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 44f; color = colorDark }
        val youW = you.measureText(youText)
        val youBox = RectF(1010f - youW - 60f, 900f, 1010f, 990f)
        fill.color = colorDark
        c.drawRoundRect(RectF(youBox.left, youBox.top + 10f, youBox.right, youBox.bottom + 10f), 26f, 26f, fill)
        fill.color = 0xFFA3E635.toInt()
        c.drawRoundRect(youBox, 26f, 26f, fill)
        c.drawRoundRect(youBox, 26f, 26f, stroke)
        c.drawText(youText, youBox.left + 30f, youBox.bottom - 30f, you)
        return card
    }

    // ---------- Вспомогательное ----------

    private fun resetToStart(keepPhoto: Boolean = false) {
        session++
        handler.removeCallbacksAndMessages(null)
        stopMascotAnimation()
        player?.release()
        player = null
        if (!keepPhoto) {
            photoBitmap = null
            photoView.setImageDrawable(null)
            photoHint.visibility = View.VISIBLE
            btnPhoto.visibility = View.VISIBLE
            btnWhich.visibility = View.GONE
        }
        show(screenStart)
    }

    private fun show(screen: View) {
        screenStart.visibility = if (screen === screenStart) View.VISIBLE else View.GONE
        screenLoading.visibility = if (screen === screenLoading) View.VISIBLE else View.GONE
        screenResult.visibility = if (screen === screenResult) View.VISIBLE else View.GONE
    }

    private fun buildBlocks(container: LinearLayout, count: Int) {
        container.removeAllViews()
        repeat(count) { i ->
            val v = View(this)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            lp.leftMargin = if (i == 0) 0 else dp(3)
            lp.rightMargin = if (i == count - 1) 0 else dp(3)
            container.addView(v, lp)
        }
    }

    private fun setBlocks(container: LinearLayout, lit: Int, offColor: Int, withStroke: Boolean) {
        for (i in 0 until container.childCount) {
            val d = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (i < lit) blockColors[i] else offColor)
                if (withStroke) setStroke(dp(2), colorDark)
            }
            container.getChildAt(i).background = d
        }
    }

    private fun startMascotAnimation() {
        stopMascotAnimation()
        loadingMascot.scaleX = 1f
        loadingMascot.scaleY = 1f
        mascotAnimator = ObjectAnimator.ofFloat(loadingMascot, View.SCALE_X, 1f, 1.12f).apply {
            duration = 300
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { loadingMascot.scaleY = loadingMascot.scaleX }
            start()
        }
    }

    private fun stopMascotAnimation() {
        mascotAnimator?.cancel()
        mascotAnimator = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_PHOTO = "photo_file"
    }
}
