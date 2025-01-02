package com.sd.demo.pageloader

import app.cash.turbine.test
import com.sd.lib.pageloader.FPageLoader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@ExperimentalCoroutinesApi
class PageRefreshTest {
  @Test
  fun `test refresh success`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    loader.refresh { page ->
      assertEquals(refreshPage, page)
      assertEquals(refreshPage, 1)
      assertEquals(true, loader.state.isRefreshing)
      assertEquals(false, loader.state.isAppending)
      listOf(1, 2)
    }.also { result ->
      assertEquals(listOf(1, 2), result.getOrThrow())
    }

    with(loader.state) {
      assertEquals(listOf(1, 2), data)
      assertEquals(true, loadResult?.isSuccess)
      assertEquals(refreshPage, loadPage)
      assertEquals(2, loadSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh failure`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    loader.refresh {
      error("refresh failure")
    }.also { result ->
      assertEquals("refresh failure", result.exceptionOrNull()!!.message)
    }

    with(loader.state) {
      assertEquals(emptyList<Int>(), data)
      assertEquals("refresh failure", loadResult!!.exceptionOrNull()!!.message)
      assertEquals(null, loadPage)
      assertEquals(null, loadSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh cancel`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    launch {
      loader.refresh {
        delay(5_000)
        listOf(1, 2)
      }
    }.also { runCurrent() }

    loader.cancelRefresh()

    with(loader.state) {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, loadResult)
      assertEquals(null, loadPage)
      assertEquals(null, loadSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh when refreshing`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    launch {
      loader.refresh {
        delay(5_000)
        listOf(1, 2)
      }
    }.also { runCurrent() }

    loader.refresh { listOf(3, 4) }

    with(loader.state) {
      assertEquals(listOf(3, 4), data)
      assertEquals(true, loadResult?.isSuccess)
      assertEquals(refreshPage, loadPage)
      assertEquals(2, loadSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh when appending`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    launch {
      loader.append {
        delay(5_000)
        listOf(1, 2)
      }
    }.also { runCurrent() }

    loader.refresh { listOf(3, 4) }

    with(loader.state) {
      assertEquals(listOf(3, 4), data)
      assertEquals(true, loadResult?.isSuccess)
      assertEquals(refreshPage, loadPage)
      assertEquals(2, loadSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh notify loading`() = runTest {
    val loader = FPageLoader<Int> { _, _ -> null }
    assertEquals(false, loader.state.isRefreshing)

    launch {
      loader.refresh(notifyLoading = false) {
        delay(5_000)
        listOf(1, 2)
      }
    }

    runCurrent()
    assertEquals(false, loader.state.isRefreshing)

    advanceUntilIdle()
    assertEquals(false, loader.state.isRefreshing)
  }

  @Test
  fun `test refresh flow`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    loader.stateFlow.test {
      with(awaitItem()) {
        assertEquals(emptyList<Int>(), data)
        assertEquals(null, loadResult)
        assertEquals(null, loadPage)
        assertEquals(null, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }

      loader.refresh { listOf(3, 4) }

      with(awaitItem()) {
        assertEquals(emptyList<Int>(), data)
        assertEquals(null, loadResult)
        assertEquals(null, loadPage)
        assertEquals(null, loadSize)
        assertEquals(true, isRefreshing)
        assertEquals(false, isAppending)
      }
      with(awaitItem()) {
        assertEquals(listOf(3, 4), data)
        assertEquals(true, loadResult?.isSuccess)
        assertEquals(refreshPage, loadPage)
        assertEquals(2, loadSize)
        assertEquals(true, isRefreshing)
        assertEquals(false, isAppending)
      }
      with(awaitItem()) {
        assertEquals(listOf(3, 4), data)
        assertEquals(true, loadResult?.isSuccess)
        assertEquals(refreshPage, loadPage)
        assertEquals(2, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }
    }
  }
}