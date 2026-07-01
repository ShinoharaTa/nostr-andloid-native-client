# "AIツールっぽさ" 脱却 — 実行計画

目的: ワイヤーフレーム/管理画面的な「無難で単調な」印象を消し、意図を感じる UI にする。
既存のデザインシステム（DeckType/DeckSpace/DeckRadius/DeckDimens・モノクロ）を土台に、4施策を段階適用する。
基準は [ui-design-principles.md](./ui-design-principles.md) / [tokens.css](./tokens.css)。

現状の実測（＝症状）:
- 1px境界が主役: `HorizontalDivider(Border)` **49** + `.border()` **8** + `DeckColors.Border` 参照 **48**。
- アイコンは全て細線: `Icons.Outlined` **39** + `AutoMirrored.Outlined` **15**（＝54）。`Filled` は5のみ。
- ウェイトは名前=SemiBold止まり、本文/メタと差が弱い。
- 面のトーン差（Bg/Surface/Surface2/Surface3）が既にあるのに、区切りを線に頼っている。

---

## 0. 前提トークン（先に整える）

| 追加/変更 | 内容 |
|---|---|
| 面の役割を明文化 | `Bg`=カラム間ガター/最背面, `Surface`=カラム/カード, `Surface2`=入れ子(入力/引用/hover下地), `Surface3`=hover/押下。**区切りは面のトーン差＋余白で作る**。 |
| `Border` を格下げ | 「原則不使用」。表的リスト等どうしても要る所のみ、極薄で例外使用。 |
| `DeckElevation`(新) | `Lv1=2.dp` 程度。**浮く要素専用**（ComposeSheet/Dialog/DropdownMenu/FAB）。面リストには影を付けない（暗背景で汚くなるため）。 |
| 文字ロール(新, §2) | 名前/本文/メタ/リンク の weight・color・size をセットで定義。 |
| アイコンファミリ(§3) | 1つに統一決定。 |

---

## 施策1. 1pxボーダー撤廃 → 面で区切る

**方針**: ノート/行の区切り線を消し、①縦リズム(§4のグループ余白) ②背景トーン差 ③浮く要素は極薄シャドウ、で境界を作る。

- **ノート間の `HorizontalDivider` を撤廃**: `FeedColumn`(l.57/66/106/122) / `FollowingFeedColumn` / `NotificationsScreen` / `ThreadColumn` / `ProfileColumn`/`ProfileScreen` / `ChannelListColumn` / `DmScreen`。→ ノート外余白(Md)＋（必要なら)交互/微トーンで分離。
- **カラム間の1px境界** (`DeckScreen`, tokens `--column-gap:1px`) → `Bg` の細ガター(2–3px)へ。線から「隙間」へ。
- **`.border()` 撤去** (`DeckRail` 枠/`+`ボタン円, `AddColumnSheet` 等 8箇所) → 枠線を消し `AccentWeak`/`Surface2` の下地 or 何もなし。
- **浮く面は影**: `ComposeSheet`/`Dialog`/`DropdownMenu`/`ReactionPicker`/`FAB` に `DeckElevation.Lv1`（M3 `tonalElevation`/`Modifier.shadow`）。線ではなく「持ち上がり」で分離。
- **例外**: `SettingsScreen` の設定リスト等は、線ではなく項目を `Surface2` カード群＋余白で「面」に。どうしても要る区切りのみ薄く残す。

影響: FeedColumn, NotificationsScreen, ThreadColumn, ProfileColumn/Screen, ChannelListColumn, DmScreen, DeckScreen, DeckRail, AddColumnSheet, ComposeSheet, ReactionPicker, SettingsScreen, QuotedNoteCard, ConnectionIndicator。

---

## 施策2. タイポのウェイト/明度コントラスト

**ロール定義（トークン化して全画面で共用）**:

| ロール | size | weight | color |
|---|---|---|---|
| 名前/見出し | Sub/Title | **Bold**（現SemiBold→強化） | Text（明） |
| 本文 | Body | Normal | Text |
| メタ（時刻/ハンドル/npub/ID） | **Label→一部Micro** | Normal | **Text3**（暗） |
| リンク/アクティブ | – | Medium | Accent(白) |

- 名前: `FontWeight.SemiBold`→`Bold`（NoteItem l.91, ChannelRoom 著者名, ColumnChrome title, ProfileScreen 名前）。
- メタ: 時刻/ハンドルを `Label`→必要に応じ `Micro`、色は `Text3` 維持（十分暗い #6F7080）。
- 本文に紛れ込んだ `SemiBold` を `Normal` に統一（誤コントラストの排除）。
- 見出し(プロフィール名など)は `Title`/`Display`＋`Bold` で階層を明確化。

影響: NoteItem, NotificationsScreen, ChannelRoomColumn, ProfileScreen, ColumnChrome, QuotedNoteCard。

---

## 施策3. アイコンのウェイトアップ（要・方針決定）

`materialIconsExtended` 導入済みなので全ファミリ利用可。**import 差し替え(54箇所)で機械的に統一可能**。

| 案 | 見え方 | 「システム感」脱却 | 懸念 |
|---|---|---|---|
| **A. Filled** | 塗り・存在感最大 | ◎ | タイムラインのアクション5個が重く見える可能性 |
| **B. Rounded** | 同ストローク・角丸 | △（太さは不変） | 変化が小さい |
| **C. Sharp** | 直線的・硬派/エッジ | ○ | 細さは残る |
| **D. 現状+2値化** | 通常Outlined/状態でFilled | △ | 現状維持寄り |

**推奨**: **A(Filled) をナビ/ヘッダに、アクション行は「通常=Text3 の Filled・押下/アクティブで白or意味色」**。重さは色(Text3)で抑えつつ形は塗りで個性を出す。硬派路線が好みなら **C(Sharp)**。
→ **A / C のどちらか要決定**。決定後 `Icons.Outlined.*`→`Icons.Filled.*`(or `Sharp`) へ一括置換、`AutoMirrored.Outlined`→`AutoMirrored.Filled` も同様。

---

## 施策4. 近接（グループのタイト化）

**ルール（DeckSpace の運用規約）**:
- **グループ内**（アバター↔名前 / 名前↔ハンドル↔時刻 / アイコン↔ラベル）= `Xs(4)`〜`Sm(8)`。
- **グループ間**（ヘッダ↔本文 / 本文↔画像 / 本文↔アクション / ノート↔ノート）= `Md(12)`〜`Lg(16)`。

NoteItem 例:
- カード padding = `Md`、アバター↔本文 = `Sm`、名前行内(名前/ハンドル/時刻) = `Xs`、本文↔アクション = `Md`、ノート間の外余白 = `Md`（＋区切り線撤廃）。
- 「名前・ハンドル・時刻」を1行で密に、本文との間に明確な段差を付け、テキスト羅列→UIブロック化。

影響: NoteItem, ChannelRoomColumn(バブル群), NotificationsScreen(行), ChannelListColumn(行), ProfileScreen。

---

## 実行フェーズ（順序）

1. **トークン下準備**: DeckColors 役割整理 + `DeckElevation` + 文字ロール定義。アイコン方針(A/C)決定。
2. **施策1（境界撤廃・面化）** — 印象が最も変わる。まずノート/カラム/レール。
3. **施策4（近接）** — 1とセットで縦リズム確立。
4. **施策2（ウェイト/明度）**。
5. **施策3（アイコン一括差し替え）**。
6. エミュ(実機同寸2076×2152)で確認 → 微調整 → コミット（施策ごとに分割コミット）。

各フェーズ後にビルド＆スクショで回帰確認。破綻したら該当施策だけ revert 可能な粒度で進める。

---

## 決めてほしいこと

1. **アイコン**: A(Filled・推奨) / C(Sharp) / その他。
2. **区切り線の撤廃範囲**: 全撤廃 / 設定など一部は残す。
3. **進め方**: この計画で一括実装 / フェーズごとに確認しながら。
