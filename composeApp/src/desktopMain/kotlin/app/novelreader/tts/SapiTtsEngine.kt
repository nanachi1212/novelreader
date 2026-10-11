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
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Windows SAPI 朗讀引擎：長駐一個 powershell.exe（System.Speech.SpeechSynthesizer），
 * 經 stdin/stdout 以 TAB 分隔的行協定溝通。
 *
 * 協定（→ 送出 / ← 收到）：
 *   → VOICES                       ← VOICE\t名稱\t文化 ...多行... VOICES_DONE
 *   → SPEAK\tid\tsapiRate\t語音\t文字   ← DONE\tid（正常唸完才回；被取消不回）
 *   → STOP / EXIT                  ← READY（啟動完成）、FATAL\t訊息（初始化失敗）
 *
 * PowerShell 端用 ReadLineAsync 輪詢 + Prompt.IsCompleted 單執行緒處理，
 * 朗讀中仍能即時收 STOP。過期的 DONE 由 Kotlin 端以 id 過濾。
 */
class SapiTtsEngine : TtsEngine {

    private val lock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    private val idGen = AtomicLong(0)

    /** 目前唸的段落 id 與回呼；DONE 的 id 不符就丟棄 */
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
                if (!send("VOICES")) return@withContext emptyList()
            }
            val voices = withTimeoutOrNull(10_000) { deferred.await() } ?: emptyList()
            // 中文語音排前面：zh-TW → zh-CN → 其他 zh → 其餘
            val sorted = voices.sortedBy { v ->
                when {
                    v.language.equals("zh-TW", true) -> 0
                    v.language.equals("zh-CN", true) -> 1
                    v.language.startsWith("zh", true) -> 2
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
            // 使用者語速 0.5–4.0 → SAPI -10..10（對數映射：0.5x≈-10、1x=0、2x=+10）；
            // SAPI Rate 硬限制在 ±10，4x 對數值會超出，故最後夾到合法範圍（實際唸速約封頂在 SAPI 的 +10=~3x）
            val sapiRate = (ln(rate.coerceIn(0.5f, 4f).toDouble()) / ln(2.0) * 10).roundToInt().coerceIn(-10, 10)
            return send("SPEAK\t$id\t$sapiRate\t${voiceId.orEmpty()}\t$clean")
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

    // ---- 內部 ----

    /** 呼叫端不需持 lock；內部自行同步。回傳 false 表示啟動失敗 */
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
            Thread({ readerLoop(p) }, "sapi-tts-reader").apply { isDaemon = true }.start()
            // 等 READY（同步等待最多 15 秒；powershell 冷啟約 1–3 秒）
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

    /** 需在 lock 內呼叫 */
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
        val pendingVoices = ArrayList<TtsVoice>()
        try {
            BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val parts = line.split('\t')
                    when (parts[0]) {
                        "READY" -> readyDeferred?.complete(true)
                        "FATAL" -> readyDeferred?.complete(false)
                        "VOICE" -> if (parts.size >= 3) {
                            pendingVoices.add(TtsVoice(id = parts[1], label = parts[1], language = parts[2]))
                        }
                        "VOICES_DONE" -> {
                            voicesDeferred?.complete(pendingVoices.toList())
                            voicesDeferred = null
                            pendingVoices.clear()
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

    private companion object {
        val WORKER_SCRIPT = """
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
  Add-Type -AssemblyName System.Speech -ErrorAction Stop
  ${'$'}syn = New-Object System.Speech.Synthesis.SpeechSynthesizer
  ${'$'}syn.SetOutputToDefaultAudioDevice()
} catch {
  [Console]::Out.WriteLine("FATAL`t" + ${'$'}_.Exception.Message)
  exit 1
}
[Console]::Out.WriteLine("READY")
# 不能用 [Console]::In：SyncTextReader 的 ReadLineAsync 是同步的，會卡住輪詢迴圈
${'$'}stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), (New-Object System.Text.UTF8Encoding(${'$'}false)))
${'$'}pending = ${'$'}null
${'$'}current = ${'$'}null
${'$'}currentId = ''
${'$'}running = ${'$'}true
while (${'$'}running) {
  if (${'$'}null -eq ${'$'}pending) { ${'$'}pending = ${'$'}stdin.ReadLineAsync() }
  ${'$'}gotLine = ${'$'}false
  try { ${'$'}gotLine = ${'$'}pending.Wait(50) } catch { break }
  if (${'$'}gotLine) {
    ${'$'}line = ${'$'}pending.Result
    ${'$'}pending = ${'$'}null
    if (${'$'}null -eq ${'$'}line) { break }
    ${'$'}parts = ${'$'}line.Split("`t")
    switch (${'$'}parts[0]) {
      'VOICES' {
        foreach (${'$'}v in ${'$'}syn.GetInstalledVoices()) {
          if (${'$'}v.Enabled) {
            ${'$'}vi = ${'$'}v.VoiceInfo
            [Console]::Out.WriteLine("VOICE`t" + ${'$'}vi.Name + "`t" + ${'$'}vi.Culture.Name)
          }
        }
        [Console]::Out.WriteLine("VOICES_DONE")
      }
      'SPEAK' {
        ${'$'}syn.SpeakAsyncCancelAll()
        ${'$'}currentId = ${'$'}parts[1]
        try { ${'$'}syn.Rate = [int]${'$'}parts[2] } catch {}
        if (${'$'}parts[3] -ne '') { try { ${'$'}syn.SelectVoice(${'$'}parts[3]) } catch {} }
        ${'$'}current = ${'$'}syn.SpeakAsync(${'$'}parts[4])
      }
      'STOP' {
        ${'$'}current = ${'$'}null
        ${'$'}syn.SpeakAsyncCancelAll()
      }
      'EXIT' { ${'$'}running = ${'$'}false }
    }
  }
  if (${'$'}null -ne ${'$'}current -and ${'$'}current.IsCompleted) {
    [Console]::Out.WriteLine("DONE`t" + ${'$'}currentId)
    ${'$'}current = ${'$'}null
  }
}
${'$'}syn.SpeakAsyncCancelAll()
${'$'}syn.Dispose()
""".trimIndent()
    }
}
