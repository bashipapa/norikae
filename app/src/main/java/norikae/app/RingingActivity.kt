package norikae.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import norikae.core.TripEvent

/** ロック画面の上に出る、アラーム鳴動中の画面 */
class RingingActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
    }

    private var title by mutableStateOf("")
    private var message by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Text(message, fontSize = 22.sp)
                        Button(
                            onClick = {
                                TripLog.add(this@RingingActivity, TripLog.Category.USER, "鳴動画面の「起きた（止める）」を押した")
                                Ringer.stop(this@RingingActivity)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                        ) { Text("起きた（止める）", fontSize = 24.sp) }
                        OutlinedButton(
                            onClick = {
                                val (_, time) = TripController.now()
                                TripController.handle(this@RingingActivity, TripEvent.UserFinished(time))
                                Ringer.stop(this@RingingActivity)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) { Text("北鈴蘭台で降りた（終了）", fontSize = 18.sp) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        title = intent.getStringExtra(EXTRA_TITLE) ?: "起きて！"
        message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
    }
}
