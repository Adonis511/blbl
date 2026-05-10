package blbl.cat3399.feature.my

import androidx.fragment.app.Fragment
import blbl.cat3399.core.api.video.VideoCollectionKind

interface MyNavigator {
    fun openFavFolder(mediaId: Long, title: String)

    fun openBangumiDetail(
        seasonId: Long,
        isDrama: Boolean,
        continueEpId: Long? = null,
        continueEpIndex: Int? = null,
    )

    /**
     * 进入「合集/系列」视频列表（与 [openFavFolder] 同样的网格 UI），由
     * [MyCollectionDetailFragment] 承接。
     */
    fun openSeasonDetail(
        kind: VideoCollectionKind,
        seasonId: Long,
        ownerMid: Long,
        title: String,
    )
}

fun Fragment.findMyNavigator(): MyNavigator? {
    return generateSequence(parentFragment) { it.parentFragment }
        .filterIsInstance<MyNavigator>()
        .firstOrNull()
        ?: (activity as? MyNavigator)
}
