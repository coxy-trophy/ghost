package com.coxy.ghost.ui

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.coxy.ghost.R
import com.coxy.ghost.net.KnownDevice
import com.coxy.ghost.net.KnownNetwork
import com.coxy.ghost.net.LanScanner
import com.coxy.ghost.net.NetworkMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Devices screen: every network GHOST has been on, and what she saw on each.
 *
 * The network you are on now is first and marked, because that is the one a command can reach.
 * Everything else is history, and useful as history — a device remembered from another network is
 * why "the office TV" can be answered with "you're not on that network" rather than "no such
 * thing".
 *
 * Rebuilt whole on every change rather than driven by an adapter. The list is a handful of networks
 * and a few dozen rows, it changes only when Coxy scans or renames something, and diffing that is
 * more machinery than the screen earns.
 */
class DevicesPanel(
    private val root: View,
    private val scope: CoroutineScope
) {
    private val context: Context = root.context
    private val list: LinearLayout = root.findViewById(R.id.devices_list)
    private val summary: TextView = root.findViewById(R.id.devices_summary)
    private val scanButton: Button = root.findViewById(R.id.devices_scan)

    private var scanning = false

    /**
     * Networks whose unidentified devices are expanded.
     *
     * Collapsed by default because they are the overwhelming majority — 39 of 44 on the network
     * this was built against — and a list where the five things you can actually command are
     * pushed off the bottom by forty rows saying "unidentified" is a worse list than no list. They
     * stay reachable, because naming one is exactly how it stops being unidentified.
     */
    private val expanded = mutableSetOf<Long>()

    init {
        scanButton.setOnClickListener { scan() }
    }

    /** Redraw from what is remembered. Cheap; safe to call whenever the screen is shown. */
    fun refresh() {
        scope.launch {
            val networks = withContext(Dispatchers.IO) { NetworkMemory.networks(context) }
            val currentId = withContext(Dispatchers.IO) {
                runCatching { LanScanner.currentNetwork(context)?.id }.getOrNull()
            }
            val rows = withContext(Dispatchers.IO) {
                networks.associateWith { NetworkMemory.devices(context, it.id) }
            }
            render(networks, rows, currentId)
        }
    }

    /**
     * A manual sweep, on request only.
     *
     * Never automatic on opening the screen: a sweep pings all 254 addresses and wakes every device
     * on the network, which is a rude thing to do because someone glanced at a list.
     */
    private fun scan() {
        if (scanning) return
        scanning = true
        scanButton.isEnabled = false
        scanButton.text = context.getString(R.string.ghost_devices_scanning)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LanScanner.scan(context, force = true) }.getOrNull()
            }
            scanning = false
            scanButton.isEnabled = true
            scanButton.text = context.getString(R.string.ghost_devices_scan)
            if (result is LanScanner.Result.NotOnWifi) {
                Toast.makeText(context, "Not on Wi-Fi — nothing to scan", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }
    }

    private fun render(
        networks: List<KnownNetwork>,
        devicesByNetwork: Map<KnownNetwork, List<KnownDevice>>,
        currentNetworkId: Long?
    ) {
        list.removeAllViews()
        val total = devicesByNetwork.values.sumOf { it.size }
        summary.text = if (networks.isEmpty()) {
            context.getString(R.string.ghost_devices_empty)
        } else {
            "${networks.size} network${if (networks.size == 1) "" else "s"}, $total devices remembered"
        }

        // Current network first: it is the only one anything can be done to.
        val ordered = networks.sortedByDescending { it.id == currentNetworkId }
        for (network in ordered) {
            list.addView(networkHeader(network, network.id == currentNetworkId))
            val all = devicesByNetwork[network].orEmpty()
            val (named, unnamed) = all.partition { it.isIdentified }
            if (all.isEmpty()) {
                list.addView(caption("Nothing recorded here yet."))
            }
            for (device in named.sortedByDescending { it.lastSeenMs }) {
                list.addView(deviceRow(device, network))
            }
            if (unnamed.isNotEmpty()) {
                val open = network.id in expanded
                list.addView(disclosure(unnamed.size, open) {
                    if (open) expanded.remove(network.id) else expanded.add(network.id)
                    refresh()
                })
                if (open) {
                    for (device in unnamed.sortedByDescending { it.lastSeenMs }) {
                        list.addView(deviceRow(device, network))
                    }
                }
            }
        }
    }

    private fun networkHeader(network: KnownNetwork, isCurrent: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(6))
            isClickable = true
            setOnClickListener { renameNetwork(network) }
        }
        row.addView(TextView(context).apply {
            text = if (isCurrent) "${network.label}  ·  ${context.getString(R.string.ghost_devices_here)}"
                   else network.label
            setTextColor(
                ContextCompat.getColor(
                    context, if (isCurrent) R.color.accent else R.color.on_surface
                )
            )
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        })
        row.addView(TextView(context).apply {
            // The gateway MAC is the identity, so it is shown: it is the thing that makes two
            // networks called "Home" different networks.
            val seen = if (isCurrent) "here now" else "last seen ${LanScannerAge.ago(network.lastSeenMs)}"
            text = "${network.subnet} · gateway ${network.gatewayMac} · $seen · ${network.scanCount} scans"
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_muted))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        return row
    }

    private fun deviceRow(device: KnownDevice, network: KnownNetwork): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_card)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            layoutParams = lp
            isClickable = true
            setOnClickListener { rename(device) }
            setOnLongClickListener { forget(device); true }
        }

        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // A small dot rather than coloured text: it reads at a glance down a long list, and
            // leaves the name itself free to show whether Coxy named it.
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    rightMargin = dp(8)
                }
                setBackgroundResource(R.drawable.bg_status_dot)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(
                        context,
                        if (isFresh(device)) R.color.state_live else R.color.state_idle
                    )
                )
            })
            addView(TextView(context).apply {
                text = device.bestName ?: "unidentified"
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (device.nickname != null) R.color.accent_soft else R.color.on_surface
                    )
                )
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            // What she can actually do to it, which is the question this screen exists to answer.
            addView(TextView(context).apply {
                text = capabilities(device)
                setTextColor(ContextCompat.getColor(context, R.color.on_surface_muted))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
            })
        })

        row.addView(TextView(context).apply {
            val age = if (isFresh(device)) "here now" else LanScannerAge.ago(device.lastSeenMs)
            val kind = device.kind?.let { "$it · " }.orEmpty()
            text = "$kind${device.ip} · $age"
            // Always muted. Forty rows of accent-coloured metadata is forty rows of nothing
            // standing out; freshness is carried by the dot beside the name instead.
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_muted))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        return row
    }

    /** Short flags for what this device will take. */
    private fun capabilities(device: KnownDevice): String = buildList {
        if (device.isAndroidTv) add(if (device.paired) "remote" else "pairable")
        if (device.canControlMedia) add("media")
        if (device.canWake) add("wake")
    }.joinToString(" ")

    private fun isFresh(device: KnownDevice): Boolean =
        System.currentTimeMillis() - device.lastSeenMs < NetworkMemory.FRESH_MS

    private fun rename(device: KnownDevice) {
        val input = EditText(context).apply {
            setText(device.nickname.orEmpty())
            hint = device.name ?: device.ip
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(context, R.style.Theme_Ghost_Dialog)
            .setTitle(device.name ?: device.ip)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        NetworkMemory.name(context, device.id, input.text.toString())
                        // The scan cache still holds the old label; a rename that does not show up
                        // until the next sweep reads as a rename that did not save.
                        LanScanner.invalidate()
                    }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameNetwork(network: KnownNetwork) {
        val input = EditText(context).apply {
            setText(network.nickname.orEmpty())
            hint = network.ssid ?: network.subnet
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(context, R.style.Theme_Ghost_Dialog)
            .setTitle("Name this network")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        NetworkMemory.nameNetwork(context, network.id, input.text.toString())
                    }
                    refresh()
                }
            }
            .setNeutralButton("Forget network") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { NetworkMemory.forgetNetwork(context, network.id) }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun forget(device: KnownDevice) {
        AlertDialog.Builder(context, R.style.Theme_Ghost_Dialog)
            .setTitle("Forget ${device.bestName ?: device.ip}?")
            .setMessage("It'll come back on the next scan if it's still there.")
            .setPositiveButton("Forget") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        NetworkMemory.forgetDevice(context, device.id)
                        LanScanner.invalidate()
                    }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "39 unidentified — show" / "hide". Tappable, and honest about how many are hidden. */
    private fun disclosure(count: Int, open: Boolean, onTap: () -> Unit) = TextView(context).apply {
        text = "$count unidentified · ${if (open) "hide" else "show"}"
        setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
        textSize = 12f
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isClickable = true
        setOnClickListener { onTap() }
    }

    private fun caption(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.on_surface_muted))
        textSize = 12f
        setPadding(dp(2), dp(4), 0, dp(4))
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}

/** Shared "how long ago" wording, so the screen and GHOST's replies say the same thing. */
object LanScannerAge {
    fun ago(ms: Long): String {
        val mins = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
        return when {
            mins < 2 -> "a moment ago"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            mins < 60 * 24 * 2 -> "yesterday"
            else -> "${mins / (60 * 24)}d ago"
        }
    }
}
