# AnalyzeImageClient

## analyze images using llm

- llmを使って画像の英語promptを得ます
- 主にllmを使った画像の学習用txtの作成など（じゃあなんでabliteratedとか聞くな)
- llmを使うのでWD14 taggerよりはるかに重い

## 環境変数
- 前もって設定しておく環境変数

- `OLLAMA_SERVER_URL="http://server.addr.jp:11434/api/chat"`
  - promptを投げるAPI URLを指定
  - OpenAI Vision API互換形式
  - llama.cppはAMDでcore dumpしまくりだったので、今回は不採用

- `OLLAMA_SERVER_MODEL="hf.co/mradermacher/Qwen3-VL-4B-Instruct-abliterated-GGUF:Q4_K_M"`
  - まあまあ軽いモデル
  - `hf.co/mradermacher/Qwen3-VL-4B-Instruct-abliterated-GGUF:Q4_K_M`
  - これはめちゃ重い

- `OLLAMA_SERVER_PROMPT="provide a very detailed description of the image in one line"`
  - llm に投げる promptそのもの
  - very detailed description の重みは自分で調整してね

## 起動
- `java -jar AnalyzeImageClient.jar`
  - dialogが現れ、GUIでも上記を設定できる
  - Select directoryボタンで、ディレクトリ内のすべての画像をサーバに送ってllmで解析させ、
    同じディレクトリの画像.txtファイルで保存します

## Server と Client が違う場合
- ServerはUbuntu ollamaで構築しました
- ollama サーバとこのクライアントのIPが違う場合、サーバ側に下記の修正が必要
```
sudo systemctl stop ollama.service
sudo systemctl edit ollama
```
  - https://qiita.com/ntrlmt/items/4f11d8e2cea2f07c2c11
  - 上記にあるように、 ### Anything と ### Line の間に以下の環境変数をいれる
  - CONTENT_LENGTHが短いと、「request (4161 tokens) exceeds the available context size (4096 tokens)」などのエラーが出る
```
[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
Environment="OLLAMA_CONTEXT_LENGTH=8192"
Environment="OLLAMA_MAX_LOADED_MODELS=1"
Environment="OLLAMA_NUM_PARALLEL=1"
Environment="OLLAMA_SCHED_SPREAD=0"
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_KV_CACHE_TYPE=q8_0"
```
- 終わったら `^X`を押して保存

```
sudo systemctl daemon-reload
sudo systemctl restart ollama
```

## TIPS
- SwingWorkerを初めて使った