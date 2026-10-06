import { use$ } from "applesauce-react/hooks/use-$";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { useEffect, useState } from "react";
import { formatArgs, useT } from "../../i18n";
import { setKansaiMode, setLocaleSetting, useLocale } from "../../i18n/locale";
import { unixNow } from "../../lib/time";
import { addVerified, eventStore } from "../../nostr/store";
import { Icon } from "../../ui/icons";
import deckStyles from "../deck/DeckColumn.module.css";
import { type Density, useThemePrefs } from "../theme/themePrefs";
import { useNow } from "../timeline/useNow";
import { StatusBody, StatusCard, StatusCardFrame } from "./StatusCard";
import styles from "./StatusDemoRoute.module.css";
import text from "./statusDemoText.json";
import { isStatusVisible, STATUS_KIND, type StatusType, sortStatuses, statusTypeOf } from "./statusModel";

/*
 * ステータスカラムの UI デモ（/demo/status。#767 仕様 §6.2。main にはマージしない）。
 * 固定データは使い捨ての鍵でこの端末の中だけで署名し、EventStore に入れるだけ（リレーへは送らない）。
 */

/** カスタム絵文字の画像は本番の絵文字 API（/api/emoji.png。文字から作る画像） */
const EMOJI_API = "https://nostrism.shino3.net/api/emoji.png?text=";
/** 実在の Spotify のトラック（OGP にジャケットが出る） */
const SPOTIFY_TRACK = "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8";

type PersonId = keyof typeof text.people;
type ContentId = keyof typeof text.content;

type Fixture = {
  who: PersonId;
  d: string;
  content: ContentId;
  /** 何秒前に出したか */
  ago: number;
  /** 何秒後に切れるか（無ければ期限なし） */
  expiresIn?: number;
  tags?: (pubkeys: Record<PersonId, string>) => string[][];
  /** 画面に出てはいけないもの（§6.2 の 10） */
  hidden?: true;
};

const FIXTURES: readonly Fixture[] = [
  // 1. music + Spotify の r + 期限 3 分後
  { who: "aoi", d: "music", content: "spotify", ago: 30, expiresIn: 180, tags: () => [["r", SPOTIFY_TRACK]] },
  // 2. music、r 無し、期限 20 秒後（その場で消える）
  { who: "kenta", d: "music", content: "noLink", ago: 60, expiresIn: 20 },
  // 3. music + spotify: の r（リンクにならない）
  {
    who: "mika",
    d: "music",
    content: "spotifyUri",
    ago: 150,
    expiresIn: 240,
    tags: () => [["r", "spotify:search:Intergalactic%20-%20Beastie%20Boys"]],
  },
  // 4. general + カスタム絵文字 + 期限 4 時間後（9. 1 と同じ人の general）
  {
    who: "aoi",
    d: "general",
    content: "emoji",
    ago: 40 * 60,
    expiresIn: 4 * 3600,
    tags: () => [
      ["emoji", "nostrism_work", `${EMOJI_API}${encodeURIComponent(text.emojiText.work)}`],
      ["emoji", "nostrism_coffee", `${EMOJI_API}${encodeURIComponent(text.emojiText.coffee)}`],
    ],
  },
  // 5. general + https の r（一般サイトの OGP カード）、期限なし
  { who: "fan", d: "general", content: "ogp", ago: 2 * 3600, tags: () => [["r", "https://nostr.com/"]] },
  // 6. general、長文（4 行で省略）
  { who: "long", d: "general", content: "long", ago: 5 * 3600 },
  // 7. general + p（@名前 の参照）
  { who: "taro", d: "general", content: "mention", ago: 26 * 3600, tags: (pk) => [["p", pk.aoi]] },
  // 8. general + 壊れた URL（文字のまま）
  {
    who: "hiro",
    d: "general",
    content: "broken",
    ago: 3 * 86400,
    tags: () => [["r", "hittps://chouseisan.com/s?h=demo"]],
  },
  // 10. 出てはいけないもの: 空 content（曲が止まった）、期限切れ、presence、31 日前の期限なし general
  {
    who: "ng1",
    d: "music",
    content: "empty",
    ago: 10,
    expiresIn: 600,
    tags: () => [["alt", "Music stopped"]],
    hidden: true,
  },
  { who: "ng2", d: "general", content: "expired", ago: 3600, expiresIn: -60, hidden: true },
  { who: "ng1", d: "presence", content: "presence", ago: 5, hidden: true },
  { who: "ng3", d: "general", content: "old", ago: 31 * 86400, hidden: true },
];

type Fixtures = { pubkeys: string[]; total: number; hidden: number };

let fixtures: Fixtures | null = null;

/** ページを開いた時刻を基準に固定データを作って EventStore に入れる（読み込みごとに 1 回） */
function loadFixtures(): Fixtures {
  if (fixtures) return fixtures;
  const base = unixNow();
  const keys = Object.fromEntries(
    (Object.keys(text.people) as PersonId[]).map((id) => [id, generateSecretKey()]),
  ) as Record<PersonId, Uint8Array>;
  const pubkeys = Object.fromEntries(
    (Object.keys(keys) as PersonId[]).map((id) => [id, getPublicKey(keys[id])]),
  ) as Record<PersonId, string>;
  for (const id of Object.keys(keys) as PersonId[]) {
    addVerified(
      finalizeEvent(
        { kind: 0, created_at: base - 86400, tags: [], content: JSON.stringify({ name: text.people[id] }) },
        keys[id],
      ),
    );
  }
  for (const f of FIXTURES) {
    const tags = [["d", f.d], ...(f.tags?.(pubkeys) ?? [])];
    if (f.expiresIn !== undefined) tags.push(["expiration", String(base + f.expiresIn)]);
    addVerified(
      finalizeEvent(
        { kind: STATUS_KIND, created_at: base - f.ago, tags, content: text.content[f.content] },
        keys[f.who],
      ),
    );
  }
  fixtures = {
    pubkeys: Object.values(pubkeys),
    total: FIXTURES.length,
    hidden: FIXTURES.filter((f) => f.hidden).length,
  };
  return fixtures;
}

type Filter = "all" | StatusType;
type Unit = "type" | "person";
type Width = "s" | "m" | "l";
type Lang = "ja" | "en" | "kansai";

const NO_EVENTS: NostrEvent[] = [];
const WIDTHS: readonly Width[] = ["s", "m", "l"];

export function StatusDemoRoute() {
  const t = useT();
  const locale = useLocale((s) => s.resolved);
  const ui = text.ui[locale === "en" ? "en" : "ja"];
  const [{ pubkeys, total, hidden }] = useState(loadFixtures);
  const events = use$(() => eventStore.timeline({ kinds: [STATUS_KIND], authors: pubkeys }), [pubkeys]);
  const now = useNow(10_000);

  const [filter, setFilter] = useState<Filter>("all");
  const [unit, setUnit] = useState<Unit>("type");
  const [linkCard, setLinkCard] = useState(true);
  const [width, setWidth] = useState<Width>("m");
  const [density, setDensity] = useState<Density>(() => useThemePrefs.getState().density);

  // 密度はこのページにいる間だけ変える（保存しない。離れるときに元へ戻す）
  useEffect(() => {
    const before = useThemePrefs.getState().density;
    return () => useThemePrefs.setState({ density: before });
  }, []);
  useEffect(() => {
    useThemePrefs.setState({ density });
  }, [density]);

  const visible = sortStatuses(
    (events ?? NO_EVENTS).filter((e) => isStatusVisible(e, filter === "all" ? null : filter, now)),
  );
  const subtitle =
    filter === "music"
      ? t("web_status_type_music")
      : filter === "general"
        ? t("web_status_type_general")
        : "NIP-38";
  const lang: Lang = locale === "en" ? "en" : locale === "ja-kansai" ? "kansai" : "ja";

  function changeLang(next: Lang) {
    setLocaleSetting(next === "en" ? "en" : "ja");
    setKansaiMode(next === "kansai");
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>{ui.title}</h1>
      <p className={styles.lead}>{ui.lead}</p>
      <div className={styles.controls}>
        <Choice
          label={ui.filter}
          value={filter}
          onChange={setFilter}
          options={[
            ["all", ui.filterAll],
            ["music", t("web_status_type_music")],
            ["general", t("web_status_type_general")],
          ]}
        />
        <Choice
          label={ui.unit}
          value={unit}
          onChange={setUnit}
          options={[
            ["type", ui.unitType],
            ["person", ui.unitPerson],
          ]}
        />
        <Choice
          label={ui.link}
          value={linkCard ? "card" : "line"}
          onChange={(v) => setLinkCard(v === "card")}
          options={[
            ["card", ui.linkCard],
            ["line", ui.linkLine],
          ]}
        />
        <Choice
          label={ui.density}
          value={density}
          onChange={setDensity}
          options={[
            ["normal", ui.densityNormal],
            ["dense", ui.densityDense],
          ]}
        />
        <Choice
          className={styles.widthControl}
          label={ui.width}
          value={width}
          onChange={setWidth}
          options={[
            ["s", "S"],
            ["m", "M"],
            ["l", "L"],
          ]}
        />
        <Choice
          label={ui.lang}
          value={lang}
          onChange={changeLang}
          options={[
            ["ja", text.langNames.ja],
            ["en", text.langNames.en],
            ["kansai", text.langNames.kansai],
          ]}
        />
      </div>
      <p className={styles.count}>{formatArgs(ui.count, [total, hidden, visible.length])}</p>
      <div className={styles.columns}>
        {WIDTHS.map((w) => (
          <section
            key={w}
            className={`${deckStyles.column} ${styles.column}`}
            data-w={w}
            data-active={w === width ? "" : undefined}
            aria-label={`${ui.columnTitle} ${w.toUpperCase()}`}
          >
            <header className={deckStyles.header}>
              <span className={deckStyles.icon}>
                <Icon name="mood" size="lg" />
              </span>
              <div className={deckStyles.titles}>
                <h2 className={deckStyles.title}>{ui.columnTitle}</h2>
                <p className={deckStyles.subtitle}>{subtitle}</p>
              </div>
              <span className={styles.widthBadge}>{w.toUpperCase()}</span>
            </header>
            {visible.length === 0 ? (
              <p className={styles.empty}>{filter === "music" ? ui.emptyMusic : ui.empty}</p>
            ) : unit === "type" ? (
              visible.map((e) => <StatusCard key={e.id} event={e} linkCard={linkCard} />)
            ) : (
              groupByPerson(visible).map((group) => (
                <StatusCardFrame key={group.pubkey} pubkey={group.pubkey} createdAt={group.createdAt}>
                  {group.events.map((e) => (
                    <StatusBody key={e.id} event={e} linkCard={linkCard} />
                  ))}
                </StatusCardFrame>
              ))
            )}
          </section>
        ))}
      </div>
    </main>
  );
}

/**
 * 代替案「1 人 = 1 枚」: 新しい順のまま人ごとにまとめる（並びは各人の一番新しいもの）。
 * カードの中は general → music の順。
 */
function groupByPerson(
  events: readonly NostrEvent[],
): { pubkey: string; createdAt: number; events: NostrEvent[] }[] {
  const groups = new Map<string, { pubkey: string; createdAt: number; events: NostrEvent[] }>();
  for (const e of events) {
    const group = groups.get(e.pubkey);
    if (group) group.events.push(e);
    else groups.set(e.pubkey, { pubkey: e.pubkey, createdAt: e.created_at, events: [e] });
  }
  const rank = (e: NostrEvent) => (statusTypeOf(e) === "general" ? 0 : 1);
  return [...groups.values()].map((group) => ({
    ...group,
    events: [...group.events].sort((a, b) => rank(a) - rank(b)),
  }));
}

function Choice<T extends string>({
  label,
  value,
  options,
  onChange,
  className,
}: {
  label: string;
  value: T;
  options: readonly (readonly [T, string])[];
  onChange: (value: T) => void;
  className?: string;
}) {
  return (
    <fieldset className={className ? `${styles.control} ${className}` : styles.control}>
      <legend className={styles.controlLabel}>{label}</legend>
      {options.map(([v, l]) => (
        <button
          key={v}
          type="button"
          className={deckStyles.chip}
          aria-pressed={v === value}
          onClick={() => onChange(v)}
        >
          {l}
        </button>
      ))}
    </fieldset>
  );
}
