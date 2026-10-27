# RawCurveEditor (Android)

Sony `.ARW` を含む各種カメラのRAW画像を **LibRaw (C++ / NDK 16-bit パイプライン)** で直接現像し、**マスター(RGB) + R / G / B 個別チャンネルのトーンカーブ**で色調整を行い、**任意の解像度（%指定・100%超の拡大出力対応・リアルタイムpx表示）** で JPEG / PNG 書き出しができるダークモノトーンUIのAndroidアプリです。

## 主な機能

1. **LibRaw 16-bit ネイティブRAW現像 (`native-lib.cpp`)**
   - CMake の `FetchContent` により `LibRaw (0.21.2)` をソースからスタティックビルド。
   - ソニーの `.ARW` をはじめ `.CR2`, `.NEF`, `.DNG`, `.RAF`, `.RW2`, `.ORF` 等をカメラホワイトバランス＋AHDデモザイクで **各チャンネル16-bit (0〜65535)** のままメモリ展開します。
2. **RGB 4チャンネル・単調三次エルミートスプラインカーブ (`CurveSplineEngine.kt`, `RgbCurvePad.kt`)**
   - `RGB (Master)`, `R`, `G`, `B` の4チャンネル切り替え。
   - タップまたはドラッグでコントロールポイント追加・移動、ポイントのダブルタップ（または上下枠外へのドラッグ）で削除。
   - 65,536段階（16-bit精度）の合成LUTを生成し、バンディング（階調飛び）のないリアルタイムプレビューを行います。
3. **任意解像度（%）・フォーマット指定書き出し (`ExportDialog.kt`)**
   - **JPEG（品質 10%〜100% 指定）** / **PNG** の選択。
   - **解像度スケール（%）**: スライダー（10%〜200%）、プリセットボタン（`25%`, `50%`, `75%`, `100%`, `125%`, `150%`, `200%`）、および直接数値入力（最大300%）に対応。
   - **ピクセル数のリアルタイム表示**: 元解像度と出力解像度（`幅 × 高さ px` および `MP`）を即座に計算表示し、C++側の **16-bit Catmull-Rom バイキュービック補間** で高画質にリサイズしてからLUTを適用します。

## ビルド方法

1. Android Studio で `/Users/cure/.gemini/antigravity/scratch/RawCurveEditor` を開きます。
2. SDK Manager から **NDK (Side by side) 26.1.10909125** と **CMake 3.22.1** がインストールされていることを確認してください。
3. 初回の Gradle Sync 時に CMake が自動的に GitHub から `LibRaw` を取得し、ネイティブライブラリ (`librawcurve.so`) をビルドします。
   - ※ オフライン環境等で LibRaw なしで確認したい場合は `app/src/main/cpp/CMakeLists.txt` の `option(USE_LIBRAW ... ON)` を `OFF` に変更すると標準デコーダ＋16bitパイプラインのみで即座にビルドできます。
