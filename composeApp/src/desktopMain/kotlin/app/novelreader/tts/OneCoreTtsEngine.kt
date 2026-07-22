package app.novelreader.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Windows OneCore 朗讀引擎。
 *
 * Windows 的自然／OneCore 聲音（例如 Yating、Zhiwei）不會出現在
 * System.Speech.Synthesis.SpeechSynthesizer 的 SAPI 清單中，因此這裡透過
 * Windows.Media.SpeechSynthesis + MediaPlayer 使用系統內建 API，完全不需要
 * 第三方服務或付費帳號。
 */
class OneCoreTtsEngine : TtsEngine {

    private val lock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val idGen = AtomicLong(0)

    @Volatile private var currentId = 0L
    @Volatile private var currentOnDone: (() -> Unit)? = null
    @Volatile private var voicesDeferred: CompletableDeferred<List<TtsVoice>>? = null
    @Volatile private var readyDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var voicesCache: List<TtsVoice>? = null

    override suspend fun listVoices(): List<TtsVoice> {
        voicesCache?.let { return it }
        return withContext(Dispatchers.IO) {
            if (!ensureProcess()) return@withContext emptyList()
            val deferred = CompletableDeferred<List<TtsVoice>>()
            synchronized(lock) {
                voicesDeferred = deferred
                pendingVoices.clear()
                if (!send("VOICES")) return@withContext emptyList()
            }
            val voices = withTimeoutOrNull(10_000) { deferred.await() } ?: emptyList()
            val sorted = voices.sortedBy { voice ->
                when {
                    voice.language.equals("zh-TW", true) -> 0
                    voice.language.equals("zh-CN", true) -> 1
                    voice.language.startsWith("zh", true) -> 2
                    else -> 3
                }
            }
            voicesCache = sorted
            sorted
        }
    }

    override fun speak(text: String, voiceId: String?, rate: Float, onDone: () -> Unit): Boolean {
        val clean = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        if (clean.isEmpty()) {
            onDone()
            return true
        }
        synchronized(lock) {
            if (!ensureProcess()) return false
            val id = idGen.incrementAndGet()
            currentId = id
            currentOnDone = onDone
            // OneCore SpeakingRate 的實際範圍約為 0.5–2.0；保留 App 的 0.5–4.0
            // UI 範圍，超過引擎上限時以 2.0 播放，避免整段派送失敗。
            val oneCoreRate = rate.coerceIn(0.5f, 2.0f)
            return send("SPEAK\t$id\t$oneCoreRate\t${voiceId.orEmpty()}\t$clean")
        }
    }

    override fun stop() {
        synchronized(lock) {
            currentId = 0
            currentOnDone = null
            if (process?.isAlive == true) send("STOP")
        }
    }

    override fun shutdown() {
        synchronized(lock) {
            currentId = 0
            currentOnDone = null
            val p = process ?: return
            try {
                send("EXIT")
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
            } catch (_: Exception) {
                p.destroy()
            }
            process = null
            writer = null
        }
    }

    private fun ensureProcess(): Boolean = synchronized(lock) {
        process?.let { if (it.isAlive) return true }
        process = null
        writer = null
        try {
            val encoded = Base64.getEncoder()
                .encodeToString(WORKER_SCRIPT.toByteArray(StandardCharsets.UTF_16LE))
            val p = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
            ).redirectError(Redirect.DISCARD).start()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
            val ready = CompletableDeferred<Boolean>()
            readyDeferred = ready
            Thread({ readerLoop(p) }, "onecore-tts-reader").apply { isDaemon = true }.start()
            val ok = try {
                kotlinx.coroutines.runBlocking { withTimeoutOrNull(15_000) { ready.await() } } == true
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                p.destroy()
                process = null
                writer = null
            }
            ok
        } catch (_: Exception) {
            process = null
            writer = null
            false
        }
    }

    private fun send(line: String): Boolean = try {
        val w = writer ?: return false
        w.write(line)
        w.write("\n")
        w.flush()
        true
    } catch (_: Exception) {
        false
    }

    private fun readerLoop(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val parts = line.split('\t')
                    when (parts[0]) {
                        "READY" -> readyDeferred?.complete(true)
                        "FATAL" -> readyDeferred?.complete(false)
                        "VOICE" -> if (parts.size >= 3) {
                            pendingVoices.add(
                                TtsVoice(id = parts[1], label = parts[1], language = parts[2]),
                            )
                        }
                        "VOICES_DONE" -> {
                            voicesDeferred?.complete(pendingVoices.toList())
                            voicesDeferred = null
                        }
                        "DONE" -> {
                            val id = parts.getOrNull(1)?.toLongOrNull() ?: continue
                            val cb = synchronized(lock) {
                                if (id == currentId) {
                                    val c = currentOnDone
                                    currentId = 0
                                    currentOnDone = null
                                    c
                                } else null
                            }
                            cb?.invoke()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            readyDeferred?.complete(false)
            voicesDeferred?.complete(emptyList())
        }
    }

    private val pendingVoices = ArrayList<TtsVoice>()

    private companion object {
        val WORKER_SCRIPT = """
Add-Type -AssemblyName System.Runtime.WindowsRuntime
try {
  ${'$'}null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer,Windows.Media.SpeechSynthesis,ContentType=WindowsRuntime]
  ${'$'}null = [Windows.Media.Playback.MediaPlayer,Windows.Media.Playback,ContentType=WindowsRuntime]
  ${'$'}null = [Windows.Media.Core.MediaSource,Windows.Media.Core,ContentType=WindowsRuntime]
  ${'$'}synth = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer
  ${'$'}player = New-Object Windows.Media.Playback.MediaPlayer
  ${'$'}asTaskMethod = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.IsGenericMethodDefinition -and
    ${'$'}_.GetGenericArguments().Count -eq 1 -and ${'$'}_.GetParameters().Count -eq 1 -and
    ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
  }
  ${'$'}voiceMap = @{}
  foreach (${ '$' }v in [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices) {
    ${'$'}voiceMap[${'$'}v.DisplayName] = ${'$'}v
  }
} catch {
  [Console]::Out.WriteLine("FATAL`t" + ${'$'}_.Exception.Message)
  exit 1
}
[Console]::Out.WriteLine("READY")
${'$'}stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), (New-Object System.Text.UTF8Encoding(${ '$' }false)))
${'$'}pending = ${'$'}null
${'$'}currentId = ''
${'$'}running = ${'$'}true

function Get-SpeechStream(${ '$' }text) {
  ${'$'}op = ${'$'}synth.SynthesizeTextToStreamAsync(${ '$' }text)
  ${'$'}task = ${'$' }asTaskMethod.MakeGenericMethod([Windows.Media.SpeechSynthesis.SpeechSynthesisStream]).Invoke(${ '$' }null, @(${ '$' }op))
  return ${'$'}task.GetAwaiter().GetResult()
}

function Stop-Playback {
  ${'$'}player.Pause()
  ${'$'}player.Source = ${'$'}null
}

while (${ '$' }running) {
  if (${ '$' }null -eq ${ '$' }pending) { ${ '$' }pending = ${ '$' }stdin.ReadLineAsync() }
  ${ '$' }gotLine = ${ '$' }false
  try { ${ '$' }gotLine = ${ '$' }pending.Wait(50) } catch { break }
  if (${ '$' }gotLine) {
    ${ '$' }line = ${ '$' }pending.Result
    ${ '$' }pending = ${ '$' }null
    if (${ '$' }null -eq ${ '$' }line) { break }
    ${ '$' }parts = ${ '$' }line -split "`t", 5
    switch (${ '$' }parts[0]) {
      'VOICES' {
        foreach (${ '$' }v in ${ '$' }voiceMap.Values) {
          [Console]::Out.WriteLine("VOICE`t" + ${ '$' }v.DisplayName + "`t" + ${ '$' }v.Language)
        }
        [Console]::Out.WriteLine("VOICES_DONE")
      }
      'SPEAK' {
        Stop-Playback
        ${ '$' }currentId = ${ '$' }parts[1]
        try { ${ '$' }synth.Options.SpeakingRate = [double](${ '$' }parts[2]) } catch {}
        ${ '$' }requested = ${ '$' }parts[3]
        if (${ '$' }voiceMap.ContainsKey(${ '$' }requested)) {
          ${ '$' }synth.Voice = ${ '$' }voiceMap[${ '$' }requested]
        } elseif (${ '$' }requested -match ' Desktop$' -and ${ '$' }voiceMap.ContainsKey(${ '$' }requested -replace ' Desktop$','')) {
          ${ '$' }synth.Voice = ${ '$' }voiceMap[${ '$' }requested -replace ' Desktop$','']
        }
        try {
          ${ '$' }stream = Get-SpeechStream ${ '$' }parts[4]
          ${ '$' }player.Source = [Windows.Media.Core.MediaSource]::CreateFromStream(${ '$' }stream, ${ '$' }stream.ContentType)
          ${ '$' }player.Play()
        } catch {
          [Console]::Out.WriteLine("DONE`t" + ${ '$' }currentId)
          ${ '$' }currentId = ''
        }
      }
      'STOP' {
        ${ '$' }currentId = ''
        Stop-Playback
      }
      'EXIT' { ${ '$' }running = ${ '$' }false }
    }
  }
  if (${ '$' }currentId -ne '') {
    try {
      ${ '$' }session = ${ '$' }player.PlaybackSession
      ${ '$' }duration = ${ '$' }session.NaturalDuration.TotalMilliseconds
      if (${ '$' }duration -gt 0 -and ${ '$' }session.Position.TotalMilliseconds -ge ${ '$' }duration -and ${ '$' }session.PlaybackState -eq 'Paused') {
        [Console]::Out.WriteLine("DONE`t" + ${ '$' }currentId)
        ${ '$' }currentId = ''
      }
    } catch {}
  }
}
Stop-Playback
${ '$' }player.Dispose()
""".trimIndent()
    }
}
