# TriChat

ローカルで **人間 + Qwen + Gemma** の3者チャットを行うAndroidアプリです。

## 構成

- Qwen3.5 2B と Gemma 4 E2B を別AndroidプロセスのServiceで常駐
- llama.cpp Android bindingを使用
- 通常は `User → Qwen → Gemma` の会議モード
- 「並列」をONにするとQwen/Gemmaを同時推論
- Thinking / CoTは使用せず、256 tokenを標準上限にして速度優先
- モデルはアプリ内のリンクから取得し、GGUFを選択して取り込み
- ログは常にヘッダーを持つ永続ファイルへ追記し、0Bにならない状態で書き出し
- GitHub Releasesの最新版をアプリから確認し、APKをダウンロードして更新

## 推奨モデル

- Qwen: `Qwen3.5-2B-Q4_K_M.gguf`（約1.4GB）
- Gemma: Google公式 `gemma-4-E2B_q4_0-it.gguf`（約3.35GB）

モデル本体はAPKには含めません。

## Build / Release

`main`へのpushでGitHub ActionsがRelease APKをビルドします。`app/build.gradle.kts` の `versionName` を上げると、そのバージョンのGitHub Releaseが自動作成されます。

Androidの上書き更新には同じ署名鍵が必要なため、この個人用リポジトリでは固定のローカル署名鍵を使用しています。公開配布向けの秘密鍵運用ではありません。

llama.cppはビルド時に `73ab7599b553c03f6f5d2db24a18ad76f2eb36a3` を自動取得します。
