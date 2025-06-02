package com.shiftsmart.plus.ui.fragments.errors

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.ItemAppIssueBinding
import com.shiftsmart.plus.models.AppIssue

class AppIssueAdapter(private var issues: List<AppIssue>) :
    RecyclerView.Adapter<AppIssueAdapter.IssueViewHolder>() {

    inner class IssueViewHolder(val binding: ItemAppIssueBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IssueViewHolder {
        val binding = ItemAppIssueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return IssueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: IssueViewHolder, position: Int) {
        val issue = issues[position]
        with(holder.binding) {
            issueTitle.text = issue.title
            issueSolution.text = issue.solution

            // Highlight title red if it's an active issue
            issueTitle.setTextColor(ContextCompat.getColor(root.context, if (issue.isIssue) R.color.red_text else R.color.green_text))
            issueSolution.setTextColor(ContextCompat.getColor(root.context, if (issue.isIssue) R.color.red_text else R.color.green_text))
            if (issue.isIssue)
            {
                infoIcon.visibility = View.VISIBLE
                infoIcon.isClickable=true
                statusIcon.setImageResource(R.drawable.ic_circle_uncheck)
                mainCard.setCardBackgroundColor(ContextCompat.getColor(root.context, R.color.red_card_bg))
            }
            else
            {
                statusIcon.setImageResource(R.drawable.ic_circle_check)
                infoIcon.visibility = View.INVISIBLE
                infoIcon.isClickable=false

                mainCard.setCardBackgroundColor(ContextCompat.getColor(root.context, R.color.green_card_bg))
            }

            // Toggle solution visibility on info icon click
            infoIcon.setOnClickListener {
                issueSolution.visibility =
                    if (issueSolution.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }

    override fun getItemCount(): Int = issues.size

    fun updateList(newIssues: List<AppIssue>) {
        this.issues = newIssues
        notifyDataSetChanged()
    }
}
