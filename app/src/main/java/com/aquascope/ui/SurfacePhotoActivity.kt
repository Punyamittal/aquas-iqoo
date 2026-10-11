package com.aquascope.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.data.ImpulseArchive
import com.aquascope.data.SurfacePhotoArchive
import com.aquascope.databinding.ActivitySurfacePhotoBinding
import com.aquascope.vision.SurfaceLookAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Torch-lit close-up of the spot that was just scanned by sound. Returns the saved photo's id
 * in [EXTRA_PHOTO_ID]; the photo carries the scan's id so teaching dry/damp labels both.
 */
class SurfacePhotoActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySurfacePhotoBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var scanId: String? = null
    private var locationId: String = ""

    @Volatile private var exposureTimeNs: Long? = null
    @Volatile private var iso: Int? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(
                this,
                if (scanId == null) R.string.surface_photo_no_permission_before else R.string.surface_photo_no_permission,
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySurfacePhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        scanId = intent.getStringExtra(EXTRA_SCAN_ID)
        locationId = intent.getStringExtra(EXTRA_LOCATION_ID) ?: run { finish(); return }
        if (scanId == null) binding.textInstruction.setText(R.string.surface_photo_instruction_before)

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnCapture.setOnClickListener { capture() }
        binding.btnCapture.isEnabled = false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        camera?.cameraControl?.enableTorch(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(true) }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener fail()
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                    }
                }
            )
            val preview = previewBuilder.build().also { it.setSurfaceProvider(binding.preview.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }.onFailure { return@addListener fail() }
            imageCapture = capture
            camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(true) }
            binding.btnCapture.isEnabled = true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val capture = imageCapture ?: return
        binding.btnCapture.isEnabled = false
        binding.textInstruction.setText(R.string.surface_photo_hold_still)
        val exposure = exposureTimeNs
        val sensitivity = iso
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = runCatching { decode(image) }
                    .onFailure { Log.w(TAG, "Decoding the photo failed", it) }
                    .getOrNull()
                image.close()
                if (bitmap == null) return fail()
                Log.i(TAG, "Photo ${bitmap.width}x${bitmap.height}, exposure=${exposure}ns iso=$sensitivity")
                lifecycleScope.launch { save(bitmap, exposure, sensitivity) }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "Capture failed", exception)
                fail()
            }
        })
    }

    /** JPEG straight from the buffer (toBitmap() does not handle it on every CameraX build), upright. */
    private fun decode(image: ImageProxy): Bitmap? {
        val raw = if (image.format == ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            image.toBitmap()
        } ?: return null
        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) return raw
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    private suspend fun save(bitmap: Bitmap, exposure: Long?, sensitivity: Int?) {
        val look = withContext(Dispatchers.Default) {
            val small = scaled(bitmap, ANALYSIS_WIDTH)
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            SurfaceLookAnalyzer.analyze(pixels, small.width, small.height, exposure, sensitivity)
        }
        val quality = SurfaceLookAnalyzer.quality(look)
        if (quality != SurfaceLookAnalyzer.Quality.OK) {
            Log.i(TAG, "Photo rejected ($quality): $look")
            binding.textInstruction.setText(
                if (quality == SurfaceLookAnalyzer.Quality.TOO_DARK) R.string.surface_photo_too_dark
                else R.string.surface_photo_uneven
            )
            binding.btnCapture.setText(R.string.surface_photo_retake_now)
            binding.btnCapture.isEnabled = true
            return
        }
        val meta = withContext(Dispatchers.Default) {
            val jpeg = ByteArrayOutputStream().use { out ->
                scaled(bitmap, STORED_WIDTH).compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
            withContext(Dispatchers.IO) {
                val label = ImpulseArchive(this@SurfacePhotoActivity).labelOf(scanId) ?: ImpulseArchive.LABEL_UNLABELLED
                SurfacePhotoArchive(this@SurfacePhotoActivity)
                    .save(scanId, locationId, label, jpeg, look, exposure, sensitivity)
                    ?.also { saveToGallery(it.id, jpeg) }
            }
        }
        if (meta == null) return fail()
        Log.i(TAG, "Saved surface photo ${meta.id}: ${meta.look}")
        Toast.makeText(
            this,
            if (scanId == null) R.string.surface_photo_saved_before else R.string.surface_photo_saved,
            Toast.LENGTH_SHORT
        ).show()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PHOTO_ID, meta.id))
        finish()
    }

    /** Copy in the phone's gallery under Pictures/AquaScope, so the user can find and share it. */
    private fun saveToGallery(id: String, jpeg: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "aquascope_$id.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AquaScope")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }.onFailure { Log.w(TAG, "Gallery copy failed", it) }
    }

    private fun scaled(bitmap: Bitmap, width: Int): Bitmap {
        if (bitmap.width <= width) return bitmap
        val height = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun fail() {
        Toast.makeText(
            this,
            if (scanId == null) R.string.surface_photo_failed_before else R.string.surface_photo_failed,
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    companion object {
        const val EXTRA_SCAN_ID = "scan_id"
        const val EXTRA_LOCATION_ID = "location_id"
        const val EXTRA_PHOTO_ID = "photo_id"
        private const val TAG = "SurfacePhoto"
        private const val ANALYSIS_WIDTH = 320
        private const val STORED_WIDTH = 1024
    }
}
