package com.smclab.autotrade.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.DialogStrategyConfigBinding
import com.smclab.autotrade.databinding.ItemParamFieldBinding
import com.smclab.autotrade.engine.StrategyCatalog
import com.smclab.autotrade.model.ParamSpec
import com.smclab.autotrade.model.StrategyConfig
import java.util.Locale

class StrategyConfigDialog : BottomSheetDialogFragment() {

    private var _binding: DialogStrategyConfigBinding? = null
    private val binding get() = _binding!!
    private lateinit var config: StrategyConfig
    private val fields = LinkedHashMap<String, Pair<ParamSpec, TextInputEditText>>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogStrategyConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val id = arguments?.getString(ARG_ID) ?: return
        val def = StrategyCatalog.byId(id) ?: return
        config = AppRepository.configFor(id)

        binding.tvConfigName.text = def.name
        binding.tvConfigCategory.text = "${def.category} · tối thiểu ${def.minConfluence} yếu tố"
        binding.tvConfigDesc.text = def.description
        binding.swConfigEnabled.isChecked = config.enabled
        binding.swConfigHtf.isChecked = config.useHtf
        binding.etConfigRisk.setText(String.format(Locale.US, "%.2f", config.riskPercent))
        binding.etConfigRr.setText(String.format(Locale.US, "%.1f", config.riskReward))

        for (param in def.params) {
            val row = ItemParamFieldBinding.inflate(layoutInflater, binding.configParamsContainer, false)
            row.root.hint = param.label
            row.etParamValue.setText(format(param, config.param(param.key, param.defaultValue)))
            row.root.helperText = hintOf(param)
            binding.configParamsContainer.addView(row.root)
            fields[param.key] = param to row.etParamValue
        }

        binding.btnConfigSave.setOnClickListener { save(def.id) }
    }

    private fun hintOf(param: ParamSpec): String {
        val range = "${trim(param.min)} – ${trim(param.max)}"
        return if (param.unit.isBlank()) range else "$range ${param.unit}"
    }

    private fun trim(value: Double): String =
        if (value == value.toInt().toDouble()) value.toInt().toString()
        else String.format(Locale.US, "%.2f", value)

    private fun format(param: ParamSpec, value: Double): String =
        String.format(Locale.US, "%.${param.decimals.coerceIn(0, 3)}f", value)

    private fun save(id: String) {
        val risk = binding.etConfigRisk.text?.toString()?.toDoubleOrNull() ?: config.riskPercent
        val rr = binding.etConfigRr.text?.toString()?.toDoubleOrNull() ?: config.riskReward
        if (risk !in 0.1..5.0) {
            binding.etConfigRisk.error = "0.1 – 5.0%"
            return
        }
        if (rr !in 0.5..10.0) {
            binding.etConfigRr.error = "0.5 – 10.0"
            return
        }
        val params = config.params.toMutableMap()
        for ((key, pair) in fields) {
            val (spec, edit) = pair
            val raw = edit.text?.toString()?.toDoubleOrNull() ?: spec.defaultValue
            params[key] = raw.coerceIn(spec.min, spec.max)
        }
        val updated = config.copy(
            enabled = binding.swConfigEnabled.isChecked,
            params = params,
            riskPercent = risk,
            riskReward = rr,
            useHtf = binding.swConfigHtf.isChecked,
        )
        AppRepository.saveStrategy(updated)
        AppRepository.notify("Đã lưu cấu hình chiến lược")
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_ID = "strategy_id"

        fun newInstance(id: String): StrategyConfigDialog = StrategyConfigDialog().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }
}
