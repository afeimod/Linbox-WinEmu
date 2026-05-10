package org.github.ewt45.winemulator.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import a.io.github.ewt45.winemulator.R
import org.github.ewt45.winemulator.inputcontrols.Binding
import org.github.ewt45.winemulator.inputcontrols.ControlElement
import org.github.ewt45.winemulator.inputcontrols.ControlsProfile
import org.github.ewt45.winemulator.inputcontrols.InputControlsManager
import org.github.ewt45.winemulator.inputcontrols.InputControlsView
import java.io.IOException
import java.io.InputStream
import java.util.Arrays

/**
 * ControlsEditorActivity - 虚拟按键编辑器
 *
 * 修复内容:
 * 1. 修复范围按钮(RANGE_BUTTON)切换时界面不刷新的问题
 * 2. 改进 UI 更新的响应性
 * 3. 修复 API 调用格式问题 (函数调用改为属性访问)
 */
class ControlsEditorActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var inputControlsView: InputControlsView
    private var profile: ControlsProfile? = null

    // 用于延迟刷新以确保 UI 更新的稳定性
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable {
        inputControlsView.invalidate()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        setContentView(R.layout.controls_editor_activity)

        // 处理屏幕方向变化
        val orientation = resources.configuration.orientation
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏时旋转90度
            window.attributes.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            window.attributes.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        inputControlsView = InputControlsView(this)
        inputControlsView.setEditMode(true)
        inputControlsView.overlayOpacity = 0.6f

        val profileId = intent.getIntExtra(EXTRA_PROFILE_ID, 0)
        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId))

        if (profile == null) {
            Toast.makeText(this, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.TVProfileName).text = profile!!.name
        inputControlsView.setProfile(profile)

        val container = findViewById<FrameLayout>(R.id.FLContainer)
        container.addView(inputControlsView, 0)

        container.findViewById<View>(R.id.BTAddElement).setOnClickListener(this)
        container.findViewById<View>(R.id.BTRemoveElement).setOnClickListener(this)
        container.findViewById<View>(R.id.BTElementSettings).setOnClickListener(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 当屏幕方向变化时，重新加载profile以更新布局
        if (profile != null) {
            inputControlsView.setProfile(profile)
        }
    }

    override fun onResume() {
        super.onResume()
        // 当从编辑界面返回时，重新加载配置以获取最新保存的按键
        if (profile != null) {
            // 重新从文件加载配置
            val profileId = profile!!.id
            profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId))
            profile?.let {
                inputControlsView.setProfile(it)
                inputControlsView.invalidate()
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.BTAddElement -> {
                if (!inputControlsView.addElement()) {
                    Toast.makeText(this, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
                }
            }
            R.id.BTRemoveElement -> {
                if (!inputControlsView.removeElement()) {
                    Toast.makeText(this, R.string.no_control_element_selected, Toast.LENGTH_SHORT).show()
                }
            }
            R.id.BTElementSettings -> {
                val selectedElement = inputControlsView.getSelectedElement()
                if (selectedElement != null) {
                    try {
                        showControlElementSettings(v, selectedElement)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this, "无法打开设置界面: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, R.string.no_control_element_selected, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 刷新设置界面的可见性
     * 修复问题2: 确保切换类型时界面立即更新
     */
    private fun updateLayoutVisibility(view: View, element: ControlElement) {
        val type = element.type

        // 根据类型显示/隐藏相应的 UI 元素
        view.findViewById<View>(R.id.LLShape)?.visibility = if (type == ControlElement.Type.BUTTON) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.CBToggleSwitch)?.visibility = if (type == ControlElement.Type.BUTTON) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.LLCustomTextIcon)?.visibility = if (type == ControlElement.Type.BUTTON) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.LLRangeOptions)?.visibility = if (type == ControlElement.Type.RANGE_BUTTON) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.LLDPadBindings)?.visibility = if (type == ControlElement.Type.D_PAD ||
                                                                          type == ControlElement.Type.STICK ||
                                                                          type == ControlElement.Type.TRACKPAD) View.VISIBLE else View.GONE

        // 加载绑定配置
        loadBindingSpinners(element, view.findViewById(R.id.LLBindings))


        // 对于 RANGE_BUTTON，初始化并刷新 scroller
        if (type == ControlElement.Type.RANGE_BUTTON) {
            // 通过 setRange 触发 scroller 初始化
            element.setRange(element.getRange())
            // 强制刷新布局
            inputControlsView.invalidate()
        }
    }

    private fun showControlElementSettings(anchorView: View, element: ControlElement) {
        val view = LayoutInflater.from(this).inflate(R.layout.control_element_settings, null)

        // 初始化时先设置一次布局可见性
        updateLayoutVisibility(view, element)

        // 加载类型选择器
        loadTypeSpinner(element, view.findViewById(R.id.SType), Runnable {
            // 类型改变后立即更新布局
            updateLayoutVisibility(view, element)
            // 刷新视图
            scheduleRefresh()
        })

        // 加载形状选择器
        loadShapeSpinner(element, view.findViewById<Spinner>(R.id.SShape).apply {
            visibility = if (element.type == ControlElement.Type.BUTTON) View.VISIBLE else View.GONE
        })

        // 加载范围选择器
        loadRangeSpinner(element, view.findViewById<Spinner>(R.id.SRange).apply {
            visibility = if (element.type == ControlElement.Type.RANGE_BUTTON) View.VISIBLE else View.GONE
        })


        // 加载个数选择器（用于RANGE_BUTTON）
        loadCountSpinner(element, view.findViewById<Spinner>(R.id.SCount).apply {
            visibility = if (element.type == ControlElement.Type.RANGE_BUTTON) View.VISIBLE else View.GONE
        })

        // 加载方向选择器
        val llRangeOptions = view.findViewById<View>(R.id.LLRangeOptions)
        if (llRangeOptions != null) {
            val rgOrientation = llRangeOptions.findViewById<RadioGroup>(R.id.RGOrientation)
            if (rgOrientation != null) {
                rgOrientation.check(if (element.orientation == 1.toByte()) R.id.RBVertical else R.id.RBHorizontal)
                rgOrientation.setOnCheckedChangeListener { _, checkedId ->
                    element.orientation = if (checkedId == R.id.RBVertical) 1 else 0
                    profile?.save()
                    scheduleRefresh()
                }
            }
        }

        // 初始化缩放滑块
        val tvScaleView = view.findViewById<TextView>(R.id.TVScale)
        val sbScale = view.findViewById<SeekBar>(R.id.SBScale)
        sbScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvScaleView.text = "$progress%"
                if (fromUser) {
                    element.scale = progress / 100.0f
                    profile?.save()
                    scheduleRefresh()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        sbScale.progress = (element.scale * 100).toInt()

        // 初始化切换开关复选框
        val cbToggleSwitch = view.findViewById<CheckBox>(R.id.CBToggleSwitch)
        cbToggleSwitch.isChecked = element.isToggleSwitch
        cbToggleSwitch.visibility = if (element.type == ControlElement.Type.BUTTON) View.VISIBLE else View.GONE
        cbToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            element.isToggleSwitch = isChecked
            profile?.save()
        }

        // 初始化自定义文本
        val etCustomText = view.findViewById<EditText>(R.id.ETCustomText)
        etCustomText.setText(element.text)
        etCustomText.visibility = if (element.type == ControlElement.Type.BUTTON) View.VISIBLE else View.GONE

        // 初始化图标列表
        val llIconList = view.findViewById<LinearLayout>(R.id.LLIconList)
        llIconList.visibility = if (element.type == ControlElement.Type.BUTTON) View.VISIBLE else View.GONE
        loadIcons(llIconList, element.iconId)

        // 再次更新布局可见性以确保正确
        updateLayoutVisibility(view, element)

        // 使用自定义 showPopupWindow 方法代替 showAsDropDown
        val popupWindow = showPopupWindow(anchorView, view, 340.dpToPx(), 0)
        popupWindow.setOnDismissListener {
            val text = etCustomText.text.toString().trim()
            var iconId: Byte = 0
            for (i in 0 until llIconList.childCount) {
                val child = llIconList.getChildAt(i)
                if (child.isSelected) {
                    iconId = child.tag as? Byte ?: 0
                    break
                }
            }

            element.text = text
            element.iconId = iconId
            profile?.save()
            scheduleRefresh()
        }
    }

    /**
     * 安排延迟刷新以确保 UI 更新的稳定性
     */
    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 50) // 50ms 延迟确保 UI 更新的稳定性
    }

    /**
     * Show a popup window anchored to a view with proper positioning
     */
    private fun showPopupWindow(anchorView: View, contentView: View, width: Int, heightOffset: Int): PopupWindow {
        val popupWindow = PopupWindow(
            contentView,
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            true
        )

        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true
        popupWindow.setBackgroundDrawable(null)

        // Measure the content to get its actual height
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val contentHeight = contentView.measuredHeight

        // Get anchor view location
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)

        // Calculate available space
        val screenHeight = window.attributes.height
        val anchorY = location[1]

        // Determine if we should show above or below
        val spaceBelow = screenHeight - anchorY - anchorView.height
        val spaceAbove = anchorY

        // Show below by default if there's enough space, otherwise show above
        val useAbove = contentHeight > spaceBelow && contentHeight < spaceAbove

        val yOffset = if (useAbove) {
            -(anchorView.height + contentHeight + heightOffset.dpToPx())
        } else {
            heightOffset.dpToPx()
        }

        popupWindow.showAtLocation(
            anchorView,
            Gravity.TOP or Gravity.START,
            location[0],
            if (useAbove) anchorY + yOffset else anchorY + anchorView.height + yOffset
        )

        return popupWindow
    }

    private fun loadTypeSpinner(element: ControlElement, spinner: Spinner, callback: Runnable) {
        val typeNames = ControlElement.Type.names()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeNames)
        spinner.setSelection(element.type.ordinal, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newType = ControlElement.Type.entries[position]
                if (element.type != newType) {
                    element.setType(newType)
                    profile?.save()
                    // 立即调用回调刷新 UI
                    callback.run()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadShapeSpinner(element: ControlElement, spinner: Spinner) {
        val shapeNames = ControlElement.Shape.names()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shapeNames)
        spinner.setSelection(element.shape.ordinal, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                element.shape = ControlElement.Shape.entries[position]
                profile?.save()
                scheduleRefresh()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadRangeSpinner(element: ControlElement, spinner: Spinner) {
        val rangeNames = ControlElement.Range.names()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, rangeNames)
        if (element.range != null) {
            spinner.setSelection(element.range!!.ordinal, false)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newRange = ControlElement.Range.entries[position]
                if (element.range != newRange) {
                    element.setRange(newRange)
                    profile?.save()
                    // 立即刷新视图
                    inputControlsView.invalidate()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * 加载个数选择器（用于RANGE_BUTTON）
     * 修复问题2补充：添加Range Button的个数设置
     */
    private fun loadCountSpinner(element: ControlElement, spinner: Spinner) {
        val countOptions = arrayOf("2", "3", "4", "5", "6", "7", "8", "9", "10")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, countOptions)
        val currentCount = element.getBindingCount()
        val index = countOptions.indexOf(currentCount.toString())
        if (index >= 0) {
            spinner.setSelection(index, false)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newCount = countOptions[position].toInt()
                if (element.getBindingCount() != newCount) {
                    element.setBindingCount(newCount)
                    profile?.save()
                    inputControlsView.invalidate()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadBindingSpinners(element: ControlElement, container: LinearLayout) {
        container.removeAllViews()

        when (element.type) {
            ControlElement.Type.BUTTON -> {
                loadBindingSpinner(element, container, 0, R.string.binding)
            }
            ControlElement.Type.D_PAD, ControlElement.Type.STICK, ControlElement.Type.TRACKPAD -> {
                loadBindingSpinner(element, container, 0, R.string.binding_up)
                loadBindingSpinner(element, container, 1, R.string.binding_right)
                loadBindingSpinner(element, container, 2, R.string.binding_down)
                loadBindingSpinner(element, container, 3, R.string.binding_left)
            }
            else -> {}
        }
    }

    private fun loadBindingSpinner(element: ControlElement, container: LinearLayout, index: Int, titleResId: Int) {
        val bindingView = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false)
        (bindingView.findViewById<TextView>(R.id.TVTitle)).setText(titleResId)
        val sBindingType = bindingView.findViewById<Spinner>(R.id.SBindingType)
        val sBinding = bindingView.findViewById<Spinner>(R.id.SBinding)

        // 设置 SBindingType 的适配器（keyboard、mouse、gamepad选项）
        sBindingType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.binding_type_entries))

        val update = Runnable {
            val bindingEntries: Array<String>
            when (sBindingType.selectedItemPosition) {
                0 -> bindingEntries = Binding.keyboardBindingLabels()
                1 -> bindingEntries = Binding.mouseBindingLabels()
                2 -> bindingEntries = Binding.gamepadBindingLabels()
                else -> bindingEntries = Binding.keyboardBindingLabels()
            }

            sBinding.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bindingEntries)
            setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).toString())
        }

        sBindingType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                update.run()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val selectedBinding = element.getBindingAt(index)
        sBindingType.setSelection(
            when {
                selectedBinding.isKeyboard() -> 0
                selectedBinding.isMouse() -> 1
                selectedBinding.isGamepad() -> 2
                else -> 0
            }, false
        )

        sBinding.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val binding = when (sBindingType.selectedItemPosition) {
                    0 -> Binding.keyboardBindingValues()[position]
                    1 -> Binding.mouseBindingValues()[position]
                    2 -> Binding.gamepadBindingValues()[position]
                    else -> Binding.NONE
                }

                if (binding != element.getBindingAt(index)) {
                    element.setBindingAt(index, binding)
                    profile?.save()
                    scheduleRefresh()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        update.run()
        container.addView(bindingView)
    }

    private fun setSpinnerSelectionFromValue(spinner: Spinner, value: String) {
        val adapter = spinner.adapter
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString().equals(value, ignoreCase = true)) {
                spinner.setSelection(i, false)
                return
            }
        }
    }

    private fun loadIcons(parent: LinearLayout, selectedId: Byte) {
        var iconIds = byteArrayOf()
        try {
            val filenames = assets.list("inputcontrols/icons/") ?: return
            iconIds = ByteArray(filenames.size)
            for (i in filenames.indices) {
                iconIds[i] = filenames[i].substringBefore(".").toByteOrNull() ?: 0
            }
        } catch (e: IOException) {}

        Arrays.sort(iconIds)

        val size = (40 * resources.displayMetrics.density).toInt()
        val margin = (2 * resources.displayMetrics.density).toInt()
        val padding = (4 * resources.displayMetrics.density).toInt()
        val params = LinearLayout.LayoutParams(size, size)
        params.setMargins(margin, 0, margin, 0)

        for (id in iconIds) {
            val imageView = ImageView(this)
            imageView.layoutParams = params
            imageView.setPadding(padding, padding, padding, padding)
            imageView.setBackgroundResource(R.drawable.icon_background)
            imageView.tag = id
            imageView.isSelected = id == selectedId

            // 选中时的缩放动画效果
            imageView.setOnClickListener {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    child.isSelected = false
                    // 重置所有图标的缩放
                    child.scaleX = 1.0f
                    child.scaleY = 1.0f
                }
                imageView.isSelected = true
                // 选中图标放大显示
                imageView.scaleX = 1.2f
                imageView.scaleY = 1.2f
            }

            try {
                assets.open("inputcontrols/icons/$id.png").use { `is` ->
                    imageView.setImageBitmap(BitmapFactory.decodeStream(`is`))
                }
            } catch (e: IOException) {}

            parent.addView(imageView)
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}