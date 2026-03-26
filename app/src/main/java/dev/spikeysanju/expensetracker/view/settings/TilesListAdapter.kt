package dev.spikeysanju.expensetracker.view.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.spikeysanju.expensetracker.databinding.ItemTileLayoutBinding

class TilesListAdapter(
    private val tiles: MutableList<String>,
    private val onUpdate: (List<String>) -> Unit
) : RecyclerView.Adapter<TilesListAdapter.TileViewHolder>() {

    inner class TileViewHolder(private val binding: ItemTileLayoutBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(tile: String, position: Int) {
            binding.tileName.text = tile
            binding.deleteButton.setOnClickListener {
                tiles.removeAt(position)
                notifyItemRemoved(position)
                onUpdate(tiles)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileViewHolder {
        val binding = ItemTileLayoutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TileViewHolder, position: Int) {
        holder.bind(tiles[position], position)
    }

    override fun getItemCount() = tiles.size
}
