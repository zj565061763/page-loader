package com.sd.demo.pageloader

import app.cash.turbine.test
import com.sd.lib.pageloader.FPageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLoaderTest {
  @Test
  fun `test default state`() {
    val loader = FPageLoader<Int> { page, pageData -> null }
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, result)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh success`(): Unit = runBlocking {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    loader.refresh { page ->
      assertEquals(refreshPage, page)
      listOf(1, 2)
    }.let { result ->
      assertEquals(true, result.isSuccess)
      assertEquals(listOf(1, 2), result.getOrThrow())
    }
    loader.state.run {
      assertEquals(listOf(1, 2), data)
      assertEquals(Result.success(Unit), result)
      assertEquals(refreshPage, page)
      assertEquals(2, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh failure`(): Unit = runBlocking {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    loader.refresh { error("failure") }.let { result ->
      assertEquals(true, result.isFailure)
      assertEquals("failure", result.exceptionOrNull()!!.message)
    }
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals("failure", result!!.exceptionOrNull()!!.message)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh cancel`(): Unit = runBlocking {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    val loading = TestContinuation()
    launch {
      loader.refresh {
        loading.resume()
        delay(Long.MAX_VALUE)
        listOf(1, 2)
      }
    }

    loading.await()
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, result)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(true, isRefreshing)
      assertEquals(false, isAppending)
    }

    loader.cancelRefresh()
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, result)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh when refreshing`(): Unit = runBlocking {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    val loading = TestContinuation()
    launch {
      loader.refresh {
        loading.resume()
        delay(Long.MAX_VALUE)
        listOf(1, 2)
      }
    }

    loading.await()
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, result)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(true, isRefreshing)
      assertEquals(false, isAppending)
    }

    loader.refresh { listOf(3, 4) }
    loader.state.run {
      assertEquals(listOf(3, 4), data)
      assertEquals(Result.success(Unit), result)
      assertEquals(refreshPage, page)
      assertEquals(2, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh when appending`(): Unit = runBlocking {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    val loading = TestContinuation()
    launch {
      loader.append {
        loading.resume()
        delay(Long.MAX_VALUE)
        listOf(1, 2)
      }
    }

    loading.await()
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, result)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(true, isAppending)
    }

    loader.refresh { listOf(3, 4) }
    loader.state.run {
      assertEquals(listOf(3, 4), data)
      assertEquals(Result.success(Unit), result)
      assertEquals(refreshPage, page)
      assertEquals(2, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test refresh notify loading`(): Unit = runBlocking {
    val loader = FPageLoader<Int> { page, pageData -> null }
    loader.state.run {
      assertEquals(false, isRefreshing)
    }

    val loading = TestContinuation()
    launch {
      loader.refresh(notifyLoading = false) {
        loading.resume()
        delay(Long.MAX_VALUE)
        listOf(1, 2)
      }
    }

    loading.await()
    loader.state.run {
      assertEquals(false, isRefreshing)
    }

    loader.refresh(notifyLoading = false) { error("failure") }
    loader.state.run {
      assertEquals(false, isRefreshing)
    }
  }

  @Test
  fun `test refresh flow`(): Unit = runBlocking {
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
        assertEquals(null, result)
        assertEquals(null, page)
        assertEquals(null, pageSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }

      loader.refresh { listOf(3, 4) }

      awaitItem().run {
        assertEquals(emptyList<Int>(), data)
        assertEquals(null, result)
        assertEquals(null, page)
        assertEquals(null, pageSize)
        assertEquals(true, isRefreshing)
        assertEquals(false, isAppending)
      }
      awaitItem().run {
        assertEquals(listOf(3, 4), data)
        assertEquals(Result.success(Unit), result)
        assertEquals(refreshPage, page)
        assertEquals(2, pageSize)
        assertEquals(true, isRefreshing)
        assertEquals(false, isAppending)
      }
      awaitItem().run {
        assertEquals(listOf(3, 4), data)
        assertEquals(Result.success(Unit), result)
        assertEquals(refreshPage, page)
        assertEquals(2, pageSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }
    }
  }


  @Test
  fun `test append success`(): Unit = runBlocking {
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
      listOf(1, 2)
    }.let { result ->
      assertEquals(true, result.isSuccess)
      assertEquals(listOf(1, 2), result.getOrThrow())
    }
    loader.state.run {
      assertEquals(listOf(1, 2), data)
      assertEquals(Result.success(Unit), result)
      assertEquals(refreshPage, page)
      assertEquals(2, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }

    // 2
    loader.append { page ->
      assertEquals(refreshPage + 1, page)
      listOf(3, 4)
    }.let { result ->
      assertEquals(true, result.isSuccess)
      assertEquals(listOf(3, 4), result.getOrThrow())
    }
    loader.state.run {
      assertEquals(listOf(1, 2, 3, 4), data)
      assertEquals(Result.success(Unit), result)
      assertEquals(refreshPage + 1, page)
      assertEquals(2, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }

    // 3 空数据
    loader.append { page ->
      assertEquals(refreshPage + 2, page)
      emptyList()
    }.let { result ->
      assertEquals(true, result.isSuccess)
      assertEquals(emptyList<Int>(), result.getOrThrow())
    }
    loader.state.run {
      assertEquals(listOf(1, 2, 3, 4), data)
      assertEquals(Result.success(Unit), result)
      assertEquals(refreshPage + 2, page)
      assertEquals(0, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }

    // 4
    loader.append { page ->
      // 由于上一次加载的是空数据，所以此次的page和上一次应该一样
      assertEquals(refreshPage + 2, page)
      emptyList()
    }.let { result ->
      assertEquals(true, result.isSuccess)
      assertEquals(emptyList<Int>(), result.getOrThrow())
    }
    loader.state.run {
      assertEquals(listOf(1, 2, 3, 4), data)
      assertEquals(Result.success(Unit), result)
      assertEquals(refreshPage + 2, page)
      assertEquals(0, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test append failure`(): Unit = runBlocking {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    loader.append { error("failure") }.let { result ->
      assertEquals(true, result.isFailure)
      assertEquals("failure", result.exceptionOrNull()!!.message)
    }
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals("failure", result!!.exceptionOrNull()!!.message)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test append cancel`(): Unit = runBlocking {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    val loading = TestContinuation()
    launch {
      loader.append {
        loading.resume()
        delay(Long.MAX_VALUE)
        listOf(1, 2)
      }
    }

    loading.await()
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, result)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(true, isAppending)
    }

    loader.cancelAppend()
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, result)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test append when appending`(): Unit = runBlocking {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    val loading = TestContinuation()
    val loadJob = launch {
      loader.append {
        loading.resume()
        delay(1_000)
        listOf(1, 2)
      }
    }

    loading.await()
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, result)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(true, isAppending)
    }

    try {
      loader.append { listOf(3, 4) }
    } catch (e: CancellationException) {
      Result.failure(e)
    }.let { result ->
      assertEquals(true, result.exceptionOrNull()!! is CancellationException)
    }

    loadJob.join()
    loader.state.run {
      assertEquals(listOf(1, 2), data)
      assertEquals(Result.success(Unit), result)
      assertEquals(refreshPage, page)
      assertEquals(2, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test append when refreshing`(): Unit = runBlocking {
    val list = mutableListOf<Int>()
    val loader = FPageLoader { page, pageData ->
      list.apply {
        if (page == refreshPage) clear()
        addAll(pageData)
      }
    }

    val loading = TestContinuation()
    val loadJob = launch {
      loader.refresh {
        loading.resume()
        delay(2_000)
        listOf(1, 2)
      }
    }

    loading.await()
    loader.state.run {
      assertEquals(emptyList<Int>(), data)
      assertEquals(null, result)
      assertEquals(null, page)
      assertEquals(null, pageSize)
      assertEquals(true, isRefreshing)
      assertEquals(false, isAppending)
    }

    try {
      loader.append { listOf(3, 4) }
    } catch (e: CancellationException) {
      Result.failure(e)
    }.let { result ->
      assertEquals(true, result.exceptionOrNull()!! is CancellationException)
    }

    loadJob.join()
    loader.state.run {
      assertEquals(listOf(1, 2), data)
      assertEquals(Result.success(Unit), result)
      assertEquals(refreshPage, page)
      assertEquals(2, pageSize)
      assertEquals(false, isRefreshing)
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test append notify loading`(): Unit = runBlocking {
    val loader = FPageLoader<Int> { page, pageData -> null }
    loader.state.run {
      assertEquals(false, isAppending)
    }

    val loading = TestContinuation()
    launch {
      loader.append(notifyLoading = false) {
        loading.resume()
        delay(Long.MAX_VALUE)
        listOf(1, 2)
      }
    }

    loading.await()
    loader.state.run {
      assertEquals(false, isAppending)
    }

    loader.append(notifyLoading = false) { error("failure") }
    loader.state.run {
      assertEquals(false, isAppending)
    }
  }

  @Test
  fun `test append flow`(): Unit = runBlocking {
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
        assertEquals(null, result)
        assertEquals(null, page)
        assertEquals(null, pageSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }

      loader.append { listOf(3, 4) }

      awaitItem().run {
        assertEquals(emptyList<Int>(), data)
        assertEquals(null, result)
        assertEquals(null, page)
        assertEquals(null, pageSize)
        assertEquals(false, isRefreshing)
        assertEquals(true, isAppending)
      }
      awaitItem().run {
        assertEquals(listOf(3, 4), data)
        assertEquals(Result.success(Unit), result)
        assertEquals(refreshPage, page)
        assertEquals(2, pageSize)
        assertEquals(false, isRefreshing)
        assertEquals(true, isAppending)
      }
      awaitItem().run {
        assertEquals(listOf(3, 4), data)
        assertEquals(Result.success(Unit), result)
        assertEquals(refreshPage, page)
        assertEquals(2, pageSize)
        assertEquals(false, isRefreshing)
        assertEquals(false, isAppending)
      }
    }
  }
}