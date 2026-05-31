package org.github.ewt45.winemulator.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 返回一个[SimpleTaskReporter]实例 */
@Composable
fun rememberTaskReporter(
    initStage: ProgressStage = ProgressStage.NOT_STARTED,
    progress: Int = 0,
    msgTitle: String = "",
    msg: String = ""
): SimpleTaskReporter {
    return remember { SimpleTaskReporter(initStage, progress, msgTitle, msg) }
}

/** 实现了 [TaskReporter] 的类。内含MutableState可以直接使用。建议使用 [rememberTaskReporter] 获取实例 */
class SimpleTaskReporter(
    initStage: ProgressStage,
    initProgress: Int,
    initMsgTitle: String,
    initMsg: String,
) : TaskReporter() {
    var stage by mutableStateOf(initStage)
    var progress by mutableIntStateOf(initProgress)
    var msgTitle by mutableStateOf(initMsgTitle)
    var msg by mutableStateOf(initMsg)
    override fun progress(percent: Float) {
        progress = (percent * 100).toInt()
    }

    override fun done(error: Exception?) {
        progress = 100
        if (error != null) throw error
    }

    override fun msg(text: String?, title: String?) {
        if (!text.isNullOrBlank()) msg += "\n$text"
        if (title != null) msgTitle = title
    }

    fun component1() = this
    fun component2() = progress
    fun component3() = msgTitle
    fun component4() = msg
}

/** 当一个执行一个长时间操作时，传入一个此类的时候一遍在屏幕上显示进度和消息
 * @param totalValue 计算百分比时的分母 . 为负数是表示无法计算进度
 */
abstract class TaskReporter(var totalValue: Long = -1) {

    /** 更新进度。若为负数代表应显示无限加载条 */
    abstract fun progress(percent: Float)

    /** 同[progress] 区别为传入参数不是 当前值/总值，而仅仅是 当前值。因为有时候调用环境不知道总值。 */
    fun progressValue(value: Long) = progress(value.toFloat() / totalValue)

    /** 执行此函数表示任务结束. 若 [error] 不为null, 说明失败了。 */
    abstract fun done(error: Exception? = null)

    /** 需要显示的文字. 当本次[title]为null时 应该显示上一次不为null的title. */
    abstract fun msg(text: String? = null, title: String? = null)

    companion object {
        val Dummy: TaskReporter = object : TaskReporter(Long.MAX_VALUE) {
            override fun progress(percent: Float) {}
            override fun done(error: Exception?) {}
            override fun msg(text: String?, title: String?) {}
        }
    }
}

enum class ProgressStage {
    NOT_STARTED, PROCESSING, DONE_SUCCESS, DONE_FAILURE
}

@Composable
fun ProgressDisplay(
    reporter: SimpleTaskReporter
) {
    ProgressDisplay(reporter.stage, reporter.progress, reporter.msgTitle, reporter.msg)
}

/**
 * @param progress 0-100
 */
@Composable
fun ProgressDisplay(
    stage: ProgressStage,
    progress: Int,
    msgTitle: String,
    msg: String,
) {
    val TAG = "ProgressDisplay"

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
//            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 标题
            Text(msgTitle, style = MaterialTheme.typography.titleMedium)

            when (stage) {
                ProgressStage.NOT_STARTED -> Unit
                ProgressStage.PROCESSING ->
                    // 处理过程中显示进度条
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(progress = { progress / 100F })
                        Text("$progress%")
                    }

                ProgressStage.DONE_SUCCESS ->
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.secondary)

                ProgressStage.DONE_FAILURE ->
                    Icon(Icons.Rounded.Warning, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.error)
            }


            // 处理时或处理后显示日志。解压成功后折叠
            var msgExpanded by remember(stage) { mutableStateOf(stage == ProgressStage.PROCESSING) }
            if (stage != ProgressStage.NOT_STARTED) {
                // weight 占据剩余空间，保证优先满足按钮的高度。否则会把按钮挤没。然后高度过高时可滚动。这俩modifier要加到包裹column上，加到text自身没用
                // 但是weight会在内容没那么高的时候还是占据所有剩余空间 导致空出来一大块。
                Text(
                    msg,
                    Modifier
                        .fillMaxWidth()
                        .clickable { msgExpanded = !msgExpanded }
                        .animateContentSize()
                        .horizontalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.run { if (stage == ProgressStage.DONE_FAILURE) error else onSurface },
                    maxLines = if (msgExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}