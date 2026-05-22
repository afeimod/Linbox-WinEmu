package org.github.ewt45.winemulator.xenvironment

import android.content.Context
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import java.io.File
import java.util.Iterator

/**
 * XEnvironment - Wine运行环境管理器
 * 对应Winlator的XEnvironment类，管理环境组件的生命周期
 */
class XEnvironment(
    private val context: Context,
    private val imageFs: ImageFs
) : Iterable<EnvironmentComponent> {
    
    private val components = ArrayList<EnvironmentComponent>()
    private var tmpDir: File? = null
    
    /**
     * 获取Context
     */
    fun getContext(): Context = context
    
    /**
     * 获取ImageFs
     */
    fun getImageFs(): ImageFs = imageFs
    
    /**
     * 添加环境组件
     */
    fun addComponent(component: EnvironmentComponent) {
        component.environment = this
        components.add(component)
    }
    
    /**
     * 获取指定类型的组件
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : EnvironmentComponent> getComponent(componentClass: Class<T>): T? {
        for (component in components) {
            if (component.javaClass == componentClass) return component as T
        }
        return null
    }
    
    override fun iterator(): Iterator<EnvironmentComponent> = components.iterator()
    
    /**
     * 获取临时目录
     */
    fun getTmpDir(): File {
        if (tmpDir == null) {
            tmpDir = File(context.filesDir, "tmp")
            if (!tmpDir!!.isDirectory) {
                tmpDir!!.mkdirs()
                Utils.chmod(tmpDir!!, "0771")
            }
        }
        return tmpDir!!
    }
    
    /**
     * 启动所有环境组件
     */
    fun startEnvironmentComponents() {
        Utils.Files.clearDirectory(getTmpDir())
        for (component in this) {
            component.start()
        }
    }
    
    /**
     * 停止所有环境组件
     */
    fun stopEnvironmentComponents() {
        for (component in this) {
            component.stop()
        }
    }
    
    /**
     * 暂停（生命周期回调）
     */
    fun onPause() {
        val launcher = getComponent(GlibcProgramLauncherComponent::class.java)
        launcher?.suspendProcess()
    }
    
    /**
     * 恢复（生命周期回调）
     */
    fun onResume() {
        val launcher = getComponent(GlibcProgramLauncherComponent::class.java)
        launcher?.resumeProcess()
    }
}