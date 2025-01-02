package com.sd.demo.pageloader

import app.cash.turbine.test
import com.sd.lib.pageloader.FPageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@ExperimentalCoroutinesApi
class PageAppendTest {
  @Test
  fun `test append success`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    // 1
    loader.append { page ->
      assertEquals(refreshPage, page)
      assertEquals(refreshPage, 1)
      assertEquals(false, loader.state.isRefreshing)
      assertEquals(true, loader.state.isAppending)
      listOf(1, 2)
    }.also { result ->
      assertEquals(listOf(1, 2), result.getOrThrow())
      with(loader.state) {
        assertEquals(listOf(1, 2), data)
        assertEquals(true, loadResult?.isSuccess)
        assertEquals(refreshPage, loadPage)
        assertEquals(2, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }
    }

    // 2
    loader.append { page ->
      assertEquals(refreshPage + 1, page)
      listOf(3, 4)
    }.also { result ->
      assertEquals(listOf(3, 4), result.getOrThrow())
      with(loader.state) {
        assertEquals(listOf(1, 2, 3, 4), data)
        assertEquals(true, loadResult?.isSuccess)
        assertEquals(refreshPage + 1, loadPage)
        assertEquals(2, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }
    }

    // 3 空数据
    loader.append { page ->
      assertEquals(refreshPage + 2, page)
      emptyList()
    }.also { result ->
      assertEquals(emptyList<Int>(), result.getOrThrow())
      with(loader.state) {
        assertEquals(listOf(1, 2, 3, 4), data)
        assertEquals(true, loadResult?.isSuccess)
        assertEquals(refreshPage + 2, loadPage)
        assertEquals(0, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }
    }

    // 4
    loader.append { page ->
      // 由于上一次加载的是空数据，所以此次的page和上一次应该一样
      assertEquals(refreshPage + 2, page)
      emptyList()
    }.also { result ->
      assertEquals(emptyList<Int>(), result.getOrThrow())
      with(loader.state) {
        assertEquals(listOf(1, 2, 3, 4), data)
        assertEquals(true, loadResult?.isSuccess)
        assertEquals(refreshPage + 2, loadPage)
        assertEquals(0, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }
    }
  }

  @Test
  fun `test append failure`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    loader.append {
      error("append failure")
    }.also { result ->
      assertEquals("append failure", result.exceptionOrNull()!!.message)
    }

    with(loader.state) {
      assertEquals(emptyList<Int>(), data)
      assertEquals("append failure", loadResult!!.exceptionOrNull()!!.message)
      assertEquals(null, loadPage)
      assertEquals(null, loadSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test append cancel`() = runTest {
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

    loader.cancelAppend()

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
  fun `test append when appending`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    val loadJob = launch {
      loader.append {
        delay(5_000)
        listOf(1, 2)
      }
    }.also { runCurrent() }

    try {
      loader.append { listOf(3, 4) }
    } catch (e: CancellationException) {
      Result.failure(e)
    }.also { result ->
      assertEquals(true, result.exceptionOrNull()!! is CancellationException)
    }

    loadJob.join()
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
  fun `test append when refreshing`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    val loadJob = launch {
      loader.refresh {
        delay(5_000)
        listOf(1, 2)
      }
    }.also { runCurrent() }

    try {
      loader.append { listOf(3, 4) }
    } catch (e: CancellationException) {
      Result.failure(e)
    }.let { result ->
      assertEquals(true, result.exceptionOrNull()!! is CancellationException)
    }

    loadJob.join()
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
  fun `test append notify loading`() = runTest {
    val loader = FPageLoader<Int> { _, _ -> null }
    assertEquals(false, loader.state.isAppending)

    launch {
      loader.append(notifyLoading = false) {
        delay(5_000)
        listOf(1, 2)
      }
    }

    runCurrent()
    assertEquals(false, loader.state.isAppending)

    advanceUntilIdle()
    assertEquals(false, loader.state.isAppending)
  }

  @Test
  fun `test append flow`() = runTest {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    loader.stateFlow.test {
      awaitItem().run {
        assertEquals(emptyList<Int>(), data)
        assertEquals(null, loadResult)
        assertEquals(null, loadPage)
        assertEquals(null, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }

      loader.append { listOf(3, 4) }

      awaitItem().run {
        assertEquals(emptyList<Int>(), data)
        assertEquals(null, loadResult)
        assertEquals(null, loadPage)
        assertEquals(null, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(true, isAppending)
      }
      awaitItem().run {
        assertEquals(listOf(3, 4), data)
        assertEquals(Result.success(Unit), loadResult)
        assertEquals(refreshPage, loadPage)
        assertEquals(2, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(true, isAppending)
      }
      awaitItem().run {
        assertEquals(listOf(3, 4), data)
        assertEquals(Result.success(Unit), loadResult)
        assertEquals(refreshPage, loadPage)
        assertEquals(2, loadSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }
    }
  }
}