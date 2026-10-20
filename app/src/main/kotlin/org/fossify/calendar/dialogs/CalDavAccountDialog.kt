package org.fossify.calendar.dialogs

import androidx.appcompat.app.AlertDialog
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.HttpException
import org.fossify.calendar.R
import org.fossify.calendar.activities.SimpleActivity
import org.fossify.calendar.caldav.CalDavClient
import org.fossify.calendar.caldav.DavAccount
import org.fossify.calendar.caldav.DavAccountUrl
import org.fossify.calendar.databinding.DialogCaldavAccountBinding
import org.fossify.calendar.extensions.config
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
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
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                        saveAccount(alertDialog)
                    }
                }
            }
    }

    private fun saveAccount(alertDialog: AlertDialog) {
        val url = DavAccountUrl.normalize(binding.caldavServerUrl.value)
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
                kotlinx.coroutines.runBlocking {
                    CalDavClient(account).discoverHomeSet()
                }
                activity.runOnUiThread {
                    onSuccess()
                }
            } catch (e: IOException) {
                activity.showErrorToast(e)
            } catch (e: DavException) {
                activity.showErrorToast(e)
            } catch (e: HttpException) {
                activity.showErrorToast(e)
            }
        }
        alertDialog.dismiss()
    }
}
