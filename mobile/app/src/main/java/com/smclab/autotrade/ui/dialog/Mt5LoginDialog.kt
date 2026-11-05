package com.smclab.autotrade.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.DialogMt5LoginBinding
import com.smclab.autotrade.model.Account
import com.smclab.autotrade.ui.Ui

/**
 * MT5-style account sheet.
 *
 * The app is fully offline: this creates a clearly labelled local demo session.
 * Login/server/broker are persisted with the demo account; the password is only
 * checked for length and then discarded.
 */
class Mt5LoginDialog : BottomSheetDialogFragment() {

    private var _binding: DialogMt5LoginBinding? = null
    private val binding get() = _binding!!

    private val servers = listOf(
        "MetaQuotes-Demo",
        "Exness-Demo",
        "ICMarkets-Demo",
        "XMTrading-Demo",
        "Vantage-Demo",
        "FBS-Demo",
        "Pepperstone-Demo",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogMt5LoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ddMt5Server.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, servers),
        )
        binding.ddMt5Server.setOnItemClickListener { _, _, position, _ ->
            servers.getOrNull(position)?.let { server ->
                binding.ddMt5Server.setText(server, false)
                binding.tilMt5Server.error = null
                if (binding.etMt5Broker.text.isNullOrBlank()) {
                    binding.etMt5Broker.setText(server.substringBefore('-'))
                }
            }
        }

        binding.btnMt5Connect.setOnClickListener { submit() }
        binding.btnMt5Cancel.setOnClickListener { dismiss() }
        binding.btnMt5Logout.setOnClickListener { confirmLogout() }

        binding.etMt5Login.doAfterTextChanged { binding.tilMt5Login.error = null }
        binding.etMt5Password.doAfterTextChanged { binding.tilMt5Password.error = null }
        binding.etMt5Login.doAfterTextChanged { binding.tvMt5Error.visibility = View.GONE }
        binding.etMt5Password.doAfterTextChanged { binding.tvMt5Error.visibility = View.GONE }
        binding.etMt5Password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        val account = AppRepository.account.value ?: Account()
        binding.etMt5Login.setText(account.login)
        binding.ddMt5Server.setText(
            account.server.ifBlank { servers.first() },
            false,
        )
        binding.etMt5Broker.setText(account.broker)
        renderSession(account)
    }

    private fun renderSession(account: Account) {
        val connected = account.isMt5Session
        binding.connectedPanel.visibility = if (connected) View.VISIBLE else View.GONE
        binding.loginForm.visibility = if (connected) View.GONE else View.VISIBLE
        binding.btnMt5Connect.visibility = if (connected) View.GONE else View.VISIBLE
        binding.tvMt5Error.visibility = View.GONE
        if (!connected) {
            binding.btnMt5Cancel.text = getString(R.string.cancel)
            binding.etMt5Password.setText("")
            return
        }
        binding.btnMt5Cancel.text = getString(R.string.close)
        binding.tvMt5ConnectedIdentity.text = account.displayName
        binding.tvMt5ConnectedServer.text = buildString {
            append(account.broker.ifBlank { "Broker demo" })
            append(" · ").append(account.server)
        }
        binding.tvMt5ConnectedBalance.text = getString(
            R.string.mt5_connected_balance,
            Ui.money(account.balance),
            account.currency,
            Ui.money(account.equity),
        )
    }

    private fun submit() {
        binding.tvMt5Error.visibility = View.GONE
        binding.tilMt5Login.error = null
        binding.tilMt5Server.error = null
        binding.tilMt5Password.error = null

        val login = binding.etMt5Login.text?.toString().orEmpty().trim()
        val password = binding.etMt5Password.text?.toString().orEmpty()
        val server = binding.ddMt5Server.text?.toString().orEmpty().trim()
        val broker = binding.etMt5Broker.text?.toString().orEmpty().trim()

        val localError = when {
            !login.matches(Regex("\\d{4,12}")) -> Triple(R.string.mt5_invalid_login, binding.tilMt5Login, true)
            server.isBlank() -> Triple(R.string.mt5_invalid_server, binding.tilMt5Server, true)
            password.length < 4 -> Triple(R.string.mt5_invalid_password, binding.tilMt5Password, true)
            else -> null
        }
        if (localError != null) {
            localError.second.error = getString(localError.first)
            showError(getString(localError.first))
            return
        }

        val error = AppRepository.connectMt5Account(
            login = login,
            server = server,
            broker = broker,
            password = password,
        )
        if (error != null) {
            showError(error)
            return
        }
        dismiss()
    }

    private fun showError(text: String) {
        binding.tvMt5Error.text = text
        binding.tvMt5Error.visibility = View.VISIBLE
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mt5_logout)
            .setMessage(R.string.mt5_logout_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.mt5_logout) { _, _ ->
                AppRepository.disconnectMt5Account()
                renderSession(AppRepository.account.value ?: Account())
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
