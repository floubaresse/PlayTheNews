package com.frandroidlabs.playthenews

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class PlaylistItemTouchHelper(
    private val adapter: PlaylistAdapter
) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN,  // Drag directions
    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT  // Swipe directions
) {

    private var deleteIcon: Drawable? = null
    private val deleteBackground = ColorDrawable(Color.RED)

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val fromPosition = viewHolder.bindingAdapterPosition
        val toPosition = target.bindingAdapterPosition
        adapter.moveItem(fromPosition, toPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        adapter.removeItem(position)
    }

    override fun isLongPressDragEnabled(): Boolean {
        // We handle drag start manually via the drag handle
        return false
    }

    override fun isItemViewSwipeEnabled(): Boolean {
        // Only allow swipe in edit mode
        return adapter.isEditMode()
    }

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        // Only allow drag in edit mode
        val dragFlags = if (adapter.isEditMode()) {
            ItemTouchHelper.UP or ItemTouchHelper.DOWN
        } else {
            0
        }

        val swipeFlags = if (adapter.isEditMode()) {
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        } else {
            0
        }

        return makeMovementFlags(dragFlags, swipeFlags)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

        val itemView = viewHolder.itemView

        // Draw delete background when swiping
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            if (deleteIcon == null) {
                deleteIcon = ContextCompat.getDrawable(
                    recyclerView.context,
                    android.R.drawable.ic_menu_delete
                )
                deleteIcon?.setTint(Color.WHITE)
            }

            val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
            val iconTop = itemView.top + iconMargin
            val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)

            when {
                dX > 0 -> { // Swiping to the right
                    deleteBackground.setBounds(
                        itemView.left,
                        itemView.top,
                        itemView.left + dX.toInt(),
                        itemView.bottom
                    )
                    val iconLeft = itemView.left + iconMargin
                    val iconRight = iconLeft + (deleteIcon?.intrinsicWidth ?: 0)
                    deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                }
                dX < 0 -> { // Swiping to the left
                    deleteBackground.setBounds(
                        itemView.right + dX.toInt(),
                        itemView.top,
                        itemView.right,
                        itemView.bottom
                    )
                    val iconRight = itemView.right - iconMargin
                    val iconLeft = iconRight - (deleteIcon?.intrinsicWidth ?: 0)
                    deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                }
                else -> {
                    deleteBackground.setBounds(0, 0, 0, 0)
                }
            }

            deleteBackground.draw(c)
            deleteIcon?.draw(c)
        }
    }
}