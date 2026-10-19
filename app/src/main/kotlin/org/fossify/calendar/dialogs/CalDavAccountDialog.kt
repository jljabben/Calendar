package org.fossify.calendar.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.calendar.R
import org.fossify.calendar.activities.SimpleActivity
import org.fossify.calendar.caldav.DavAccount
import org.fossify.calendar.databinding.DialogCaldavAccountBinding
import org.fossify.calendar.extensions.config
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.IOException

class CalDavAccountDialog(
    private val activity: SimpleActivity,
    private val onSuccess: () -> Unit,
) {
    private val binding by activity.viewBinding(DialogCaldavAccountBinding::inflate)
    private val config = activity.config

    init {
        binding.apply {
            caldavServerUrl.setText(config.caldavServerUrl)
            caldavUsername.setText(config.caldavUsername)
            caldavPassword.setText(config.caldavPassword)
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                        saveAccount(alertDialog)
                    }
                }
            }
    }

    private fun saveAccount(alertDialog: AlertDialog) {
        val url = binding.caldavServerUrl.value.trim()
        val username = binding.caldavUsername.value.trim()
        val password = binding.caldavPassword.value

        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
            activity.toast(R.string.caldav_connection_failed)
            return
        }

        config.caldavServerUrl = url
        config.caldavUsername = username
        config.caldavPassword = password

        ensureBackgroundThread {
            val account = DavAccount(url, username, password)
            try {
                org.fossify.calendar.caldav.CalDavClient(account).discoverHomeSet()
                activity.runOnUiThread {
                    onSuccess()
                }
            } catch (e: IOException) {
                activity.showErrorToast(e)
                activity.runOnUiThread {
                    activity.toast(R.string.caldav_connection_failed)
                }
            } catch (e: at.bitfire.dav4jvm.ktor.exception.DavException) {
                activity.showErrorToast(e)
                activity.runOnUiThread {
                    activity.toast(R.string.caldav_connection_failed)
                }
            } catch (e: at.bitfire.dav4jvm.ktor.exception.HttpException) {
                activity.showErrorToast(e)
                activity.runOnUiThread {
                    activity.toast(R.string.caldav_connection_failed)
                }
            }
        }
        alertDialog.dismiss()
    }
}
