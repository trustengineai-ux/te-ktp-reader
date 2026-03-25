package com.trustengine.ktpreader.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.trustengine.ktpreader.model.KtpData

/**
 * Opsi B: Full e-KTP chip data reader (R&D)
 * 
 * e-KTP follows ICAO 9303 standard (similar to e-Passport).
 * Data is protected by Basic Access Control (BAC).
 * 
 * Challenge: e-KTP doesn't have MRZ (Machine Readable Zone) like passports.
 * BAC key must be derived differently.
 * 
 * Known e-KTP chip structure:
 * - EF.COM (Common data) 
 * - EF.DG1 (MRZ data / text data)
 * - EF.DG2 (Facial image - JPEG2000)
 * - EF.DG3 (Fingerprint - optional, EAC protected)
 * - EF.DG11 (Additional personal details)
 * - EF.DG14 (Security infos for EAC)
 * - EF.SOD (Document Security Object)
 * 
 * APDU Commands:
 * - SELECT: 00 A4 04 0C 07 A0 00 00 02 47 10 01
 * - GET CHALLENGE: 00 84 00 00 08
 * - EXTERNAL AUTHENTICATE: 00 82 00 00 28 [encrypted data]
 * - SELECT EF: 00 A4 02 0C 02 [file ID]
 * - READ BINARY: 00 B0 [offset] [length]
 */
object EktpChipReader {
    
    private const val TAG = "EktpChipReader"
    
    // ICAO eMRTD AID
    private val MRTD_AID = byteArrayOf(
        0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(),
        0x47.toByte(), 0x10.toByte(), 0x01.toByte()
    )
    
    // Data Group file IDs
    private val EF_COM = byteArrayOf(0x01.toByte(), 0x1E.toByte())
    private val EF_DG1 = byteArrayOf(0x01.toByte(), 0x01.toByte())  // Text data
    private val EF_DG2 = byteArrayOf(0x01.toByte(), 0x02.toByte())  // Facial image
    private val EF_DG11 = byteArrayOf(0x01.toByte(), 0x0B.toByte()) // Additional personal
    private val EF_SOD = byteArrayOf(0x01.toByte(), 0x1D.toByte())  // Security object
    
    data class ChipReadResult(
        val success: Boolean,
        val data: KtpData? = null,
        val error: String? = null,
        val debugLog: List<String> = emptyList()
    )
    
    /**
     * Attempt to read e-KTP chip data.
     * 
     * Strategy:
     * 1. SELECT eMRTD applet
     * 2. Try PACE (if supported) or BAC authentication
     * 3. Read data groups (DG1, DG2, DG11)
     * 4. Parse personal data
     * 
     * BAC Key Derivation for e-KTP (RESEARCH NEEDED):
     * - Standard: key = f(MRZ data) but e-KTP has no MRZ
     * - Hypothesis 1: CAN printed on card used as key seed
     * - Hypothesis 2: NIK used as key seed
     * - Hypothesis 3: Combination of NIK + DOB + expiry
     * - Need physical e-KTP testing to confirm
     */
    fun readChipData(tag: Tag, nik: String? = null, dob: String? = null): ChipReadResult {
        val log = mutableListOf<String>()
        
        try {
            val isoDep = IsoDep.get(tag) ?: return ChipReadResult(
                false, error = "IsoDep not available", debugLog = log
            )
            
            isoDep.connect()
            isoDep.timeout = 10000
            log.add("Connected to chip")
            
            // Step 1: SELECT eMRTD applet
            val selectResp = sendApdu(isoDep, 0x00, 0xA4, 0x04, 0x0C, MRTD_AID)
            log.add("SELECT eMRTD: ${selectResp.sw}")
            
            if (selectResp.sw != "9000") {
                isoDep.close()
                return ChipReadResult(false, error = "eMRTD applet not found (${selectResp.sw})", debugLog = log)
            }
            
            // Step 2: Try reading without authentication (some cards allow this)
            log.add("Trying unauthenticated read...")
            val comResp = readFile(isoDep, EF_COM, log)
            
            if (comResp != null) {
                log.add("EF.COM read SUCCESS (${comResp.size} bytes) — No BAC required!")
                
                // Read DG1 (text data)
                val dg1 = readFile(isoDep, EF_DG1, log)
                if (dg1 != null) {
                    log.add("EF.DG1 read SUCCESS (${dg1.size} bytes)")
                }
                
                // Read DG2 (facial image)
                val dg2 = readFile(isoDep, EF_DG2, log)
                if (dg2 != null) {
                    log.add("EF.DG2 read SUCCESS (${dg2.size} bytes) — Contains face photo")
                }
                
                isoDep.close()
                
                val data = KtpData(chipReadSuccess = true, nfcScanned = true)
                // TODO: Parse DG1 and DG2 data into KtpData fields
                return ChipReadResult(true, data, debugLog = log)
            }
            
            // Step 3: BAC authentication required
            log.add("BAC authentication required — attempting key derivation...")
            
            // GET CHALLENGE
            val challengeResp = sendApdu(isoDep, 0x00, 0x84, 0x00, 0x00, expectedLen = 8)
            if (challengeResp.sw == "9000" && challengeResp.data != null) {
                log.add("GET CHALLENGE: ${challengeResp.data.toHexString()}")
                
                // TODO: Implement BAC key derivation
                // This is the main R&D challenge for e-KTP
                // Standard ICAO: K_seed = SHA1(MRZ_info)[0:16]
                // For e-KTP: need to find what data replaces MRZ
                
                if (nik != null && dob != null) {
                    log.add("Trying BAC with NIK=$nik, DOB=$dob")
                    // Hypothesis: use NIK + DOB as key material
                    // K_seed = SHA1(NIK + DOB + checksum)
                    // This needs real-world testing
                    log.add("BAC key derivation: RESEARCH IN PROGRESS")
                }
            } else {
                log.add("GET CHALLENGE failed: ${challengeResp.sw}")
            }
            
            isoDep.close()
            return ChipReadResult(
                false, 
                error = "BAC authentication not yet implemented (R&D)", 
                debugLog = log
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Chip read error: ${e.message}", e)
            log.add("ERROR: ${e.message}")
            return ChipReadResult(false, error = e.message, debugLog = log)
        }
    }
    
    // APDU response wrapper
    data class ApduResponse(val data: ByteArray?, val sw: String)
    
    private fun sendApdu(
        isoDep: IsoDep, 
        cla: Int, ins: Int, p1: Int, p2: Int, 
        data: ByteArray? = null,
        expectedLen: Int? = null
    ): ApduResponse {
        val cmd = mutableListOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte())
        
        if (data != null) {
            cmd.add(data.size.toByte())
            cmd.addAll(data.toList())
        }
        
        if (expectedLen != null) {
            cmd.add(expectedLen.toByte())
        }
        
        val response = isoDep.transceive(cmd.toByteArray())
        val sw = if (response.size >= 2) {
            "%02X%02X".format(response[response.size - 2], response[response.size - 1])
        } else "0000"
        
        val respData = if (response.size > 2) response.copyOfRange(0, response.size - 2) else null
        
        return ApduResponse(respData, sw)
    }
    
    private fun readFile(isoDep: IsoDep, fileId: ByteArray, log: MutableList<String>): ByteArray? {
        // SELECT file
        val selectResp = sendApdu(isoDep, 0x00, 0xA4, 0x02, 0x0C, fileId)
        if (selectResp.sw != "9000") {
            log.add("SELECT ${fileId.toHexString()}: FAILED (${selectResp.sw})")
            return null
        }
        
        // READ BINARY (first 4 bytes to get length)
        val headerResp = sendApdu(isoDep, 0x00, 0xB0, 0x00, 0x00, expectedLen = 4)
        if (headerResp.sw != "9000" || headerResp.data == null) {
            log.add("READ ${fileId.toHexString()}: FAILED (${headerResp.sw})")
            return null
        }
        
        return headerResp.data
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}
