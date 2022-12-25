package com.xayah.databackup.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.xayah.databackup.App
import com.xayah.databackup.data.AppInfo
import com.xayah.databackup.data.AppInfoItem
import com.xayah.databackup.data.BackupInfo
import com.xayah.databackup.data.MediaInfo
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

        /**
         * 切换至IO协程运行
         */
        private suspend fun <T> runOnIO(block: suspend () -> T): T {
            return withContext(Dispatchers.IO) { block() }
        }

        /**
         * `cat`命令, 用于文件读取
         */
        private suspend fun cat(path: String): Pair<Boolean, String> {
            val exec = execute("cat $path", false)
            return Pair(exec.isSuccess, exec.out.joinToString(separator = "\n"))
        }

        /**
         * `ls -i`命令
         */
        suspend fun ls(path: String): Boolean {
            execute("ls -i $path").apply {
                return this.isSuccess
            }
        }

        /**
         * 利用`ls`计数
         */
        suspend fun countFile(path: String): Int {
            execute("ls -i $path").apply {
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
            execute("rm -rf $path").apply {
                return this.isSuccess
            }
        }

        /**
         * `mkdir`命令, 用于文件夹创建, 可递归
         */
        suspend fun mkdir(path: String): Boolean {
            if (execute("ls -i $path").isSuccess)
                return true
            execute("mkdir -p $path").apply {
                return this.isSuccess
            }
        }

        /**
         * Deprecated, `unzip`命令, 用于解压zip, 不兼容部分机型
         */
        @Deprecated("unzip", ReplaceWith(""))
        suspend fun unzip(filePath: String, outPath: String) {
            if (mkdir(outPath)) execute("unzip $filePath -d $outPath")
        }

        /**
         * `cp`命令, 用于复制
         */
        private suspend fun cp(src: String, dst: String): Boolean {
            return execute("cp $src $dst").isSuccess
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
         * 构建应用列表
         */
        suspend fun getAppInfoList(): MutableList<AppInfo> {
            val appInfoList = mutableListOf<AppInfo>()

            runOnIO {
                // 读取应用列表配置文件
                cat(Path.getAppInfoListPath()).apply {
                    if (this.first) {
                        val jsonArray = JSON.stringToJsonArray(this.second)
                        for (i in jsonArray) {
                            val item = JSON.jsonElementToEntity(i, AppInfo::class.java) as AppInfo
                            appInfoList.add(item)
                        }
                    }
                }

                // 根据备份目录实际文件调整列表
                var hasApp = false
                var hasData = false
                execute("find ${Path.getBackupDataSavePath()} -name \"*\" -type f").apply {
                    if (isSuccess) {
                        this.out.add("///") // 添加尾部元素, 保证原尾部元素参与

                        var restoreList = mutableListOf<AppInfoItem>()

                        for ((index, i) in this.out.withIndex()) {
                            if (index < this.out.size - 1) {
                                val info = i.replace(Path.getBackupDataSavePath(), "").split("/")
                                val infoNext =
                                    this.out[index + 1].replace(Path.getBackupDataSavePath(), "")
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

                                    if (date != dateNext) {
                                        // 与下一路径不同日期
                                        // 尝试获取原列表数据
                                        if (restoreList.isEmpty()) {
                                            val appInfoIndex =
                                                appInfoList.indexOfFirst { packageName == it.packageName }
                                            if (appInfoIndex != -1) {
                                                restoreList =
                                                    appInfoList[appInfoIndex].restoreList
                                            }
                                        }

                                        val restoreListIndex =
                                            restoreList.indexOfFirst { date == it.date }
                                        val restore = if (restoreListIndex == -1) AppInfoItem(
                                            app = false,
                                            data = false,
                                            hasApp = true,
                                            hasData = true,
                                            versionName = "",
                                            versionCode = 0,
                                            appSize = "",
                                            userSize = "",
                                            userDeSize = "",
                                            dataSize = "",
                                            obbSize = "",
                                            date = date
                                        ) else restoreList[restoreListIndex]

                                        restore.apply {
                                            this.hasApp = this.hasApp && hasApp
                                            this.hasData = this.hasData && hasData
                                            this.app = this.app && hasApp
                                            this.data = this.data && hasData
                                        }

                                        if (restoreListIndex == -1) restoreList.add(restore)

                                        hasApp = false
                                        hasData = false
                                    }
                                    if (packageName != packageNameNext) {
                                        // 与下一路径不同包名
                                        // 寻找已保存的数据
                                        val appInfoIndex =
                                            appInfoList.indexOfFirst { packageName == it.packageName }
                                        val appInfo = if (appInfoIndex == -1)
                                            AppInfo(
                                                appName = "",
                                                packageName = "",
                                                isSystemApp = false,
                                                firstInstallTime = 0,
                                                backup = AppInfoItem(
                                                    app = false,
                                                    data = false,
                                                    hasApp = true,
                                                    hasData = true,
                                                    versionName = "",
                                                    versionCode = 0,
                                                    appSize = "",
                                                    userSize = "",
                                                    userDeSize = "",
                                                    dataSize = "",
                                                    obbSize = "",
                                                    date = ""
                                                ),
                                                _restoreIndex = -1,
                                                restoreList = mutableListOf(),
                                                appIconString = "",
                                            ) else appInfoList[appInfoIndex]

                                        val appName = GlobalString.appRetrieved
                                        appInfo.apply {
                                            this.appName = appName
                                            this.packageName = packageName
                                            this.isOnThisDevice = false
                                            this.restoreList = restoreList
                                        }

                                        if (appInfoIndex == -1) appInfoList.add(appInfo)

                                        hasApp = false
                                        hasData = false
                                        restoreList = mutableListOf()
                                    }
                                }
                            }
                        }
                    }
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

                        // 寻找已保存的数据
                        val appInfoIndex =
                            appInfoList.indexOfFirst { i.packageName == it.packageName }
                        val appInfo = if (appInfoIndex == -1)
                            AppInfo(
                                appName = "",
                                packageName = "",
                                isSystemApp = isSystemApp,
                                firstInstallTime = 0,
                                backup = AppInfoItem(
                                    app = false,
                                    data = false,
                                    hasApp = true,
                                    hasData = true,
                                    versionName = "",
                                    versionCode = 0,
                                    appSize = "",
                                    userSize = "",
                                    userDeSize = "",
                                    dataSize = "",
                                    obbSize = "",
                                    date = ""
                                ),
                                _restoreIndex = -1,
                                restoreList = mutableListOf(),
                                appIconString = "",
                            ) else appInfoList[appInfoIndex]

                        val appIcon = i.applicationInfo.loadIcon(packageManager)
                        val appName = i.applicationInfo.loadLabel(packageManager).toString()
                        val versionName = i.versionName
                        val versionCode = i.longVersionCode
                        val packageName = i.packageName
                        val firstInstallTime = i.firstInstallTime
                        appInfo.apply {
                            this.appName = appName
                            this.packageName = packageName
                            this.firstInstallTime = firstInstallTime
                            this.appIcon = appIcon
                            this.backup.versionName = versionName
                            this.backup.versionCode = versionCode
                            this.isOnThisDevice = true
                        }
                        if (appInfoIndex == -1) appInfoList.add(appInfo)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            return appInfoList
        }

        /**
         * 列表操作, 添加或更新
         */
        fun addOrUpdateList(
            item: Any,
            dst: MutableList<Any>,
            callback: (item: Any) -> Boolean
        ) {
            val tmp = dst.find { callback(it) }
            val tmpIndex = dst.indexOf(tmp)
            if (tmpIndex == -1) dst.add(item)
            else dst[tmpIndex] = item
        }

        suspend fun getCachedMediaInfoBackupList(isFiltered: Boolean = false): MutableList<MediaInfo> {
            // 媒体备份列表
            var cachedMediaInfoList = mutableListOf<MediaInfo>()
            runOnIO {
                cat(Path.getMediaInfoBackupListPath()).apply {
                    if (this.first) {
                        try {
                            val jsonArray = JSON.stringToJsonArray(this.second)
                            for (i in jsonArray) {
                                val item =
                                    JSON.jsonElementToEntity(i, MediaInfo::class.java) as MediaInfo
                                cachedMediaInfoList.add(item)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                if (cachedMediaInfoList.isEmpty()) {
                    cachedMediaInfoList.add(
                        MediaInfo(
                            "Pictures", "/storage/emulated/0/Pictures", false, ""
                        )
                    )
                    cachedMediaInfoList.add(
                        MediaInfo(
                            "Download", "/storage/emulated/0/Download", false, ""
                        )
                    )
                    cachedMediaInfoList.add(
                        MediaInfo(
                            "Music",
                            "/storage/emulated/0/Music",
                            false,
                            ""
                        )
                    )
                    cachedMediaInfoList.add(
                        MediaInfo(
                            "DCIM",
                            "/storage/emulated/0/DCIM",
                            false,
                            ""
                        )
                    )
                }
                if (isFiltered) cachedMediaInfoList =
                    cachedMediaInfoList.filter { it.data }.toMutableList()
            }
            return cachedMediaInfoList
        }

        suspend fun getCachedMediaInfoRestoreList(): MutableList<MediaInfo> {
            // 根据媒体文件获取实际列表
            val cachedMediaInfoRestoreActualList = mutableListOf<MediaInfo>()
            val cachedMediaInfoRestoreList = mutableListOf<MediaInfo>()
            runOnIO {
                cat(Path.getMediaInfoRestoreListPath()).apply {
                    if (this.first) {
                        try {
                            val jsonArray = JSON.stringToJsonArray(this.second)
                            for (i in jsonArray) {
                                val item =
                                    JSON.jsonElementToEntity(i, MediaInfo::class.java) as MediaInfo
                                cachedMediaInfoRestoreList.add(item)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                execute("find ${Path.getBackupMediaSavePath()} -name \"*\" -type f").apply {
                    if (this.isSuccess) {
                        for (i in this.out) {
                            val info = i.split("/").last().split(".")
                            if (info.isNotEmpty()) {
                                val mediaInfo =
                                    MediaInfo(info[0], GlobalString.customDir, true, "-1")
                                val tmp = cachedMediaInfoRestoreList.find { it.name == info[0] }
                                val tmpIndex = cachedMediaInfoRestoreList.indexOf(tmp)
                                if (tmpIndex != -1) {
                                    mediaInfo.apply {
                                        data = cachedMediaInfoRestoreList[tmpIndex].data
                                    }
                                }
                                cachedMediaInfoRestoreActualList.add(mediaInfo)
                            }
                        }
                    }
                }
            }
            return cachedMediaInfoRestoreActualList
        }

        suspend fun getCachedBackupInfoList(): MutableList<BackupInfo> {
            val cachedBackupInfoList = mutableListOf<BackupInfo>()
            runOnIO {
                cat(Path.getBackupInfoListPath()).apply {
                    if (this.first) {
                        try {
                            val jsonArray = JSON.stringToJsonArray(this.second)
                            for (i in jsonArray) {
                                val item = JSON.jsonElementToEntity(
                                    i, BackupInfo::class.java
                                ) as BackupInfo
                                cachedBackupInfoList.add(item)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            return cachedBackupInfoList
        }

        fun extractAssets(mContext: Context, assetsPath: String, outName: String) {
            try {
                val assets = File(Path.getFilesDir(), outName)
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

        fun getABI(): String {
            val mABIs = Build.SUPPORTED_ABIS
            if (mABIs.isNotEmpty()) {
                return mABIs[0]
            }
            return ""
        }

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
            runOnIO {
                if (dataType == "media") {
                    countSize(dataPath, 1).apply {
                        if (this == dataSize) {
                            update = true
                        }
                    }
                } else {
                    countSize(
                        "${dataPath}/${packageName}", 1
                    ).apply {
                        if (this == dataSize) {
                            update = true
                        }
                    }
                }
                if (update) {
                    Bashrc.compress(
                        compressionType, dataType, packageName, outPut, dataPath
                    ) { onAddLine(it) }.apply {
                        if (!this.first) {
                            ret = false
                        }
                    }

                    // 检测是否生成压缩包
                    if (dataType == "media") {
                        ls("${outPut}/${packageName}.tar*").apply {
                            if (!this) ret = false
                        }
                    } else {
                        ls("${outPut}/${dataType}.tar*").apply {
                            if (!this) ret = false
                        }
                    }
                }
            }
            return ret
        }

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
            runOnIO {
                val apkPathPair = Bashrc.getAPKPath(packageName, userId).apply { ret = this.first }
                countSize(
                    apkPathPair.second, 1
                ).apply {
                    if (this == apkSize) {
                        update = true
                    }
                }
                if (update) {
                    Bashrc.cd(apkPathPair.second).apply { ret = this.first }
                    Bashrc.compressAPK(compressionType, outPut) {
                        onAddLine(it)
                    }.apply { ret = this.first }
                    Bashrc.cd("~").apply { ret = this.first }

                    ls("${outPut}/apk.tar*").apply {
                        // 后续若直接令state = this会导致state非正常更新
                        if (!this) ret = false
                    }
                }
            }
            return ret
        }

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
                Bashrc.decompress(
                    compressionType,
                    dataType,
                    inputPath,
                    packageName,
                    dataPath
                ) { onAddLine(it) }.apply { ret = this.first }
            }
            return ret
        }

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
            return ret
        }

        suspend fun setOwnerAndSELinux(
            dataType: String,
            packageName: String,
            path: String,
            userId: String,
        ) {
            Bashrc.setOwnerAndSELinux(
                dataType,
                packageName,
                path,
                userId,
                App.globalContext.readAutoFixMultiUserContext()
            )
                .apply {
                    if (!this.first) {
                        return
                    }
                }
        }

        private suspend fun getAppVersionCode(userId: String, packageName: String): String {
            Bashrc.getAppVersionCode(userId, packageName).apply {
                if (!this.first) {
                    return ""
                }
                return this.second
            }
        }

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

        suspend fun countSize(path: String, type: Int = 0): String {
            Bashrc.countSize(path, type).apply {
                return if (!this.first) "0"
                else if (this.second.isEmpty()) "0"
                else this.second
            }
        }

        suspend fun checkRoot(): Boolean {
            return execute("ls /").isSuccess && Shell.rootAccess()
        }

        suspend fun checkBin(): Boolean {
            execute("ls -l ${Path.getFilesDir()}/bin").out.apply {
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

        suspend fun checkBashrc(): Boolean {
            return execute("check_bashrc").isSuccess
        }

        fun getVersion(): String {
            return App.globalContext.packageManager.getPackageInfo(
                App.globalContext.packageName, 0
            ).versionName
        }

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
                e.printStackTrace()
            }
            return date
        }

        suspend fun getCompressionTypeByPath(path: String): String {
            execute("ls $path").out.joinToLineString.apply {
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

        suspend fun execute(
            cmd: String,
            isAddToLog: Boolean = true,
            callback: ((line: String) -> Unit)? = null
        ): Shell.Result {
            val result = runOnIO {
                if (isAddToLog)
                    App.logcat.addLine("SHELL_IN: $cmd")
                val shell = Shell.cmd(cmd)
                callback?.apply {
                    val callbackList: CallbackList<String?> = object : CallbackList<String?>() {
                        override fun onAddElement(line: String?) {
                            line?.apply {
                                if (isAddToLog)
                                    App.logcat.addLine("SHELL_OUT: $line")
                                callback(line)
                            }
                        }
                    }
                    shell.to(callbackList)
                }
                shell.exec().apply {
                    if (isAddToLog)
                        this.apply {
                            for (i in this.out) App.logcat.addLine("Shell_Out: $i")
                        }
                }
            }
            return result
        }

        suspend fun checkLsZd(): Boolean {
            return execute("ls -Zd").isSuccess
        }

        suspend fun listBackupUsers(): MutableList<String> {
            val exec = execute("ls ${Path.getBackupUserPath()}")
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