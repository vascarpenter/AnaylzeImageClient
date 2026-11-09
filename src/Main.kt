import java.awt.Font
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.SwingWorker
import javax.swing.UIManager


// ==============================
// メイン
// ==============================
fun main(args: Array<String>)
{
    val b = AnalyzeImageClient()
}

class AnalyzeImageClient: JFrame()
{
    private var thisApp: AnalyzeImageClient? = null
    private var g: gui? = null

    /*

環境変数

ollama サーバとこのクライアントのIPが違う場合、サーバ側に下記の修正が必要

sudo systemctl stop ollama.service
sudo systemctl edit ollama
# https://qiita.com/ntrlmt/items/4f11d8e2cea2f07c2c11
# ここにあるように、 ### Anything と ### Line の間に以下の環境変数をいれる
#  CONTENT_LENGTHが短いと、「request (4161 tokens) exceeds the available context size (4096 tokens)」などのエラーが出る

[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
Environment="OLLAMA_CONTEXT_LENGTH=8192"
Environment="OLLAMA_MAX_LOADED_MODELS=1"
Environment="OLLAMA_NUM_PARALLEL=1"
Environment="OLLAMA_SCHED_SPREAD=0"
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_KV_CACHE_TYPE=q8_0"

# 終わったら ^Xを押して保存

sudo systemctl daemon-reload
sudo systemctl restart ollama

    */

    private val OLLAMA_LABEL  = "Ollama (/api/chat)"
    private val OPENAI_LABEL  = "OpenAI互換 (/v1/chat/completions)"
    private var syncing = false

    var SERVER_URL = ""

    var MODEL_NAME = ""

    // 画像解析用プロンプト
    var PROMPT = ""
    // APIキー（未入力ならヘッダー付与しない）
    var API_KEY = ""

    /** URL文字列からAPI形式ラベルを推定する */
    private fun detectFormat(url: String): String {
        val u = url.trim().lowercase()
        return when {
            u.contains("/v1/") || u.contains("8080") -> OPENAI_LABEL   // OpenAI互換
            else -> OLLAMA_LABEL                                  // /api/chat は Ollama
        }
    }

    /** URLのホスト部（scheme://host:port）を取り出す */
    private fun hostPart(url: String): String {
        val u = url.trim()
        val scheme = u.indexOf("://")
        if (scheme < 0) return u
        val pathSlash = u.indexOf('/', scheme + 3)
        return if (pathSlash < 0) u else u.substring(0, pathSlash)
    }

    /** API形式ラベルに対応するURL（ホストは現在の値を維持）を返す */
    private fun urlForFormat(label: String): String {
        val base = hostPart(g!!.serverComboBox.editor.item?.toString().orEmpty())
        return if (label == OPENAI_LABEL) "$base/v1/chat/completions"
        else                      "$base/api/chat"
    }

    init
    {
        thisApp = this
        g = gui()
        contentPane = g!!.panel
        title = "v1.0 analyze image and write prompt using llm"
        defaultCloseOperation = EXIT_ON_CLOSE
        UIManager.put("OptionPane.messageFont", Font("Dialog", Font.PLAIN, 12))
        UIManager.put("OptionPane.buttonFont", Font("Dialog", Font.PLAIN, 12))

        SERVER_URL = /*System.getenv("OLLAMA_SERVER_URL")?:*/ "http://192.168.11.100:8888/v1/chat/completions" // "http://192.168.11.100:8080/v1/chat/completions" //
        MODEL_NAME = /*System.getenv("OLLAMA_SERVER_MODEL")?:*/ "hf.co/OBLITERATUS/Qwen3.8-27B-OBLITERATED:Q2_K"
        PROMPT = System.getenv("OLLAMA_SERVER_PROMPT")?:  """
make a very detailed description for stable diffusion anima of the image in one line, 
but remove hairstyle and hair color and eye color and breast size of woman character specifications 
from the description in English natural language style, not danbooru tag style."""
        API_KEY = System.getenv("OPENAI_API_KEY") ?: "apikey"
        g!!.apiPasswordField.text = API_KEY

        val serverPresets = arrayOf(
            "http://10.8.0.6:11434/api/chat",
            "http://10.8.0.10:11434/api/chat",
            "http://192.168.11.100:11434/api/chat",
            "http://192.168.11.100:8888/v1/chat/completions"
        )
        g!!.serverComboBox.isEditable = true
        serverPresets.forEach { preset -> g!!.serverComboBox.addItem(preset) }
        g!!.serverComboBox.selectedItem = SERVER_URL

        g!!.apiFormatComboBox.addItem(OLLAMA_LABEL)
        g!!.apiFormatComboBox.addItem(OPENAI_LABEL)
        syncing = true
        g!!.apiFormatComboBox.selectedItem =
            detectFormat(g!!.serverComboBox.editor.item?.toString().orEmpty())
        syncing = false

        g!!.modelTextField.text = MODEL_NAME
        g!!.promptTextField.text = PROMPT

        setSize(900, 600)
        isResizable = false
        setLocationRelativeTo(null)
        isVisible = true

        g!!.selectDirectoryButton.addActionListener {
            startAnalysis()
        }
        // URL を変えたら → API形式を自動選択
        g!!.serverComboBox.addActionListener {
            if (syncing) return@addActionListener
            syncing = true
            g!!.apiFormatComboBox.selectedItem =
                detectFormat(g!!.serverComboBox.editor.item?.toString().orEmpty())
            syncing = false
        }

        // API形式を変えたら → URLのパス部分を追随させる
        g!!.apiFormatComboBox.addActionListener {
            if (syncing) return@addActionListener
            syncing = true
            g!!.serverComboBox.editor.item =
                urlForFormat(g!!.apiFormatComboBox.selectedItem?.toString().orEmpty())
            syncing = false
        }
    }

    private fun startAnalysis()
    {
        g!!.selectDirectoryButton.isEnabled = false

        MODEL_NAME = g!!.modelTextField.text
        SERVER_URL = g!!.serverComboBox.editor.item?.toString()?.trim().orEmpty()
        PROMPT = g!!.promptTextField.text
        API_KEY = g!!.apiPasswordField.text.trim()

        val apiFormat = when (g!!.apiFormatComboBox.selectedItem?.toString()) {
            "OpenAI互換 (/v1/chat/completions)" -> ApiFormat.OPENAI_COMPATIBLE
            else -> ApiFormat.OLLAMA
        }

        val fc = JFileChooser()
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)      // select directory only
        val returnVal = fc.showOpenDialog(thisApp)
        if (returnVal != JFileChooser.APPROVE_OPTION) {
            g!!.selectDirectoryButton.isEnabled = true
            return
        }
        val dir = fc.selectedFile

        val worker =
            object : SwingWorker<Unit, UpdateData>()
            {
                override fun doInBackground()
                {

                    // HTTPクライアント
                    val client = HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(30))
                        .build()

                    // 対象画像を取得
                    val images = Files.list(dir.toPath())
                        .use { stream ->
                            stream
                                .filter { isImageFile(it) }
                                .sorted()
                                .toList()
                        }

                    publish(
                        UpdateData.Message("画像枚数: ${images.size}")
                    )

                    // 画像を1枚ずつ処理
                    for ((index, imagePath) in images.withIndex())
                    {
                        publish(
                            UpdateData.Message("画像: ${imagePath.fileName} の解析をサーバに問い合わせ中")
                        )

                        try
                        {
                            val result = analyzeImage(
                                client = client,
                                imagePath = imagePath,
                                modelName = MODEL_NAME,
                                prompt = PROMPT,
                                serverURL = SERVER_URL,
                                apiFormat = apiFormat,
                                apiKey = API_KEY
                            )

                            // 結果を保存
                            saveResult(
                                imagePath = imagePath,
                                result = result
                            )
                            publish(
                                UpdateData.Message("解析終了: $result")
                            )

                        }
                        catch (e: Exception)
                        {

                            publish(
                                UpdateData.Result("解析失敗: ${imagePath.fileName}")
                            )
                            System.err.println(e.message)
                        }

                    }

                }

                override fun process(
                    chunks: MutableList<UpdateData>
                )
                {

                    /*
                     * process() はSwingのEDT上で
                     * 実行されるのでGUIを安全に更新できる。
                     */

                    for (data in chunks)
                    {
                        updateGui(data)
                    }
                }


                override fun done() {
                    try {
                        // doInBackground() の例外をここで検出する
                        get()
                        g!!.textArea1.append("\n\n**全画像の処理が正常終了しました**")

                    } catch (e: java.util.concurrent.ExecutionException) {
                        val cause = e.cause ?: e

                        val message =
                            "\n\n**異常終了しました**\n" +
                                    "原因: ${cause.javaClass.name}\n" +
                                    "詳細: ${cause.message}"

                        g!!.textArea1.append(message)
                        System.err.println(message)
                        cause.printStackTrace()

                    } catch (e: java.util.concurrent.CancellationException) {
                        g!!.textArea1.append("\n\n**処理がキャンセルされました**")

                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        g!!.textArea1.append("\n\n**終了確認が中断されました**")

                    } finally {
                        g!!.selectDirectoryButton.isEnabled = true
                    }
                }

            }

        worker.execute()

    }


    private fun updateGui(
        data: UpdateData
    )
    {

        when (data)
        {

            is UpdateData.Message ->
            {

                g!!.textArea1.append(
                    "\n${data.text}"
                )

                /*
                 * 最後まで自動スクロール
                 */

                g!!.textArea1.caretPosition =
                    g!!.textArea1.document.length
            }

            is UpdateData.Result ->
            {

                g!!.textArea1.append(
                    "\n${data.text}\n"
                )

                g!!.textArea1.caretPosition =
                    g!!.textArea1.document.length
            }
        }
    }

}

// ==============================
// 画像解析
// ==============================

enum class ApiFormat {
    OLLAMA,
    OPENAI_COMPATIBLE
}

// ==============================
// 画像解析
// ==============================

fun analyzeImage(
    client: HttpClient,
    imagePath: Path,
    modelName: String,
    prompt: String,
    serverURL: String,
    apiFormat: ApiFormat,
    apiKey: String
): String {

    val imageBytes = Files.readAllBytes(imagePath)
    val imageBase64 = Base64.getEncoder().encodeToString(imageBytes)
    val mimeType = getMimeType(imagePath)

    val json = when (apiFormat) {
        ApiFormat.OLLAMA -> """
            {
              "model": ${jsonString(modelName)},
              "messages": [
                {
                  "role": "user",
                  "content": ${jsonString(prompt)},
                  "images": [${jsonString(imageBase64)}]
                }
              ],
              "stream": false
            }
        """.trimIndent()

        ApiFormat.OPENAI_COMPATIBLE -> {
            val dataUrl = "data:$mimeType;base64,$imageBase64"
            """
            {
              "model": ${jsonString(modelName)},
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {
                      "type": "text",
                      "text": ${jsonString(prompt)}
                    },
                    {
                      "type": "image_url",
                      "image_url": {
                        "url": ${jsonString(dataUrl)}
                      }
                    }
                  ]
                }
              ],
              "stream": false
            }
            """.trimIndent()
        }
    }

    System.err.println("API形式: $apiFormat")
    System.err.println("送信先: $serverURL")
    System.err.println("送信画像: $imagePath")
    System.err.println("画像サイズ: ${imageBytes.size} bytes / MIME: $mimeType")

    val builder = HttpRequest.newBuilder()
        .uri(URI.create(serverURL))
        .header("Content-Type", "application/json")
        .timeout(java.time.Duration.ofMinutes(10))
    if (apiKey.isNotBlank()) {
        builder.header("Authorization", "Bearer $apiKey")
    }
    val request = builder
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() !in 200..299) {
        throw RuntimeException("APIエラー (${response.statusCode()})\n${response.body()}")
    }

    val body = response.body()
    return when (apiFormat) {
        ApiFormat.OLLAMA -> {
            val messageStart = body.indexOf("\"message\"")
            if (messageStart < 0) {
                throw RuntimeException("Ollamaレスポンスに message がありません: $body")
            }
            extractJsonStringValue(body, "content", messageStart)
                ?: throw RuntimeException("Ollamaレスポンスから message.content を取得できません: $body")
        }

        ApiFormat.OPENAI_COMPATIBLE -> {
            val choicesStart = body.indexOf("\"choices\"")
            if (choicesStart < 0) {
                throw RuntimeException("OpenAI互換レスポンスに choices がありません: $body")
            }
            val messageStart = body.indexOf("\"message\"", choicesStart)
            if (messageStart < 0) {
                throw RuntimeException("OpenAI互換レスポンスに message がありません: $body")
            }
            extractJsonStringValue(body, "content", messageStart)
                ?: throw RuntimeException("OpenAI互換レスポンスから choices[0].message.content を取得できません: $body")
        }
    }
}

// 指定位置以降にある JSON 文字列プロパティを取得する
fun extractJsonStringValue(json: String, key: String, fromIndex: Int = 0): String? {
    val keyToken = "\"$key\""
    val keyIndex = json.indexOf(keyToken, fromIndex)
    if (keyIndex < 0) return null

    var pos = json.indexOf(':', keyIndex + keyToken.length)
    if (pos < 0) return null
    pos++

    while (pos < json.length && json[pos].isWhitespace()) pos++
    if (pos >= json.length || json[pos] != '"') return null
    pos++

    val result = StringBuilder()
    var escaped = false
    while (pos < json.length) {
        val c = json[pos++]
        if (escaped) {
            when (c) {
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    if (pos + 4 <= json.length) {
                        val code = json.substring(pos, pos + 4).toIntOrNull(16) ?: return null
                        result.append(code.toChar())
                        pos += 4
                    } else return null
                }
                else -> result.append(c)
            }
            escaped = false
        } else {
            when (c) {
                '\\' -> escaped = true
                '"' -> return result.toString()
                else -> result.append(c)
            }
        }
    }
    return null
}

// GUI更新用データ
// =========================================

sealed class UpdateData {

    data class Message(
        val text: String
    ) : UpdateData()

    data class Result(
        val text: String
    ) : UpdateData()
}


// ==============================
// JSONから回答を取り出す
// ==============================

fun extractContent(json: String): String {

    /*
     * 簡易的なJSON解析。
     *
     * 本格的に使う場合は Jackson / kotlinx.serialization
     * などを使用することを推奨します。
     */

    val key = "\"content\""

    val start = json.indexOf(key)

    if (start < 0) {
        return json
    }

    val colon = json.indexOf(':', start)

    if (colon < 0) {
        return json
    }

    var pos = colon + 1

    while (pos < json.length &&
        json[pos].isWhitespace()
    ) {
        pos++
    }

    if (pos >= json.length || json[pos] != '"') {
        return json
    }

    pos++

    val result = StringBuilder()

    var escaped = false

    while (pos < json.length) {

        val c = json[pos]

        if (escaped) {

            when (c) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                else -> result.append(c)
            }

            escaped = false

        } else {

            when (c) {

                '\\' -> {
                    escaped = true
                }

                '"' -> {
                    break
                }

                else -> {
                    result.append(c)
                }
            }
        }

        pos++
    }

    return result.toString()
}


// ==============================
// 結果保存
// ==============================

fun saveResult(
    imagePath: Path,
    result: String
) {

    val fileName =
        imagePath.fileName.toString()

    val baseName =
        fileName.substringBeforeLast('.')

    val outputPath = imagePath.parent.resolve("$baseName.txt")

    Files.writeString(
        outputPath,
        result
    )

//    println("保存: $outputPath")
}


// ==============================
// 画像ファイル判定
// ==============================

fun isImageFile(path: Path): Boolean {

    if (!Files.isRegularFile(path)) {
        return false
    }

    val name =
        path.fileName.toString()
            .lowercase()

    return name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".webp")
}


// ==============================
// MIMEタイプ
// ==============================

fun getMimeType(path: Path): String {

    val name =
        path.fileName.toString()
            .lowercase()

    return when {

        name.endsWith(".jpg") ||
                name.endsWith(".jpeg") ->
            "image/jpeg"

        name.endsWith(".png") ->
            "image/png"

        name.endsWith(".webp") ->
            "image/webp"

        else ->
            "application/octet-stream"
    }
}


// ==============================
// JSON文字列エスケープ
// ==============================

fun jsonString(value: String): String {

    return buildString {

        append('"')

        for (c in value) {

            when (c) {

                '"' ->
                    append("\\\"")

                '\\' ->
                    append("\\\\")

                '\n' ->
                    append("\\n")

                '\r' ->
                    append("\\r")

                '\t' ->
                    append("\\t")

                '\b' ->
                    append("\\b")

                '\u000C' ->
                    append("\\f")

                else ->
                    append(c)
            }
        }

        append('"')
    }
}