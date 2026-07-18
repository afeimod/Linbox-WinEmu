package org.github.ewt45.winemulator.glibcwine

import android.content.Context
import android.util.Log
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Utils
import org.json.JSONObject
import java.io.File

/**
 * Wine 容器管理器, 移植自 winlator-glibc 的 ContainerManager.java。
 *
 * 管理 wine 容器 (前缀) 的创建、列表、激活、删除。
 * 容器数据存储在 imagefs/home/xuser-<id>/ 下。
 * activateContainer() 创建 xuser -> xuser-<id> 符号链接。
 *
 * 注意: 不依赖 container_pattern.tzst 资源, 直接创建基本 wine prefix 目录结构。
 * wine 首次启动时会自动初始化 prefix。
 */
class WineContainerManager(private val context: Context) {
    private val TAG = "WineContainerManager"
    private val containers = mutableListOf<WineContainer>()
    private var maxContainerId = 0
    private val homeDir: File

    init {
        val rootDir = GlibcImageFs.find(context).rootDir
        homeDir = File(rootDir, "home")
        loadContainers()
    }

    fun getContainers(): List<WineContainer> = containers.toList()

    /**
     * 从 home 目录加载所有容器。
     */
    private fun loadContainers() {
        containers.clear()
        maxContainerId = 0

        if (!homeDir.isDirectory) return

        homeDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("${GlibcWineConsts.USER}-")) {
                try {
                    val id = file.name.replace("${GlibcWineConsts.USER}-", "").toInt()
                    val container = WineContainer(id, File(homeDir, "${GlibcWineConsts.USER}-$id"))
                    val configFile = container.getConfigFile()
                    if (configFile.exists()) {
                        val data = JSONObject(configFile.readText())
                        container.loadData(data)
                        containers.add(container)
                        maxContainerId = maxOf(maxContainerId, id)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "加载容器 ${file.name} 失败", e)
                }
            }
        }
    }

    /**
     * 激活容器: 创建 xuser -> xuser-<id> 符号链接。
     * wine 通过 HOME=/home/xuser 访问当前激活的容器。
     */
    fun activateContainer(container: WineContainer) {
        container.rootDir = File(homeDir, "${GlibcWineConsts.USER}-${container.id}")
        val linkFile = File(homeDir, GlibcWineConsts.USER)
        linkFile.delete()
        try {
            Utils.Files.symlink(container.rootDir, linkFile)
        } catch (e: Exception) {
            Log.e(TAG, "激活容器失败, 尝试直接创建目录", e)
            // 如果符号链接失败, 直接创建目录 (不理想但作为 fallback)
            linkFile.mkdirs()
        }
    }

    /**
     * 创建新容器。
     * 直接创建基本的 wine prefix 目录结构, 不依赖 container_pattern 资源。
     * wine 首次启动时会自动初始化完整的 prefix。
     * @param data 容器配置 JSON
     * @return 新容器, 失败返回 null
     */
    fun createContainer(data: JSONObject): WineContainer? {
        try {
            val id = maxContainerId + 1
            data.put("id", id)

            val containerDir = File(homeDir, "${GlibcWineConsts.USER}-$id")
            if (!containerDir.mkdirs()) {
                Log.e(TAG, "创建容器目录失败: ${containerDir.path}")
                return null
            }

            val container = WineContainer(id, containerDir)
            container.loadData(data)

            // 注意: 不手动创建 wine prefix 目录结构。
            // wine 首次运行 (wineboot) 时会自动初始化完整的 prefix
            // (注册表、系统 DLL、drive_c、dosdevices 等)。
            // 这里只创建容器目录本身和保存配置。

            container.saveData()
            maxContainerId++
            containers.add(container)
            Log.i(TAG, "容器创建成功: ${container.name} (id=$id)")
            return container
        } catch (e: Exception) {
            Log.e(TAG, "创建容器失败", e)
            return null
        }
    }

    /**
     * 删除容器。
     */
    fun removeContainer(container: WineContainer) {
        if (container.rootDir.deleteRecursively()) {
            containers.remove(container)
            Log.i(TAG, "容器已删除: ${container.name}")
        }
    }

    fun getContainerById(id: Int): WineContainer? = containers.find { it.id == id }

    fun getNextContainerId(): Int = maxContainerId + 1

    /**
     * 获取当前激活的容器 (通过 xuser 符号链接判断)。
     */
    fun getActivatedContainer(): WineContainer? {
        val linkFile = File(homeDir, GlibcWineConsts.USER)
        if (FileUtils.isSymlink(linkFile)) {
            try {
                val targetPath = linkFile.canonicalPath
                containers.forEach { container ->
                    if (container.rootDir.canonicalPath == targetPath) return container
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取激活容器失败", e)
            }
        }
        return null
    }
}
