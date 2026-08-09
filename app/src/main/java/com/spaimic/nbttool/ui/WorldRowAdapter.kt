package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** One row: a world's display name plus its LastPlayed date/time, so worlds
 * with the same name (e.g. several "New World"s) can still be told apart. */
data class WorldRow(val name: String, val realName: String, val dateLine: String, val timeLine: String, val iconPath: String? = null)

class WorldRowAdapter(context: Context, private val rows: List<WorldRow>) :
    ArrayAdapter<WorldRow>(context, 0, rows) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = rows[position]

        val nameView = TextView(context).apply {
            text = row.name
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.START
        }
        val realNameView = TextView(context).apply {
            text = row.realName
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.START
        }
        val nameColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(nameView)
            if (row.realName.isNotBlank()) addView(realNameView)
        }
        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(72, 72).apply { rightMargin = 20 }
            scaleType = ImageView.ScaleType.CENTER_CROP
            val path = row.iconPath
            if (path != null && File(path).isFile) {
                setImageBitmap(BitmapFactory.decodeFile(path))
            } else {
                visibility = View.GONE
            }
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
            addView(iconView)
            addView(nameColumn)
            if (row.dateLine.isNotEmpty()) addView(dateTimeColumn)
        }
    }
}
