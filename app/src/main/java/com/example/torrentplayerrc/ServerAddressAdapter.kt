package com.example.torrentplayerrc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ServerAddressAdapter(
    private val dataSet: List<String>,
    private val onDeleteCallback: (pos: Int) -> Unit,
    private val onSelectCallback: (pos: Int) -> Unit
): RecyclerView.Adapter<ServerAddressAdapter.ViewHolder>() {
    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.server_address_row, viewGroup, false)

        return ViewHolder(v, onDeleteCallback, onSelectCallback)
    }

    override fun getItemCount() = dataSet.size

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        viewHolder.textView.text = dataSet[position]
    }

    class ViewHolder(
        view: View,
         onDeleteCallback: (pos: Int) -> Unit,
         onSelectCallback: (pos: Int) -> Unit
    ): RecyclerView.ViewHolder(view) {
        val textView: TextView

        init {
            view.findViewById<ImageView>(R.id.delete).setOnClickListener {
                onDeleteCallback(adapterPosition)
            }
            textView = view.findViewById(R.id.text)
            textView.setOnClickListener {
                onSelectCallback(adapterPosition)
            }
        }
    }
}