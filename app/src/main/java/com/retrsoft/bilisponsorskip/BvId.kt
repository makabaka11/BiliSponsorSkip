package com.retrsoft.bilisponsorskip

internal object BvId {
    private const val TABLE = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
    private const val XOR = 23_442_827_791_579L

    // Algorithm used by BiliRoaming and documented by bilibili-API-collect.
    fun fromAid(aid: Long): String {
        require(aid > 0) { "aid must be positive" }
        val result = CharArray(12) { index -> if (index < 3) "BV1"[index] else '0' }
        var value = ((1L shl 51) or aid) xor XOR
        var index = 11
        while (value > 0 && index >= 0) {
            result[index--] = TABLE[(value % 58).toInt()]
            value /= 58
        }
        result[3] = result[9].also { result[9] = result[3] }
        result[4] = result[7].also { result[7] = result[4] }
        return String(result)
    }
}
