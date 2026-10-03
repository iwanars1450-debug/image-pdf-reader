package jp.local.imagepdf

import org.junit.Assert.*
import org.junit.Test

class NaturalOrderTest {
    @Test fun numbersAreNaturalIncludingLargeNumbers() {
        assertEquals(listOf("1.pdf","2.pdf","10.pdf","999999999999999999999999.pdf"),listOf("10.pdf","999999999999999999999999.pdf","2.pdf","1.pdf").sortedWith(NaturalOrder))
    }
    @Test fun mixedNamesAndCaseAreStable() {
        assertTrue(NaturalOrder.compare("Vol2 chapter9.pdf","vol2 chapter10.pdf")<0)
        assertTrue(NaturalOrder.compare("作品9.pdf","作品12.pdf")<0)
        assertEquals(0,NaturalOrder.compare("01.pdf","01.pdf"))
    }
    @Test fun manualOrderIsNotReversed() {
        data class Item(val name: String,val rank: Int)
        val a=Item("10.pdf",0);val b=Item("1.pdf",1)
        assertEquals(listOf(a,b),sortedItems(listOf(b,a),"manual",true,{it.name},{it.rank},{0L},{0L}))
    }
    @Test fun comparatorIsTransitive() {
        val names=listOf("1.pdf","01.pdf","2.pdf","10.pdf","a1.pdf","A2.pdf","作品1.pdf","作品10.pdf")
        for(a in names) for(b in names) for(c in names) {
            if(NaturalOrder.compare(a,b)<=0&&NaturalOrder.compare(b,c)<=0) assertTrue(NaturalOrder.compare(a,c)<=0)
            assertEquals(-NaturalOrder.compare(b,a).sign(),NaturalOrder.compare(a,b).sign())
        }
    }
    private fun Int.sign()=compareTo(0)
}
