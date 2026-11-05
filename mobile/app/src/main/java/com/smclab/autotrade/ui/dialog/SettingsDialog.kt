package com.smclab.autotrade.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.DialogSettingsBinding
import com.smclab.autotrade.model.Account
import java.util.Locale

class SettingsDialog : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = AppRepository.settings.value ?: return
        binding.etSettingsBalance.setText(String.format(Locale.US, "%.0f", settings.initialBalance))
        binding.etSettingsLeverage.setText(settings.leverage.toString())
        binding.etSettingsRisk.setText(String.format(Locale.US, "%.2f", settings.defaultRisk))
        binding.etSettingsCommission.setText(String.format(Locale.US, "%.2f", settings.commissionPerLot))
        binding.tvSettingsAbout.text = getString(R.string.settings_about_body)
        renderMt5Section()

        binding.btnSettingsMt5.setOnClickListener {
            dismiss()
            if (parentFragmentManager.findFragmentByTag("mt5_login") == null) {
                Mt5LoginDialog().show(parentFragmentManager, "mt5_login")
            }
        }
        binding.btnSettingsSave.setOnClickListener {
            val balance = binding.etSettingsBalance.text?.toString()?.toDoubleOrNull() ?: settings.initialBalance
            val leverage = binding.etSettingsLeverage.text?.toString()?.toIntOrNull() ?: settings.leverage
            val risk = binding.etSettingsRisk.text?.toString()?.toDoubleOrNull() ?: settings.defaultRisk
            val commission = binding.etSettingsCommission.text?.toString()?.toDoubleOrNull()
                ?: settings.commissionPerLot
            if (balance < 100.0) {
                binding.etSettingsBalance.error = "Tối thiểu 100 USD"
                return@setOnClickListener
            }
            if (leverage !in 1..2000) {
                binding.etSettingsLeverage.error = "1 – 2000"
                return@setOnClickListener
            }
            AppRepository.saveSettings(
                settings.copy(
                    initialBalance = balance,
                    leverage = leverage,
                    defaultRisk = risk.coerceIn(0.1, 5.0),
                    commissionPerLot = commission.coerceIn(0.0, 100.0),
                    autoHtfFilter = settings.autoHtfFilter,
                )
            )
            AppRepository.notify("Đã lưu cài đặt")
            dismiss()
        }

        binding.btnResetAccount.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_reset_account)
                .setMessage(R.string.reset_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset) { _, _ -> AppRepository.resetAccount() }
                .show()
        }
    }

    private fun renderMt5Section() {
        val account = AppRepository.account.value ?: Account()
        if (account.isMt5Session) {
            binding.tvSettingsMt5Status.text = getString(R.string.mt5_connected, account.displayName)
            binding.tvSettingsMt5Detail.text = buildString {
                append(account.broker.ifBlank { "Broker demo" })
                append(" · ").append(account.server)
                append(" · ").append("Demo")
            }
            binding.btnSettingsMt5.setText(R.string.mt5_manage_action)
        } else {
            binding.tvSettingsMt5Status.text = getString(R.string.settings_mt5_logged_out)
            binding.tvSettingsMt5Detail.text = getString(R.string.settings_mt5_hint)
            binding.btnSettingsMt5.setText(R.string.mt5_login_action)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
