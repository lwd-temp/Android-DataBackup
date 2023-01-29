package com.xayah.databackup.util

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.xayah.databackup.App
import com.xayah.databackup.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class Command {
    companion object {
        const val TAG = "Command"

        private val storageStatsManager =
            App.globalContext.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager

        /**
         * 切换至IO协程运行
         */
        private suspend fun <T> runOnIO(block: suspend () -> T): T {
            return withContext(Dispatchers.IO) { block() }
        }

        /**
         * `ls -i`命令
         */
        suspend fun ls(path: String): Boolean {
            execute("ls -i \"${path}\"").apply {
                return this.isSuccess
            }
        }

        /**
         * 利用`ls`计数
         */
        suspend fun countFile(path: String): Int {
            execute("ls -i \"${path}\"").apply {
                return if (this.isSuccess)
                    this.out.size
                else
                    0
            }
        }

        /**
         * `rm -rf`命令, 用于删除文件, 可递归
         */
        suspend fun rm(path: String): Boolean {
            execute("rm -rf \"${path}\"").apply {
                return this.isSuccess
            }
        }

        /**
         * `mkdir`命令, 用于文件夹创建, 可递归
         */
        suspend fun mkdir(path: String): Boolean {
            if (execute("ls -i \"${path}\"").isSuccess)
                return true
            execute("mkdir -p \"${path}\"").apply {
                return this.isSuccess
            }
        }

        /**
         * Deprecated, `unzip`命令, 用于解压zip, 不兼容部分机型
         */
        @Deprecated("unzip", ReplaceWith(""))
        suspend fun unzip(filePath: String, outPath: String) {
            if (mkdir(outPath)) execute("unzip \"${filePath}\" -d \"${outPath}\"")
        }

        /**
         * `cp`命令, 用于复制
         */
        suspend fun cp(src: String, dst: String): Boolean {
            return execute("cp \"${src}\" \"${dst}\"").isSuccess
        }

        /**
         * 使用`net.lingala.zip4j`库解压zip
         */
        fun unzipByZip4j(filePath: String, outPath: String) {
            try {
                ZipFile(filePath).extractAll(outPath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 构建应用备份哈希表
         */
        suspend fun getAppInfoBackupMap(): AppInfoBackupMap {
            var appInfoBackupMap: AppInfoBackupMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                try {
                    SuFile(Path.getAppInfoBackupMapPath()).apply {
                        appInfoBackupMap =
                            GsonUtil.getInstance().fromAppInfoBackupMapJson(readText())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 根据本机应用调整列表
                val packageManager = App.globalContext.packageManager
                val userId = App.globalContext.readBackupUser()
                // 通过PackageManager获取所有应用信息
                val packages = packageManager.getInstalledPackages(0)
                // 获取指定用户的所有应用信息
                val listPackages = Bashrc.listPackages(userId).second
                for ((index, j) in listPackages.withIndex()) listPackages[index] =
                    j.replace("package:", "")
                for (i in packages) {
                    try {
                        // 自身或非指定用户应用
                        if (i.packageName == App.globalContext.packageName || listPackages.indexOf(i.packageName) == -1) continue
                        val isSystemApp =
                            (i.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                        val appIcon = i.applicationInfo.loadIcon(packageManager)
                        val appName = i.applicationInfo.loadLabel(packageManager).toString()
                        val versionName = i.versionName
                        val versionCode = i.longVersionCode
                        val packageName = i.packageName
                        val firstInstallTime = i.firstInstallTime

                        if (appInfoBackupMap.containsKey(packageName).not()) {
                            appInfoBackupMap[packageName] = AppInfoBackup()
                        }
                        val appInfoBackup = appInfoBackupMap[packageName]!!
                        appInfoBackup.apply {
                            this.detailBase.appIcon = appIcon
                            this.detailBase.appName = appName
                            this.detailBase.packageName = packageName
                            this.firstInstallTime = firstInstallTime
                            this.detailBackup.versionName = versionName
                            this.detailBackup.versionCode = versionCode
                            this.detailBase.isSystemApp = isSystemApp
                            this.isOnThisDevice = true
                        }
                        try {
                            storageStatsManager.queryStatsForPackage(
                                i.applicationInfo.storageUuid,
                                i.packageName,
                                Process.myUserHandle()
                            ).apply {
                                val storageStats =
                                    AppInfoStorageStats(appBytes, cacheBytes, dataBytes)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    storageStats.externalCacheBytes = externalCacheBytes
                                }
                                appInfoBackup.storageStats = storageStats
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            return appInfoBackupMap
        }

        /**
         * 构建应用恢复哈希表
         */
        suspend fun getAppInfoRestoreMap(): AppInfoRestoreMap {
            var appInfoRestoreMap: AppInfoRestoreMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                try {
                    SuFile(Path.getAppInfoRestoreMapPath()).apply {
                        appInfoRestoreMap =
                            GsonUtil.getInstance().fromAppInfoRestoreMapJson(readText())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 根据备份目录实际文件调整列表
                var hasApp = false
                var hasData = false
                execute("find \"${Path.getBackupDataSavePath()}\" -name \"*\" -type f").apply {
                    // 根据实际文件和配置调整RestoreList
                    for (i in appInfoRestoreMap) {
                        val tmpList = mutableListOf<AppInfoDetailRestore>()
                        for (j in i.value.detailRestoreList) {
                            if (this.out.toString().contains(j.date)) {
                                tmpList.add(j)
                            }
                        }
                        appInfoRestoreMap[i.key]!!.detailRestoreList = tmpList
                    }
                    if (isSuccess) {
                        this.out.add("///") // 添加尾部元素, 保证原尾部元素参与
                        var detailList = mutableListOf<AppInfoDetailRestore>()
                        for ((index, i) in this.out.withIndex()) {
                            try {
                                if (index < this.out.size - 1) {
                                    val info =
                                        i.replace(Path.getBackupDataSavePath(), "").split("/")
                                    val infoNext =
                                        this.out[index + 1].replace(
                                            Path.getBackupDataSavePath(),
                                            ""
                                        )
                                            .split("/")
                                    val packageName = info[1]
                                    val packageNameNext = infoNext[1]
                                    val date = info[2]
                                    val dateNext = infoNext[2]
                                    val fileName = info[3]
                                    if (info.size == 4) {
                                        if (fileName.contains("apk.tar"))
                                            hasApp = true
                                        else if (fileName.contains("data.tar"))
                                            hasData = true
                                        else if (fileName.contains("obb.tar"))
                                            hasData = true
                                        else if (fileName.contains("user.tar"))
                                            hasData = true
                                        else if (fileName.contains("user_de.tar"))
                                            hasData = true

                                        if (date != dateNext || packageName != packageNameNext) {
                                            // 与下一路径不同日期
                                            val detailListIndex =
                                                detailList.indexOfFirst { date == it.date }
                                            val detail =
                                                if (detailListIndex == -1) AppInfoDetailRestore().apply {
                                                    this.date = date
                                                } else detailList[detailListIndex]

                                            detail.apply {
                                                this.hasApp = this.hasApp && hasApp
                                                this.hasData = this.hasData && hasData
                                                this.selectApp = this.selectApp && hasApp
                                                this.selectData = this.selectData && hasData
                                            }

                                            if (detailListIndex == -1) detailList.add(detail)

                                            hasApp = false
                                            hasData = false
                                        }
                                        if (packageName != packageNameNext) {
                                            // 与下一路径不同包名
                                            // 寻找已保存的数据
                                            var isRetrieved = false
                                            if (appInfoRestoreMap.containsKey(packageName).not()) {
                                                appInfoRestoreMap[packageName] = AppInfoRestore()
                                                isRetrieved = true
                                            }
                                            val appInfoRestore = appInfoRestoreMap[packageName]!!

                                            appInfoRestore.apply {
                                                if (isRetrieved) this.detailBase.appName =
                                                    GlobalString.appRetrieved
                                                this.detailBase.packageName = packageName
                                                this.detailRestoreList = detailList
                                            }

                                            hasApp = false
                                            hasData = false
                                            detailList = mutableListOf()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
            return appInfoRestoreMap
        }

        /**
         * 构建媒体备份哈希表
         */
        suspend fun getMediaInfoBackupMap(): MediaInfoBackupMap {
            var mediaInfoBackupMap: MediaInfoBackupMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                try {
                    SuFile(Path.getMediaInfoBackupMapPath()).apply {
                        mediaInfoBackupMap =
                            GsonUtil.getInstance().fromMediaInfoBackupMapJson(readText())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 如果为空, 添加默认路径
                if (mediaInfoBackupMap.isEmpty()) {
                    val nameList = listOf("Pictures", "Download", "Music", "DCIM")
                    val pathList = listOf(
                        "/storage/emulated/0/Pictures",
                        "/storage/emulated/0/Download",
                        "/storage/emulated/0/Music",
                        "/storage/emulated/0/DCIM"
                    )
                    for ((index, _) in nameList.withIndex()) {
                        mediaInfoBackupMap[nameList[index]] = MediaInfoBackup().apply {
                            this.name = nameList[index]
                            this.path = pathList[index]
                            this.backupDetail.apply {
                                this.data = false
                                this.size = ""
                                this.date = ""
                            }
                        }
                    }
                }
            }
            return mediaInfoBackupMap
        }

        /**
         * 构建媒体恢复哈希表
         */
        suspend fun getMediaInfoRestoreMap(): MediaInfoRestoreMap {
            var mediaInfoRestoreMap: MediaInfoRestoreMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                try {
                    SuFile(Path.getMediaInfoRestoreMapPath()).apply {
                        mediaInfoRestoreMap =
                            GsonUtil.getInstance().fromMediaInfoRestoreMapJson(readText())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 根据备份目录实际文件调整列表
                var hasData = false
                execute("find \"${Path.getBackupMediaSavePath()}\" -name \"*\" -type f").apply {
                    // 根据实际文件和配置调整RestoreList
                    for (i in mediaInfoRestoreMap) {
                        val tmpList = mutableListOf<MediaInfoDetailBase>()
                        for (j in i.value.detailRestoreList) {
                            if (this.out.toString().contains(j.date)) {
                                tmpList.add(j)
                            }
                        }
                        mediaInfoRestoreMap[i.key]!!.detailRestoreList = tmpList
                    }
                    if (isSuccess) {
                        this.out.add("///") // 添加尾部元素, 保证原尾部元素参与
                        var detailList = mutableListOf<MediaInfoDetailBase>()
                        for ((index, i) in this.out.withIndex()) {
                            if (index < this.out.size - 1) {
                                val info = i.replace(Path.getBackupMediaSavePath(), "").split("/")
                                val infoNext =
                                    this.out[index + 1].replace(Path.getBackupMediaSavePath(), "")
                                        .split("/")
                                val name = info[1]
                                val nameNext = infoNext[1]
                                val date = info[2]
                                val dateNext = infoNext[2]
                                val fileName = info[3]
                                if (info.size == 4) {
                                    if (fileName.contains("${name}.tar"))
                                        hasData = true

                                    if (date != dateNext || name != nameNext) {
                                        // 与下一路径不同日期
                                        val detailListIndex =
                                            detailList.indexOfFirst { date == it.date }
                                        val detail =
                                            if (detailListIndex == -1) MediaInfoDetailBase().apply {
                                                this.data = false
                                                this.size = ""
                                                this.date = ""
                                            } else detailList[detailListIndex]

                                        detail.apply {
                                            this.data = this.data && hasData
                                        }

                                        if (detailListIndex == -1) detailList.add(detail)

                                        hasData = false
                                    }
                                    if (name != nameNext) {
                                        // 与下一路径不同包名
                                        // 寻找已保存的数据
                                        if (mediaInfoRestoreMap.containsKey(name).not()) {
                                            mediaInfoRestoreMap[name] = MediaInfoRestore()
                                        }
                                        val mediaInfoRestore = mediaInfoRestoreMap[name]!!

                                        mediaInfoRestore.apply {
                                            this.name = name
                                            this.detailRestoreList = detailList
                                        }

                                        hasData = false
                                        detailList = mutableListOf()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return mediaInfoRestoreMap
        }

        /**
         * 读取备份记录
         */
        suspend fun getBackupInfoList(): BackupInfoList {
            var backupInfoList: BackupInfoList = mutableListOf()

            runOnIO {
                // 读取配置文件
                try {
                    SuFile(Path.getBackupInfoListPath()).apply {
                        backupInfoList =
                            GsonUtil.getInstance().fromBackupInfoListJson(readText())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return backupInfoList
        }

        /**
         * 释放资源文件
         */
        fun releaseAssets(mContext: Context, assetsPath: String, outName: String) {
            try {
                val assets = File(Path.getAppInternalFilesPath(), outName)
                if (!assets.exists()) {
                    val outStream = FileOutputStream(assets)
                    val inputStream = mContext.resources.assets.open(assetsPath)
                    inputStream.copyTo(outStream)
                    assets.setExecutable(true)
                    assets.setReadable(true)
                    assets.setWritable(true)
                    outStream.flush()
                    inputStream.close()
                    outStream.close()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        /**
         * 获取ABI
         */
        fun getABI(): String {
            val mABIs = Build.SUPPORTED_ABIS
            if (mABIs.isNotEmpty()) {
                return mABIs[0]
            }
            return ""
        }

        /**
         * 压缩
         */
        suspend fun compress(
            compressionType: String,
            dataType: String,
            packageName: String,
            outPut: String,
            dataPath: String,
            dataSize: String? = null,
            onAddLine: (line: String?) -> Unit = {}
        ): Boolean {
            var ret = true
            var update = true
            val filePath = if (dataType == "media") {
                "${outPut}/${packageName}.${getSuffixByCompressionType(compressionType)}"
            } else {
                "${outPut}/${dataType}.${getSuffixByCompressionType(compressionType)}"
            }

            runOnIO {
                if (App.globalContext.readBackupStrategy() == BackupStrategy.Cover) {
                    // 当备份策略为覆盖时, 计算目录大小并判断是否更新
                    if (dataType == "media") {
                        countSize(dataPath, 1).apply {
                            if (this == dataSize) {
                                update = false
                            }
                        }
                    } else {
                        countSize(
                            "${dataPath}/${packageName}", 1
                        ).apply {
                            if (this == dataSize) {
                                update = false
                            }
                        }
                    }
                    // 检测是否实际存在压缩包, 若不存在则仍然更新
                    ls(filePath).apply {
                        if (!this) update = true
                    }
                }
                if (update) {
                    onAddLine(ProcessCompressing)
                    Bashrc.compress(
                        compressionType, dataType, packageName, outPut, dataPath
                    ) { onAddLine(it) }.apply {
                        if (!this.first) {
                            ret = false
                        }
                    }
                } else {
                    onAddLine(ProcessSkip)
                }
                // 检测是否生成压缩包
                ls(filePath).apply {
                    if (!this) ret = false
                    else {
                        if (App.globalContext.readIsBackupTest()) {
                            // 校验
                            onAddLine(ProcessTesting)
                            ret = testArchive(compressionType, filePath)
                        }
                    }
                }
            }
            onAddLine(ProcessFinished)
            return ret
        }

        /**
         * 压缩APK
         */
        suspend fun compressAPK(
            compressionType: String,
            packageName: String,
            outPut: String,
            userId: String,
            apkSize: String? = null,
            onAddLine: (line: String?) -> Unit = {}
        ): Boolean {
            var ret = true
            var update = true
            val filePath = "${outPut}/apk.${getSuffixByCompressionType(compressionType)}"
            runOnIO {
                val apkPathPair = Bashrc.getAPKPath(packageName, userId).apply { ret = this.first }
                if (App.globalContext.readBackupStrategy() == BackupStrategy.Cover) {
                    // 当备份策略为覆盖时, 计算目录大小并判断是否更新
                    countSize(
                        apkPathPair.second, 1
                    ).apply {
                        if (this == apkSize) {
                            update = false
                        }
                    }
                    // 检测是否实际存在压缩包, 若不存在则仍然更新
                    ls(filePath).apply {
                        // 后续若直接令state = this会导致state非正常更新
                        if (!this) update = true
                    }
                }
                if (update) {
                    onAddLine(ProcessCompressing)
                    Bashrc.cd(apkPathPair.second).apply { ret = this.first }
                    Bashrc.compressAPK(compressionType, outPut) {
                        onAddLine(it)
                    }.apply { ret = this.first }
                    Bashrc.cd("/").apply { ret = this.first }
                } else {
                    onAddLine(ProcessSkip)
                }
                // 检测是否生成压缩包
                ls(filePath).apply {
                    // 后续若直接令state = this会导致state非正常更新
                    if (!this) ret = false
                    else {
                        if (App.globalContext.readIsBackupTest()) {
                            // 校验
                            onAddLine(ProcessTesting)
                            ret = testArchive(compressionType, filePath)
                        }
                    }
                }
            }
            onAddLine(ProcessFinished)
            return ret
        }

        /**
         * 解压
         */
        suspend fun decompress(
            compressionType: String,
            dataType: String,
            inputPath: String,
            packageName: String,
            dataPath: String,
            onAddLine: (line: String?) -> Unit = {}
        ): Boolean {
            var ret = true
            runOnIO {
                onAddLine(ProcessDecompressing)
                Bashrc.decompress(
                    compressionType,
                    dataType,
                    inputPath,
                    packageName,
                    dataPath
                ) { onAddLine(it) }.apply { ret = this.first }
            }
            onAddLine(ProcessFinished)
            return ret
        }

        /**
         * 安装APK
         */
        suspend fun installAPK(
            inPath: String,
            packageName: String,
            userId: String,
            versionCode: String,
            onAddLine: (line: String?) -> Unit = {}
        ): Boolean {
            var ret = true
            runOnIO {
                val appVersionCode = getAppVersionCode(userId, packageName)
                ret = versionCode < appVersionCode
                // 禁止APK验证
                Bashrc.setInstallEnv()
                // 安装APK
                onAddLine(ProcessInstallingApk)
                Bashrc.installAPK(inPath, packageName, userId) {
                    onAddLine(it)
                }.apply {
                    ret = when (this.first) {
                        0 -> true
                        else -> {
                            false
                        }
                    }
                }
            }
            onAddLine(ProcessFinished)
            return ret
        }

        /**
         * 配置Owner以及SELinux相关
         */
        suspend fun setOwnerAndSELinux(
            dataType: String,
            packageName: String,
            path: String,
            userId: String,
            context: String,
            onAddLine: (line: String?) -> Unit = {}
        ) {
            onAddLine(ProcessSettingSELinux)
            Bashrc.setOwnerAndSELinux(
                dataType,
                packageName,
                path,
                userId,
                App.globalContext.readAutoFixMultiUserContext(),
                context
            )
                .apply {
                    if (!this.first) {
                        return
                    }
                }
            onAddLine(ProcessFinished)
        }

        /**
         * 获取应用版本代码
         */
        private suspend fun getAppVersionCode(userId: String, packageName: String): String {
            Bashrc.getAppVersionCode(userId, packageName).apply {
                if (!this.first) {
                    return ""
                }
                return this.second
            }
        }

        /**
         * 测试压缩包
         */
        private suspend fun testArchive(
            compressionType: String,
            inputPath: String,
        ): Boolean {
            Bashrc.testArchive(compressionType, inputPath).apply {
                if (!this.first) {
                    return false
                }
            }
            return true
        }

        /**
         * 备份自身
         */
        suspend fun backupItself(
            packageName: String, outPut: String, userId: String
        ): Boolean {
            mkdir(outPut)
            val apkPath = Bashrc.getAPKPath(packageName, userId)
            val apkPathPair = apkPath.apply {
                if (!this.first) {
                    return false
                }
            }
            val apkSize = countSize("${outPut}/DataBackup.apk", 1)
            countSize(apkPathPair.second, 1).apply {
                if (this == apkSize) {
                    return true
                }
            }
            cp("${apkPathPair.second}/base.apk", "${outPut}/DataBackup.apk").apply {
                if (!this) {
                    return false
                }
            }
            return true
        }

        /**
         * 计算大小(占用)
         */
        suspend fun countSize(path: String, type: Int = 0): String {
            Bashrc.countSize(path, type).apply {
                return if (!this.first) "0"
                else if (this.second.isEmpty()) "0"
                else this.second
            }
        }

        /**
         * 检查ROOT
         */
        suspend fun checkRoot(): Boolean {
            return execute("ls /").isSuccess && Shell.rootAccess()
        }

        /**
         * 检查二进制文件
         */
        suspend fun checkBin(): Boolean {
            execute("ls -l \"${Path.getAppInternalFilesPath()}/bin\"").out.apply {
                var count = 0
                try {
                    val fileList = this.subList(1, this.size)
                    for (i in fileList) if (i.contains("-rwxrwxrwx")) count++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return count == 4
            }
        }

        /**
         * 检查Bash环境
         */
        suspend fun checkBashrc(): Boolean {
            return execute("check_bashrc").isSuccess
        }

        /**
         * 获取本应用版本名称
         */
        fun getVersion(): String {
            return App.globalContext.packageManager.getPackageInfo(
                App.globalContext.packageName, 0
            ).versionName
        }

        /**
         * 获取日期, `timeStamp`为空时获取当前日期, 否则为时间戳转日期
         */
        fun getDate(timeStamp: String = ""): String {
            var date = ""
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
                    date = if (timeStamp == "") {
                        format(Date())
                    } else {
                        format(Date(timeStamp.toLong()))
                    }
                }
            } catch (e: Exception) {
                date = timeStamp
                e.printStackTrace()
            }
            return date
        }

        /**
         * 通过路径解析压缩方式
         */
        suspend fun getCompressionTypeByPath(path: String): String {
            execute("ls \"$path\"").out.joinToLineString.apply {
                return try {
                    when (this.split("/").last().split(".").last()) {
                        "tar" -> "tar"
                        "lz4" -> "lz4"
                        "zst" -> "zstd"
                        else -> ""
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
            }
        }

        /**
         * 通过压缩方式得到后缀
         */
        fun getSuffixByCompressionType(type: String): String {
            return when (type) {
                "tar" -> "tar"
                "lz4" -> "tar.lz4"
                "zstd" -> "tar.zst"
                else -> ""
            }
        }

        /**
         * 经由Log封装的执行函数
         */
        suspend fun execute(
            cmd: String,
            isAddToLog: Boolean = true,
            callback: ((line: String) -> Unit)? = null
        ): Shell.Result {
            val result = runOnIO {
                if (isAddToLog)
                    Logcat.getInstance().addLine("SHELL_IN: $cmd")
                val shell = Shell.cmd(cmd)
                callback?.apply {
                    val callbackList: CallbackList<String?> = object : CallbackList<String?>() {
                        override fun onAddElement(line: String?) {
                            line?.apply {
                                if (isAddToLog)
                                    Logcat.getInstance().addLine("SHELL_OUT: $line")
                                callback(line)
                            }
                        }
                    }
                    shell.to(callbackList)
                }
                shell.exec().apply {
                    if (isAddToLog)
                        this.apply {
                            for (i in this.out) Logcat.getInstance().addLine("SHELL_OUT: $i")
                        }
                }
            }
            return result
        }

        /**
         * 检查`ls -Zd`命令是否可用
         */
        suspend fun checkLsZd(): Boolean {
            return execute("ls -Zd").isSuccess
        }

        /**
         * 列出备份用户
         */
        suspend fun listBackupUsers(): MutableList<String> {
            val exec = execute("ls \"${Path.getBackupUserPath()}\"")
            val users = mutableListOf<String>()
            for (i in exec.out) {
                try {
                    i.toInt()
                    users.add(i)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                }
            }
            return users
        }
    }
}