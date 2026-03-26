package io.github.carstenleue.sshfsprovider

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Full-screen help activity explaining how to use SSH FS Provider.
 *
 * Launched from the overflow menu in [ConfigImportActivity].
 * The Up button navigates back to [ConfigImportActivity] as declared in
 * AndroidManifest.xml via `android:parentActivityName`.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
