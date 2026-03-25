package com.trustengine.ktpreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.trustengine.ktpreader.model.KtpData

class ResultActivity : AppCompatActivity() {
    
    private lateinit var ktpData: KtpData
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        
        ktpData = intent.getSerializableExtra("ktpData") as? KtpData ?: KtpData()
        
        val resultText: TextView = findViewById(R.id.fullResultText)
        val verifyStatus: TextView = findViewById(R.id.verifyStatus)
        val btnCopy: Button = findViewById(R.id.btnCopy)
        val btnShare: Button = findViewById(R.id.btnShare)
        val btnBack: Button = findViewById(R.id.btnBack)
        
        // Build result display
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════")
        sb.appendLine("  TrustEngine KTP Reader")
        sb.appendLine("═══════════════════════")
        sb.appendLine()
        
        // OCR Data
        if (ktpData.ocrScanned) {
            sb.appendLine("📷 DATA OCR (dari foto KTP)")
            sb.appendLine("─────────────────────")
            sb.appendLine("Nama      : ${ktpData.nama}")
            sb.appendLine("NIK       : ${ktpData.nik}")
            sb.appendLine("TTL       : ${ktpData.tempatLahir}, ${ktpData.tanggalLahir}")
            sb.appendLine("JK        : ${ktpData.jenisKelamin}")
            sb.appendLine("Alamat    : ${ktpData.alamat}")
            if (ktpData.rtRw.isNotEmpty()) sb.appendLine("RT/RW     : ${ktpData.rtRw}")
            if (ktpData.kelDesa.isNotEmpty()) sb.appendLine("Kel/Desa  : ${ktpData.kelDesa}")
            if (ktpData.kecamatan.isNotEmpty()) sb.appendLine("Kecamatan : ${ktpData.kecamatan}")
            if (ktpData.agama.isNotEmpty()) sb.appendLine("Agama     : ${ktpData.agama}")
            sb.appendLine("Status    : ${ktpData.statusPerkawinan}")
            if (ktpData.pekerjaan.isNotEmpty()) sb.appendLine("Pekerjaan : ${ktpData.pekerjaan}")
            sb.appendLine("WN        : ${ktpData.kewarganegaraan}")
            sb.appendLine("Berlaku   : ${ktpData.berlakuHingga}")
            sb.appendLine()
        }
        
        // NIK Validation
        if (ktpData.nik.isNotEmpty()) {
            sb.appendLine("🔍 VALIDASI NIK")
            sb.appendLine("─────────────────────")
            sb.appendLine("Status    : ${if (ktpData.nikValid) "✅ VALID" else "❌ TIDAK VALID"}")
            sb.appendLine("Provinsi  : ${ktpData.nikProvinsi}")
            sb.appendLine("Gender    : ${if (ktpData.nikGender == "L") "Laki-laki" else "Perempuan"}")
            sb.appendLine("TTL (NIK) : ${ktpData.nikTglLahir}")
            sb.appendLine()
        }
        
        // NFC Data
        if (ktpData.nfcScanned) {
            sb.appendLine("📱 DATA NFC (dari chip)")
            sb.appendLine("─────────────────────")
            sb.appendLine("UID       : ${ktpData.nfcUid}")
            sb.appendLine("Tech      : ${ktpData.nfcTechType}")
            sb.appendLine("e-KTP     : ${if (ktpData.isChipVerified) "✅ TERVERIFIKASI" else "⚠️ Tidak terdeteksi"}")
            if (ktpData.chipReadSuccess) {
                sb.appendLine("Chip Data : ✅ Berhasil dibaca")
            }
            sb.appendLine()
        }
        
        // Verification Summary
        sb.appendLine("═══════════════════════")
        sb.appendLine("  KESIMPULAN VERIFIKASI")
        sb.appendLine("═══════════════════════")
        
        val ocrOk = ktpData.ocrScanned && ktpData.nama.isNotEmpty()
        val nikOk = ktpData.nikValid
        val nfcOk = ktpData.nfcScanned && ktpData.isChipVerified
        
        when {
            ocrOk && nikOk && nfcOk -> {
                sb.appendLine("✅ KTP TERVERIFIKASI (OCR + NIK + NFC)")
                verifyStatus.text = "✅ TERVERIFIKASI"
                verifyStatus.setTextColor(0xFF2E7D32.toInt())
            }
            ocrOk && nikOk -> {
                sb.appendLine("⚠️ DATA VALID (OCR + NIK) — NFC belum scan")
                verifyStatus.text = "⚠️ PERLU NFC SCAN"
                verifyStatus.setTextColor(0xFFE65100.toInt())
            }
            ocrOk -> {
                sb.appendLine("⚠️ DATA TERBACA — NIK/NFC perlu verifikasi")
                verifyStatus.text = "⚠️ PERLU VERIFIKASI"
                verifyStatus.setTextColor(0xFFE65100.toInt())
            }
            else -> {
                sb.appendLine("❌ DATA BELUM LENGKAP")
                verifyStatus.text = "❌ BELUM LENGKAP"
                verifyStatus.setTextColor(0xFFC62828.toInt())
            }
        }
        
        resultText.text = sb.toString()
        
        // Copy JSON
        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("KTP Data", ktpData.toJson()))
            Toast.makeText(this, "Data JSON disalin!", Toast.LENGTH_SHORT).show()
        }
        
        // Share
        btnShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(Intent.EXTRA_TEXT, sb.toString())
            startActivity(Intent.createChooser(shareIntent, "Bagikan hasil"))
        }
        
        btnBack.setOnClickListener { finish() }
    }
}
