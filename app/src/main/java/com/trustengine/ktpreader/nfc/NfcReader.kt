package com.trustengine.ktpreader.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.util.Log

/**
 * Opsi A: Read NFC chip UID and basic info for authenticity verification.
 * e-KTP uses ISO 14443 Type B (NfcB) typically.
 */
object NfcReader {
    
    private const val TAG = "NfcReader"
    
    data class NfcResult(
        val uid: String,
        val techType: String,
        val isEktp: Boolean,
        val atr: String? = null,
        val chipInfo: String? = null,
        val error: String? = null
    )
    
    fun readTag(tag: Tag): NfcResult {
        val uid = tag.id.toHexString()
        val techList = tag.techList.joinToString(", ")
        
        Log.d(TAG, "Tag detected - UID: $uid, Tech: $techList")
        
        // e-KTP typically uses IsoDep (ISO 14443-4) over NfcB
        val isEktp = tag.techList.any { 
            it == "android.nfc.tech.IsoDep" || it == "android.nfc.tech.NfcB" 
        }
        
        var atr: String? = null
        var chipInfo: String? = null
        
        // Try to read ATR (Answer To Reset) from IsoDep
        try {
            val isoDep = IsoDep.get(tag)
            if (isoDep != null) {
                isoDep.connect()
                isoDep.timeout = 5000
                
                // Get historical bytes (contains card info)
                val hiBytes = isoDep.historicalBytes
                val hiLenBytes = isoDep.hiLayerResponse
                
                atr = hiBytes?.toHexString() ?: hiLenBytes?.toHexString()
                
                // Try SELECT command for e-KTP applet
                // e-KTP AID: A0 00 00 02 47 10 01 (ICAO eMRTD)
                val selectCmd = byteArrayOf(
                    0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x0C.toByte(),
                    0x07.toByte(),
                    0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(),
                    0x47.toByte(), 0x10.toByte(), 0x01.toByte()
                )
                
                val response = isoDep.transceive(selectCmd)
                val sw = response.takeLast(2).toByteArray().toHexString()
                
                chipInfo = when (sw) {
                    "9000" -> "e-KTP Applet Found ✅ (ICAO eMRTD)"
                    "6A82" -> "Applet not found (non-standard e-KTP)"
                    "6985" -> "Conditions not satisfied"
                    else -> "Response: $sw"
                }
                
                Log.d(TAG, "SELECT response: $sw - $chipInfo")
                
                isoDep.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "IsoDep error: ${e.message}")
            chipInfo = "Read error: ${e.message}"
        }
        
        return NfcResult(
            uid = uid,
            techType = techList,
            isEktp = isEktp,
            atr = atr,
            chipInfo = chipInfo
        )
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}
