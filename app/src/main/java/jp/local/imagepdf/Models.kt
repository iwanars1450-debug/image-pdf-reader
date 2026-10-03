package jp.local.imagepdf

import org.json.JSONObject
import java.math.BigInteger

data class Work(val id: String, val name: String, val order: Int, val updated: Long, val read: Long, val columns: Int, val sort: String, val descending: Boolean)
data class Book(val id: String, val work: String, val name: String, val pages: Int, val order: Int, val updated: Long, val read: Long, val page: Int, val offset: Float)
data class Preferences(val volume: Boolean = true, val step: Int = 25, val speed: Int = 2, val gap: Int = 8, val zoom: Boolean = true, val reopen: Boolean = true, val crop: Boolean = false) {
    fun json() = JSONObject().put("volume",volume).put("step",step).put("speed",speed).put("gap",gap).put("zoom",zoom).put("reopen",reopen).put("crop",crop)
    companion object { fun from(j: JSONObject) = Preferences(j.optBoolean("volume",true),j.optInt("step",25).takeIf { it in listOf(10,25,50,75,100) } ?: 25,j.optInt("speed",2).coerceIn(1,4),j.optInt("gap",8).takeIf { it in listOf(0,4,8,16) } ?: 8,j.optBoolean("zoom",true),j.optBoolean("reopen",true),j.optBoolean("crop",false)) }
}
object NaturalOrder : Comparator<String> {
    private val chunks = Regex("[0-9]+|[^0-9]+")
    override fun compare(a: String, b: String): Int {
        val aa=chunks.findAll(a.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
        val bb=chunks.findAll(b.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
        for(i in 0 until minOf(aa.size,bb.size)) {
            val x=aa[i]; val y=bb[i]
            val c=if(x[0].isDigit() && y[0].isDigit()) BigInteger(x).compareTo(BigInteger(y)) else x.compareTo(y)
            if(c!=0) return c
        }
        return aa.size.compareTo(bb.size).takeIf { it!=0 } ?: a.compareTo(b)
    }
}
fun <T> sortedItems(items: List<T>, mode: String, descending: Boolean, name: (T)->String, order:(T)->Int, read:(T)->Long, updated:(T)->Long): List<T> {
    val comparator=Comparator<T> { a,b -> when(mode) { "read" -> read(a).compareTo(read(b)); "updated" -> updated(a).compareTo(updated(b)); "manual" -> order(a).compareTo(order(b)); else -> NaturalOrder.compare(name(a),name(b)) }.takeIf { it!=0 } ?: NaturalOrder.compare(name(a),name(b)) }
    return items.sortedWith(if(descending && mode!="manual") comparator.reversed() else comparator)
}
