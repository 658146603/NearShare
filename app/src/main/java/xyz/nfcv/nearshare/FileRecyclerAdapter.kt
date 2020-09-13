package xyz.nfcv.nearshare

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.selected_uri_list_item.view.*
import java.io.InputStream

class FileRecyclerAdapter(private val context: Context, private val listener: OnItemClickListener) : RecyclerView.Adapter<FileRecyclerAdapter.ViewHolder>() {
    private val mFiles: ArrayList<Uri> = ArrayList()
    var mSelected: ArrayList<Uri> = ArrayList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.selected_uri_list_item, parent, false)
        return ViewHolder(view, listener)
    }

    override fun getItemCount(): Int = mFiles.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val input: InputStream? = context.contentResolver.openInputStream(mFiles[position])
        val size: Int = input?.available() ?: -1
        input?.close()
        holder.deviceName.text = mFiles[position].path?.split('/')?.last()
        holder.deviceType.text = "$size B"
        if (mFiles[position] in mSelected) {
            holder.container.setBackgroundResource(R.color.uri_selected)
        } else {
            holder.container.setBackgroundResource(R.color.white)
        }
    }

    class ViewHolder(itemView: View, mListener: OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
        var listener: OnItemClickListener = mListener
        var container: View = itemView

        init {
            container.setOnClickListener {
                listener.onItemClick(this::class.java, layoutPosition)
            }
        }

        var deviceName: TextView = itemView.txtUriName
        var deviceType: TextView = itemView.txtUriLength
    }

    fun addFile(uri: Uri) {
        mFiles.add(uri)
        mSelected.add(uri)
        notifyDataSetChanged()
    }

    fun addAllFile(uri: List<Uri>) {
        mFiles.addAll(uri)
        mSelected.addAll(uri)
        notifyDataSetChanged()
    }

    fun removeFile(uri: Uri) {
        mFiles.remove(uri)
        mSelected.remove(uri)
        notifyDataSetChanged()
    }

    fun setSelectedPosition(position: Int) {
        if (mFiles[position] in mSelected) {
            mSelected.remove(mFiles[position])
        } else {
            mSelected.add(mFiles[position])
        }
        notifyDataSetChanged()
    }

    fun clear() {
        mFiles.clear()
        mSelected.clear()
        notifyDataSetChanged()
    }

    interface OnItemClickListener {
        fun onItemClick(clazz: Class<*>, position: Int)
    }
}