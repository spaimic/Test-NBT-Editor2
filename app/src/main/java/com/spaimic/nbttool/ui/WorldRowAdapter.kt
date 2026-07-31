package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView

/** One row: a world's display name plus its LastPlayed date/time, so worlds
 * with the same name (e.g. several "New World"s) can still be told apart. */
data class WorldRow(val name: String, val dateLine: String, val timeLine: String)

class WorldRowAdapter(context: Context, private val rows: List<WorldRow>) :
    ArrayAdapter<WorldRow>(context, 0, rows) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = rows[position]

        val nameView = TextView(context).apply {
            text = row.name
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        val dateView = TextView(context).apply {
            text = row.dateLine
            textSize = 13f
            gravity = Gravity.END
        }
        val timeView = TextView(context).apply {
            text = row.timeLine
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.END
        }
        val dateTimeColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(dateView)
            addView(timeView)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER_VERTICAL
            addView(nameView)
            if (row.dateLine.isNotEmpty()) addView(dateTimeColumn)
        }
    }
}
