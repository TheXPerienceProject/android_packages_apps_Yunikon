/*
 * Copyright (C) 2017 The LineageOS Project
 * Copyright (C) 2021 The XPerience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mx.xperience.Yunikon.ui

import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebView.FindListener
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import mx.xperience.Yunikon.utils.UiUtils.hideKeyboard
import mx.xperience.Yunikon.utils.UiUtils.setImageButtonEnabled
import mx.xperience.Yunikon.utils.UiUtils.showKeyboard

class SearchBarController(private val mWebView: WebView, private val mEditor: EditText, private val mStatus: TextView,
                          private val mPrevButton: ImageButton, private val mNextButton: ImageButton,
                          private val mCancelButton: ImageButton, private val mListener: OnCancelListener) : TextWatcher, OnEditorActionListener, FindListener, View.OnClickListener {
    private var mHasStartedSearch = false
    private var mCurrentResultPosition = 0
    private var mTotalResultCount = 0
    fun onShow() {
        mEditor.requestFocus()
        showKeyboard(mEditor)
        clearSearchResults()
        updateNextAndPrevButtonEnabledState()
        updateStatusText()
    }

    fun onCancel() {
        mStatus.text = null
        mWebView.clearMatches()
        mListener.onCancelSearch()
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable) {
        startSearch()
        updateNextAndPrevButtonEnabledState()
    }

    override fun onEditorAction(view: TextView, actionId: Int, event: KeyEvent): Boolean {
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
            hideKeyboard(view)
            startSearch()
            return true
        }
        return false
    }

    override fun onFindResultReceived(activeMatchOrdinal: Int, numberOfMatches: Int,
                                      isDoneCounting: Boolean) {
        mCurrentResultPosition = activeMatchOrdinal
        mTotalResultCount = numberOfMatches
        updateNextAndPrevButtonEnabledState()
        updateStatusText()
    }

    override fun onClick(view: View) {
        hideKeyboard(mEditor)
        if (view === mCancelButton) {
            onCancel()
        } else if (!mHasStartedSearch) {
            startSearch()
        } else {
            mWebView.findNext(view === mNextButton)
        }
    }

    private fun startSearch() {
        val query = query
        if (TextUtils.isEmpty(query)) {
            clearSearchResults()
            mStatus.text = null
        } else {
            mWebView.findAllAsync(query!!)
            mHasStartedSearch = true
        }
        updateStatusText()
    }

    private fun clearSearchResults() {
        mCurrentResultPosition = -1
        mTotalResultCount = -1
        mWebView.clearMatches()
        mHasStartedSearch = false
    }

    private fun updateNextAndPrevButtonEnabledState() {
        val hasText = !TextUtils.isEmpty(query)
        setImageButtonEnabled(mPrevButton,
                hasText && (!mHasStartedSearch || mCurrentResultPosition > 0))
        setImageButtonEnabled(mNextButton,
                hasText && (!mHasStartedSearch || mCurrentResultPosition < mTotalResultCount - 1))
    }

    private fun updateStatusText() {
        if (mTotalResultCount > 0) {
            mStatus.text = (mCurrentResultPosition + 1).toString() + "/" + mTotalResultCount
        } else {
            mStatus.text = null
        }
    }

    private val query: String?
        private get() {
            val s = mEditor.text
            return s?.toString()
        }

    interface OnCancelListener {
        fun onCancelSearch()
    }

    init {
        mEditor.addTextChangedListener(this)
        mEditor.setOnEditorActionListener(this)
        mWebView.setFindListener(this)
        mPrevButton.setOnClickListener(this)
        mNextButton.setOnClickListener(this)
        mCancelButton.setOnClickListener(this)
    }
}