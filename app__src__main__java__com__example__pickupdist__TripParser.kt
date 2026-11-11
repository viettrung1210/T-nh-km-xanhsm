package com.example.pickupdist

data class Trip(val pickupQuery: String, val dropoffQuery: String?)

/**
 * Màn hình cuốc xe (theo ảnh bạn gửi), các dòng chữ theo thứ tự từ trên xuống:
 *   "36.1km • 1 điểm đến"
 *   "Chung cư A5 - Khu chung cư Làng Quốc tế Thăng Long"   <- tên điểm đón
 *   "Phố Trần Đăng Ninh, Phường Nghĩa Đô, ... Việt Nam"    <- địa chỉ điểm đón (kết thúc bằng "Việt Nam")
 *   "Phường Đường Hào"                                      <- tên điểm trả
 *   "Tỉnh Hưng Yên, Việt Nam"                               <- địa chỉ điểm trả
 *   "Nhận chuyến"
 * Mỗi điểm = các dòng liên tiếp cho tới dòng có "Việt Nam".
 */
object TripParser {
    fun parse(texts: List<String>): Trip? {
        val idx = texts.indexOfFirst { it.contains("điểm đến", ignoreCase = true) }
        if (idx < 0) return null

        val rest = texts.drop(idx + 1).takeWhile { !it.contains("Nhận chuyến", ignoreCase = true) }
        if (rest.isEmpty()) return null

        val groups = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        for (s in rest) {
            cur.add(s)
            if (s.contains("Việt Nam", ignoreCase = true)) {
                groups.add(cur); cur = mutableListOf()
            }
        }
        if (cur.isNotEmpty()) groups.add(cur)

        fun clean(g: List<String>) = g.joinToString(", ").replace(", Việt Nam", "").trim()
        return Trip(clean(groups[0]), groups.getOrNull(1)?.let { clean(it) })
    }
}
