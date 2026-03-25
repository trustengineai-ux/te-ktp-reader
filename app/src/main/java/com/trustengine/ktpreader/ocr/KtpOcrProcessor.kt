package com.trustengine.ktpreader.ocr

import com.trustengine.ktpreader.model.KtpData

/**
 * Processes OCR text from KTP image and extracts structured data.
 * Uses regex patterns to identify and extract fields from Indonesian KTP.
 */
object KtpOcrProcessor {
    
    fun parseOcrText(ocrText: String): KtpData {
        val data = KtpData(ocrScanned = true)
        val text = ocrText.uppercase()
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        
        // Extract NIK (16 digit number)
        val nikPattern = Regex("\\b(\\d{16})\\b")
        nikPattern.find(text)?.let {
            data.nik = it.groupValues[1]
            // Validate NIK
            val nikResult = NikValidator.validate(data.nik)
            data.nikValid = nikResult.valid
            data.nikProvinsi = nikResult.provinsi
            data.nikGender = nikResult.gender
            data.nikTglLahir = nikResult.tglLahir
        }
        
        // Extract Nama
        extractField(lines, listOf("NAMA", "NAME")) { value ->
            data.nama = value.replace(Regex("^[:\\s]+"), "").trim()
        }
        
        // Extract Tempat/Tgl Lahir
        extractField(lines, listOf("TEMPAT", "LAHIR", "TTL")) { value ->
            val clean = value.replace(Regex("^[:\\s]+"), "").trim()
            // Format: KOTA, DD-MM-YYYY or KOTA DD-MM-YYYY
            val datePattern = Regex("(\\d{2})[\\-/](\\d{2})[\\-/](\\d{4})")
            datePattern.find(clean)?.let { match ->
                data.tanggalLahir = "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
                val beforeDate = clean.substring(0, clean.indexOf(match.value)).trim().trimEnd(',').trim()
                if (beforeDate.isNotEmpty()) data.tempatLahir = beforeDate
            }
        }
        
        // Extract Jenis Kelamin
        extractField(lines, listOf("JENIS KELAMIN", "JEN.KELAMIN", "KELAMIN")) { value ->
            val clean = value.replace(Regex("^[:\\s]+"), "").trim()
            data.jenisKelamin = when {
                clean.contains("LAKI") -> "LAKI-LAKI"
                clean.contains("PEREM") || clean.contains("WANITA") -> "PEREMPUAN"
                else -> clean
            }
        }
        
        // Extract Alamat
        extractField(lines, listOf("ALAMAT", "ADDRESS")) { value ->
            data.alamat = value.replace(Regex("^[:\\s]+"), "").trim()
        }
        
        // Extract RT/RW
        extractField(lines, listOf("RT/RW", "RT /RW")) { value ->
            data.rtRw = value.replace(Regex("^[:\\s]+"), "").trim()
        }
        
        // Extract Kel/Desa
        extractField(lines, listOf("KEL/DESA", "KEL /DESA", "KELURAHAN", "DESA")) { value ->
            data.kelDesa = value.replace(Regex("^[:\\s]+"), "").trim()
        }
        
        // Extract Kecamatan
        extractField(lines, listOf("KECAMATAN", "KEC")) { value ->
            data.kecamatan = value.replace(Regex("^[:\\s]+"), "").trim()
        }
        
        // Extract Agama
        extractField(lines, listOf("AGAMA")) { value ->
            data.agama = value.replace(Regex("^[:\\s]+"), "").trim()
        }
        
        // Extract Status Perkawinan
        extractField(lines, listOf("STATUS PERKAWINAN", "PERKAWINAN", "STATUS")) { value ->
            val clean = value.replace(Regex("^[:\\s]+"), "").trim()
            data.statusPerkawinan = when {
                clean.contains("KAWIN") && !clean.contains("BELUM") && !clean.contains("CERAI") -> "KAWIN"
                clean.contains("BELUM") -> "BELUM KAWIN"
                clean.contains("CERAI HIDUP") -> "CERAI HIDUP"
                clean.contains("CERAI MATI") -> "CERAI MATI"
                else -> clean
            }
        }
        
        // Extract Pekerjaan
        extractField(lines, listOf("PEKERJAAN")) { value ->
            data.pekerjaan = value.replace(Regex("^[:\\s]+"), "").trim()
        }
        
        // Extract Kewarganegaraan
        extractField(lines, listOf("KEWARGANEGARAAN", "WARGANEGARA")) { value ->
            val clean = value.replace(Regex("^[:\\s]+"), "").trim()
            data.kewarganegaraan = if (clean.contains("WNI") || clean.contains("INDONESIA")) "WNI" else clean
        }
        
        // Extract Berlaku Hingga
        extractField(lines, listOf("BERLAKU", "HINGGA")) { value ->
            val clean = value.replace(Regex("^[:\\s]+"), "").trim()
            data.berlakuHingga = if (clean.contains("SEUMUR")) "SEUMUR HIDUP" else clean
        }
        
        return data
    }
    
    private fun extractField(lines: List<String>, keywords: List<String>, callback: (String) -> Unit) {
        for (i in lines.indices) {
            for (keyword in keywords) {
                if (lines[i].contains(keyword)) {
                    // Value might be on same line after keyword or on next line
                    val afterKeyword = lines[i].substringAfter(keyword)
                    if (afterKeyword.trim().length > 1) {
                        callback(afterKeyword)
                    } else if (i + 1 < lines.size) {
                        callback(lines[i + 1])
                    }
                    return
                }
            }
        }
    }
}
