package dev.spikeysanju.expensetracker.view.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.spikeysanju.expensetracker.databinding.ItemAccountLayoutBinding
import dev.spikeysanju.expensetracker.model.Account
import indianRupee

class AccountAdapter : RecyclerView.Adapter<AccountAdapter.AccountVH>() {

    inner class AccountVH(val binding: ItemAccountLayoutBinding) :
        RecyclerView.ViewHolder(binding.root)

    private val differCallback = object : DiffUtil.ItemCallback<Account>() {
        override fun areItemsTheSame(oldItem: Account, newItem: Account): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Account, newItem: Account): Boolean {
            return oldItem == newItem
        }
    }

    val differ = AsyncListDiffer(this, differCallback)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccountVH {
        val binding =
            ItemAccountLayoutBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AccountVH(binding)
    }

    override fun getItemCount(): Int {
        return differ.currentList.size
    }

    override fun onBindViewHolder(holder: AccountVH, position: Int) {
        val item = differ.currentList[position]
        holder.binding.apply {
            accountName.text = item.name
            accountBalance.text = indianRupee(item.balance)

            holder.itemView.setOnClickListener {
                onItemClickListener?.let { it(item) }
            }

            deleteAccountBtn.setOnClickListener {
                onDeleteClickListener?.let { it(item) }
            }
        }
    }

    private var onItemClickListener: ((Account) -> Unit)? = null
    fun setOnItemClickListener(listener: (Account) -> Unit) {
        onItemClickListener = listener
    }

    private var onDeleteClickListener: ((Account) -> Unit)? = null
    fun setOnDeleteClickListener(listener: (Account) -> Unit) {
        onDeleteClickListener = listener
    }
}
