package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogMimoTtsConfigBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.mimo.MiMoGlobalConfig
import io.legado.app.service.mimo.MiMoTtsConfigStore
import io.legado.app.service.mimo.MiMoTtsContract
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

class MiMoTtsConfigDialog : BaseDialogFragment(R.layout.dialog_mimo_tts_config) {
    private val binding by viewBinding(DialogMimoTtsConfigBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.mimo_tts)
        val global = MiMoTtsConfigStore.loadGlobal(requireContext())
        val entries = resources.getStringArray(R.array.mimo_voice_entries)
        val values = resources.getStringArray(R.array.mimo_voice_values)
        binding.spinnerVoice.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            entries
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerVoice.setSelection(values.indexOf(global.voice).coerceAtLeast(0))
        binding.editApiKey.setText(global.apiKey)
        binding.editGlobalStyle.setText(global.style)

        val book = ReadBook.book
        if (book == null) {
            binding.llBookStyle.gone()
        } else {
            binding.llBookStyle.visible()
            binding.editBookStyle.setText(book.getMiMoTtsStyle().orEmpty())
        }

        binding.tvClearBookStyle.setOnClickListener {
            binding.editBookStyle.setText("")
            toastOnUi(R.string.mimo_book_style_inherited)
        }
        binding.tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvOk.setOnClickListener {
            val selectedVoice = values.getOrElse(binding.spinnerVoice.selectedItemPosition) {
                MiMoTtsContract.DEFAULT_VOICE
            }
            MiMoTtsConfigStore.saveGlobal(
                requireContext(),
                MiMoGlobalConfig(
                    apiKey = binding.editApiKey.text?.toString().orEmpty(),
                    voice = selectedVoice,
                    style = binding.editGlobalStyle.text?.toString().orEmpty()
                )
            )
            ReadBook.book?.setMiMoTtsStyle(binding.editBookStyle.text?.toString())
            ReadBook.saveRead()
            ReadAloud.refreshMiMoConfig(requireContext())
            dismissAllowingStateLoss()
        }
    }
}
