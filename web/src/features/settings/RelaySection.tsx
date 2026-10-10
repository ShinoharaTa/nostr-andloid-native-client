import { use$ } from "applesauce-react/hooks/use-$";
import { type FormEvent, useEffect, useId, useMemo, useRef, useState } from "react";
import { map } from "rxjs";
import { t, useT } from "../../i18n";
import { displayRelayUrl, wsRelayUrlsFromEvent } from "../../nostr/outbox";
import { addRelay, type RelayRow, removeRelay, setRelayReadWrite, useRelayRows } from "../../nostr/pool";
import { type AuthPolicy, setAuthPolicy, useAuthPolicy } from "../../nostr/relayAuth";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { ConfirmDialog } from "../../ui/ConfirmDialog";
import { showToast } from "../../ui/toast";
import { isWsRelayInput, parseRelayInput, publishRelayList, RelayListError } from "./relayList";
import {
  RELAY_PRESETS,
  type RelayPresetCategory,
  type RelayRec,
  relayPresetCategoryLabel,
  relayPresetNoteLabel,
  relayRecs$,
} from "./relayRecs";
import styles from "./SettingsSections.module.css";

/** 各行の source のヒント文言（ネイティブ RelaySettings の HintText と同じ意味。#585） */
function sourceLabel(source: RelayRow["source"]): string {
  switch (source) {
    case "nip65":
      return "NIP-65";
    case "manual":
      return t("web_settings_relay_source_manual");
    case "default":
      return t("web_settings_relay_source_default");
  }
}

/** 保存の失敗の文言 */
function failureMessage(e: unknown): string {
  if (e instanceof RelayListError && e.reason === "no-relay-list") {
    return t("web_relays_no_base");
  }
  if (e instanceof RelayListError && e.reason === "stale") {
    return t("web_relays_stale");
  }
  return t("relays_publish_failed");
}

/**
 * リレー（ネイティブ RelaySettings）。追加 / 削除 / Read・Write の切替は pool.ts のリレー表へ即反映し、
 * 接続先がすぐ変わる（#585）。「保存」は今の一覧を NIP-65（kind:10002）として公開するだけ
 * （直前に最新の kind:10002 を取り直し、取れなければ公開しない。basedOnId が食い違えば stale。#478）。
 */
export function RelaySection() {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const rows = useRelayRows();
  // 一覧はネイティブ allRelays（ORDER BY source ASC, url ASC）と同じ並び: 既定 → 手動 → NIP-65、URL 昇順
  const sortedRows = useMemo(
    () => [...rows].sort((a, b) => a.source.localeCompare(b.source) || a.url.localeCompare(b.url)),
    [rows],
  );
  // 保存の直前の取り直しと突き合わせる、今わかっている自分の kind:10002 の id
  const latest = use$(
    () =>
      me ? eventStore.timeline({ kinds: [10002], authors: [me] }).pipe(map(([e]) => e ?? null)) : undefined,
    [me],
  );
  // kind:10002 に入っている ws:// のリレー。接続はせず、一覧に「Web 版では接続できません」として出すだけ
  // （保存しても buildRelayListTemplate がそのまま引き継ぐ。#580 / #776）
  const wsUrls = useMemo(() => (latest ? wsRelayUrlsFromEvent(latest) : []), [latest]);
  const [confirming, setConfirming] = useState(false);
  const [saving, setSaving] = useState(false);

  async function save() {
    setConfirming(false);
    if (!me) return;
    setSaving(true);
    try {
      const prefs = rows.map(({ url, read, write }) => ({ url, read, write }));
      await publishRelayList(me, prefs, latest?.id ?? null);
      showToast(t("relays_published"));
    } catch (e) {
      showToast(failureMessage(e));
    } finally {
      setSaving(false);
    }
  }

  const canSave = !saving && rows.some((r) => r.read || r.write);

  return (
    <>
      <div className={styles.block}>
        <h3 className={styles.caption}>{t("relays_title")}</h3>
        <p className={styles.desc}>{t("web_settings_relays_desc")}</p>
        <AddRelayForm list={rows} onAdd={(url) => addRelay(url)} />
        <button
          type="button"
          className={`${styles.primary} ${styles.alignStart}`}
          disabled={!canSave}
          onClick={() => setConfirming(true)}
        >
          {saving ? t("common_saving") : t("common_save")}
        </button>
      </div>
      <AuthPolicyBlock />
      <div className={styles.block}>
        <ul className={styles.relays} aria-label={t("web_settings_relays_list_label")}>
          {sortedRows.map((row) => (
            <RelayRowItem
              key={row.url}
              row={row}
              onChange={(read, write) => setRelayReadWrite(row.url, read, write)}
              onRemove={() => removeRelay(row.url)}
            />
          ))}
          {wsUrls.map((url) => (
            <WsRelayRowItem key={url} url={url} />
          ))}
        </ul>
        {rows.length === 0 && wsUrls.length === 0 && (
          <p className={styles.desc}>{t("web_settings_relays_empty")}</p>
        )}
        <RelayRecsBlock me={me} list={rows} onAdd={(url) => addRelay(url)} />
      </div>
      {confirming && (
        <ConfirmDialog
          title={t("relays_publish_title")}
          text={t("web_relays_publish_text")}
          confirmLabel={t("relays_publish_confirm")}
          onConfirm={() => void save()}
          onDismiss={() => setConfirming(false)}
        />
      )}
    </>
  );
}

const authChoices = (): readonly { policy: AuthPolicy; label: string }[] => [
  { policy: "dm", label: t("auth_dm_mine") },
  { policy: "always", label: t("auth_always") },
  { policy: "off", label: t("auth_off") },
];

/** AUTH（NIP-42）の応答ポリシー（ネイティブ RelaySettings の末尾と同じ 3 択） */
function AuthPolicyBlock() {
  const t = useT();
  const policy = useAuthPolicy((s) => s.policy);
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("auth_title")}</h3>
      <p className={styles.desc}>{t("auth_desc")}</p>
      <div className={styles.choices}>
        {authChoices().map((choice) => (
          <button
            key={choice.policy}
            type="button"
            className={styles.choice}
            aria-pressed={policy === choice.policy}
            onClick={() => setAuthPolicy(choice.policy)}
          >
            {choice.label}
          </button>
        ))}
      </div>
    </div>
  );
}

/**
 * 候補から追加（ネイティブ RelaySettings の「▼ 候補から追加（おすすめ）」）。一覧の下に折りたたみで出し、
 * 開いたらフォロー中の kind:10002 を集計して使っている人の多い順に出す（1 度だけ。登録済みは出さない）。
 * 集計できなければ定番の候補（RELAY_PRESETS）。押すと即座にリレー表へ追加する（手動扱い。#585）。
 */
function RelayRecsBlock({
  me,
  list,
  onAdd,
}: {
  me: string | null;
  list: readonly RelayRow[];
  onAdd(url: string): void;
}) {
  const [open, setOpen] = useState(false);
  const [recs, setRecs] = useState<RelayRec[] | null>(null);
  // 集計から外す登録済み（開いた時点の一覧）
  const listRef = useRef(list);
  listRef.current = list;

  useEffect(() => {
    if (!open || recs !== null) return;
    if (!me) {
      setRecs([]);
      return;
    }
    const registered = new Set(listRef.current.map((p) => p.url));
    const sub = relayRecs$(me, registered).subscribe((next) => setRecs(next));
    return () => sub.unsubscribe();
  }, [open, recs, me]);

  const registered = new Set(list.map((p) => p.url));
  const remaining = (recs ?? []).filter((r) => !registered.has(r.url));

  return (
    <>
      <button
        type="button"
        className={`${styles.link} ${styles.alignStart}`}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {open ? t("recs_close") : t("recs_open")}
      </button>
      {open &&
        (recs === null ? (
          <p className={styles.desc}>{t("relays_recs_loading")}</p>
        ) : recs.length > 0 ? (
          remaining.length > 0 && (
            <>
              <p className={styles.desc}>{t("presets_popular_follows")}</p>
              <ul className={styles.chips} aria-label={t("web_settings_relays_recs_label")}>
                {remaining.map((r) => (
                  <li key={r.url}>
                    <RelayChip url={r.url} note={t("presets_users_fmt", r.count)} onAdd={onAdd} />
                  </li>
                ))}
              </ul>
            </>
          )
        ) : (
          <>
            <p className={styles.desc}>{t("relays_recs_empty")}</p>
            <PresetChips registered={registered} onAdd={onAdd} />
          </>
        ))}
    </>
  );
}

const PRESET_CATEGORIES: readonly RelayPresetCategory[] = ["general", "japan", "paid"];

/** 定番の候補（カテゴリ順に見出し付き。登録済みは出さない） */
function PresetChips({ registered, onAdd }: { registered: ReadonlySet<string>; onAdd(url: string): void }) {
  const t = useT();
  const presets = RELAY_PRESETS.flatMap((p) => {
    const url = parseRelayInput(p.url);
    return url && !registered.has(url) ? [{ ...p, url }] : [];
  });
  return PRESET_CATEGORIES.map((category) => {
    const items = presets.filter((p) => p.category === category);
    if (items.length === 0) return null;
    const label = relayPresetCategoryLabel(category);
    return (
      <div key={category} className={styles.presetGroup}>
        <p className={styles.presetCategory}>{label}</p>
        <ul className={styles.chips} aria-label={t("web_settings_relays_presets_label", label)}>
          {items.map((p) => (
            <li key={p.url}>
              <RelayChip url={p.url} note={p.note ? relayPresetNoteLabel(p.note) : undefined} onAdd={onAdd} />
            </li>
          ))}
        </ul>
      </div>
    );
  });
}

/** 候補のチップ（ネイティブ PresetChip。「＋」+ ホスト名 + 補足）。押すと即座に足す */
function RelayChip({ url, note, onAdd }: { url: string; note?: string; onAdd(url: string): void }) {
  const t = useT();
  const label = displayRelayUrl(url);
  return (
    <button
      type="button"
      className={styles.chip}
      aria-label={
        note ? t("web_settings_relay_add_label_note", label, note) : t("web_settings_relay_add_label", label)
      }
      onClick={() => onAdd(url)}
    >
      <span className={styles.chipPlus} aria-hidden="true">
        ＋
      </span>
      {label}
      {note && <span className={styles.chipNote}>{note}</span>}
    </button>
  );
}

function AddRelayForm({ list, onAdd }: { list: readonly RelayRow[]; onAdd(url: string): void }) {
  const t = useT();
  const inputId = useId();
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    const url = parseRelayInput(value);
    if (!url) {
      setError(
        isWsRelayInput(value) ? t("web_settings_relay_ws_unsupported") : t("web_settings_relay_url_invalid"),
      );
      return;
    }
    if (list.some((p) => p.url === url)) {
      setError(t("web_settings_relay_already_added"));
      return;
    }
    onAdd(url);
    setValue("");
    setError(null);
  }

  return (
    <form className={styles.row} onSubmit={submit}>
      <label htmlFor={inputId} className="srOnly">
        {t("web_settings_relays_url_label")}
      </label>
      <input
        id={inputId}
        className={styles.input}
        type="text"
        inputMode="url"
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        placeholder="wss://…"
        value={value}
        aria-invalid={error !== null}
        onChange={(e) => {
          setValue(e.target.value);
          setError(null);
        }}
      />
      <button type="submit" className={styles.ghost} disabled={value.trim() === ""}>
        {t("common_add")}
      </button>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
    </form>
  );
}

function RelayRowItem({
  row,
  onChange,
  onRemove,
}: {
  row: RelayRow;
  onChange(read: boolean, write: boolean): void;
  onRemove(): void;
}) {
  const label = displayRelayUrl(row.url);
  return (
    <li className={styles.relay}>
      <span className={styles.relayUrl} title={row.url}>
        {label}
      </span>
      <span className={styles.relayMeta}>{sourceLabel(row.source)}</span>
      <label className={styles.check}>
        <input
          type="checkbox"
          checked={row.read}
          aria-label={t("web_settings_relay_read_label", label)}
          onChange={(e) => onChange(e.target.checked, row.write)}
        />
        Read
      </label>
      <label className={styles.check}>
        <input
          type="checkbox"
          checked={row.write}
          aria-label={t("web_settings_relay_write_label", label)}
          onChange={(e) => onChange(row.read, e.target.checked)}
        />
        Write
      </label>
      <button
        type="button"
        className={styles.textButton}
        aria-label={t("web_settings_relay_remove_label", label)}
        onClick={onRemove}
      >
        {t("common_delete")}
      </button>
    </li>
  );
}

/** NIP-65 にある ws:// のリレーの行。接続しないので Read / Write・削除は出さない（#776） */
function WsRelayRowItem({ url }: { url: string }) {
  const label = url.replace(/\/$/, "");
  return (
    <li className={styles.relay}>
      <span className={`${styles.relayUrl} ${styles.dimmed}`} title={url}>
        {label}
      </span>
      <span className={styles.relayMeta}>NIP-65</span>
      <span className={styles.relayMeta}>{t("web_settings_relay_ws_unreachable")}</span>
    </li>
  );
}
