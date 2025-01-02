package com.sd.lib.pageloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface FPageLoader<T> {

  /** 状态 */
  val state: PageState<T>

  /** 状态流 */
  val stateFlow: StateFlow<PageState<T>>

  /**
   * 刷新，如果当前正在刷新或者正在加载更多，会取消正在进行的加载
   *
   * @param notifyLoading 是否通知[PageState.isRefreshing]
   * @param onLoad 加载回调
   */
  suspend fun refresh(
    notifyLoading: Boolean = true,
    onLoad: suspend LoadScope<T>.(page: Int) -> List<T>,
  ): Result<List<T>>

  /**
   * 加载更多，如果当前正在刷新或者正在加载更多，会抛出[CancellationException]异常，取消本次调用
   *
   * @param notifyLoading 是否通知[PageState.isAppending]
   * @param onLoad 加载回调
   */
  suspend fun append(
    notifyLoading: Boolean = true,
    onLoad: suspend LoadScope<T>.(page: Int) -> List<T>,
  ): Result<List<T>>

  /**
   * 取消刷新
   */
  suspend fun cancelRefresh()

  /**
   * 取消加载更多
   */
  suspend fun cancelAppend()

  interface LoadScope<T> {
    /** 当前状态 */
    val currentState: PageState<T>

    /** 刷新数据的页码，例如数据源页码从1开始，那么[refreshPage]就为1 */
    val refreshPage: Int
      get() = currentState.refreshPage
  }
}

/**
 * [FPageLoader]
 *
 * @param initial 初始值
 * @param refreshPage 刷新数据的页码，例如数据源页码从1开始，那么[refreshPage]就为1
 * @param dataHandler 处理每页的数据，并返回总的数据，返回null则总数据不变
 */
fun <T> FPageLoader(
  initial: List<T> = emptyList(),
  refreshPage: Int = 1,
  dataHandler: suspend FPageLoader.LoadScope<T>.(page: Int, pageData: List<T>) -> List<T>?,
): FPageLoader<T> {
  return PageLoaderImpl(
    initial = initial,
    refreshPage = refreshPage,
    dataHandler = dataHandler,
  )
}

//-------------------- state --------------------

data class PageState<T>(
  /** 总数据 */
  val data: List<T> = emptyList(),

  /** 最后一次加载的结果 */
  val result: Result<Unit>? = null,

  /** 最后一次加载的页码 */
  val page: Int? = null,

  /** 最后一次加载的数据个数 */
  val pageSize: Int? = null,

  /** 刷新数据的页码，例如数据源页码从1开始，那么[refreshPage]就为1 */
  val refreshPage: Int = 1,

  /** 是否正在刷新 */
  val isRefreshing: Boolean = false,

  /** 是否正在加载更多 */
  val isAppending: Boolean = false,
)

/** 是否显示没有更多数据 */
val PageState<*>.showNoMoreData: Boolean get() = data.isNotEmpty() && pageSize == 0

/** 是否显示加载数据为空 */
val PageState<*>.showLoadEmpty: Boolean get() = data.isEmpty() && result?.isSuccess == true

/** 是否显示加载数据失败 */
val PageState<*>.showLoadFailure: Boolean get() = data.isEmpty() && result?.isFailure == true

//-------------------- impl --------------------

private class PageLoaderImpl<T>(
  initial: List<T>,
  refreshPage: Int,
  private val dataHandler: suspend FPageLoader.LoadScope<T>.(page: Int, pageData: List<T>) -> List<T>?,
) : FPageLoader<T>, FPageLoader.LoadScope<T> {

  private val _refreshLoader = MutatorMutex()
  private val _appendLoader = MutatorMutex()

  private val _stateFlow = MutableStateFlow(
    PageState(
      data = initial,
      refreshPage = refreshPage,
    )
  )

  override val state: PageState<T>
    get() = _stateFlow.value

  override val stateFlow: StateFlow<PageState<T>>
    get() = _stateFlow.asStateFlow()

  override val currentState: PageState<T>
    get() = _stateFlow.value

  override suspend fun refresh(
    notifyLoading: Boolean,
    onLoad: suspend FPageLoader.LoadScope<T>.(page: Int) -> List<T>,
  ): Result<List<T>> {
    // 刷新之前，取消加载更多
    cancelAppend()
    return load(
      mutator = _refreshLoader,
      onStart = {
        if (notifyLoading) {
          _stateFlow.update { it.copy(isRefreshing = true) }
        }
      },
      onFinish = {
        if (notifyLoading) {
          _stateFlow.update { it.copy(isRefreshing = false) }
        }
      },
      getPage = { state.refreshPage },
      onLoad = onLoad,
    )
  }

  override suspend fun append(
    notifyLoading: Boolean,
    onLoad: suspend FPageLoader.LoadScope<T>.(page: Int) -> List<T>,
  ): Result<List<T>> {
    if (state.isRefreshing || state.isAppending) {
      throw AppendCancellationException()
    }
    return load(
      mutator = _appendLoader,
      onStart = {
        if (notifyLoading) {
          _stateFlow.update { it.copy(isAppending = true) }
        }
      },
      onFinish = {
        if (notifyLoading) {
          _stateFlow.update { it.copy(isAppending = false) }
        }
      },
      getPage = { getAppendPage() },
      onLoad = onLoad,
    )
  }

  private suspend fun load(
    mutator: MutatorMutex,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    getPage: () -> Int,
    onLoad: suspend FPageLoader.LoadScope<T>.(page: Int) -> List<T>,
  ): Result<List<T>> {
    return mutator.mutate {
      val page = getPage()
      try {
        onStart()
        onLoad(page)
          .also { handleLoadSuccess(page, it) }
          .let { Result.success(it) }
      } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Result.failure<List<T>>(e).also {
          currentCoroutineContext().ensureActive()
          _stateFlow.update { it.copy(result = Result.failure(e)) }
        }
      } finally {
        onFinish()
      }
    }
  }

  override suspend fun cancelRefresh() {
    _refreshLoader.cancelAndJoin()
  }

  override suspend fun cancelAppend() {
    _appendLoader.cancelAndJoin()
  }

  private fun getAppendPage(): Int {
    if (state.data.isEmpty()) return state.refreshPage
    val lastPage = state.page ?: return state.refreshPage
    return if (state.pageSize!! <= 0) lastPage else lastPage + 1
  }

  private suspend fun handleLoadSuccess(page: Int, data: List<T>) {
    currentCoroutineContext().ensureActive()
    val totalData = dataHandler(page, data)
    currentCoroutineContext().ensureActive()

    _stateFlow.update { state ->
      state.copy(
        data = totalData ?: state.data,
        result = Result.success(Unit),
        page = page,
        pageSize = data.size,
      )
    }
  }
}

private class AppendCancellationException : CancellationException("Append cancellation") {
  override fun fillInStackTrace(): Throwable {
    stackTrace = emptyArray()
    return this
  }
}