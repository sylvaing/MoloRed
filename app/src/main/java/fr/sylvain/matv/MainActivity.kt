package fr.sylvain.matv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Channel grid. Opens straight into the last watched channel when the app starts. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private val adapter = ChannelCardAdapter(scope, onClick = { openChannel(it) })
    private var autoStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        status = findViewById(R.id.status)
        grid.layoutManager = GridLayoutManager(this, 6)
        grid.adapter = adapter
        autoStart = savedInstanceState == null
        if (!Auth.canAuthenticate(this)) startLogin() else load()
    }

    override fun onResume() {
        super.onResume()
        adapter.playingId = Repo.lastChannel(this)
        adapter.notifyDataSetChanged() // refresh "now" titles
        focusLastChannel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startLogin() {
        status.text = getString(R.string.login_needed)
        status.visibility = View.VISIBLE
        startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN) {
            if (resultCode == RESULT_OK) load() else finish()
        }
    }

    private fun load() {
        status.text = getString(R.string.loading)
        status.visibility = View.VISIBLE
        scope.launch {
            try {
                val token = Auth.validToken(this@MainActivity) ?: return@launch startLogin()
                try {
                    Repo.loadChannels(token)
                } catch (e: AuthExpiredException) {
                    Auth.invalidate(this@MainActivity)
                    val t2 = Auth.validToken(this@MainActivity) ?: return@launch startLogin()
                    Repo.loadChannels(t2)
                }
                status.visibility = View.GONE
                adapter.notifyDataSetChanged()
                val last = Repo.lastChannel(this@MainActivity)
                if (autoStart && last != null && Repo.channels.any { it.serviceId == last }) {
                    autoStart = false
                    openChannel(Repo.channels.indexOfFirst { it.serviceId == last })
                } else {
                    focusLastChannel()
                }
                Repo.refreshEpgIfNeeded()
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                status.text = getString(R.string.load_error, e.message ?: e.javaClass.simpleName)
                status.visibility = View.VISIBLE
            }
        }
    }

    private fun focusLastChannel() {
        val last = Repo.lastChannel(this) ?: return
        val i = Repo.channels.indexOfFirst { it.serviceId == last }
        if (i < 0) return
        grid.scrollToPosition(i)
        grid.post { grid.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
    }

    private fun openChannel(index: Int) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, index))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(arrayOf(getString(R.string.reload), getString(R.string.logout))) { _, which ->
                    if (which == 0) load() else { Auth.logout(this); startLogin() }
                }
                .show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object { private const val REQ_LOGIN = 1 }
}
