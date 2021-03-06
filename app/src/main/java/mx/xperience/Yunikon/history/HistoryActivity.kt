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
package mx.xperience.Yunikon.history

import android.app.ProgressDialog
import android.content.ContentResolver
import android.content.ContentValues
import android.content.DialogInterface
import android.database.Cursor
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.loader.app.LoaderManager
import androidx.loader.content.CursorLoader
import androidx.loader.content.Loader
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.snackbar.Snackbar
import mx.xperience.Yunikon.R
import mx.xperience.Yunikon.history.HistoryCallBack.OnDeleteListener
import mx.xperience.Yunikon.utils.UiUtils

class HistoryActivity : AppCompatActivity() {
    private var mEmptyView: View? = null
    private var mAdapter: HistoryAdapter? = null
    private val mAdapterDataObserver: AdapterDataObserver = object : AdapterDataObserver() {
        override fun onChanged() {
            updateHistoryView(mAdapter!!.itemCount == 0)
        }
    }

    override fun onCreate(savedInstance: Bundle?) {
        super.onCreate(savedInstance)
        setContentView(R.layout.activity_history)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
        val list = findViewById<RecyclerView>(R.id.history_list)
        mEmptyView = findViewById(R.id.history_empty_layout)
        mAdapter = HistoryAdapter(this)
        val loader = LoaderManager.getInstance(this)
        loader.initLoader(0, null, object : LoaderManager.LoaderCallbacks<Cursor> {
            override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor> {
                return CursorLoader(this@HistoryActivity, HistoryProvider.Columns.CONTENT_URI,
                        null, null, null, HistoryProvider.Columns.TIMESTAMP + " DESC")
            }

            override fun onLoadFinished(loader: Loader<Cursor>, data: Cursor?) {
                mAdapter!!.swapCursor(data)
            }

            override fun onLoaderReset(loader: Loader<Cursor>) {
                mAdapter?.swapCursor(null)
            }
        })
        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(HistoryAnimationDecorator(this))
        list.itemAnimator = DefaultItemAnimator()
        list.adapter = mAdapter
        mAdapter?.registerAdapterDataObserver(mAdapterDataObserver)
        val helper = ItemTouchHelper(HistoryCallBack(this, object : OnDeleteListener {
            override fun onItemDeleted(data: ContentValues?) {
                Snackbar.make(findViewById(R.id.coordinator_layout),
                        R.string.history_snackbar_item_deleted, Snackbar.LENGTH_LONG)
                        .setAction(R.string.history_snackbar_item_deleted_message) {
                            contentResolver.insert(HistoryProvider.Columns.CONTENT_URI, data)
                        }
                        .show()
            }
        }))
        helper.attachToRecyclerView(list)
        val listTop = list.top
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val elevate = recyclerView.getChildAt(0) != null &&
                        recyclerView.getChildAt(0).top < listTop
                toolbar.elevation = if (elevate) UiUtils.dpToPx(resources,
                        resources.getDimension(R.dimen.toolbar_elevation)) else 0f
            }
        })
    }

    public override fun onDestroy() {
        mAdapter!!.unregisterAdapterDataObserver(mAdapterDataObserver)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.menu_history_delete) {
            return super.onOptionsItemSelected(item)
        }
        AlertDialog.Builder(this)
                .setTitle(R.string.history_delete_title)
                .setMessage(R.string.history_delete_message)
                .setPositiveButton(R.string.history_delete_positive
                ) { dialog: DialogInterface?, which: Int -> deleteAll() }
                .setNegativeButton(android.R.string.cancel) { d: DialogInterface, w: Int -> d.dismiss() }
                .show()
        return true
    }

    private fun updateHistoryView(empty: Boolean) {
        mEmptyView!!.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun deleteAll() {
        val dialog = ProgressDialog(this)
        dialog.setTitle(getString(R.string.history_delete_title))
        dialog.setMessage(getString(R.string.history_deleting_message))
        dialog.setCancelable(false)
        dialog.isIndeterminate = true
        dialog.show()
        DeleteAllHistoryTask(contentResolver, dialog).execute()
    }

    private class DeleteAllHistoryTask internal constructor(private val contentResolver: ContentResolver, private val dialog: ProgressDialog) : AsyncTask<Void?, Void?, Void?>() {
        protected override fun doInBackground(vararg params: Void?): Void? {
            contentResolver.delete(HistoryProvider.Columns.Companion.CONTENT_URI, null, null)
            return null
        }

        override fun onPostExecute(v: Void?) {
            Handler().postDelayed({ dialog.dismiss() }, 1000)
        }
    }
}