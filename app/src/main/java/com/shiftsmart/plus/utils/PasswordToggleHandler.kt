package com.shiftsmart.plus.utils

import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import com.shiftsmart.plus.R

class PasswordToggleHandler(private val editText: EditText, private val imageView: ImageView) {

    private var isPasswordVisible = false

    fun setupPasswordToggle() {
        imageView.setOnClickListener {
            isPasswordVisible = !isPasswordVisible

            if (isPasswordVisible) {
                // Show password
                editText.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                imageView.setImageResource(R.drawable.ic_visibility) // Change icon to 'eye open'
            } else {
                // Hide password
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                imageView.setImageResource(R.drawable.ic_visibility_off) // Change icon to 'eye closed'
            }

            // Move cursor to the end
            editText.setSelection(editText.text.length)
        }
    }
}
