package roro.stellar.manager.shortcut

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import roro.stellar.Stellar
import roro.stellar.manager.R
import roro.stellar.manager.db.AppDatabase
import java.util.concurrent.Executors

class CommandShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val commandId = intent.takeIf { it.action == ACTION_EXECUTE_COMMAND }
            ?.getStringExtra(EXTRA_COMMAND_ID)
        if (commandId == null) {
            finish()
            return
        }
        val appContext = applicationContext
        finish()
        executor.execute {
            val command = runBlocking {
                AppDatabase.get(appContext).commandDao().getAll()
                    .firstOrNull { it.id == commandId }
            }
            val message = when {
                command == null -> R.string.shortcut_command_not_found
                !Stellar.pingBinder() -> R.string.service_not_running
                else -> try {
                    Stellar.newProcess(arrayOf("sh", "-c", command.command), null, null)
                    R.string.shortcut_command_started
                } catch (_: Throwable) {
                    R.string.execution_failed
                }
            }
            mainHandler.post {
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val ACTION_EXECUTE_COMMAND = "roro.stellar.manager.action.EXECUTE_COMMAND_SHORTCUT"
        const val EXTRA_COMMAND_ID = "command_id"
        private val executor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
