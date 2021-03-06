package android.fitget

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class PlansAdapter(private val mPlans: ArrayList<Plan>) : RecyclerView.Adapter<PlansAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.plan, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val view = holder.itemView.findViewById(R.id.tv_plan_name) as TextView
        view.text = mPlans[position].title
    }

    override fun getItemCount(): Int {
        return mPlans.size
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}