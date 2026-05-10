package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.video.VideoCollectionKind
import blbl.cat3399.core.api.video.VideoCollectionSection
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.paging.PagedGridStateMachine
import blbl.cat3399.core.paging.appliedOrNull
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.video.VideoDetailActivity
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.LinkedHashSet

/**
 * 「我的」- 合集：`list-all` + 订阅夹或全夹扫描 `resource/list`（`type=21`）；打开条目时优先
 * [BiliApi.spaceFavSeasonListPage]（与空间 `favlist?ftype=collect&ctype=21` 同源），失败再 [BiliApi.ugcSeasonArchives]；
 * 仍无数据时回退空间 `seasons_series_list`。
 */
class MyFollowedCollectionsFragment : Fragment(), MyTabSwitchFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MyFollowedCollectionAdapter
    private val paging = PagedGridStateMachine(initialKey = 1)
    private val seenStableKeys = LinkedHashSet<String>()
    private var seasonFavMediaId: Long? = null
    private var usePolymerFallback: Boolean = false
    /** `collected/list?platform=web` 已作为数据源时，分页请求递增 `collectedUgcNextPn`。 */
    private var useCollectedUgcFeed: Boolean = false
    private var collectedUgcListTried: Boolean = false
    private var collectedUgcNextPn: Int = 1
    /** 已在「无单独订阅夹 media_id」路径下跑过全夹 type=21 扫描（每轮刷新重置）。 */
    private var usedAllFoldersType21Scan: Boolean = false
    private var initialLoadTriggered: Boolean = false
    private var pendingRestorePosition: Int? = null
    private var pendingFocusFirstItemFromTabSwitch: Boolean = false
    private var pendingFocusFirstItemAfterRefresh: Boolean = false
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                MyFollowedCollectionAdapter { position, row ->
                    pendingRestorePosition = position
                    openCollectionEntry(row)
                }
        }
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager =
            StaggeredGridLayoutManager(spanCountForWidth(resources), StaggeredGridLayoutManager.VERTICAL).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
            }
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            return focusSelectedMyTabIfAvailable()
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevMyTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextMyTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = !paging.snapshot().endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                    ),
            ).also { it.install() }
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val s = paging.snapshot()
                    if (s.isLoading || s.endReached) return
                    val lm = recyclerView.layoutManager as? StaggeredGridLayoutManager ?: return
                    val last = IntArray(lm.spanCount).let { ia -> lm.findLastVisibleItemPositions(ia); ia.maxOrNull() ?: -1 }
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - last - 1 <= 8) loadNextPage()
                }
            },
        )
        binding.swipeRefresh.setOnRefreshListener { resetAndLoad(fromUserRefresh = true) }
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? StaggeredGridLayoutManager)?.spanCount = spanCountForWidth(resources)
        maybeTriggerInitialLoad()
        restoreFocusIfNeeded()
        maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        resetAndLoad(fromUserRefresh = true)
        return true
    }

    override fun requestFocusFirstItemFromTabSwitch(): Boolean {
        pendingFocusFirstItemFromTabSwitch = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    private fun maybeConsumePendingFocusFirstItemFromTabSwitch(): Boolean {
        if (!pendingFocusFirstItemFromTabSwitch) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false
        if (pendingRestorePosition != null) return false

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstItemFromTabSwitch = false
            return false
        }

        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val b = _binding ?: return false
        val recycler = b.recycler
        val isUiAlive = { _binding === b && isResumed }
        recycler.postIfAlive(isAlive = isUiAlive) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
                return@postIfAlive
            }
            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = isUiAlive) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
            }
        }
        return true
    }

    private fun maybeTriggerInitialLoad() {
        if (initialLoadTriggered) return
        if (!this::adapter.isInitialized) return
        if (adapter.itemCount != 0) {
            initialLoadTriggered = true
            return
        }
        if (binding.swipeRefresh.isRefreshing) return
        binding.swipeRefresh.isRefreshing = true
        resetAndLoad(fromUserRefresh = false)
        initialLoadTriggered = true
    }

    private fun resetAndLoad(fromUserRefresh: Boolean) {
        if (fromUserRefresh) {
            pendingFocusFirstItemAfterRefresh = true
            pendingRestorePosition = null
            pendingFocusFirstItemFromTabSwitch = false
        }
        paging.reset()
        seenStableKeys.clear()
        seasonFavMediaId = null
        usePolymerFallback = false
        useCollectedUgcFeed = false
        collectedUgcListTried = false
        collectedUgcNextPn = 1
        usedAllFoldersType21Scan = false
        dpadGridController?.clearPendingFocusAfterLoadMore()
        dpadGridController?.parkFocusForDataSetReset()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private data class FetchedPage(
        val rows: List<MyFollowedCollectionRow>,
        val endReached: Boolean,
    )

    private fun loadNextPage(isRefresh: Boolean = false) {
        val startSnap = paging.snapshot()
        if (startSnap.isLoading || startSnap.endReached) return
        val startGen = startSnap.generation
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result =
                    paging.loadNextPage(
                        isRefresh = isRefresh,
                        fetch = fetch@{ page ->
                            val nav = BiliApi.nav()
                            val mid = nav.optJSONObject("data")?.optLong("mid") ?: 0L
                            AppLog.i(
                                "BlblFavSeason",
                                "COLLECTIONS_TAB_V2 page=$page mid=$mid sess=${BiliClient.cookies.hasSessData()} (若看不到本行说明未安装新 APK)",
                            )
                            if (mid <= 0) error("invalid mid")

                            if (!usePolymerFallback) {
                                when {
                                    useCollectedUgcFeed -> {
                                        val pn = collectedUgcNextPn
                                        val res = BiliApi.favFolderCollectedUgcSeasonPage(upMid = mid, pn = pn, ps = 20)
                                        collectedUgcNextPn = pn + 1
                                        val rows = res.items.map { it.toFollowedRow() }
                                        AppLog.i(
                                            "BlblFavSeason",
                                            "MyCollections collected/ugc pn=$pn rows=${rows.size} hasMore=${res.hasMore}",
                                        )
                                        return@fetch FetchedPage(rows = rows, endReached = !res.hasMore)
                                    }
                                    !collectedUgcListTried -> {
                                        collectedUgcListTried = true
                                        val probe = BiliApi.favFolderCollectedUgcSeasonPage(upMid = mid, pn = 1, ps = 20)
                                        if (probe.items.isNotEmpty()) {
                                            useCollectedUgcFeed = true
                                            collectedUgcNextPn = 2
                                            val rows = probe.items.map { it.toFollowedRow() }
                                            AppLog.i(
                                                "BlblFavSeason",
                                                "MyCollections collected/ugc probe rows=${rows.size} hasMore=${probe.hasMore}",
                                            )
                                            return@fetch FetchedPage(rows = rows, endReached = !probe.hasMore)
                                        }
                                    }
                                }

                                if (seasonFavMediaId == null) {
                                    seasonFavMediaId = BiliApi.favSeasonSubscribeFolderMediaId(mid)
                                }
                                val mediaId = seasonFavMediaId
                                if (mediaId != null && mediaId > 0L) {
                                    val res = BiliApi.favSeasonSubscribeResourcePage(mediaId = mediaId, pn = page, ps = 20)
                                    val rows = res.items.map { it.toFollowedRow() }
                                    AppLog.i(
                                        "BlblFavSeason",
                                        "MyCollections page=$page rows=${rows.size} hasMore=${res.hasMore} (fav resource)",
                                    )
                                    FetchedPage(rows = rows, endReached = !res.hasMore)
                                } else if (usedAllFoldersType21Scan) {
                                    FetchedPage(rows = emptyList(), endReached = true)
                                } else {
                                    usedAllFoldersType21Scan = true
                                    val scanned = BiliApi.favScanAllCreatedFoldersType21(upMid = mid)
                                    if (scanned.isNotEmpty()) {
                                        val rows = scanned.map { it.toFollowedRow() }
                                        AppLog.i(
                                            "BlblFavSeason",
                                            "MyCollections page=$page rows=${rows.size} (scan all folders type21)",
                                        )
                                        FetchedPage(rows = rows, endReached = true)
                                    } else {
                                        usePolymerFallback = true
                                        AppLog.w(
                                            "BlblFavSeason",
                                            "MyCollections scan type21 empty -> seasons_series_list mid=$mid",
                                        )
                                        buildPolymerFetchedPage(mid, page)
                                    }
                                }
                            } else {
                                buildPolymerFetchedPage(mid, page)
                            }
                        },
                        reduce = { page, fetched ->
                            val filtered = fetched.rows.filter { seenStableKeys.add(it.stableKey) }
                            val nextPage = page + 1
                            PagedGridStateMachine.Update(
                                items = filtered,
                                nextKey = nextPage,
                                endReached = fetched.endReached,
                            )
                        },
                    )

                val applied = result.appliedOrNull() ?: return@launch
                if (applied.isRefresh) {
                    adapter.submit(applied.items)
                } else if (applied.items.isNotEmpty()) {
                    adapter.append(applied.items)
                }
                _binding?.let { b ->
                    b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        if (isRefresh && pendingFocusFirstItemAfterRefresh) {
                            pendingFocusFirstItemAfterRefresh = false
                            pendingFocusFirstItemFromTabSwitch = false
                            pendingRestorePosition = null

                            val recycler = b.recycler
                            val isUiAlive = { _binding === b && isResumed }
                            recycler.requestFocusFirstItemOrSelfAfterRefresh(
                                itemCount = adapter.itemCount,
                                smoothScroll = false,
                                isAlive = isUiAlive,
                                onDone = { dpadGridController?.unparkFocusAfterDataSetReset() },
                            )
                            return@postIfAlive
                        }
                        maybeConsumePendingFocusFirstItemFromTabSwitch()
                        dpadGridController?.consumePendingFocusAfterLoadMore()
                    }
                }
                restoreFocusIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("BlblFavSeason", "MyCollections load failed", t)
                context?.let {
                    AppToast.show(
                        it,
                        "加载失败。请用 Android Studio Logcat 搜索「BlblFavSeason」或「MyCollections」（标签形如 BLBL/…）",
                    )
                }
            } finally {
                if (paging.snapshot().generation == startGen) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun openCollectionEntry(row: MyFollowedCollectionRow) {
        val ctx = context ?: return
        // 优先按合集/系列网格打开（与「收藏夹详情」相同的多列网格 UI）
        val sid = row.seasonId
        val om = row.ownerMid
        if (sid != null && sid > 0L && om != null && om > 0L) {
            val nav = findMyNavigator()
            if (nav != null) {
                AppLog.i(
                    "BlblFavSeason",
                    "open MyCollectionDetail kind=${row.kind} season=$sid owner=$om title=${row.title}",
                )
                nav.openSeasonDetail(
                    kind = row.kind,
                    seasonId = sid,
                    ownerMid = om,
                    title = row.title,
                )
                return
            }
        }
        // 没有 seasonId/ownerMid 的退路：仅有单视频 BV，直接进视频详情。
        val direct = row.entryBvid.trim().takeIf { it.isNotBlank() }
        if (direct != null) {
            AppLog.i("BlblFavSeason", "open VideoDetail bvid=$direct title=${row.title}")
            startActivity(
                Intent(ctx, VideoDetailActivity::class.java)
                    .putExtra(VideoDetailActivity.EXTRA_BVID, direct)
                    .apply { row.entryCid?.takeIf { it > 0L }?.let { putExtra(VideoDetailActivity.EXTRA_CID, it) } },
            )
            return
        }
        AppLog.w("BlblFavSeason", "open collection skipped: no seasonId/ownerMid/entryBvid title=${row.title}")
        AppToast.show(ctx, "打开合集失败")
    }

    private fun restoreFocusIfNeeded() {
        val pos = pendingRestorePosition ?: return
        if (_binding == null) return
        if (pos < 0 || pos >= adapter.itemCount) return
        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null }) {
            recycler.scrollToPosition(pos)
            recycler.postIfAlive(isAlive = { _binding != null }) {
                recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                pendingRestorePosition = null
            }
        }
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        seenStableKeys.clear()
        seasonFavMediaId = null
        usePolymerFallback = false
        useCollectedUgcFeed = false
        collectedUgcListTried = false
        collectedUgcNextPn = 1
        usedAllFoldersType21Scan = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    private suspend fun buildPolymerFetchedPage(mid: Long, page: Int): FetchedPage {
        val parsed = BiliApi.collectionSections(mid = mid, pageNum = page, pageSize = 10)
        val rows = ArrayList<MyFollowedCollectionRow>(parsed.sections.size)
        for (section in parsed.sections) {
            val row = section.toRowOrNull()
            if (row == null) {
                AppLog.d(
                    "BlblFavSeason",
                    "skip polymer section kind=${section.kind} id=${section.id} items=${section.items.size}",
                )
            } else {
                rows.add(row)
            }
        }
        val endReached =
            when {
                parsed.totalPages > 0 -> page >= parsed.totalPages
                else -> rows.isEmpty()
            }
        AppLog.i(
            "BlblFavSeason",
            "polymer fallback page=$page sections=${parsed.sections.size} rows=${rows.size} endReached=$endReached",
        )
        return FetchedPage(rows = rows, endReached = endReached)
    }
}

private fun BiliApi.FavSeasonSubscribeResource.toFollowedRow(): MyFollowedCollectionRow =
    MyFollowedCollectionRow(
        stableKey = "fav21:$seasonId",
        kind = VideoCollectionKind.SEASON,
        title = title,
        totalCount = null,
        coverUrl = coverUrl,
        entryBvid = directBvid.orEmpty(),
        entryCid = null,
        seasonId = seasonId,
        ownerMid = ownerMid,
    )

private fun VideoCollectionSection.toRowOrNull(): MyFollowedCollectionRow? {
    val first = items.firstOrNull() ?: return null
    val bvid = first.bvid.trim().takeIf { it.isNotBlank() } ?: return null
    val keyPrefix =
        when (kind) {
            VideoCollectionKind.SEASON -> "season"
            VideoCollectionKind.SERIES -> "series"
        }
    return MyFollowedCollectionRow(
        stableKey = "$keyPrefix:$id",
        kind = kind,
        title = title,
        totalCount = totalCount,
        coverUrl = first.coverUrl.trim().takeIf { it.isNotBlank() },
        entryBvid = bvid,
        entryCid = first.cid?.takeIf { it > 0L },
        seasonId = null,
        ownerMid = null,
    )
}
