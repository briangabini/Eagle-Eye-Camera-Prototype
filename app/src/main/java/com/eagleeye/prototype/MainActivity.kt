package com.eagleeye.prototype

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Lens
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.eagleeye.prototype.ui.theme.CameraPrototypeTheme
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var textureView: TextureView
    private lateinit var imageReader: android.media.ImageReader
    private var cameraId: String = ""

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
                    CameraPreview()
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
        Box(modifier = Modifier.fillMaxSize()) {
            CameraPreview()

            // Semi-transparent container for the capture button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp) // Adjust height as needed
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.5f)) // Semi-transparent background
            ) {
                IconButton(
                    onClick = {
                        captureImage() // Capture image when clicked
                    },
                    modifier = Modifier
                        .align(Alignment.Center) // Center the button within the container
                        .size(100.dp) // Set size of the icon button
                        .padding(8.dp)
                        .clip(CircleShape) // Clip to make it circular
                ) {
                    Icon(
                        imageVector = Icons.Sharp.Lens,
                        contentDescription = "Take picture",
                        tint = Color.White,
                        modifier = Modifier
                            .size(80.dp) // Set size of the icon
                            .padding(1.dp)
                            .border(1.dp, Color.White, CircleShape)
                    )
                }
            }
        }
    }

    private fun setUpCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager.cameraIdList[0] // Select the first camera (usually rear)

        // Initialize the ImageReader to capture the image
        imageReader = android.media.ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
    }

    private fun openCamera() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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

    private fun createCameraPreviewSession() {
        try {
            val surfaceTexture = textureView.surfaceTexture
            surfaceTexture?.setDefaultBufferSize(1920, 1080) // Set the preview size

            val previewSurface = Surface(surfaceTexture)
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(previewSurface)

            cameraDevice.createCaptureSession(
                listOf(previewSurface, imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CameraError", "Failed to configure camera preview")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e("CameraError", "Failed to create camera preview session", e)
        }
    }

    private fun captureImage() {
        try {
            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(imageReader.surface)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                // Save the image with proper orientation
                saveImage(bytes)
                image.close()
            }, null)

            cameraCaptureSession.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Toast.makeText(this@MainActivity, "Image Captured", Toast.LENGTH_SHORT).show()
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.e("CameraError", "Error capturing image", e)
        }
    }

    private fun saveImage(bytes: ByteArray) {
        // Convert byte array to Bitmap
        val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Adjust the orientation of the image
        val rotatedBitmap = adjustImageRotation(originalBitmap)

        val filename = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val outputStream: OutputStream?
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        outputStream = imageUri?.let { resolver.openOutputStream(it) }

        outputStream?.use {
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
            it.close()
        }
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

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun startCameraPreview() {
        if (::textureView.isInitialized && textureView.isAvailable) {
            setUpCamera()
            openCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice.close() // Clean up the camera resources
    }
}
