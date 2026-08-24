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


    var SERVER_URL = ""

    var MODEL_NAME = ""

    // 画像解析用プロンプト
    var PROMPT = ""

    init
    {
        thisApp = this
        g = gui()
        contentPane = g!!.panel
        title = "v1.0 analyze image and write prompt using llm"
        defaultCloseOperation = EXIT_ON_CLOSE
        UIManager.put("OptionPane.messageFont", Font("Dialog", Font.PLAIN, 12))
        UIManager.put("OptionPane.buttonFont", Font("Dialog", Font.PLAIN, 12))

        SERVER_URL = System.getenv("OLLAMA_SERVER_URL")?: "http://127.0.0.1:11434/api/chat"
        MODEL_NAME = System.getenv("OLLAMA_SERVER_MODEL")?: "hf.co/nurdich/Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-MTP-GGUF:IQ2_M"
        PROMPT = System.getenv("OLLAMA_SERVER_PROMPT")?: "provide a very detailed description of the image in one line"
        g!!.modelTextField.text = MODEL_NAME
        g!!.promptTextField.text = PROMPT
        g!!.serverTextField.text = SERVER_URL

        setSize(900, 600)
        isResizable = false
        setLocationRelativeTo(null)
        isVisible = true

        g!!.selectDirectoryButton.addActionListener {
            startAnalysis()
        }

    }

    private fun startAnalysis()
    {
        g!!.selectDirectoryButton.isEnabled = false

        MODEL_NAME = g!!.modelTextField.text
        SERVER_URL = g!!.serverTextField.text
        PROMPT = g!!.promptTextField.text

        val fc = JFileChooser()
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)      // select directory only
        val returnVal = fc.showOpenDialog(thisApp)
        if (returnVal != JFileChooser.APPROVE_OPTION)
            return
        val dir = fc.selectedFile

        val worker =
            object : SwingWorker<Unit, UpdateData>()
            {
                override fun doInBackground()
                {

                    // HTTPクライアント
                    val client = HttpClient.newBuilder()
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
                                serverURL = SERVER_URL
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

                override fun done()
                {

                    g!!.selectDirectoryButton.isEnabled = true

                    g!!.textArea1.append("\n\n**完了**")
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

fun analyzeImage(
    client: HttpClient,
    imagePath: Path,
    modelName: String,
    prompt: String,
    serverURL: String,
): String {

    // 画像を読み込む
    val imageBytes = Files.readAllBytes(imagePath)

    val imageBase64 = Base64.getEncoder()
        .encodeToString(imageBytes)

    // MIMEタイプを判定
    val mimeType = getMimeType(imagePath)

    /*
     * OpenAI Vision API互換形式
     *
     * messages:
     * [
     *   {
     *     role: "user",
     *     content: [
     *       {
     *         type: "text",
     *         text: "..."
     *       },
     *       {
     *         type: "image_url",
     *         image_url: {
     *           url: "data:image/jpeg;base64,..."
     *         }
     *       }
     *     ]
     *   }
     * ]
     */

    val escapedPrompt = prompt
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
    val json = """
        {
          "model": "$modelName",
          "messages": [
            {
              "role": "user",
              "content": "$escapedPrompt",
              "images": [
                "$imageBase64"
              ]
            }
          ],
          "stream": false
        }
    """.trimIndent()

    // HTTP POST
    val request = HttpRequest.newBuilder()
        .uri(URI.create(serverURL))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build()


    val response = client.send(
        request,
        HttpResponse.BodyHandlers.ofString()
    )

    if (response.statusCode() != 200) {
        throw RuntimeException(
            "Ollama API error: ${response.statusCode()}\n${response.body()}"
        )
    }

    // 簡単なJSON解析
    val body = response.body()
    val regex = Regex(
        """"content"\s*:\s*"((?:\\.|[^"\\])*)""""
    )

    val match = regex.find(body)
        ?: throw RuntimeException("解析結果を取得できませんでした: $body")

    return match.groupValues[1]
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}



// =========================================
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