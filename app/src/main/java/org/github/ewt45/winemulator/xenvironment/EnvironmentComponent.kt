package org.github.ewt45.winemulator.xenvironment

/**
 * EnvironmentComponent - 环境组件基类
 * 对应Winlator的EnvironmentComponent接口，所有环境组件都继承此类
 */
abstract class EnvironmentComponent {
    @JvmField
    var environment: XEnvironment? = null
    
    /**
     * 启动组件
     */
    abstract fun start()
    
    /**
     * 停止组件
     */
    abstract fun stop()
}