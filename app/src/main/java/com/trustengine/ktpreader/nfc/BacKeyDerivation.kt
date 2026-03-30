package com.trustengine.ktpreader.nfc

import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESedeKeySpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BAC (Basic Access Control) Key Derivation for e-KTP Indonesia
 * 
 * Standard ICAO 9303: K_seed = SHA1(MRZ_information)[0:16]
 * But e-KTP has no MRZ, so we try multiple hypotheses.
 * 
 * Each hypothesis generates a K_seed, which is then used to derive:
 * - K_enc (encryption key) using SHA1(K_seed + counter_enc)
 * - K_mac (MAC key) using SHA1(K_seed + counter_mac)
 * 
 * These keys are used in the EXTERNAL AUTHENTICATE handshake.
 */
object BacKeyDerivation {
    
    private const val TAG = "BacKeyDerivation"
    
    data class BacKeys(
        val kEnc: ByteArray,
        val kMac: ByteArray,
        val hypothesis: String
    )
    
    /**
     * Generate all possible BAC key sets from available KTP data.
     * Returns list of key pairs to try during authentication.
     */
    fun generateAllKeyHypotheses(
        nik: String?,
        dateOfBirth: String?,   // DD-MM-YYYY or YYYYMMDD
        expiryDate: String?,    // DD-MM-YYYY or YYYYMMDD  
        cardNumber: String?     // If any number printed on e-KTP
    ): List<BacKeys> {
        val hypotheses = mutableListOf<BacKeys>()
        
        val dobYYMMDD = convertToYYMMDD(dateOfBirth)
        val expYYMMDD = convertToYYMMDD(expiryDate)
        
        // === HYPOTHESIS 1: Standard ICAO using NIK as document number ===
        // MRZ_info = docNo + checkDigit + DOB + checkDigit + expiry + checkDigit
        if (nik != null && dobYYMMDD != null) {
            val docNo = nik.take(9) // First 9 chars of NIK
            val mrzInfo = docNo + calcCheckDigit(docNo) + 
                         dobYYMMDD + calcCheckDigit(dobYYMMDD) + 
                         (expYYMMDD ?: "991231") + calcCheckDigit(expYYMMDD ?: "991231")
            val kSeed = deriveKSeed(mrzInfo)
            hypotheses.add(deriveBacKeys(kSeed, "H1: NIK[0:9]+DOB+EXP (ICAO standard)"))
            
            // Also try full NIK
            val mrzInfo2 = nik + calcCheckDigit(nik) +
                          dobYYMMDD + calcCheckDigit(dobYYMMDD) +
                          (expYYMMDD ?: "991231") + calcCheckDigit(expYYMMDD ?: "991231")
            val kSeed2 = deriveKSeed(mrzInfo2)
            hypotheses.add(deriveBacKeys(kSeed2, "H1b: Full NIK+DOB+EXP"))
        }
        
        // === HYPOTHESIS 2: NIK only as seed ===
        if (nik != null) {
            val kSeed = deriveKSeed(nik)
            hypotheses.add(deriveBacKeys(kSeed, "H2: NIK only"))
        }
        
        // === HYPOTHESIS 3: NIK + DOB concatenated ===
        if (nik != null && dobYYMMDD != null) {
            val kSeed = deriveKSeed(nik + dobYYMMDD)
            hypotheses.add(deriveBacKeys(kSeed, "H3: NIK+DOB"))
        }
        
        // === HYPOTHESIS 4: DOB only (some cards use this) ===
        if (dobYYMMDD != null) {
            val kSeed = deriveKSeed(dobYYMMDD)
            hypotheses.add(deriveBacKeys(kSeed, "H4: DOB only"))
        }
        
        // === HYPOTHESIS 5: Last 6 digits of NIK (sequential) + DOB ===
        if (nik != null && nik.length == 16 && dobYYMMDD != null) {
            val seq = nik.substring(10, 16)
            val mrzInfo = seq + calcCheckDigit(seq) +
                         dobYYMMDD + calcCheckDigit(dobYYMMDD) +
                         "991231" + calcCheckDigit("991231")
            val kSeed = deriveKSeed(mrzInfo)
            hypotheses.add(deriveBacKeys(kSeed, "H5: NIK[10:16]+DOB+noExpiry"))
        }
        
        // === HYPOTHESIS 6: CAN (Card Access Number) if available ===
        if (cardNumber != null) {
            val kSeed = deriveKSeed(cardNumber)
            hypotheses.add(deriveBacKeys(kSeed, "H6: CAN/CardNumber"))
        }
        
        // === HYPOTHESIS 7: NIK embedded DOB (digits 6-11) ===
        if (nik != null && nik.length == 16) {
            // NIK format: PPKKCC-DDMMYY-SSSS
            val nikDob = nik.substring(6, 12) // DDMMYY from NIK
            val docNo = nik.take(9)
            val mrzInfo = docNo + calcCheckDigit(docNo) +
                         nikDob + calcCheckDigit(nikDob) +
                         "991231" + calcCheckDigit("991231")
            val kSeed = deriveKSeed(mrzInfo)
            hypotheses.add(deriveBacKeys(kSeed, "H7: NIK[0:9]+NIK_DOB[6:12]+noExpiry"))
        }
        
        // === HYPOTHESIS 8: PACE with NIK as CAN ===
        if (nik != null) {
            // Some e-KTP might use PACE instead of BAC
            // CAN is typically 6 digits
            val can6 = nik.takeLast(6)
            val kSeed = deriveKSeed(can6)
            hypotheses.add(deriveBacKeys(kSeed, "H8: PACE CAN=NIK[-6:]"))
            
            // Also try first 6 digits
            val can6first = nik.take(6)
            val kSeed2 = deriveKSeed(can6first)
            hypotheses.add(deriveBacKeys(kSeed2, "H8b: PACE CAN=NIK[0:6]"))
        }
        
        // === HYPOTHESIS 9: Raw NIK bytes as key directly ===
        if (nik != null && nik.length == 16) {
            val raw = nik.toByteArray(Charsets.US_ASCII)
            val kSeed = raw.copyOfRange(0, 16)
            hypotheses.add(deriveBacKeys(kSeed, "H9: Raw NIK bytes as seed"))
        }
        
        // === HYPOTHESIS 10: SHA256 of NIK ===
        if (nik != null) {
            val sha256 = MessageDigest.getInstance("SHA-256").digest(nik.toByteArray())
            val kSeed = sha256.copyOfRange(0, 16)
            hypotheses.add(deriveBacKeys(kSeed, "H10: SHA256(NIK)[0:16]"))
        }
        
        Log.d(TAG, "Generated ${hypotheses.size} key hypotheses")
        return hypotheses
    }
    
    /**
     * Convert date to YYMMDD format
     */
    private fun convertToYYMMDD(date: String?): String? {
        if (date == null) return null
        return try {
            val clean = date.replace(Regex("[^0-9]"), "")
            when (clean.length) {
                8 -> { // DDMMYYYY or YYYYMMDD
                    if (clean.substring(0, 2).toInt() > 31) {
                        // YYYYMMDD
                        clean.substring(2, 4) + clean.substring(4, 6) + clean.substring(6, 8)
                    } else {
                        // DDMMYYYY
                        clean.substring(4, 6) + clean.substring(2, 4) + clean.substring(0, 2)
                    }
                }
                6 -> clean // Already YYMMDD
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * ICAO 9303 check digit calculation
     */
    fun calcCheckDigit(input: String): String {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        for (i in input.indices) {
            val c = input[i]
            val value = when {
                c in '0'..'9' -> c - '0'
                c in 'A'..'Z' -> c - 'A' + 10
                c == '<' -> 0
                else -> 0
            }
            sum += value * weights[i % 3]
        }
        return (sum % 10).toString()
    }
    
    /**
     * Derive K_seed from MRZ information using SHA-1
     */
    private fun deriveKSeed(mrzInfo: String): ByteArray {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest(mrzInfo.toByteArray(Charsets.US_ASCII))
        return hash.copyOfRange(0, 16) // First 16 bytes
    }
    
    private fun deriveKSeed(data: ByteArray): ByteArray {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest(data)
        return hash.copyOfRange(0, 16)
    }
    
    /**
     * Derive BAC encryption and MAC keys from K_seed
     * Per ICAO 9303 Part 11, Section 9.7.1
     */
    private fun deriveBacKeys(kSeed: ByteArray, hypothesis: String): BacKeys {
        // K_enc: SHA1(K_seed || 00000001)[0:16] adjusted for parity
        val counterEnc = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val sha1Enc = MessageDigest.getInstance("SHA-1")
        sha1Enc.update(kSeed)
        sha1Enc.update(counterEnc)
        val hashEnc = sha1Enc.digest()
        val kEncRaw = hashEnc.copyOfRange(0, 16)
        val kEnc = adjustParity(extendTo24Bytes(kEncRaw))
        
        // K_mac: SHA1(K_seed || 00000002)[0:16] adjusted for parity
        val counterMac = byteArrayOf(0x00, 0x00, 0x00, 0x02)
        val sha1Mac = MessageDigest.getInstance("SHA-1")
        sha1Mac.update(kSeed)
        sha1Mac.update(counterMac)
        val hashMac = sha1Mac.digest()
        val kMacRaw = hashMac.copyOfRange(0, 16)
        val kMac = adjustParity(extendTo24Bytes(kMacRaw))
        
        return BacKeys(kEnc, kMac, hypothesis)
    }
    
    /**
     * Extend 16-byte key to 24-byte 3DES key (K1 || K2 || K1)
     */
    private fun extendTo24Bytes(key16: ByteArray): ByteArray {
        val key24 = ByteArray(24)
        System.arraycopy(key16, 0, key24, 0, 8)   // K1
        System.arraycopy(key16, 8, key24, 8, 8)   // K2
        System.arraycopy(key16, 0, key24, 16, 8)  // K1 again
        return key24
    }
    
    /**
     * Adjust DES key parity bits
     */
    private fun adjustParity(key: ByteArray): ByteArray {
        for (i in key.indices) {
            var b = key[i].toInt() and 0xFE
            var parity = 0
            for (bit in 1..7) {
                parity = parity xor ((b shr bit) and 1)
            }
            key[i] = (b or (parity and 1)).toByte()
        }
        return key
    }
    
    // ========== BAC AUTHENTICATION PROTOCOL ==========
    
    /**
     * Perform mutual authentication
     * Returns session keys (KS_enc, KS_mac) and send sequence counter
     */
    fun performBac(
        keys: BacKeys,
        rndIcc: ByteArray  // 8-byte challenge from GET CHALLENGE
    ): BacAuthResult? {
        try {
            // Step 1: Generate random numbers
            val rndIfd = generateRandom(8)  // Terminal random
            val kIfd = generateRandom(16)   // Terminal key material
            
            // Step 2: Build S = RND.IFD || RND.ICC || K.IFD
            val s = ByteArray(32)
            System.arraycopy(rndIfd, 0, s, 0, 8)
            System.arraycopy(rndIcc, 0, s, 8, 8)
            System.arraycopy(kIfd, 0, s, 16, 16)
            
            // Step 3: Encrypt S with K_enc
            val eifd = encrypt3DES(s, keys.kEnc)
            
            // Step 4: Compute MAC over encrypted data
            val mifd = computeMAC(eifd, keys.kMac)
            
            // Step 5: Build command data = E.IFD || M.IFD
            val cmdData = ByteArray(40)
            System.arraycopy(eifd, 0, cmdData, 0, 32)
            System.arraycopy(mifd, 0, cmdData, 32, 8)
            
            return BacAuthResult(cmdData, rndIfd, kIfd)
            
        } catch (e: Exception) {
            Log.e(TAG, "BAC auth error: ${e.message}")
            return null
        }
    }
    
    /**
     * Process response from EXTERNAL AUTHENTICATE
     * Verify ICC's response and derive session keys
     */
    fun processBacResponse(
        response: ByteArray,
        keys: BacKeys,
        rndIfd: ByteArray,
        kIfd: ByteArray,
        rndIcc: ByteArray
    ): SessionKeys? {
        try {
            if (response.size < 40) return null
            
            val eIcc = response.copyOfRange(0, 32)
            val mIcc = response.copyOfRange(32, 40)
            
            // Verify MAC
            val expectedMac = computeMAC(eIcc, keys.kMac)
            if (!expectedMac.contentEquals(mIcc)) {
                Log.d(TAG, "MAC verification failed")
                return null
            }
            
            // Decrypt
            val decrypted = decrypt3DES(eIcc, keys.kEnc)
            
            // Extract: RND.ICC || RND.IFD || K.ICC
            val rndIccResp = decrypted.copyOfRange(0, 8)
            val rndIfdResp = decrypted.copyOfRange(8, 16)
            val kIcc = decrypted.copyOfRange(16, 32)
            
            // Verify RND.IFD matches what we sent
            if (!rndIfdResp.contentEquals(rndIfd)) {
                Log.d(TAG, "RND.IFD verification failed")
                return null
            }
            
            // Derive session keys: K_seed = K.IFD XOR K.ICC
            val kSeedSession = ByteArray(16)
            for (i in 0 until 16) {
                kSeedSession[i] = (kIfd[i].toInt() xor kIcc[i].toInt()).toByte()
            }
            
            val sessionKeys = deriveBacKeys(kSeedSession, "session")
            
            // Send Sequence Counter = RND.ICC[4:8] || RND.IFD[4:8]
            val ssc = ByteArray(8)
            System.arraycopy(rndIcc, 4, ssc, 0, 4)
            System.arraycopy(rndIfd, 4, ssc, 4, 4)
            
            return SessionKeys(sessionKeys.kEnc, sessionKeys.kMac, ssc)
            
        } catch (e: Exception) {
            Log.e(TAG, "Process BAC response error: ${e.message}")
            return null
        }
    }
    
    data class BacAuthResult(
        val commandData: ByteArray,  // 40 bytes to send in EXTERNAL AUTHENTICATE
        val rndIfd: ByteArray,
        val kIfd: ByteArray
    )
    
    data class SessionKeys(
        val ksEnc: ByteArray,
        val ksMac: ByteArray,
        val ssc: ByteArray
    )
    
    // ========== CRYPTO HELPERS ==========
    
    private fun encrypt3DES(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DESede/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "DESede")
        val iv = IvParameterSpec(ByteArray(8)) // Zero IV
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv)
        return cipher.doFinal(data)
    }
    
    private fun decrypt3DES(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DESede/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "DESede")
        val iv = IvParameterSpec(ByteArray(8))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
        return cipher.doFinal(data)
    }
    
    private fun computeMAC(data: ByteArray, key: ByteArray): ByteArray {
        // ISO 9797-1 MAC Algorithm 3 (Retail MAC)
        // Single DES CBC with K1, then encrypt last block with K2
        
        // Pad data to multiple of 8 bytes
        val padded = pad(data)
        
        // DES CBC with K1 (first 8 bytes of key)
        val k1 = key.copyOfRange(0, 8)
        val k2 = key.copyOfRange(8, 16)
        
        val cipher1 = Cipher.getInstance("DES/CBC/NoPadding")
        val keySpec1 = SecretKeySpec(k1, "DES")
        val iv = IvParameterSpec(ByteArray(8))
        cipher1.init(Cipher.ENCRYPT_MODE, keySpec1, iv)
        val intermediate = cipher1.doFinal(padded)
        
        // Take last 8 bytes
        val lastBlock = intermediate.copyOfRange(intermediate.size - 8, intermediate.size)
        
        // Decrypt with K2
        val cipher2 = Cipher.getInstance("DES/ECB/NoPadding")
        val keySpec2 = SecretKeySpec(k2, "DES")
        cipher2.init(Cipher.DECRYPT_MODE, keySpec2)
        val decrypted = cipher2.doFinal(lastBlock)
        
        // Encrypt with K1
        val cipher3 = Cipher.getInstance("DES/ECB/NoPadding")
        cipher3.init(Cipher.ENCRYPT_MODE, keySpec1)
        return cipher3.doFinal(decrypted)
    }
    
    private fun pad(data: ByteArray): ByteArray {
        // ISO 9797-1 padding method 2
        val padLen = 8 - (data.size % 8)
        val padded = ByteArray(data.size + padLen)
        System.arraycopy(data, 0, padded, 0, data.size)
        padded[data.size] = 0x80.toByte()
        // Rest is already 0x00
        return padded
    }
    
    private fun generateRandom(length: Int): ByteArray {
        val random = java.security.SecureRandom()
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes
    }
}
