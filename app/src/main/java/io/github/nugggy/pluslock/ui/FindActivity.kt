package io.github.nugggy.pluslock.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nugggy.pluslock.core.FindPhone
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Prefs

/** The bright, flashing screen shown over the lock screen while find my phone is ringing. */
class FindActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        if (!FindPhone.running) { finish(); return }
        FindPhone.onStopped = { runOnUiThread { finish() } }
        // Back doesn't silence it: someone has to hold the button.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        val flash = P.findFlash.value
        val message = P.findMessage.value
        setContent {
            // Under three flashes a second, to stay clear of photosensitive seizure triggers.
            val tick by produceState(0) { while (true) { kotlinx.coroutines.delay(400); value++ } }
            val bright = flash && tick % 2 == 0
            val bg = if (bright) Color.White else Color(0xFFD7263D)
            val fg = if (bright) Color(0xFFD7263D) else Color.White
            Box(Modifier.fillMaxSize().background(bg).padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Here I am", color = fg, fontSize = 48.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    Text(
                        message.ifBlank { "This phone's owner is looking for it." },
                        color = fg, fontSize = 22.sp, textAlign = TextAlign.Center,
                    )
                    val left = ((FindPhone.stopAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0) + tick * 0
                    Text("Stops by itself in ${left / 60}:%02d".format(left % 60), color = fg, fontSize = 16.sp)
                    Box(
                        Modifier
                            .padding(top = 24.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(fg)
                            .pointerInput(Unit) { detectTapGestures(onLongPress = { FindPhone.stop() }) }
                            .padding(vertical = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Hold to stop", color = bg, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    override fun onDestroy() {
        if (FindPhone.onStopped != null && isFinishing) FindPhone.onStopped = null
        super.onDestroy()
    }
}
