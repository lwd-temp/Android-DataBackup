package com.xayah.databackup.fragment.restore

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.ListPopupWindow
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xayah.databackup.App
import com.xayah.databackup.activity.list.AppListRestoreActivity
import com.xayah.databackup.activity.processing.ProcessingActivity
import com.xayah.databackup.data.AppInfoBaseNum
import com.xayah.databackup.util.*
import com.xayah.databackup.view.fastInitialize
import com.xayah.databackup.view.setLoading
import com.xayah.databackup.view.util.setWithConfirm
import kotlinx.coroutines.launch

class RestoreViewModel : ViewModel() {
    val _isInitialized by lazy {
        MutableLiveData(false)
    }
    private var isInitialized
        get() = _isInitialized.value!!
        set(value) = _isInitialized.postValue(value)

    // 是否第一次进入Fragment
    private val _isFirst by lazy {
        MutableLiveData(true)
    }
    private var isFirst
        get() = _isFirst.value!!
        set(value) = _isFirst.postValue(value)

    var lazyChipGroup = ObservableBoolean(true)
    var lazyList = ObservableBoolean(false)

    // 应用恢复列表
    private val appInfoRestoreList
        get() = App.appInfoList.value.filter { if (it.restoreList.isNotEmpty()) it.restoreList[it.restoreIndex].app || it.restoreList[it.restoreIndex].data else false }
            .toMutableList()
    private val appInfoRestoreListNum
        get() = run {
            val appInfoBaseNum = AppInfoBaseNum(0, 0)
            for (i in appInfoRestoreList) {
                if (i.restoreList[i.restoreIndex].app) appInfoBaseNum.appNum++
                if (i.restoreList[i.restoreIndex].data) appInfoBaseNum.dataNum++
            }
            appInfoBaseNum
        }
    var appNum = ObservableField("0")
    var dataNum = ObservableField("0")

    // 媒体备份列表
    val mediaInfoBackupList
        get() = App.mediaInfoBackupList.value

    // 媒体恢复列表
    val mediaInfoRestoreList
        get() = App.mediaInfoRestoreList.value

    var backupUser = ObservableField("${GlobalString.user}0")
    var restoreUser = ObservableField("${GlobalString.user}0")

    var autoFixMultiUserContextEnable = ObservableBoolean(false)
    var onResume = {}

    init {
        onResume = {
            if (isFirst) {
                isFirst = false
            } else {
                isInitialized = false
                lazyChipGroup.set(true)
            }
        }
        viewModelScope.launch {
            Command.checkLsZd().apply {
                autoFixMultiUserContextEnable.set(this)
                App.globalContext.saveAutoFixMultiUserContext(this)
            }
        }
    }

    fun onChangeBackupUser(v: View) {
        viewModelScope.launch {
            val context = v.context
            var items =
                if (Bashrc.listUsers().first) Bashrc.listUsers().second else mutableListOf(
                    "0"
                )
            // 加入备份目录用户集
            items.addAll(Command.listBackupUsers())
            // 去重排序
            items = items.toSortedSet().toMutableList()
            val choice = items.indexOf(App.globalContext.readBackupUser())

            ListPopupWindow(context).apply {
                fastInitialize(v, items.toTypedArray(), choice)
                setOnItemClickListener { _, _, position, _ ->
                    dismiss()
                    context.saveBackupUser(items[position])
                    backupUser.set("${GlobalString.user}${items[position]}")
                    onResume()
                }
                show()
            }
        }
    }

    fun onChangeRestoreUser(v: View) {
        viewModelScope.launch {
            val context = v.context
            val items =
                if (Bashrc.listUsers().first) Bashrc.listUsers().second.toTypedArray() else arrayOf(
                    "0"
                )
            val choice = items.indexOf(App.globalContext.readRestoreUser())

            ListPopupWindow(context).apply {
                fastInitialize(v, items, choice)
                setOnItemClickListener { _, _, position, _ ->
                    dismiss()
                    context.saveRestoreUser(items[position])
                    restoreUser.set("${GlobalString.user}${items[position]}")
                }
                show()
            }
        }
    }

    fun onSelectAppBtnClick(v: View) {
        v.context.startActivity(Intent(v.context, AppListRestoreActivity::class.java).apply {
            putExtra("isRestore", true)
        })
    }

    fun onRestoreAppBtnClick(v: View) {
        v.context.startActivity(Intent(v.context, ProcessingActivity::class.java).apply {
            putExtra("isRestore", true)
            putExtra("isMedia", false)
        })
    }

    fun onRestoreMediaBtnClick(v: View) {
        v.context.startActivity(Intent(v.context, ProcessingActivity::class.java).apply {
            putExtra("isRestore", true)
            putExtra("isMedia", true)
        })
    }

    fun onRestoreAppClearBtnClick(v: View) {
        val context = v.context
        MaterialAlertDialogBuilder(context).apply {
            setWithConfirm("${GlobalString.confirmRemoveAllAppAndDataThatBackedUp}${GlobalString.symbolQuestion}") {
                BottomSheetDialog(v.context).apply {
                    setLoading()
                    val that = this
                    viewModelScope.launch {
                        Command.rm("${Path.getBackupDataSavePath()} ${Path.getAppInfoRestoreListPath()}")
                            .apply {
                                if (this) {
                                    App.loadList()
                                    refresh()
                                    Toast.makeText(
                                        context, GlobalString.success, Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context, GlobalString.failed, Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        that.dismiss()
                    }
                }
            }
        }
    }

    suspend fun refresh() {
        // 加载列表
        setUser()
        appNum.set(appInfoRestoreListNum.appNum.toString())
        dataNum.set(appInfoRestoreListNum.dataNum.toString())
        isInitialized = true
    }

    private fun setUser() {
        backupUser.set("${GlobalString.user}${App.globalContext.readBackupUser()}")
        restoreUser.set("${GlobalString.user}${App.globalContext.readRestoreUser()}")
    }
}