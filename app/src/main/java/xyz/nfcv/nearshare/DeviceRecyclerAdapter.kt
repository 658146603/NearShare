package xyz.nfcv.nearshare

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.microsoft.connecteddevices.remotesystems.RemoteSystem
import kotlinx.android.synthetic.main.discovered_device_list_item.view.*

class DeviceRecyclerAdapter(private val context: Context, private val listener: OnItemClickListener) : RecyclerView.Adapter<DeviceRecyclerAdapter.ViewHolder>() {
    private val mDevices: ArrayList<RemoteSystem> = ArrayList()
    var mSelected: RemoteSystem? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.discovered_device_list_item, parent, false)
        return ViewHolder(view, listener)
    }

    override fun getItemCount(): Int = mDevices.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.deviceName.text = mDevices[position].displayName
        holder.deviceType.text = mDevices[position].kind
        if (mSelected == mDevices[position]) {
            holder.container.setBackgroundResource(R.color.device_selected)
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

        var deviceName: TextView = itemView.txtDeviceName
        var deviceType: TextView = itemView.txtDeviceType
    }

    fun addDevice(remoteSystem: RemoteSystem) {
        mDevices.add(remoteSystem)
        mSelected = null
        notifyDataSetChanged()
    }

    fun removeDevice(remoteSystem: RemoteSystem) {
        mDevices.remove(remoteSystem)
        mSelected = null
        notifyDataSetChanged()
    }

    fun setSelectedPosition(position: Int) {
        mSelected = (if (mSelected == mDevices[position]) null else mDevices[position])
        notifyDataSetChanged()
    }

    fun clear() {
        mDevices.clear()
        mSelected = null
        notifyDataSetChanged()
    }

    interface OnItemClickListener {
        fun onItemClick(clazz: Class<*>, position: Int)
    }
}