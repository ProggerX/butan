package dev.butan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.butan.nfc.NfcGateway
import dev.butan.nfc.NfcSlot
import dev.butan.ui.ButanRoot
import dev.butan.ui.theme.ButanTheme

class MainActivity : ComponentActivity() {
    private lateinit var nfc: NfcGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfc = NfcGateway(this)
        setContent {
            ButanTheme {
                ButanRoot(nfc)
            }
        }
    }

    override fun onPause() {
        nfc.stopReading()
        NfcSlot.disarm()
        super.onPause()
    }
}
