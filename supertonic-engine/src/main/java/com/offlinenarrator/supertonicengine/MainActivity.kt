package com.offlinenarrator.supertonicengine

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "Bolo Supertonic Engine"
            textSize = 24f
        }

        val info = TextView(this).apply {
            text = "Supertonic 3 · LiteRT · local after setup\n\nDownload the model once, then return to Bolo."
            textSize = 16f
            setPadding(0, dp(16), 0, dp(20))
        }

        val status = TextView(this).apply {
            text = "Not verified yet."
            textSize = 15f
            setPadding(0, 0, 0, dp(20))
        }

        val button = Button(this).apply {
            text = "Download / verify model"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        root.addView(title)
        root.addView(info)
        root.addView(status)
        root.addView(button)
        setContentView(root)

        button.setOnClickListener {
            button.isEnabled = false
            status.text = "Preparing Supertonic model… first setup is a large download."

            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = ModelManager.ensureTtsModels(
                            this@MainActivity,
                            TtsModel.SUPERTONIC,
                        )

                        SpeechSynthesizer(
                            SpeechSynthesizerConfig(
                                modelDir = dir,
                                useNnapi = false,
                                ttsModel = TtsModel.SUPERTONIC,
                            )
                        ).use { synth ->
                            val test = synth.synthesize(
                                "Bolo Supertonic engine is ready.",
                                "en",
                                "F3",
                            )
                            check(test.pcm16.isNotEmpty()) {
                                "Model loaded but produced no audio"
                            }
                        }
                        dir
                    }
                }

                button.isEnabled = true
                status.text = if (result.isSuccess) {
                    "Ready. Return to Bolo and select Supertonic 3."
                } else {
                    "Setup failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
