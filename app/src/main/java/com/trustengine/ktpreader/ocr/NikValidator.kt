package com.trustengine.ktpreader.ocr

/**
 * Deep NIK validation for Indonesian e-KTP
 * NIK format: PPKKCC-DDMMYY-SSSS
 * PP = Provinsi, KK = Kab/Kota, CC = Kecamatan
 * DDMMYY = Tanggal lahir (DD+40 for female)
 * SSSS = Sequential number
 */
object NikValidator {
    
    data class NikResult(
        val valid: Boolean,
        val provinsi: String = "",
        val kabKota: String = "",
        val kecamatan: String = "",
        val gender: String = "",
        val tglLahir: String = "",
        val errors: List<String> = emptyList()
    )
    
    // Kode provinsi Indonesia (36 provinsi + DKI)
    private val PROVINSI_CODES = mapOf(
        "11" to "Aceh", "12" to "Sumatera Utara", "13" to "Sumatera Barat",
        "14" to "Riau", "15" to "Jambi", "16" to "Sumatera Selatan",
        "17" to "Bengkulu", "18" to "Lampung", "19" to "Kep. Bangka Belitung",
        "21" to "Kep. Riau", "31" to "DKI Jakarta", "32" to "Jawa Barat",
        "33" to "Jawa Tengah", "34" to "DI Yogyakarta", "35" to "Jawa Timur",
        "36" to "Banten", "51" to "Bali", "52" to "Nusa Tenggara Barat",
        "53" to "Nusa Tenggara Timur", "61" to "Kalimantan Barat",
        "62" to "Kalimantan Tengah", "63" to "Kalimantan Selatan",
        "64" to "Kalimantan Timur", "65" to "Kalimantan Utara",
        "71" to "Sulawesi Utara", "72" to "Sulawesi Tengah",
        "73" to "Sulawesi Selatan", "74" to "Sulawesi Tenggara",
        "75" to "Gorontalo", "76" to "Sulawesi Barat",
        "81" to "Maluku", "82" to "Maluku Utara",
        "91" to "Papua", "92" to "Papua Barat",
        "93" to "Papua Selatan", "94" to "Papua Tengah",
        "95" to "Papua Pegunungan", "96" to "Papua Barat Daya"
    )
    
    fun validate(nik: String, expectedTgl: String? = null, expectedGender: String? = null): NikResult {
        val errors = mutableListOf<String>()
        
        // Basic format check
        val cleanNik = nik.replace("[^0-9]".toRegex(), "")
        if (cleanNik.length != 16) {
            return NikResult(false, errors = listOf("NIK harus 16 digit (ditemukan ${cleanNik.length} digit)"))
        }
        
        // Provinsi code
        val provCode = cleanNik.substring(0, 2)
        val provinsi = PROVINSI_CODES[provCode]
        if (provinsi == null) {
            errors.add("Kode provinsi '$provCode' tidak valid")
        }
        
        // Extract date components
        val dd = cleanNik.substring(6, 8).toIntOrNull() ?: 0
        val mm = cleanNik.substring(8, 10).toIntOrNull() ?: 0
        val yy = cleanNik.substring(10, 12).toIntOrNull() ?: 0
        
        // Gender detection (DD > 40 = female)
        val gender = if (dd > 40) "P" else "L"
        val actualDD = if (dd > 40) dd - 40 else dd
        
        // Validate date
        if (mm < 1 || mm > 12) errors.add("Bulan tidak valid: $mm")
        if (actualDD < 1 || actualDD > 31) errors.add("Tanggal tidak valid: $actualDD")
        
        // Format tanggal lahir
        val tahun = if (yy > 30) "19${"$yy".padStart(2, '0')}" else "20${"$yy".padStart(2, '0')}"
        val tglLahir = "${"$actualDD".padStart(2, '0')}-${"$mm".padStart(2, '0')}-$tahun"
        
        // Cross-check with expected gender
        if (expectedGender != null && gender != expectedGender) {
            errors.add("Gender NIK ($gender) ≠ input ($expectedGender)")
        }
        
        // Cross-check with expected date
        if (expectedTgl != null && expectedTgl.isNotEmpty()) {
            // Expected format: YYYY-MM-DD or DD-MM-YYYY
            // Compare month and day
            val parts = expectedTgl.split("-")
            if (parts.size == 3) {
                val expDD = if (parts[0].length == 4) parts[2] else parts[0]
                val expMM = parts[1]
                if (expDD.padStart(2, '0') != "$actualDD".padStart(2, '0') ||
                    expMM.padStart(2, '0') != "$mm".padStart(2, '0')) {
                    errors.add("TTL di NIK ($tglLahir) ≠ input ($expectedTgl)")
                }
            }
        }
        
        return NikResult(
            valid = errors.isEmpty(),
            provinsi = provinsi ?: "Unknown ($provCode)",
            kabKota = cleanNik.substring(2, 4),
            kecamatan = cleanNik.substring(4, 6),
            gender = gender,
            tglLahir = tglLahir,
            errors = errors
        )
    }
}
