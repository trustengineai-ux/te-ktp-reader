package com.trustengine.ktpreader.model

import java.io.Serializable

data class KtpData(
    var nama: String = "",
    var nik: String = "",
    var tempatLahir: String = "",
    var tanggalLahir: String = "",
    var jenisKelamin: String = "",
    var alamat: String = "",
    var rtRw: String = "",
    var kelDesa: String = "",
    var kecamatan: String = "",
    var agama: String = "",
    var statusPerkawinan: String = "",
    var pekerjaan: String = "",
    var kewarganegaraan: String = "",
    var berlakuHingga: String = "",
    var fotoPath: String? = null,
    
    // NFC data
    var nfcUid: String? = null,
    var nfcTechType: String? = null,
    var isChipVerified: Boolean = false,
    var chipReadSuccess: Boolean = false,
    
    // Source flags
    var ocrScanned: Boolean = false,
    var nfcScanned: Boolean = false,
    
    // NIK validation
    var nikValid: Boolean = false,
    var nikProvinsi: String = "",
    var nikKabKota: String = "",
    var nikKecamatan: String = "",
    var nikGender: String = "",
    var nikTglLahir: String = ""
) : Serializable {
    
    fun toJson(): String {
        return """
        {
            "nama": "$nama",
            "nik": "$nik",
            "tempat_lahir": "$tempatLahir",
            "tanggal_lahir": "$tanggalLahir",
            "jenis_kelamin": "$jenisKelamin",
            "alamat": "$alamat",
            "rt_rw": "$rtRw",
            "kel_desa": "$kelDesa",
            "kecamatan": "$kecamatan",
            "agama": "$agama",
            "status_perkawinan": "$statusPerkawinan",
            "pekerjaan": "$pekerjaan",
            "kewarganegaraan": "$kewarganegaraan",
            "berlaku_hingga": "$berlakuHingga",
            "nfc_uid": ${nfcUid?.let { "\"$it\"" } ?: "null"},
            "nfc_verified": $isChipVerified,
            "chip_read": $chipReadSuccess,
            "nik_valid": $nikValid,
            "nik_provinsi": "$nikProvinsi",
            "nik_gender": "$nikGender"
        }
        """.trimIndent()
    }
}
