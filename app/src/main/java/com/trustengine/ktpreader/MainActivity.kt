package com.trustengine.ktpreader

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.trustengine.ktpreader.model.KtpData
import com.trustengine.ktpreader.nfc.EktpChipReader
import com.trustengine.ktpreader.nfc.NfcReader

class MainActivity : AppCompatActivity() {
    
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null
    
    private var currentKtpData: KtpData = KtpData()
    private var waitingForNfc: Boolean = false
    
    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var nfcStatusIcon: ImageView
    private lateinit var resultText: TextView
    private lateinit var btnScanOcr: Button
    private lateinit var btnScanNfc: Button
    private lateinit var btnViewResult: Button
    private lateinit var btnChipRead: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Init NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC tidak tersedia di perangkat ini", Toast.LENGTH_LONG).show()
        }
        
        // Setup NFC foreground dispatch
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        
        val tagDetected = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        val techDetected = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        intentFilters = arrayOf(tagDetected, techDetected)
        
        techLists = arrayOf(
            arrayOf("android.nfc.tech.IsoDep"),
            arrayOf("android.nfc.tech.NfcA"),
            arrayOf("android.nfc.tech.NfcB")
        )
        
        // Init UI
        statusText = findViewById(R.id.statusText)
        nfcStatusIcon = findViewById(R.id.nfcStatusIcon)
        resultText = findViewById(R.id.resultText)
        btnScanOcr = findViewById(R.id.btnScanOcr)
        btnScanNfc = findViewById(R.id.btnScanNfc)
        btnViewResult = findViewById(R.id.btnViewResult)
        btnChipRead = findViewById(R.id.btnChipRead)
        
        // Check NFC status
        updateNfcStatus()
        
        // Button listeners
        btnScanOcr.setOnClickListener {
            val intent = Intent(this, ScanActivity::class.java)
            startActivityForResult(intent, REQUEST_OCR_SCAN)
        }
        
        btnScanNfc.setOnClickListener {
            waitingForNfc = true
            statusText.text = "📱 Tempelkan e-KTP ke belakang HP..."
            Toast.makeText(this, "Tempelkan e-KTP ke NFC reader", Toast.LENGTH_SHORT).show()
        }
        
        btnChipRead.setOnClickListener {
            waitingForNfc = true
            statusText.text = "🔬 Chip Read — Tempelkan e-KTP ke NFC..."
            Toast.makeText(this, "Tempelkan e-KTP. Jangan geser sampai selesai!", Toast.LENGTH_LONG).show()
        }
        
        // Long press result to copy log
        resultText.setOnLongClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NFC Log", resultText.text))
            Toast.makeText(this, "📋 Log disalin!", Toast.LENGTH_SHORT).show()
            true
        }
        
        btnViewResult.setOnClickListener {
            val intent = Intent(this, ResultActivity::class.java)
            intent.putExtra("ktpData", currentKtpData)
            startActivity(intent)
        }
    }
    
    private fun updateNfcStatus() {
        if (nfcAdapter == null) {
            statusText.text = "❌ NFC tidak tersedia"
        } else if (!nfcAdapter!!.isEnabled) {
            statusText.text = "⚠️ NFC mati — aktifkan di Settings"
        } else {
            statusText.text = "✅ NFC aktif — siap scan"
        }
    }
    
    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
    }
    
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) return
        
        // Opsi A: Basic NFC UID read
        val nfcResult = NfcReader.readTag(tag)
        currentKtpData.nfcUid = nfcResult.uid
        currentKtpData.nfcTechType = nfcResult.techType
        currentKtpData.isChipVerified = nfcResult.isEktp
        currentKtpData.nfcScanned = true
        
        val chipStatus = if (nfcResult.isEktp) "✅ e-KTP Chip Terdeteksi" else "⚠️ Bukan chip e-KTP"
        
        var resultMsg = """
            📱 NFC Scan Berhasil!
            
            UID: ${nfcResult.uid}
            Tech: ${nfcResult.techType}
            Status: $chipStatus
            ${nfcResult.chipInfo?.let { "\nChip Info: $it" } ?: ""}
            ${nfcResult.atr?.let { "\nATR: $it" } ?: ""}
        """.trimIndent()
        
        // Opsi B: Full chip read attempt
        if (statusText.text.contains("R&D") || statusText.text.contains("Chip")) {
            resultMsg += "\n\n🔬 Memulai Full Chip Read..."
            resultText.text = resultMsg
            
            val chipResult = EktpChipReader.readChipData(
                tag, 
                nik = currentKtpData.nik.ifEmpty { null },
                dob = currentKtpData.tanggalLahir.ifEmpty { null },
                expiry = currentKtpData.berlakuHingga.ifEmpty { null }
            )
            
            resultMsg += "\n\n🔬 ═══ CHIP READ LOG ═══\n"
            resultMsg += chipResult.debugLog.joinToString("\n")
            
            if (chipResult.success) {
                resultMsg += "\n\n🎉 CHIP DATA READ SUCCESS!"
                if (chipResult.successHypothesis != null) {
                    resultMsg += "\n🔑 Key: ${chipResult.successHypothesis}"
                }
                currentKtpData.chipReadSuccess = true
                
                // Merge chip data
                chipResult.data?.let { chipData ->
                    if (chipData.nama.isNotEmpty()) currentKtpData.nama = chipData.nama
                    if (chipData.nik.isNotEmpty()) currentKtpData.nik = chipData.nik
                }
                
                // Save face image if found
                chipResult.faceImageBytes?.let { faceBytes ->
                    resultMsg += "\n🖼️ Foto wajah: ${faceBytes.size} bytes"
                    // TODO: Save to file and display
                }
            } else {
                resultMsg += "\n\n❌ ${chipResult.error}"
                resultMsg += "\n\nℹ️ Kirim log ini ke developer untuk analisis"
            }
        }
        
        resultText.text = resultMsg
        statusText.text = chipStatus
        waitingForNfc = false
        
        Toast.makeText(this, "NFC scan selesai!", Toast.LENGTH_SHORT).show()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_OCR_SCAN && resultCode == RESULT_OK) {
            val ocrData = data?.getSerializableExtra("ktpData") as? KtpData
            if (ocrData != null) {
                // Merge OCR data with existing NFC data
                currentKtpData.nama = ocrData.nama
                currentKtpData.nik = ocrData.nik
                currentKtpData.tempatLahir = ocrData.tempatLahir
                currentKtpData.tanggalLahir = ocrData.tanggalLahir
                currentKtpData.jenisKelamin = ocrData.jenisKelamin
                currentKtpData.alamat = ocrData.alamat
                currentKtpData.rtRw = ocrData.rtRw
                currentKtpData.kelDesa = ocrData.kelDesa
                currentKtpData.kecamatan = ocrData.kecamatan
                currentKtpData.agama = ocrData.agama
                currentKtpData.statusPerkawinan = ocrData.statusPerkawinan
                currentKtpData.pekerjaan = ocrData.pekerjaan
                currentKtpData.kewarganegaraan = ocrData.kewarganegaraan
                currentKtpData.berlakuHingga = ocrData.berlakuHingga
                currentKtpData.nikValid = ocrData.nikValid
                currentKtpData.nikProvinsi = ocrData.nikProvinsi
                currentKtpData.nikGender = ocrData.nikGender
                currentKtpData.nikTglLahir = ocrData.nikTglLahir
                currentKtpData.ocrScanned = true
                
                resultText.text = """
                    📷 OCR Scan Berhasil!
                    
                    Nama: ${currentKtpData.nama}
                    NIK: ${currentKtpData.nik}
                    TTL: ${currentKtpData.tempatLahir}, ${currentKtpData.tanggalLahir}
                    JK: ${currentKtpData.jenisKelamin}
                    Alamat: ${currentKtpData.alamat}
                    NIK Valid: ${if (currentKtpData.nikValid) "✅" else "❌"}
                    Provinsi: ${currentKtpData.nikProvinsi}
                """.trimIndent()
                
                statusText.text = "📷 OCR OK — Tap NFC untuk verifikasi"
            }
        }
    }
    
    companion object {
        private const val REQUEST_OCR_SCAN = 1001
    }
}
