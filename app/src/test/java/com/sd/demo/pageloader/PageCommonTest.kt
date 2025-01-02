package com.sd.demo.pageloader

import com.sd.lib.pageloader.FPageLoader
import org.junit.Assert.assertEquals
import org.junit.Test

class PageCommonTest {
  @Test
  fun `test default state`() {
    val loader = FPageLoader<Int> { _, _ -> null }
    with(loader.state) {
      assertEquals(emptyList<Int>(), data)
      assertEquals(1, refreshPage)
      assertEquals(null, loadResult)
      assertEquals(null, loadPage)
      assertEquals(null, loadSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }
}