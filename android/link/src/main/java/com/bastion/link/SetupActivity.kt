package com.bastion.link

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One screen: what Link is, whether it is set up, and the import button. */
class SetupActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 3, pad, pad) }
        fun text(s: String, size: Float = 15f) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, 0, 0, pad)
        }.also(col::addView)

        text("BASTION Link", 26f)
        text(
            "This small app connects BASTION to the Meshtastic app's local TAK server, so BASTION can show " +
                "unit positions and TAK chat.\n\n" +
                "It is separate on purpose: Android requires the Internet permission even for a connection " +
                "inside this phone. Link holds that permission and none of your data; BASTION holds your data " +
                "and no Internet permission. Link only ever connects to 127.0.0.1 (this phone) and only BASTION " +
                "can talk to it."
        )
        status = text("")
        Button(this).apply {
            text = "Import TAK data package"
            setOnClickListener {
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK,
                    )
                } catch (_: ActivityNotFoundException) {
                    status.text = "This phone has no file picker app."
                }
            }
        }.also(col::addView)
        Button(this).apply {
            text = "Forget the package"
            setOnClickListener { TakConfig.clear(this@SetupActivity); refresh(null) }
        }.also(col::addView)
        text(
            "\nHow to get the package: Meshtastic → Settings → Advanced → TAK Server → switch on “Enable Local TAK " +
                "Server” and “Mesh to CoT Converter” → “Export TAK Data Package” → save the .zip → import it here. " +
                "Then open BASTION → Mesh."
        )
        setContentView(ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        refresh(null)
    }

    private fun refresh(note: String?) {
        val cfg = TakConfig.load(this)
        status.text = (note?.let { "$it\n\n" } ?: "") +
            if (cfg != null) "Status: set up for ${cfg.host}:${cfg.port}. Open BASTION → Mesh to use it."
            else "Status: not set up — no TAK data package imported yet."
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK || resultCode != RESULT_OK || uri == null) return
        val result = contentResolver.openInputStream(uri)?.use { TakConfig.import(this, it) }
            ?: "Could not open the selected file."
        refresh(result)
    }

    private companion object { const val PICK = 1 }
}
