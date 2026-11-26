package dev.nooko.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.nooko.launcher.MainActivity.Companion.WRITE_SECURE

// ---------- Home setup: choose a way, then three steps that tick themselves ----------
// The first screen offers every way Home can land on Nooko Launcher, the best one for this TV first. "On this TV" opens a
// checklist: Developer options, ADB debugging, then Connect, which runs [AdbSelf]. Each step reads the TV's own settings,
// so it ticks itself when the person comes back with Back. When all three are done, the Home service switches on.

/** Stock Android TV honours the default-home role; Google TV and Fire OS take the request and ignore it. */
private fun MainActivity.roleOffered(): Boolean {
    if (fireTv() || googleTv() || Build.VERSION.SDK_INT < 29) return false
    val roles = getSystemService(RoleManager::class.java) ?: return false
    return roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)
}

internal fun MainActivity.choosePopup() {
    stepsRefresh = null
    val role = roleOffered()
    val buttons = buildList<Pair<String, () -> Unit>> {
        if (role) add(getString(R.string.hs_default) to { rolePopup() })
        add(getString(if (role) R.string.hs_this_tv else R.string.hs_this_tv_rec) to { stepsPopup() })
        add(getString(R.string.hs_computer) to { adbPopup() })
        add(getString(R.string.hs_by_hand) to { tryHomeService() })
        add(getString(R.string.hs_later) to { setupDialog?.dismiss() })
    }
    homePopup(0, getString(R.string.hs_choose_title), getString(R.string.hs_choose_body), null, *buttons.toTypedArray())
}

private fun MainActivity.openSystem(action: String) {
    try {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}

private fun MainActivity.globalOn(name: String) = try { Settings.Global.getInt(contentResolver, name, 0) == 1 } catch (e: Exception) { false }

internal fun MainActivity.stepsPopup() {
    val status = label("", 13f).apply { maxLines = 3; ellipsize = null; secondary(0.7f); setPadding(0, dp(10), 0, 0) }
    val circles = ArrayList<TextView>()
    val inks = ArrayList<Int>()
    val actions = ArrayList<View>()
    var connecting = false

    lateinit var refresh: () -> Unit
    fun connect() {
        if (connecting) return
        connecting = true
        status.setText(R.string.adb_connecting)
        val hosts = listOfNotNull("127.0.0.1", lanAddress())
        Work.net.execute {
            val result = AdbSelf.grant(this, WRITE_SECURE, hosts) { ui.post { status.setText(R.string.adb_approve) } }
            ui.post {
                connecting = false
                if (isDestroyed) return@post
                when (result) {
                    AdbSelf.Result.DONE -> { status.setText(R.string.adb_done); refresh() }
                    AdbSelf.Result.NOT_LISTENING -> status.setText(R.string.adb_off_body)
                    AdbSelf.Result.NOT_ALLOWED -> status.setText(R.string.adb_denied)
                    AdbSelf.Result.FAILED -> status.setText(R.string.adb_failed)
                }
            }
        }
    }

    val steps = listOf(
        Triple(R.string.hs_dev_title, R.string.hs_dev_body, R.string.hs_open_about) to { openSystem(Settings.ACTION_DEVICE_INFO_SETTINGS) },
        Triple(R.string.hs_adb_title, R.string.hs_adb_body, R.string.hs_open_dev) to { openSystem(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
        Triple(R.string.hs_connect_title, R.string.hs_connect_body, R.string.hs_connect) to { connect() },
    )
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, 0)
        steps.forEachIndexed { i, (text, action) ->
            val circle = label("${i + 1}", 15f).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }
            circles += circle
            inks += circle.currentTextColor
            val words = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(getString(text.first), 17f).apply { setTypeface(typeface, Typeface.BOLD) })
                addView(label(getString(text.second), 13f).apply { maxLines = 3; ellipsize = null; secondary(0.7f); setLineSpacing(0f, 1.1f) })
            }
            val button = settingsRow(getString(text.third), "") { action() }
            actions += button
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(circle, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16); marginEnd = dp(16) })
                addView(button, LinearLayout.LayoutParams(dp(230), dp(44)))
            })
        }
        addView(status)
    }

    refresh = {
        val adb = globalOn(Settings.Global.ADB_ENABLED)
        val done = listOf(adb || globalOn(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), adb,
            checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED)
        circles.forEachIndexed { i, c ->
            c.text = if (done[i]) "✓" else "${i + 1}"
            c.setTextColor(if (done[i]) Color.BLACK else inks[i])
            c.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (done[i]) 0xFF3DDC97.toInt() else 0x22808080)
                if (!done[i]) setStroke(dp(1), 0x66808080)
            }
        }
        if (done.all { it }) {
            stepsRefresh = null
            tryHomeService() // the permission lets it switch the Home service on, then the Home press check runs
        } else actions[done.indexOfFirst { !it }].requestFocus()
    }

    homePopup(0, getString(R.string.hs_steps_title), getString(R.string.hs_steps_body), list,
        getString(R.string.hs_other_way) to { choosePopup() },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() }, wide = true)
    stepsRefresh = refresh
    refresh()
}
