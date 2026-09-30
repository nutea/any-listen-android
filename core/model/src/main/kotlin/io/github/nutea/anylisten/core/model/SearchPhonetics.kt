package io.github.nutea.anylisten.core.model

import com.github.promeg.pinyinhelper.Pinyin
import com.github.promeg.pinyinhelper.PinyinMapDict

/** Context readings for common music terms and artist names; remaining characters use the library default. */
internal object SearchPhonetics {
    init {
        Pinyin.init(Pinyin.newConfig().with(object : PinyinMapDict() {
            override fun mapping(): Map<String, Array<String>> = mapOf(
                "莫文蔚" to arrayOf("MO", "WEN", "WEI"),
                "单依纯" to arrayOf("SHAN", "YI", "CHUN"),
                "曾轶可" to arrayOf("ZENG", "YI", "KE"),
                "音乐" to arrayOf("YIN", "YUE"), "乐队" to arrayOf("YUE", "DUI"),
                "快乐" to arrayOf("KUAI", "LE"), "成长" to arrayOf("CHENG", "ZHANG"),
                "长大" to arrayOf("ZHANG", "DA"), "重庆" to arrayOf("CHONG", "QING"),
                "重逢" to arrayOf("CHONG", "FENG"), "重来" to arrayOf("CHONG", "LAI"),
                "蔚蓝" to arrayOf("WEI", "LAN"),
            )
        }))
    }
    fun syllables(text: String): List<String> = Pinyin.toPinyin(text, " ").split(' ').filter { it.isNotBlank() }
}
