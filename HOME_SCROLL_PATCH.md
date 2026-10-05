# ホーム一覧スクロール復元PATCH — 2026-10-05

## 原因と変更

ホームはGridViewで、フォルダを開く前に既存の`rememberGrid()`が先頭可視位置とchild.topを記録する。しかし再生成時の`setSelectionFromTop()`はタッチモードのGridViewで非ゼロ位置への移動が成立しないケースがあり、先頭へ戻っていた。またchild.topはpaddingを含む座標なので、同じ値を同APIへ渡すと余白分のずれも発生する。旧実装で位置・オフセットの不一致を実機再現した。

本番変更は`MainActivity.kt`のみ。ホームのGridView標準Parcelable状態をActivity内の`homeGridState`へ追加保存する。既存の`gridPositions`（先頭可視アイテム番号・ピクセル上端座標）も継続利用する。フォルダ選択前など、既存の`rememberGrid()`呼び出し時に保存する。

ホームのadapter設定後に標準状態を復元し、初回レイアウト後・描画前に先頭行の位置を補正する。標準状態で先頭行の部分スクロール量が省略される場合に備え、先頭アイテム番号が一致していれば`scrollListBy(child.top - 保存top)`でピクセル差だけ移動する。待ち時間のあるアニメーションや対象作品の中央揃えは行わない。古いグリッドへの復元はguardで除外する。

ホーム以外のPDF一覧・検索の保存復元コードは従来のまま。PDFビューア・Fast Scroller・レンダラー・読書位置保存・DB・設定・署名・applicationId・依存関係は変更していない。

`homeGridState`と`gridPositions`はActivityのメモリ上だけで保持し、Bundle・DB・SharedPreferences・ファイルへ保存しない。新Activity生成時はnull／空の状態となるため、終了後の新規ホームは先頭から始まる。既存の前回PDF自動再開設定はそのまま。

## テスト変更

`HomeScrollTest.kt`を追加。別DBに120件の合成作品を作成し、実際のMainActivityのGridViewをスクロールして、作品を開き、上部「←」ボタンで戻る。

- 2列：上部・中央・下部を含む5回の往復。
- 3列・手動ソート：4回の往復。
- 各回、先頭可視アイテム番号とchild.topがピクセル単位で一致することを確認。
- 初期化した新Activityに既存のメモリ位置状態がないことも確認。
- テスト用の作品・設定は別DBへ保存する。ユーザーの作品や設定を書き換えず、実PDFを開かない。テスト起動には合成外部PDFを使い、ユーザーの前回PDFの自動再開を避ける。

## 結果とデータ保持

- ROG Phone 6 / Android 14へ同じ署名で`pm install -r`により上書き更新成功。アンインストール・データclearは実施していない。
- 最終APKの更新直前と更新直後（初回起動前）のSHA-256比較：PDF280件、サムネイル218件、DB関連4ファイル、設定1ファイル、計503ファイルがすべて一致。DBファイルの一致により作品構成・関連付け・読書位置・既読情報・並び順・DB内設定も保持される。
- assembleDebug / assembleDebugAndroidTest / testDebugUnitTest / lintDebug成功。
- JVM単体テスト4件成功。
- 実機HomeScrollTest 2件 + 既存ReaderTest 9件 = 11件成功。
- lint：エラー0、警告19（既存の非推奨API、テストコードに関する警告等）。

検証用の端末ファイル名・ハッシュ一覧は公開ソースや成果物に含めない。確認したケースに未解決の不具合はない。スクロール復元は同一Activityの画面遷移を対象とし、プロセス終了やActivity再生成を越える永続復元は追加していない。
