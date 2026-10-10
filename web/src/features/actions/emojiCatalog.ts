/**
 * リアクションピッカー用の Unicode 絵文字カタログ（#684）。
 * 起動直後は厳選リスト（旧ネイティブ EmojiCatalog.kt の写し・約120個）を EMOJI_CATEGORIES / EMOJI_ALL に
 * 持っておき、ピッカーを開いたら loadEmojiCatalog() で標準の絵文字を全部（約1,900個）に差し替える。
 * データは npm の emojibase-data（MIT）。ja/en の compact.json（ラベル・タグ）と ja/messages.json（カテゴリ名）
 * だけをピッカーを開いたときに動的 import し、バンドルには含めない（他に読み込む場所がないので初期チャンクは増えない）。
 * 肌の色・髪型のバリエーション（group: "component"）は基本形のみにするため丸ごと除外する。国旗は含める。
 * カテゴリ名は辞書から引く（#722。描画時に t() するので言語の切り替えに追従する）。
 */

import { t } from "../../i18n";

export type EmojiEntry = { char: string; keywords: readonly string[] };
export type EmojiCategory = { title: () => string; emojis: readonly EmojiEntry[] };

/** NFKC 正規化 + 小文字化 + カタカナ→ひらがな。検索キーワードと入力クエリの両方をこれで揃えてから比較する */
function normalizeForSearch(s: string): string {
  return s
    .normalize("NFKC")
    .toLowerCase()
    .replace(/[ァ-ヶ]/g, (ch) => String.fromCharCode(ch.charCodeAt(0) - 0x60));
}

const e = (char: string, ...keywords: string[]): EmojiEntry => ({
  char,
  keywords: keywords.map(normalizeForSearch),
});

/** 読み込み前のフォールバック（ネイティブ EmojiCatalog.kt の写し。並び・キーワードは変えない） */
const FALLBACK_CATEGORIES: readonly EmojiCategory[] = [
  {
    title: () => t("emoji_cat_faces"),
    emojis: [
      e("😀", "grin", "smile", "笑顔", "にこ"),
      e("😃", "smile", "happy", "笑顔", "うれしい"),
      e("😄", "smile", "happy", "笑", "わらい"),
      e("😁", "grin", "beam", "にやり", "笑"),
      e("😆", "laugh", "haha", "爆笑", "わらい"),
      e("😅", "sweat", "苦笑", "あせ", "汗"),
      e("🤣", "rofl", "lol", "爆笑", "わらい"),
      e("😂", "joy", "tears", "笑い泣き", "わらい"),
      e("🙂", "slight smile", "ほほえみ", "にこ"),
      e("🙃", "upside down", "さかさ", "とぼけ"),
      e("😉", "wink", "ウインク", "ちゃめ"),
      e("😊", "blush", "smile", "にこ", "照れ"),
      e("😍", "heart eyes", "love", "好き", "ラブ"),
      e("🥰", "love", "smiling hearts", "好き", "ラブ"),
      e("😘", "kiss", "キス", "ちゅ"),
      e("😎", "cool", "sunglasses", "クール", "かっこいい"),
      e("🤔", "thinking", "考え", "うーん", "なやみ"),
      e("🤐", "zip", "だまる", "むぐ"),
      e("😴", "sleep", "ねむい", "睡眠", "zzz"),
      e("😭", "cry", "sob", "泣く", "なき"),
      e("😱", "scream", "shock", "驚き", "びっくり"),
      e("😡", "angry", "怒り", "おこ", "rage"),
      e("🥺", "pleading", "うるうる", "おねがい"),
      e("😇", "angel", "天使", "せいなる"),
      e("🤩", "star eyes", "すごい", "キラキラ"),
      e("😏", "smirk", "にやり", "どや"),
      e("😬", "grimace", "うわ", "やばい"),
      e("🥳", "party", "celebrate", "お祝い", "パーティ"),
      e("😮", "wow", "おどろき", "ほー"),
      e("🤗", "hug", "ハグ", "うれしい"),
    ],
  },
  {
    title: () => t("emoji_cat_gestures"),
    emojis: [
      e("👍", "thumbs up", "good", "いいね", "グッド", "了解"),
      e("👎", "thumbs down", "bad", "だめ", "わるい"),
      e("👏", "clap", "拍手", "ぱちぱち", "すごい"),
      e("🙏", "pray", "thanks", "please", "おねがい", "感謝", "ありがとう"),
      e("🙌", "raise", "万歳", "やった"),
      e("👌", "ok", "オーケー", "了解"),
      e("✌️", "victory", "peace", "ピース"),
      e("🤝", "handshake", "握手", "よろしく"),
      e("💪", "muscle", "strong", "がんばる", "筋肉"),
      e("👋", "wave", "hello", "bye", "やあ", "ばいばい"),
      e("🤙", "call me", "しゃか"),
      e("👀", "eyes", "見てる", "目"),
      e("🫡", "salute", "敬礼", "了解"),
      e("🤷", "shrug", "さあ", "しらない"),
    ],
  },
  {
    title: () => t("emoji_cat_hearts"),
    emojis: [
      e("❤️", "heart", "love", "好き", "ハート", "ラブ"),
      e("🧡", "orange heart", "オレンジ", "ハート"),
      e("💛", "yellow heart", "黄色", "ハート"),
      e("💚", "green heart", "緑", "ハート"),
      e("💙", "blue heart", "青", "ハート"),
      e("💜", "purple heart", "紫", "ハート"),
      e("🖤", "black heart", "黒", "ハート"),
      e("🤍", "white heart", "白", "ハート"),
      e("💗", "growing heart", "ときめき", "ハート"),
      e("💕", "two hearts", "ハート", "ラブ"),
      e("💔", "broken heart", "失恋", "こわれ"),
      e("🔥", "fire", "hot", "炎", "あつい", "やばい"),
      e("✨", "sparkles", "キラキラ", "すごい"),
      e("⭐", "star", "星", "すごい"),
      e("🎉", "party", "tada", "おめでとう", "クラッカー"),
      e("🎊", "confetti", "お祝い", "くす玉"),
      e("💯", "100", "perfect", "満点", "完璧"),
      e("💢", "anger", "怒り", "イライラ"),
      e("💦", "sweat", "あせ", "汗"),
      e("💤", "zzz", "ねむい", "睡眠"),
    ],
  },
  {
    title: () => t("emoji_cat_nature"),
    emojis: [
      e("🐶", "dog", "犬", "いぬ"),
      e("🐱", "cat", "猫", "ねこ"),
      e("🐭", "mouse", "ねずみ"),
      e("🐰", "rabbit", "うさぎ"),
      e("🦊", "fox", "きつね"),
      e("🐻", "bear", "くま"),
      e("🐼", "panda", "パンダ"),
      e("🐸", "frog", "かえる"),
      e("🐧", "penguin", "ペンギン"),
      e("🐤", "chick", "ひよこ"),
      e("🦄", "unicorn", "ユニコーン"),
      e("🐝", "bee", "はち"),
      e("🌸", "cherry blossom", "桜", "さくら"),
      e("🌺", "flower", "花", "はな"),
      e("🌈", "rainbow", "虹", "にじ"),
      e("☀️", "sun", "晴れ", "たいよう"),
      e("🌙", "moon", "月", "つき"),
      e("⚡", "lightning", "雷", "かみなり"),
      e("❄️", "snow", "雪", "ゆき"),
      e("🌊", "wave", "波", "なみ"),
    ],
  },
  {
    title: () => t("emoji_cat_food"),
    emojis: [
      e("🍎", "apple", "りんご"),
      e("🍌", "banana", "バナナ"),
      e("🍓", "strawberry", "いちご"),
      e("🍅", "tomato", "トマト"),
      e("🍙", "rice ball", "おにぎり"),
      e("🍣", "sushi", "寿司", "すし"),
      e("🍜", "ramen", "ラーメン", "麺"),
      e("🍕", "pizza", "ピザ"),
      e("🍔", "burger", "ハンバーガー"),
      e("🍰", "cake", "ケーキ"),
      e("🍩", "donut", "ドーナツ"),
      e("🍺", "beer", "ビール", "酒"),
      e("🍷", "wine", "ワイン"),
      e("☕", "coffee", "コーヒー", "お茶"),
      e("🍵", "tea", "お茶", "緑茶"),
      e("🎂", "birthday cake", "誕生日", "ケーキ"),
    ],
  },
  {
    title: () => t("emoji_cat_activity"),
    emojis: [
      e("⚽", "soccer", "サッカー"),
      e("⚾", "baseball", "野球"),
      e("🏀", "basketball", "バスケ"),
      e("🎮", "game", "ゲーム"),
      e("🎵", "music", "音楽", "おんがく"),
      e("🎸", "guitar", "ギター"),
      e("📷", "camera", "カメラ", "写真"),
      e("💻", "laptop", "pc", "パソコン"),
      e("📱", "phone", "スマホ", "携帯"),
      e("💰", "money", "お金", "かね"),
      e("🎁", "gift", "present", "プレゼント"),
      e("✅", "check", "ok", "完了", "チェック"),
      e("❌", "cross", "no", "ばつ", "だめ"),
      e("❓", "question", "はてな", "疑問"),
      e("❗", "exclamation", "びっくり", "注意"),
      e("🆗", "ok", "オーケー"),
      e("🈵", "full", "満"),
      e("🚀", "rocket", "ロケット", "すごい"),
      e("👑", "crown", "王冠", "おう"),
      e("💎", "gem", "diamond", "ダイヤ", "宝石"),
    ],
  },
];

/** カテゴリ配列を頭から舐めて char 重複を除いた全エントリにする */
function flattenAll(categories: readonly EmojiCategory[]): readonly EmojiEntry[] {
  const seen = new Set<string>();
  const out: EmojiEntry[] = [];
  for (const category of categories) {
    for (const entry of category.emojis) {
      if (seen.has(entry.char)) continue;
      seen.add(entry.char);
      out.push(entry);
    }
  }
  return out;
}

/** 読み込み前はフォールバックの厳選リスト。loadEmojiCatalog() が終わると全絵文字に差し替わる（ライブバインディング） */
export let EMOJI_CATEGORIES: readonly EmojiCategory[] = FALLBACK_CATEGORIES;
export let EMOJI_ALL: readonly EmojiEntry[] = flattenAll(FALLBACK_CATEGORIES);

// ---- emojibase-data（標準の絵文字全部）の読み込み ----

type CompactEmojiEntry = {
  hexcode: string;
  unicode: string;
  label: string;
  tags?: string[];
  group?: number;
  order?: number;
};

type EmojiMessages = {
  groups: readonly { key: string; message: string; order: number }[];
};

/**
 * emojibase の ja ラベル・タグは公式データが漢字表記（笑う・嬉しい 等）で、ひらがな入力（わらう 等）だと
 * NFKC 正規化 + カタカナ→ひらがな だけでは一致しない。よく使う感情語（辞書形の完全な読み）だけ、対応する
 * 漢字へ橋渡しする。厳選リストの短い手作りキーワード（「わらい」「おこ」等）と衝突しないよう、
 * 語幹ではなく完全な読みで判定する（「わらい」は「わらう」を含まないので厳選リストのテストには影響しない）。
 */
const JA_READING_TO_KANJI: readonly (readonly [reading: string, kanji: string])[] = [
  ["わらう", "笑"],
  ["なく", "泣"],
  ["おこる", "怒"],
  ["いかり", "怒"],
  ["かなしい", "悲"],
  ["うれしい", "嬉"],
  ["たのしい", "楽"],
  ["こわい", "怖"],
  ["すき", "好"],
  ["ねむい", "眠"],
  ["おどろく", "驚"],
];

/** 肌の色・髪型などの合成用パーツ（単体では出さない） */
const EXCLUDED_GROUP_KEY = "component";

/** emojibase のグループ（messages.json の key）→ カテゴリ名。ja の値は emojibase-data/ja/messages.json のまま */
const GROUP_TITLES: Readonly<Record<string, () => string>> = {
  "smileys-emotion": () => t("web_picker_group_smileys_emotion"),
  "people-body": () => t("web_picker_group_people_body"),
  "animals-nature": () => t("web_picker_group_animals_nature"),
  "food-drink": () => t("web_picker_group_food_drink"),
  "travel-places": () => t("web_picker_group_travel_places"),
  activities: () => t("web_picker_group_activities"),
  objects: () => t("web_picker_group_objects"),
  symbols: () => t("web_picker_group_symbols"),
  flags: () => t("web_picker_group_flags"),
};

function buildFullCatalog(
  ja: readonly CompactEmojiEntry[],
  en: readonly CompactEmojiEntry[],
  messages: EmojiMessages,
): readonly EmojiCategory[] {
  const enByHex = new Map(en.map((entry) => [entry.hexcode, entry]));
  const seen = new Set<string>();
  const byGroup = new Map<number, { order: number; entry: EmojiEntry }[]>();

  for (const entry of ja) {
    const group = entry.group;
    // group が無いのは国旗を組む regional indicator 等の部品。単体の絵文字ではないので除く
    if (typeof group !== "number" || seen.has(entry.unicode)) continue;
    seen.add(entry.unicode);
    const enEntry = enByHex.get(entry.hexcode);
    const rawKeywords = [entry.label, ...(entry.tags ?? []), enEntry?.label, ...(enEntry?.tags ?? [])].filter(
      (v): v is string => typeof v === "string",
    );
    const keywords = Array.from(new Set(rawKeywords.map(normalizeForSearch)));
    const list = byGroup.get(group) ?? [];
    list.push({ order: entry.order ?? 0, entry: { char: entry.unicode, keywords } });
    byGroup.set(group, list);
  }

  const categories: EmojiCategory[] = [];
  for (const meta of [...messages.groups].sort((a, b) => a.order - b.order)) {
    if (meta.key === EXCLUDED_GROUP_KEY) continue;
    const list = byGroup.get(meta.order);
    if (!list || list.length === 0) continue;
    list.sort((a, b) => a.order - b.order);
    // 辞書に無いグループ（emojibase の更新で増えたもの）は messages.json の名前をそのまま出す
    categories.push({
      title: GROUP_TITLES[meta.key] ?? (() => meta.message),
      emojis: list.map((x) => x.entry),
    });
  }
  return categories;
}

let fullCatalogPromise: Promise<readonly EmojiCategory[]> | null = null;

/**
 * ピッカーを開いたときに呼ぶ。初回だけ emojibase-data（ja/en の compact.json + ja の messages.json）を
 * 動的 import して EMOJI_CATEGORIES / EMOJI_ALL を標準の絵文字全部（約1,900個）に差し替える。
 * 2 回目以降は同じ Promise を返すのでキャッシュされる。差し替わるまでは呼び出し側は今の一覧のまま描画してよい。
 */
export function loadEmojiCatalog(): Promise<readonly EmojiCategory[]> {
  if (fullCatalogPromise) return fullCatalogPromise;
  fullCatalogPromise = (async () => {
    const [ja, en, messages] = await Promise.all([
      import("emojibase-data/ja/compact.json"),
      import("emojibase-data/en/compact.json"),
      import("emojibase-data/ja/messages.json"),
    ]);
    const categories = buildFullCatalog(ja.default, en.default, messages.default);
    EMOJI_CATEGORIES = categories;
    EMOJI_ALL = flattenAll(categories);
    return categories;
  })();
  return fullCatalogPromise;
}

/** 日英キーワードの部分一致か、絵文字そのものとの一致で探す。空なら [] */
export function searchEmojis(query: string): EmojiEntry[] {
  const trimmed = query.trim();
  if (trimmed === "") return [];
  const q = normalizeForSearch(trimmed);
  const kanjiHints = JA_READING_TO_KANJI.filter(([reading]) => q.includes(reading)).map(([, kanji]) => kanji);
  return EMOJI_ALL.filter(
    (entry) =>
      entry.char === trimmed ||
      entry.keywords.some((k) => k.includes(q) || kanjiHints.some((kanji) => k.includes(kanji))),
  );
}
