package com.amr3d.preview.pro

import android.app.Dialog
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class NestingFragment : Fragment() {
    private lateinit var rootLayout: LinearLayout
    private lateinit var preview: NestingPreviewView
    private lateinit var progress: ProgressBar
    private lateinit var progressPanel: LinearLayout
    private lateinit var cancelButton: Button
    private lateinit var resultText: TextView
    private lateinit var sourceText: TextView
    private lateinit var copiesEdit: EditText
    private lateinit var boardWEdit: EditText
    private lateinit var boardHEdit: EditText
    private lateinit var clearanceEdit: EditText
    private lateinit var rotationEdit: EditText
    private lateinit var deviationEdit: EditText
    private lateinit var toolEdit: EditText
    private lateinit var grainSpinner: Spinner
    private lateinit var processSpinner: Spinner
    private lateinit var boardPresetSpinner: Spinner
    private lateinit var runButton: Button
    private var boardColor: Int = 0xFF0D0F14.toInt()

    private var currentShape: NestingPolygon? = null
    private var lastResult: NestingResult? = null
    private var engineJob: Job? = null
    private val cancelled = AtomicBoolean(false)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        loadSelectedDxf(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.background_dark))
        }

        rootLayout.addView(toolbar())
        rootLayout.addView(previewSection(), LinearLayout.LayoutParams(-1, dp(300)))
        rootLayout.addView(settingsSection(), LinearLayout.LayoutParams(-1, 0, 1f))

        loadSessionIfAvailable()
        return rootLayout
    }

    private fun toolbar(): View = LinearLayout(requireContext()).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(color(R.color.surface_dark))

        val back = TextView(context).apply {
            text = "‹"
            textSize = 34f
            setTextColor(color(R.color.accent_orange))
            setOnClickListener { (activity as? MainActivity)?.closeNesting() }
        }
        addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))

        val title = TextView(context).apply {
            text = getString(R.string.nesting_title)
            textSize = 20f
            setTextColor(color(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        val expand = TextView(context).apply {
            text = "⛶"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.accent_orange))
            setOnClickListener { openFullscreenPreview() }
        }
        addView(expand, LinearLayout.LayoutParams(dp(48), dp(48)))
        this
    }

    private fun previewSection(): View {
        val frame = FrameLayout(requireContext()).apply {
            setBackgroundColor(color(R.color.background_dark))
        }
        preview = NestingPreviewView(requireContext())
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val hint = TextView(requireContext()).apply {
            text = getString(R.string.nesting_preview_hint)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_secondary))
            setPadding(dp(10))
        }
        frame.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        progressPanel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14))
            setBackgroundColor(0xE6101218.toInt())
            visibility = View.GONE
        }
        progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent_orange))
        }
        progressPanel.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        cancelButton = Button(requireContext()).apply {
            text = getString(R.string.action_cancel)
            setOnClickListener { cancelEngine() }
        }
        progressPanel.addView(cancelButton, LinearLayout.LayoutParams(-1, dp(42)))
        frame.addView(progressPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        return frame
    }

    private fun settingsSection(): View {
        val scroll = ScrollView(requireContext())
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12))
        }
        sourceText = label("")
        box.addView(sourceText)
        val choose = button(getString(R.string.nesting_choose_dxf)) {
            filePicker.launch(arrayOf("application/dxf", "application/octet-stream", "*/*"))
        }
        box.addView(choose)

        box.addView(sectionTitle(getString(R.string.nesting_board)))
        boardWEdit = numberField("1220")
        boardHEdit = numberField("2440")
        copiesEdit = numberField("10")
        clearanceEdit = numberField("0")
        rotationEdit = numberField("15")
        deviationEdit = numberField("10")
        toolEdit = numberField("6")

        boardPresetSpinner = Spinner(requireContext())
        boardPresetSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.nesting_board_1220x2440), getString(R.string.nesting_board_1220x3050),
                getString(R.string.nesting_board_1830x3660), getString(R.string.nesting_board_custom)))
        boardPresetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> { boardWEdit.setText("1220"); boardHEdit.setText("2440") }
                    1 -> { boardWEdit.setText("1220"); boardHEdit.setText("3050") }
                    2 -> { boardWEdit.setText("1830"); boardHEdit.setText("3660") }
                }
                val custom = position == 3
                boardWEdit.isEnabled = custom
                boardHEdit.isEnabled = custom
            }
        }
        box.addView(labeled(getString(R.string.nesting_board_size), boardPresetSpinner))
        box.addView(labeled(getString(R.string.nesting_board_width), boardWEdit))
        box.addView(labeled(getString(R.string.nesting_board_height), boardHEdit))
        box.addView(labeled(getString(R.string.nesting_copies), copiesEdit))

        processSpinner = Spinner(requireContext())
        processSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.nesting_process_cnc), getString(R.string.nesting_process_laser)))
        box.addView(labeled(getString(R.string.nesting_process), processSpinner))
        box.addView(labeled(getString(R.string.nesting_tool_diameter), toolEdit))
        box.addView(labeled(getString(R.string.nesting_clearance), clearanceEdit))

        processSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val cnc = position == 0
                toolEdit.visibility = if (cnc) View.VISIBLE else View.GONE
                clearanceEdit.setText(if (cnc) toolEdit.text.toString() else "0")
            }
        }
        toolEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && processSpinner.selectedItemPosition == 0) clearanceEdit.setText(toolEdit.text.toString())
        }

        grainSpinner = Spinner(requireContext())
        grainSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.nesting_grain_free), getString(R.string.nesting_grain_horizontal), getString(R.string.nesting_grain_vertical)))
        box.addView(labeled(getString(R.string.nesting_grain), grainSpinner))
        box.addView(labeled(getString(R.string.nesting_grain_deviation), deviationEdit))
        grainSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                deviationEdit.visibility = if (position == 0) View.GONE else View.VISIBLE
            }
        }

        box.addView(labeled(getString(R.string.nesting_rotation_step), rotationEdit))

        val colorButton = Button(requireContext()).apply {
            text = getString(R.string.nesting_board_color)
            setTextColor(color(R.color.text_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(boardColor)
            setOnClickListener {
                val colors = intArrayOf(
                    0xFF0D0F14.toInt(), 0xFF2B2118.toInt(), 0xFF3A2B1D.toInt(),
                    0xFF182B20.toInt(), 0xFF202B3A.toInt(), 0xFF33202B.toInt()
                )
                val names = arrayOf("Dark", "Wood", "Walnut", "Green", "Blue", "Brown")
                var selected = colors.indexOf(boardColor).coerceAtLeast(0)
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.nesting_board_color))
                    .setSingleChoiceItems(names, selected) { d, which ->
                        boardColor = colors[which]
                        colorButton.backgroundTintList = android.content.res.ColorStateList.valueOf(boardColor)
                        d.dismiss()
                    }.show()
            }
        }
        box.addView(colorButton, LinearLayout.LayoutParams(-1, dp(44)))

        runButton = button(getString(R.string.nesting_run)) { startEngine() }
        box.addView(runButton)

        resultText = label(getString(R.string.nesting_no_result)).apply { setPadding(dp(4), dp(12), dp(4), dp(20)) }
        box.addView(resultText)
        scroll.addView(box)
        return scroll
    }

    private fun loadSessionIfAvailable() {
        val model = NestingSession.model
        if (model == null) {
            sourceText.text = getString(R.string.nesting_no_source)
            return
        }
        sourceText.text = getString(R.string.nesting_source, NestingSession.sourceName.ifBlank { "DXF" })
        lifecycleScope.launch(Dispatchers.Default) {
            val shape = NestingShapeBuilder.fromModel(model)
            withContext(Dispatchers.Main) {
                currentShape = shape
                if (shape == null) {
                    sourceText.text = getString(R.string.nesting_invalid_shape)
                    runButton.isEnabled = false
                } else {
                    sourceText.text = getString(R.string.nesting_source_ready, NestingSession.sourceName.ifBlank { "DXF" },
                        "%.1f × %.1f mm".format(shape.outer.maxOf { it.x } - shape.outer.minOf { it.x },
                            shape.outer.maxOf { it.y } - shape.outer.minOf { it.y }))
                }
            }
        }
    }

    private fun loadSelectedDxf(uri: Uri) {
        sourceText.text = getString(R.string.nesting_loading)
        lifecycleScope.launch {
            try {
                val model = withContext(Dispatchers.IO) { DXFParser.parse(requireContext(), uri) }
                NestingSession.model = model
                NestingSession.sourceUri = uri
                NestingSession.sourceName = uri.lastPathSegment ?: "DXF"
                loadSessionIfAvailable()
            } catch (e: Exception) {
                sourceText.text = getString(R.string.toast_error_prefix, e.message ?: "DXF")
            }
        }
    }

    private fun startEngine() {
        val shape = currentShape ?: run {
            Toast.makeText(context, getString(R.string.nesting_no_source), Toast.LENGTH_SHORT).show()
            return
        }
        if (engineJob?.isActive == true) return

        val bw = boardWEdit.text.toString().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1220.0
        val bh = boardHEdit.text.toString().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 2440.0
        val copies = copiesEdit.text.toString().toIntOrNull()?.coerceIn(1, 10000) ?: 1
        val clearance = clearanceEdit.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val step = rotationEdit.text.toString().toDoubleOrNull()?.coerceIn(1.0, 90.0) ?: 15.0
        val deviation = deviationEdit.text.toString().toDoubleOrNull()?.coerceIn(0.0, 90.0) ?: 10.0
        val cnc = processSpinner.selectedItemPosition == 0
        val toolDiameter = toolEdit.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 6.0
        val clearance = if (cnc) toolDiameter else 0.0
        clearanceEdit.setText("%.2f".format(clearance))
        val grain = when (grainSpinner.selectedItemPosition) {
            1 -> GrainAxis.HORIZONTAL
            2 -> GrainAxis.VERTICAL
            else -> GrainAxis.FREE
        }

        cancelled.set(false)
        progress.progress = 0
        progressPanel.visibility = View.VISIBLE
        runButton.isEnabled = false
        resultText.text = getString(R.string.nesting_running)

        engineJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                NestingEngine.nest(
                    shape,
                    NestingConfig(bw, bh, copies, step, RotationMode.FREE, grain, deviation, clearance, 12.0, boardColor),
                    onProgress = { p ->
                        launch(Dispatchers.Main) {
                            progress.progress = p.percent
                        }
                    },
                    isCancelled = { cancelled.get() }
                )
            }
            lastResult = result
            preview.result = result
            progress.progress = (result.totalPlaced * 100 / result.totalRequested.coerceAtLeast(1)).coerceIn(0,100)
            progressPanel.visibility = View.GONE
            runButton.isEnabled = true
            resultText.text = getString(R.string.nesting_result,
                result.totalPlaced, result.totalRequested, result.boards.size,
                result.utilization, result.wasteArea, result.elapsedMs)
        }
    }

    private fun cancelEngine() {
        cancelled.set(true)
        engineJob?.cancel()
        engineJob = null
        progressPanel.visibility = View.GONE
        runButton.isEnabled = true
        resultText.text = getString(R.string.nesting_cancelled)
    }

    private fun openFullscreenPreview() {
        val r = lastResult ?: preview.result ?: return
        val dialog = Dialog(requireContext())
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.background_dark))
        }
        val bar = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10))
            setBackgroundColor(color(R.color.surface_dark))
        }
        val title = TextView(requireContext()).apply {
            text = getString(R.string.nesting_fullscreen)
            textSize = 18f
            setTextColor(color(R.color.text_primary))
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var full: NestingPreviewView
        fun t(txt:String, click:()->Unit)=TextView(requireContext()).apply{
            text=txt;textSize=22f;gravity=Gravity.CENTER;setTextColor(color(R.color.accent_orange));setOnClickListener{click()}
        }
        val minus=t("−"){full.zoomOut()}
        val plus=t("+"){full.zoomIn()}
        val fit=t("Fit"){full.fitAll()}
        bar.addView(minus,LinearLayout.LayoutParams(dp(48),dp(48)))
        bar.addView(plus,LinearLayout.LayoutParams(dp(48),dp(48)))
        bar.addView(fit,LinearLayout.LayoutParams(dp(60),dp(48)))
        val close=t("×"){dialog.dismiss()}
        bar.addView(close,LinearLayout.LayoutParams(dp(48),dp(48)))
        box.addView(bar)
        full = NestingPreviewView(requireContext()).apply { result = r; showAllBoards = true }
        box.addView(full, LinearLayout.LayoutParams(-1,0,1f))
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1,-1)
        dialog.show()
        dialog.window?.setLayout(-1,-1)
    }

    private fun sectionTitle(text:String)=TextView(requireContext()).apply{
        this.text=text;textSize=15f;setTextColor(color(R.color.accent_orange));setTypeface(typeface,android.graphics.Typeface.BOLD)
        setPadding(0,dp(12),0,dp(6))
    }
    private fun label(text:String)=TextView(requireContext()).apply{
        this.text=text;textSize=13f;setTextColor(color(R.color.text_secondary))
    }
    private fun numberField(value:String)=EditText(requireContext()).apply{
        setText(value);textSize=14f;setTextColor(color(R.color.text_primary));setSingleLine(true)
        inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setBackgroundResource(R.drawable.bg_toggle_button);setPadding(dp(10))
    }
    private fun labeled(text:String, v:View)=LinearLayout(requireContext()).apply{
        orientation=LinearLayout.VERTICAL;setPadding(0,dp(4),0,dp(4));addView(label(text));addView(v,LinearLayout.LayoutParams(-1,dp(44)))
    }
    private fun button(text:String, click:()->Unit)=Button(requireContext()).apply{
        this.text=text;setTextColor(Color.BLACK);backgroundTintList=android.content.res.ColorStateList.valueOf(color(R.color.accent_orange))
        setOnClickListener{click()}
        setPadding(dp(4))
    }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).roundToInt()
    private fun color(id:Int)=requireContext().getColor(id)

    override fun onDestroyView() {
        cancelled.set(true)
        engineJob?.cancel()
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = NestingFragment()
    }
}
