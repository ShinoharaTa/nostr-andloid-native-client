import { useState } from "react";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForProfile } from "../../lib/content/labels";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { badgeText } from "../../ui/badge";
import { AddIcon } from "../../ui/icons";
import { Avatar } from "../timeline/NoteItem";
import styles from "./ConversationList.module.css";
import { resumeDecrypting } from "./dmService";
import { type DmConversation, useConversations, useDm } from "./dmStore";
import { NewConversationDialog } from "./NewConversationDialog";

/**
 * 一覧に出す会話（#970）。tabs = 「フォロー中」「その他」のタブで分ける（メッセージ画面）、
 * accepted = 「フォロー中」だけ、all = 全部（DM カラム。⋯ の「フォローしていない人の DM も出す」）
 */
export type ConversationScope = "tabs" | "accepted" | "all";

type Tab = "following" | "others";

/**
 * 会話の一覧（ネイティブ DmConversationRows。メッセージ画面と DM カラムで使う）。新しい順。
 * 行 = アバター（プロフィールへ）・表示名・最後のメッセージ 1 行・未読数（ネイティブ #416）。行を押すと onSelect。
 * showBanners なら上に復号の状態（NIP-44 が無い・一時停止・復号中）を出す。
 * showNewRow なら先頭に「新しいメッセージを送る」（相手を入れて onSelect。まだ会話の無い相手でもよい）。
 * scope で出す会話を選ぶ（#970。ネイティブ DmList / DmColumn と同じ）
 */
export function ConversationList({
  selectedPeer,
  onSelect,
  showBanners,
  showNewRow,
  scope,
}: {
  selectedPeer: string | null;
  onSelect(peer: string): void;
  showBanners: boolean;
  showNewRow: boolean;
  scope: ConversationScope;
}) {
  const t = useT();
  const all = useConversations();
  const loaded = useDm((s) => s.loaded);
  const [composing, setComposing] = useState(false);
  // 開いている会話が「その他」なら、最初は「その他」を見せる（選んだ行が隠れないように）
  const [tab, setTab] = useState<Tab>(() =>
    all.find((c) => c.peer === selectedPeer)?.accepted === false ? "others" : "following",
  );
  const others = all.filter((c) => !c.accepted);
  const othersUnread = others.reduce((sum, c) => sum + c.unread, 0);
  const othersTab = scope === "tabs" && tab === "others";
  const conversations = scope === "all" ? all : othersTab ? others : all.filter((c) => c.accepted);
  const empty = !loaded ? t("loading") : othersTab ? t("dm_others_empty") : t("dm_empty");
  return (
    <div className={styles.root}>
      {showBanners && <Banners />}
      {scope === "tabs" && (
        <div role="tablist" aria-label={t("nav_dm")} className={styles.tabs}>
          <button
            type="button"
            role="tab"
            aria-selected={tab === "following"}
            className={styles.tab}
            onClick={() => setTab("following")}
          >
            {t("dm_tab_following")}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === "others"}
            className={styles.tab}
            onClick={() => setTab("others")}
          >
            {t("dm_tab_others")}
            {/* 通知と未読の合計には数えないので、ここで気付けるように件数を出す */}
            {othersUnread > 0 && (
              <span className={styles.tabCount} aria-hidden="true">
                {badgeText(othersUnread)}
              </span>
            )}
          </button>
        </div>
      )}
      {othersTab && <p className={styles.caption}>{t("dm_others_desc")}</p>}
      {showNewRow && (
        <button type="button" className={styles.newRow} onClick={() => setComposing(true)}>
          <AddIcon className={styles.newIcon} />
          {t("dm_new_row")}
        </button>
      )}
      {composing && (
        <NewConversationDialog
          onOpen={(pubkey) => {
            setComposing(false);
            onSelect(pubkey);
          }}
          onDismiss={() => setComposing(false)}
        />
      )}
      {conversations.length === 0 ? (
        <p className={styles.empty}>{empty}</p>
      ) : (
        <ul className={styles.rows}>
          {conversations.map((conversation) => (
            <ConversationRow
              key={conversation.peer}
              conversation={conversation}
              selected={conversation.peer === selectedPeer}
              onSelect={onSelect}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

function Banners() {
  const t = useT();
  const nip17 = useDm((s) => s.nip17);
  const paused = useDm((s) => s.paused);
  const pending = useDm((s) => s.pending);
  return (
    <>
      {nip17 === "no-nip44" && (
        <div role="status" className={styles.banner}>
          {t("web_dm_banner_no_nip44")}
        </div>
      )}
      {paused ? (
        <div role="status" className={styles.banner}>
          <span className={styles.bannerText}>{t("web_dm_banner_paused")}</span>
          <button type="button" className={styles.bannerButton} onClick={resumeDecrypting}>
            {t("web_dm_banner_resume")}
          </button>
        </div>
      ) : (
        pending > 0 && (
          <div role="status" className={styles.banner}>
            {t("web_dm_banner_decrypting", pending)}
          </div>
        )
      )}
    </>
  );
}

function ConversationRow({
  conversation,
  selected,
  onSelect,
}: {
  conversation: DmConversation;
  selected: boolean;
  onSelect(peer: string): void;
}) {
  const t = useT();
  const { peer, last, unread } = conversation;
  const profile = useProfile(peer);
  const picture = pictureOf(profile);
  const name = displayName(profile, peer);
  return (
    <li className={styles.item}>
      <Link
        className={styles.avatarLink}
        to={hrefForProfile(peer)}
        aria-label={t("web_dm_profile_label", name)}
      >
        <Avatar key={picture} url={picture} size="lg" seed={name} pubkey={peer} />
      </Link>
      <button
        type="button"
        className={styles.row}
        aria-current={selected ? "true" : undefined}
        aria-label={unread > 0 ? t("web_dm_row_unread_label", name, unread, last.content) : undefined}
        onClick={() => onSelect(peer)}
      >
        <span className={styles.texts}>
          <span className={styles.name}>{name}</span>
          <span className={styles.last}>{last.content}</span>
        </span>
        {unread > 0 && (
          <span className={styles.unread} aria-hidden="true">
            {badgeText(unread)}
          </span>
        )}
      </button>
    </li>
  );
}
