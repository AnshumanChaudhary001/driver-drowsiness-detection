package com.example.drowsiness_alarm

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import com.example.drowsiness_alarm.R
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                showCameraScreen()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showCameraScreen()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showCameraScreen() {
        setContent {
            var fps by remember { mutableIntStateOf(0) }
            var latency by remember { mutableLongStateOf(0L) }
            var smilingProb by remember { mutableFloatStateOf(0f) }
            var leftEyeOpen by remember { mutableFloatStateOf(0f) }
            var rightEyeOpen by remember { mutableFloatStateOf(0f) }
            var yawningProb by remember { mutableFloatStateOf(0f) }

            val logText = "FPS: $fps, Latency: $latency ms, " +
                    "Smiling: ${"%.2f".format(smilingProb)}, " +
                    "Left Eye: ${"%.2f".format(leftEyeOpen)}, " +
                    "Right Eye: ${"%.2f".format(rightEyeOpen)}, " +
                    "Yawning: ${"%.2f".format(yawningProb)}"

            Box(modifier = Modifier.fillMaxSize()) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onMetricsChanged = { newFps, newLatency, newSmiling, newLeftEye, newRightEye, newYawn ->
                        fps = newFps
                        latency = newLatency
                        smilingProb = newSmiling
                        leftEyeOpen = newLeftEye
                        rightEyeOpen = newRightEye
                        yawningProb = newYawn
                    }
                )

                Text(
                    text = logText,
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onMetricsChanged: (fps: Int, latency: Long, smiling: Float, leftEye: Float, rightEye: Float, yawn: Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // State to track eye closure time
    var eyeClosureStartTime by remember { mutableStateOf<Long?>(null) }

    // Media player for the alarm sound
    val mediaPlayer = remember {
        try {
            MediaPlayer.create(context, R.raw.sharp_alarm)
        } catch (e: Exception) {
            // Handle case where sound file is missing
            null
        }
    }

    val database = remember { Firebase.database.reference }

    // Clean up the media player when the composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    val detector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    LaunchedEffect(Unit) {
        var frameCount = 0
        var startTime = System.currentTimeMillis()
        var currentFps = 0

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val startFrameTime = System.currentTimeMillis()
                        try {
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )

                                val faces = Tasks.await(detector.process(image))
                                val latency = System.currentTimeMillis() - startFrameTime

                                frameCount++
                                val elapsedTime = System.currentTimeMillis() - startTime
                                if (elapsedTime > 1000) {
                                    currentFps = frameCount
                                    frameCount = 0
                                    startTime = System.currentTimeMillis()
                                }

                                val face = faces.firstOrNull()
                                val smiling = face?.smilingProbability ?: 0f
                                
                                    // Swap eye probabilities to align with the mirrored camera preview.
                                val leftEye = face?.rightEyeOpenProbability ?: 0f
                                val rightEye = face?.leftEyeOpenProbability ?: 0f
                                val yawn = face?.let(::calculateYawn) ?: 0f

                                // Check for eye closure to trigger alarm
                                val bothEyesClosed = leftEye < 0.3f && rightEye < 0.3f
                                if (bothEyesClosed) {
                                    val startTime = eyeClosureStartTime
                                    if (startTime == null) {
                                        eyeClosureStartTime = System.currentTimeMillis() // Start timer
                                    } else {
                                        if (System.currentTimeMillis() - startTime > 1000) {
                                            if (mediaPlayer?.isPlaying == false) {
                                                mediaPlayer.start()
                                            }

                                            // We do this inside the alarm trigger
                                            sendDrowsinessAlertToFirebase(
                                                database = database,
                                                driverId = "Driver_001" // Hardcoded for now
                                            )

                                            // Reset timer after alarm to avoid immediate re-trigger
                                            eyeClosureStartTime = null
                                        }
                                    }
                                } else {
                                    eyeClosureStartTime = null // Reset timer if eyes are open
                                }

                                ContextCompat.getMainExecutor(context).execute {
                                    onMetricsChanged(currentFps, latency, smiling, leftEye, rightEye, yawn)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    analyzer
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

private fun sendDrowsinessAlertToFirebase(database: DatabaseReference, driverId: String) {
    // Get a unique key for the new alert
    val alertId = database.child("alerts").push().key ?: return

    val timestamp = System.currentTimeMillis()

    // Create the alert data as a Map
    val alertData = mapOf(
        "driverId" to driverId,
        "timestamp" to timestamp
    )

    // Write the data to the database at "alerts/{new_alert_id}"
    database.child("alerts").child(alertId).setValue(alertData)
        .addOnSuccessListener {
            // Optional: Log success
            android.util.Log.d("Firebase", "Alert sent successfully.")
        }
        .addOnFailureListener {
            // Optional: Log failure
            android.util.Log.e("Firebase", "Failed to send alert.", it)
        }
}

fun calculateYawn(face: Face): Float {
    val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1f
    val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1f
    val avgEyeClosure = 1 - (leftEyeOpenProb + rightEyeOpenProb) / 2f
    return avgEyeClosure * avgEyeClosure
}

