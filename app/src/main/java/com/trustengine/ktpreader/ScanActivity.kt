package com.trustengine.ktpreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.trustengine.ktpreader.ocr.KtpOcrProcessor
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera activity for OCR scanning of KTP.
 * Uses CameraX for preview and Google ML Kit for text recognition.
 */
class ScanActivity : AppCompatActivity() {
    
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var resultPreview: TextView
    private lateinit var capturedImage: ImageView
    private lateinit var btnCapture: Button
    private lateinit var btnGallery: Button
    private lateinit var btnConfirm: Button
    
    private var imageCapture: ImageCapture? = null
    private var lastBitmap: Bitmap? = null
    private var ocrResult: com.trustengine.ktpreader.model.KtpData? = null
    
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.scanStatus)
        resultPreview = findViewById(R.id.ocrPreview)
        capturedImage = findViewById(R.id.capturedImage)
        btnCapture = findViewById(R.id.btnCapture)
        btnGallery = findViewById(R.id.btnGallery)
        btnConfirm = findViewById(R.id.btnConfirm)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
        
        btnCapture.setOnClickListener { captureImage() }
        btnGallery.setOnClickListener { pickFromGallery() }
        btnConfirm.setOnClickListener { confirmResult() }
        btnConfirm.isEnabled = false
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                statusText.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun captureImage() {
        val imageCapture = imageCapture ?: return
        
        statusText.text = "📸 Mengambil foto..."
        
        val photoFile = File(cacheDir, "ktp_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    processImage(bitmap)
                }
                
                override fun onError(exception: ImageCaptureException) {
                    statusText.text = "❌ Gagal capture: ${exception.message}"
                }
            }
        )
    }
    
    private fun pickFromGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, REQUEST_GALLERY)
    }
    
    private fun processImage(bitmap: Bitmap) {
        lastBitmap = bitmap
        capturedImage.setImageBitmap(bitmap)
        capturedImage.visibility = android.view.View.VISIBLE
        previewView.visibility = android.view.View.GONE
        
        statusText.text = "🔍 Memproses OCR..."
        
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                statusText.text = "✅ OCR Selesai!"
                
                // Parse KTP data from OCR text
                ocrResult = KtpOcrProcessor.parseOcrText(rawText)
                
                resultPreview.text = """
                    Nama: ${ocrResult?.nama ?: "-"}
                    NIK: ${ocrResult?.nik ?: "-"} ${if (ocrResult?.nikValid == true) "✅" else "❌"}
                    TTL: ${ocrResult?.tempatLahir ?: "-"}, ${ocrResult?.tanggalLahir ?: "-"}
                    JK: ${ocrResult?.jenisKelamin ?: "-"}
                    Alamat: ${ocrResult?.alamat ?: "-"}
                    Provinsi (NIK): ${ocrResult?.nikProvinsi ?: "-"}
                """.trimIndent()
                
                btnConfirm.isEnabled = true
                btnCapture.text = "📸 Ulang"
            }
            .addOnFailureListener { e ->
                statusText.text = "❌ OCR gagal: ${e.message}"
            }
    }
    
    private fun confirmResult() {
        ocrResult?.let { data ->
            val resultIntent = Intent()
            resultIntent.putExtra("ktpData", data)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_GALLERY && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                processImage(bitmap)
            }
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    
    companion object {
        private const val REQUEST_GALLERY = 2001
    }
}
