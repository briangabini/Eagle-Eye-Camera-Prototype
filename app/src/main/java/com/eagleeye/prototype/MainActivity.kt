package com.eagleeye.prototype

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.FlipCameraAndroid
import androidx.compose.material.icons.sharp.Lens
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import com.eagleeye.prototype.ui.theme.CameraPrototypeTheme
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var textureView: TextureView
    private lateinit var imageReader: ImageReader
    private val handler = Handler(Looper.getMainLooper())
    private var cameraId: String = ""
    private var latestImagePath: String? by mutableStateOf(null)
    private var isUsingFrontCamera = false


    private val permissionsRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        permissions.entries.forEach { permission ->
            if (!permission.value) {
                // Permission was denied
            } else {
                startCameraPreview()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CameraPrototypeTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraScreen()
                }
            }
        }

        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        val allPermissionsGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            startCameraPreview()
//            fetchLatestImageFromGallery()
        } else {
            permissionsRequest.launch(permissions)
        }
    }

    @Composable
    fun CameraPreview() {
        AndroidView(factory = { context ->
            textureView = TextureView(context)
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    setUpCamera()
                    openCamera()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
            textureView
        }, modifier = Modifier.fillMaxSize())
    }

    @Composable
    fun CameraScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            // Container Box for CameraPreview and GridOverlay
            Box(
                modifier = Modifier
                    .weight(1f) // This makes the box take up all available space except the height of the bottom box
                    .fillMaxWidth()
            ) {
                CameraPreview() // Camera preview
//                GridOverlay()   // Grid overlay on top of the preview
            }

            // Semi-transparent container for the capture button and other UI elements
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp) // Height of the bottom box
                    .background(Color.Black)
            ) {
                // Capture button
                IconButton(
                    onClick = {
                        captureBurstImages() // Capture burst images when clicked
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(100.dp)
                        .padding(8.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Sharp.Lens,
                        contentDescription = "Take picture",
                        tint = Color.White,
                        modifier = Modifier
                            .size(80.dp)
                            .padding(1.dp)
                            .border(1.dp, Color.White, CircleShape)
                    )
                }
            }
        }
    }

    private fun setUpCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            cameraId = if (isUsingFrontCamera) {
                cameraManager.cameraIdList.first { id ->
                    cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                }
            } else {
                cameraManager.cameraIdList.first { id ->
                    cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            }

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            if (map != null) {
                // Get the available sizes for the JPEG format
                val outputSizes = map.getOutputSizes(ImageFormat.JPEG)

                // Select the largest available size
                val maxSize = outputSizes?.maxByOrNull { it.width * it.height }

                maxSize?.let { selectedSize ->
                    Log.d("Camera", "Max resolution: ${selectedSize.width}x${selectedSize.height}")

                    // Update the ImageReader with the maximum resolution
                    imageReader = android.media.ImageReader.newInstance(selectedSize.width, selectedSize.height, ImageFormat.JPEG, 10)

                    // Set the preview size to match the max resolution
                    textureView.surfaceTexture?.setDefaultBufferSize(selectedSize.width, selectedSize.height)
                }
            } else {
                Log.e("CameraError", "No supported sizes found")
            }
        } catch (e: CameraAccessException) {
            Log.e("CameraError", "Failed to access camera characteristics", e)
        }
    }

    private fun openCamera() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    @RequiresApi(Build.VERSION_CODES.P)
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCameraPreviewSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraDevice.close()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraDevice.close()
                    }
                }, null)
            }
        } catch (e: CameraAccessException) {
            Log.e("CameraError", "Failed to open camera", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createCameraPreviewSession() {
        try {
            val surfaceTexture = textureView.surfaceTexture
            surfaceTexture?.setDefaultBufferSize(1920, 1080) // Set the preview size

            val previewSurface = Surface(surfaceTexture)
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(previewSurface)

            // Create OutputConfiguration objects
            val outputConfigurations = listOf(
                OutputConfiguration(previewSurface),
                OutputConfiguration(imageReader.surface)
            )

            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigurations,
                ContextCompat.getMainExecutor(this), // Use Executor instead of Handler
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CameraError", "Failed to configure camera preview")
                    }
                }
            )

            cameraDevice.createCaptureSession(sessionConfiguration)
        } catch (e: CameraAccessException) {
            Log.e("CameraError", "Failed to create camera preview session", e)
        }
    }

    private fun captureBurstImages() {
        if (!::cameraDevice.isInitialized) {
            showToastAtTop("Camera is not ready")
            return
        }
        try {
            val burstCaptureRequests = mutableListOf<CaptureRequest>()
            for (i in 0 until 10) { // Capture 10 images in burst
                val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureRequestBuilder.addTarget(imageReader.surface)
                burstCaptureRequests.add(captureRequestBuilder.build())
            }

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() // Use acquireNextImage instead of acquireLatestImage
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    // Save the image with proper orientation
                    saveImage(bytes)
                    image.close() // Release image immediately after processing
                }
            }, handler)

            cameraCaptureSession.captureBurst(burstCaptureRequests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                }
            }, handler)
            showToastAtTop("Burst Image Captured")
        } catch (e: CameraAccessException) {
            Log.e("CameraError", "Error capturing burst images", e)
        }
    }

    private fun saveImage(bytes: ByteArray) {
        val filename = "IMG_${System.currentTimeMillis()}.jpeg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        contentResolver.openOutputStream(uri!!).use {
            it?.write(bytes)
        }

        Toast.makeText(this, "Image captured and saved.", Toast.LENGTH_SHORT).show()
    }

    // Function to adjust image rotation based on device orientation
    private fun adjustImageRotation(bitmap: Bitmap): Bitmap {
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()

        // Adjust the matrix to correct the image orientation
        when (rotation) {
            Surface.ROTATION_0 -> matrix.postRotate(90f)
            Surface.ROTATION_90 -> matrix.postRotate(0f)
            Surface.ROTATION_180 -> matrix.postRotate(270f)
            Surface.ROTATION_270 -> matrix.postRotate(180f)
        }

        // Additional rotation for the front camera
        if (isUsingFrontCamera) {
            matrix.postRotate(180f) // Rotate 180 degrees counterclockwise
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun startCameraPreview() {
        if (::textureView.isInitialized && textureView.isAvailable) {
            setUpCamera()
            openCamera()
        }
    }

    private fun closeCamera() {
        try {
            if (::cameraCaptureSession.isInitialized) {
                cameraCaptureSession.close()
            }
            if (::cameraDevice.isInitialized) {
                cameraDevice.close()
            }
            if (::imageReader.isInitialized) {
                imageReader.close()
            }
        } catch (e: Exception) {
            Log.e("CameraError", "Error closing camera", e)
        }
    }

    private fun showToastAtTop(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 100) // Adjust the offset as needed
        toast.show()
    }

    override fun onResume() {
        super.onResume()
        if (::textureView.isInitialized && textureView.isAvailable) {
            startCameraPreview()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraDevice.isInitialized) {
            closeCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraDevice.isInitialized) {
            closeCamera()
        }
    }

}
