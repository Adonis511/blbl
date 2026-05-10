package blbl.cat3399.feature.my

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.api.video.VideoCollectionKind
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ItemFavFolderBinding

data class MyFollowedCollectionRow(
    val stableKey: String,
    val kind: VideoCollectionKind,
    val title: String,
    val totalCount: Int?,
    val coverUrl: String?,
    /** 有值则直接进详情；为空时用 [seasonId]+[ownerMid] 请求合集稿件列表 */
    val entryBvid: String,
    val entryCid: Long?,
    val seasonId: Long? = null,
    val ownerMid: Long? = null,
)

class MyFollowedCollectionAdapter(
    private val onClick: (position: Int, row: MyFollowedCollectionRow) -> Unit,
) : RecyclerView.Adapter<MyFollowedCollectionAdapter.Vh>() {
    private val items = ArrayList<MyFollowedCollectionRow>()

    init {
        setHasStableIds(true)
    }

    fun invalidateSizing() {
        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<MyFollowedCollectionRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<MyFollowedCollectionRow>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemId(position: Int): Long = items[position].stableKey.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemFavFolderBinding.inflate(
                LayoutInflater.from(parent.context).cloneInUserScale(parent.context),
                parent,
                false,
            )
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemFavFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MyFollowedCollectionRow, onClick: (position: Int, row: MyFollowedCollectionRow) -> Unit) {
            binding.tvTitle.text = item.title
            val count = item.totalCount?.takeIf { it > 0 }
            binding.tvCount.text =
                if (count != null) {
                    binding.root.context.getString(R.string.my_fav_item_count_fmt, count)
                } else {
                    binding.root.context.getString(
                        when (item.kind) {
                            VideoCollectionKind.SEASON -> R.string.my_collection_kind_season
                            VideoCollectionKind.SERIES -> R.string.my_collection_kind_series
                        },
                    )
                }
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onClick(pos, item)
            }
        }
    }
}
