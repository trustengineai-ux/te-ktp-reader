package com.trustengine.ktpreader.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.trustengine.ktpreader.model.KtpData

/**
 * e-KTP Full Chip Reader (Opsi B)
 * 
 * Attempts to read personal data + photo from e-KTP NFC chip.
 * Uses ICAO 9303 protocol with multiple BAC key hypotheses.
 * 
 * Data Groups:
 * - DG1: Personal text data (MRZ equivalent)
 * - DG2: Facial image (JPEG2000/JPEG)
 * - DG3: Fingerprints (EAC protected - won't be accessible)
 * - DG11: Additional personal details
 * - DG14: Security infos
 * - EF.COM: List of available data groups
 * - EF.SOD: Digital signature
 */
object EktpChipReader {
    
    private const val TAG = "EktpChipReader"
    
    // ICAO eMRTD AID
    private val MRTD_AID = byteArrayOf(
        0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(),
        0x47.toByte(), 0x10.toByte(), 0x01.toByte()
    )
    
    // Data Group file IDs (short EF identifiers)
    private val EF_COM  = byteArrayOf(0x01.toByte(), 0x1E.toByte())
    private val EF_DG1  = byteArrayOf(0x01.toByte(), 0x01.toByte())
    private val EF_DG2  = byteArrayOf(0x01.toByte(), 0x02.toByte())
    private val EF_DG11 = byteArrayOf(0x01.toByte(), 0x0B.toByte())
    private val EF_DG14 = byteArrayOf(0x01.toByte(), 0x0E.toByte())
    private val EF_SOD  = byteArrayOf(0x01.toByte(), 0x1D.toByte())
    
    data class ChipReadResult(
        val success: Boolean,
        val data: KtpData? = null,
        val faceImageBytes: ByteArray? = null,
        val error: String? = null,
        val debugLog: List<String> = emptyList(),
        val successHypothesis: String? = null
    )
    
    /**
     * Main entry point - attempt to read e-KTP chip data.
     */
    fun readChipData(
        tag: Tag, 
        nik: String? = null, 
        dob: String? = null,
        expiry: String? = null
    ): ChipReadResult {
        val log = mutableListOf<String>()
        
        try {
            val isoDep = IsoDep.get(tag) ?: return ChipReadResult(
                false, error = "IsoDep not available on this tag", debugLog = log
            )
            
            isoDep.connect()
            isoDep.timeout = 10000 // 10 second timeout
            log.add("✅ Connected to chip (timeout: ${isoDep.timeout}ms)")
            log.add("Max transceive: ${isoDep.maxTransceiveLength} bytes")
            
            // Get historical bytes
            val hiBytes = isoDep.historicalBytes
            val hiLayer = isoDep.hiLayerResponse
            if (hiBytes != null) log.add("Historical bytes: ${hiBytes.toHex()}")
            if (hiLayer != null) log.add("HiLayer response: ${hiLayer.toHex()}")
            
            // Step 1: SELECT eMRTD application
            log.add("\n--- STEP 1: SELECT eMRTD ---")
            val selectResp = transceive(isoDep, 
                byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, 0x07) + MRTD_AID
            )
            log.add("SELECT response: SW=${selectResp.sw}")
            
            if (selectResp.sw != "9000") {
                // Try alternative AIDs
                log.add("Standard eMRTD AID failed, trying alternatives...")
                
                // Try Indonesian e-KTP specific AID (if any)
                val altAids = listOf(
                    byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01),
                    byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x48, 0x02, 0x00),
                    byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x77, 0x01, 0x08, 0x00, 0x07, 0x00, 0x00, 0xFE.toByte(), 0x00, 0x00, 0x01, 0x00)
                )
                
                var selected = false
                for (aid in altAids) {
                    val cmd = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, aid.size.toByte()) + aid
                    val resp = transceive(isoDep, cmd)
                    log.add("Alt AID ${aid.toHex()}: SW=${resp.sw}")
                    if (resp.sw == "9000") {
                        selected = true
                        break
                    }
                }
                
                if (!selected) {
                    isoDep.close()
                    return ChipReadResult(false, error = "No eMRTD applet found", debugLog = log)
                }
            }
            log.add("✅ eMRTD applet selected")
            
            // Step 2: Try reading WITHOUT authentication first
            log.add("\n--- STEP 2: Try unauthenticated read ---")
            val unauthRead = tryReadFile(isoDep, EF_COM, "EF.COM", log)
            
            if (unauthRead != null) {
                log.add("🎉 No BAC required! Reading data groups...")
                val result = readAllDataGroups(isoDep, log)
                isoDep.close()
                return result
            }
            
            // Step 3: GET CHALLENGE (BAC required)
            log.add("\n--- STEP 3: BAC Authentication ---")
            val challengeResp = transceive(isoDep, 
                byteArrayOf(0x00, 0x84.toByte(), 0x00, 0x00, 0x08)
            )
            
            if (challengeResp.sw != "9000" || challengeResp.data == null || challengeResp.data.size < 8) {
                log.add("❌ GET CHALLENGE failed: ${challengeResp.sw}")
                
                // Try PACE instead
                log.add("\n--- Trying PACE protocol ---")
                val paceResult = tryPace(isoDep, nik, log)
                if (paceResult) {
                    val result = readAllDataGroups(isoDep, log)
                    isoDep.close()
                    return result
                }
                
                isoDep.close()
                return ChipReadResult(false, error = "GET CHALLENGE failed, PACE also failed", debugLog = log)
            }
            
            val rndIcc = challengeResp.data.copyOfRange(0, 8)
            log.add("✅ Challenge received: ${rndIcc.toHex()}")
            
            // Step 4: Try all key hypotheses
            log.add("\n--- STEP 4: Trying key hypotheses ---")
            val allKeys = BacKeyDerivation.generateAllKeyHypotheses(nik, dob, expiry, null)
            log.add("Generated ${allKeys.size} hypotheses")
            
            var authenticated = false
            var sessionKeys: BacKeyDerivation.SessionKeys? = null
            var successHypothesis = ""
            
            for ((index, keys) in allKeys.withIndex()) {
                log.add("\n[${index+1}/${allKeys.size}] Trying: ${keys.hypothesis}")
                
                val authResult = BacKeyDerivation.performBac(keys, rndIcc)
                if (authResult == null) {
                    log.add("  → Auth data generation failed")
                    continue
                }
                
                // EXTERNAL AUTHENTICATE
                val authCmd = byteArrayOf(
                    0x00, 0x82.toByte(), 0x00, 0x00, 
                    0x28  // 40 bytes
                ) + authResult.commandData + byteArrayOf(0x28)
                
                val authResp = transceive(isoDep, authCmd)
                log.add("  → EXTERNAL AUTH response: SW=${authResp.sw}, data=${authResp.data?.size ?: 0} bytes")
                
                if (authResp.sw == "9000" && authResp.data != null && authResp.data.size >= 40) {
                    // Try to process response
                    sessionKeys = BacKeyDerivation.processBacResponse(
                        authResp.data, keys, authResult.rndIfd, authResult.kIfd, rndIcc
                    )
                    
                    if (sessionKeys != null) {
                        authenticated = true
                        successHypothesis = keys.hypothesis
                        log.add("  🎉 AUTHENTICATION SUCCESS! Hypothesis: ${keys.hypothesis}")
                        break
                    } else {
                        log.add("  → Response received but session key derivation failed")
                    }
                } else if (authResp.sw == "6300") {
                    log.add("  → Wrong key (verification failed)")
                } else if (authResp.sw == "6983") {
                    log.add("  → Authentication method blocked")
                    break // No point trying more
                } else if (authResp.sw == "6A80") {
                    log.add("  → Incorrect data parameters")
                }
                
                // Re-issue GET CHALLENGE for next attempt
                if (index < allKeys.size - 1) {
                    val newChallenge = transceive(isoDep, 
                        byteArrayOf(0x00, 0x84.toByte(), 0x00, 0x00, 0x08)
                    )
                    if (newChallenge.sw == "9000" && newChallenge.data != null) {
                        System.arraycopy(newChallenge.data, 0, rndIcc, 0, 8)
                    }
                }
            }
            
            if (!authenticated) {
                isoDep.close()
                log.add("\n❌ All hypotheses exhausted. BAC authentication failed.")
                log.add("ℹ️ This e-KTP may use a non-standard key derivation method.")
                return ChipReadResult(false, error = "BAC auth failed - all ${allKeys.size} hypotheses tried", debugLog = log)
            }
            
            // Step 5: Read data groups with secure messaging
            log.add("\n--- STEP 5: Reading secured data ---")
            // TODO: Implement Secure Messaging wrapper for APDU commands
            // For now, try plain read (some chips allow after auth)
            val result = readAllDataGroups(isoDep, log)
            isoDep.close()
            
            return result.copy(successHypothesis = successHypothesis)
            
        } catch (e: Exception) {
            Log.e(TAG, "Chip read error", e)
            log.add("\n💥 EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            return ChipReadResult(false, error = "${e.javaClass.simpleName}: ${e.message}", debugLog = log)
        }
    }
    
    /**
     * Read all available data groups
     */
    private fun readAllDataGroups(isoDep: IsoDep, log: MutableList<String>): ChipReadResult {
        val data = KtpData(chipReadSuccess = true, nfcScanned = true)
        var faceBytes: ByteArray? = null
        
        // Read EF.COM to see available DGs
        val comData = tryReadFile(isoDep, EF_COM, "EF.COM", log)
        if (comData != null) {
            log.add("EF.COM: ${comData.size} bytes → ${comData.toHex()}")
            // Parse available DG list from EF.COM
        }
        
        // Read DG1 (Personal text data)
        val dg1Data = tryReadFullFile(isoDep, EF_DG1, "DG1 (Personal)", log)
        if (dg1Data != null) {
            log.add("DG1: ${dg1Data.size} bytes")
            parseDG1(dg1Data, data, log)
        }
        
        // Read DG2 (Face image)
        val dg2Data = tryReadFullFile(isoDep, EF_DG2, "DG2 (Photo)", log)
        if (dg2Data != null) {
            log.add("DG2: ${dg2Data.size} bytes (contains face image)")
            faceBytes = extractFaceImage(dg2Data, log)
        }
        
        // Read DG11 (Additional personal details)
        val dg11Data = tryReadFullFile(isoDep, EF_DG11, "DG11 (Additional)", log)
        if (dg11Data != null) {
            log.add("DG11: ${dg11Data.size} bytes")
            parseDG11(dg11Data, data, log)
        }
        
        val success = data.nama.isNotEmpty() || data.nik.isNotEmpty() || faceBytes != null
        return ChipReadResult(success, data, faceBytes, debugLog = log)
    }
    
    /**
     * Try PACE protocol (alternative to BAC)
     */
    private fun tryPace(isoDep: IsoDep, nik: String?, log: MutableList<String>): Boolean {
        // PACE uses MSE:Set AT command
        // CLA=00 INS=22 P1=C1 P2=A4
        val mseCmd = byteArrayOf(
            0x00, 0x22, 0xC1.toByte(), 0xA4.toByte(),
            0x06, // data length
            0x80.toByte(), 0x01, 0x0D, // Protocol: id-PACE-DH-GM-AES-CBC-CMAC-256
            0x83.toByte(), 0x01, 0x02  // Reference: CAN
        )
        
        val mseResp = transceive(isoDep, mseCmd)
        log.add("MSE:Set AT (PACE): SW=${mseResp.sw}")
        
        if (mseResp.sw == "9000") {
            log.add("PACE supported! But full implementation needed.")
            // TODO: Implement full PACE protocol
            // This requires: General Authenticate commands, DH key exchange, etc.
            return false
        }
        
        return false
    }
    
    // ========== FILE READING ==========
    
    private fun tryReadFile(isoDep: IsoDep, fileId: ByteArray, name: String, log: MutableList<String>): ByteArray? {
        // SELECT file
        val selectCmd = byteArrayOf(0x00, 0xA4.toByte(), 0x02, 0x0C, 0x02) + fileId
        val selectResp = transceive(isoDep, selectCmd)
        
        if (selectResp.sw != "9000") {
            log.add("SELECT $name: ❌ ${selectResp.sw}")
            return null
        }
        
        // READ BINARY (first few bytes)
        val readCmd = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x04)
        val readResp = transceive(isoDep, readCmd)
        
        if (readResp.sw != "9000" || readResp.data == null) {
            log.add("READ $name: ❌ ${readResp.sw}")
            return null
        }
        
        log.add("READ $name: ✅ ${readResp.data.size} bytes (header)")
        return readResp.data
    }
    
    private fun tryReadFullFile(isoDep: IsoDep, fileId: ByteArray, name: String, log: MutableList<String>): ByteArray? {
        // SELECT file
        val selectCmd = byteArrayOf(0x00, 0xA4.toByte(), 0x02, 0x0C, 0x02) + fileId
        val selectResp = transceive(isoDep, selectCmd)
        
        if (selectResp.sw != "9000") {
            log.add("SELECT $name: ❌ ${selectResp.sw}")
            return null
        }
        
        // Read in chunks
        val maxRead = minOf(isoDep.maxTransceiveLength - 10, 0xDF) // Safe chunk size
        val allData = mutableListOf<Byte>()
        var offset = 0
        
        while (true) {
            val p1 = (offset shr 8) and 0xFF
            val p2 = offset and 0xFF
            val le = minOf(maxRead, 0xDF)
            
            val readCmd = byteArrayOf(0x00, 0xB0.toByte(), p1.toByte(), p2.toByte(), le.toByte())
            val readResp = transceive(isoDep, readCmd)
            
            if (readResp.sw == "9000" && readResp.data != null) {
                allData.addAll(readResp.data.toList())
                offset += readResp.data.size
                
                if (readResp.data.size < le) break // Last chunk
            } else if (readResp.sw.startsWith("6C")) {
                // Wrong Le, retry with correct length
                val correctLe = readResp.sw.substring(2).toInt(16)
                val retryCmd = byteArrayOf(0x00, 0xB0.toByte(), p1.toByte(), p2.toByte(), correctLe.toByte())
                val retryResp = transceive(isoDep, retryCmd)
                if (retryResp.data != null) {
                    allData.addAll(retryResp.data.toList())
                    offset += retryResp.data.size
                }
                break
            } else {
                break
            }
            
            // Safety limit: 100KB max
            if (allData.size > 100_000) {
                log.add("$name: Size limit reached (${allData.size} bytes)")
                break
            }
        }
        
        if (allData.isEmpty()) {
            log.add("READ $name: ❌ No data")
            return null
        }
        
        log.add("READ $name: ✅ ${allData.size} bytes total")
        return allData.toByteArray()
    }
    
    // ========== DATA PARSING ==========
    
    /**
     * Parse DG1 - contains MRZ-equivalent text data
     */
    private fun parseDG1(data: ByteArray, ktp: KtpData, log: MutableList<String>) {
        try {
            // DG1 is TLV encoded, tag 0x61 containing 0x5F1F (MRZ)
            val text = String(data, Charsets.US_ASCII).filter { it.isLetterOrDigit() || it in "<-, ./" }
            log.add("DG1 text: ${text.take(100)}...")
            
            // Try to extract NIK from data
            val nikPattern = Regex("\\d{16}")
            nikPattern.find(text)?.let {
                ktp.nik = it.value
                log.add("Found NIK in DG1: ${ktp.nik}")
            }
            
            // Try to find name (typically after certain tags)
            // e-KTP DG1 format may differ from passport MRZ
            log.add("DG1 raw hex: ${data.take(64).toByteArray().toHex()}")
            
        } catch (e: Exception) {
            log.add("DG1 parse error: ${e.message}")
        }
    }
    
    /**
     * Parse DG11 - additional personal details
     * May contain: full name, DOB, place of birth, address, etc.
     */
    private fun parseDG11(data: ByteArray, ktp: KtpData, log: MutableList<String>) {
        try {
            val text = String(data, Charsets.UTF_8)
            log.add("DG11 text preview: ${text.take(200)}")
            log.add("DG11 raw hex: ${data.take(64).toByteArray().toHex()}")
        } catch (e: Exception) {
            log.add("DG11 parse error: ${e.message}")
        }
    }
    
    /**
     * Extract face image from DG2
     * DG2 contains biometric data in CBEFF format
     * The actual image is typically JPEG or JPEG2000
     */
    private fun extractFaceImage(data: ByteArray, log: MutableList<String>): ByteArray? {
        try {
            // Look for JPEG header (FFD8FF)
            for (i in 0 until data.size - 3) {
                if (data[i] == 0xFF.toByte() && data[i+1] == 0xD8.toByte() && data[i+2] == 0xFF.toByte()) {
                    // Find JPEG end (FFD9)
                    for (j in data.size - 2 downTo i) {
                        if (data[j] == 0xFF.toByte() && data[j+1] == 0xD9.toByte()) {
                            val imageData = data.copyOfRange(i, j + 2)
                            log.add("🖼️ Face JPEG found: offset=$i, size=${imageData.size} bytes")
                            return imageData
                        }
                    }
                }
            }
            
            // Look for JPEG2000 header (0000000C6A502020)
            val jp2Header = byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20)
            for (i in 0 until data.size - 8) {
                var match = true
                for (j in jp2Header.indices) {
                    if (data[i+j] != jp2Header[j]) { match = false; break }
                }
                if (match) {
                    val imageData = data.copyOfRange(i, data.size)
                    log.add("🖼️ Face JPEG2000 found: offset=$i, size=${imageData.size} bytes")
                    return imageData
                }
            }
            
            log.add("⚠️ No recognizable image format found in DG2")
            return null
            
        } catch (e: Exception) {
            log.add("Face extract error: ${e.message}")
            return null
        }
    }
    
    // ========== APDU HELPERS ==========
    
    data class ApduResponse(val data: ByteArray?, val sw: String)
    
    private fun transceive(isoDep: IsoDep, command: ByteArray): ApduResponse {
        return try {
            val response = isoDep.transceive(command)
            val sw = if (response.size >= 2) {
                "%02X%02X".format(response[response.size - 2], response[response.size - 1])
            } else "0000"
            val data = if (response.size > 2) response.copyOfRange(0, response.size - 2) else null
            ApduResponse(data, sw)
        } catch (e: Exception) {
            Log.e(TAG, "Transceive error: ${e.message}")
            ApduResponse(null, "FFFF")
        }
    }
    
    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    private fun List<Byte>.toByteArray(): ByteArray = this.toByteArray()
}
