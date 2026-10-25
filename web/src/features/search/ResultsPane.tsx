import { useMemo, useState } from "react";
import { Link } from "react-router";
import { Virtuoso } from "react-virtuoso";
import { useT } from "../../i18n";
import { buildSearchColumn } from "../../lib/columns";
import { hrefForProfile, oneLine } from "../../lib/content/labels";
import { unixNow } from "../../lib/time";
import { useDeck } from "../../store/deck";
import { useColumnFeed } from "../deck/useColumnFeed";
import { nip05Label } from "../profile/nip05";
import { Avatar, NoteItem } from "../timeline/NoteItem";
import styles from "./ResultsPane.module.css";
import { SEARCH_ROWS_TOTAL, searchSpecOf, searchSummary, tokensToFilter, userQueryOf } from "./searchTokens";
import { type UserHit, useUserSearch } from "./searchUsers";

type Tab = "posts" | "users";

/**
 * 検索結果（ネイティブ ResultsPane）。見出し行（← 履歴・検索: 条件・Deckに追加）→ 投稿 / ユーザーの切替 → 一覧。
 * 実行し直すたびに呼び出し側の key で作り直す（REQ の張り直しと「投稿」への戻り）。
 */
export function ResultsPane({ tokens, onBack }: { tokens: readonly string[]; onBack?: () => void }) {
  const t = useT();
  // 条件の中身が変わったときだけ作り直す（語に空白・改行は入らない）
  const key = tokens.join("\n");
  const spec = useMemo(() => searchSpecOf(key === "" ? [] : key.split("\n")), [key]);
  const { events, loading } = useColumnFeed(spec);
  const posts = useMemo(() => events.slice(0, SEARCH_ROWS_TOTAL), [events]);
  const { users, loading: usersLoading } = useUserSearch(userQueryOf(tokens));
  const [tab, setTab] = useState<Tab>("posts");

  function addToDeck() {
    const s = useDeck.getState();
    const { words, hashtags } = tokensToFilter(tokens);
    // デッキへの移動は AppShell（宛先の外で jump したらデッキへ出す）が行う
    s.addColumn(buildSearchColumn(words, hashtags, new Set(s.columns.map((c) => c.id)), unixNow()));
  }

  return (
    <div className={styles.results}>
      <div className={styles.head}>
        {onBack && (
          <button type="button" className={styles.textButton} onClick={onBack}>
            {t("search_back_history")}
          </button>
        )}
        <p className={styles.summary}>{searchSummary(tokens)}</p>
        <button type="button" className={styles.ghost} onClick={addToDeck}>
          {t("search_add_deck")}
        </button>
      </div>
      <div role="tablist" aria-label={t("web_search_result_tabs_label")} className={styles.tabs}>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "posts"}
          className={styles.tab}
          onClick={() => setTab("posts")}
        >
          {t("search_tab_posts")}
          {posts.length > 0 ? ` ${posts.length}` : ""}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "users"}
          className={styles.tab}
          onClick={() => setTab("users")}
        >
          {t("search_tab_users")}
          {users.length > 0 ? ` ${users.length}` : ""}
        </button>
      </div>
      <hr className={styles.divider} />
      <div className={styles.body} role="tabpanel">
        {tab === "posts" ? (
          posts.length === 0 ? (
            <StateView loading={loading} emptyText={t("search_no_results")} />
          ) : (
            <Virtuoso
              data={posts}
              computeItemKey={(_, e) => e.id}
              itemContent={(_, e) => <NoteItem event={e} />}
              style={{ height: "100%" }}
            />
          )
        ) : users.length === 0 ? (
          <StateView loading={usersLoading} emptyText={t("search_no_users")} />
        ) : (
          <Virtuoso
            data={users}
            computeItemKey={(_, h) => h.pubkey}
            itemContent={(_, h) => <UserRow hit={h} />}
            style={{ height: "100%" }}
          />
        )}
      </div>
    </div>
  );
}

/** 読み込み中 / 空（ネイティブ ColumnStateView） */
function StateView({ loading, emptyText }: { loading: boolean; emptyText: string }) {
  const t = useT();
  if (loading) {
    return (
      <div className={styles.state} role="status">
        <span className={styles.spinner} aria-hidden="true" />
        <span>{t("loading")}</span>
      </div>
    );
  }
  return <p className={styles.state}>{emptyText}</p>;
}

/** ユーザーの 1 行（ネイティブ SearchUserRow）。押すとプロフィール */
function UserRow({ hit }: { hit: UserHit }) {
  return (
    <Link to={hrefForProfile(hit.pubkey)} className={styles.user}>
      <Avatar
        key={hit.picture}
        url={hit.picture}
        size="lg"
        seed={hit.name || hit.pubkey}
        pubkey={hit.pubkey}
      />
      <span className={styles.userTexts}>
        <span className={styles.userName}>{hit.name || hit.pubkey.slice(0, 10)}</span>
        {hit.handle && <span className={styles.userHandle}>{nip05Label(hit.handle)}</span>}
        {hit.about && <span className={styles.userAbout}>{oneLine(hit.about)}</span>}
      </span>
    </Link>
  );
}
