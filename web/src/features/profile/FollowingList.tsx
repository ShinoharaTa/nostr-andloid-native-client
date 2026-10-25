import { Link } from "react-router";
import { Virtuoso } from "react-virtuoso";
import { useT } from "../../i18n";
import { hrefForProfile } from "../../lib/content/labels";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { ScreenHeader } from "../../ui/ScreenHeader";
import { Avatar } from "../timeline/NoteItem";
import styles from "./FollowingList.module.css";
import { nip05Label } from "./nip05";

/**
 * フォロー中の一覧（ネイティブ UserListScreen）。プロフィールを置き換えて出し、「←」で戻る。
 * 行を押すとその人のプロフィール。プロフィールは表示された行の分だけまとめて取りに行く。
 */
export function FollowingList({ pubkeys, onBack }: { pubkeys: readonly string[]; onBack: () => void }) {
  const t = useT();
  return (
    <div className={styles.screen}>
      <ScreenHeader title={t("tpl_following")} onBack={onBack} />
      <hr className={styles.divider} />
      {pubkeys.length === 0 ? (
        <p className={styles.empty}>{t("not_found")}</p>
      ) : (
        <Virtuoso
          data={pubkeys}
          computeItemKey={(_, pk) => pk}
          itemContent={(_, pk) => <UserRow pubkey={pk} />}
          style={{ flex: 1 }}
        />
      )}
    </div>
  );
}

/** ユーザー一覧の 1 行（アバター・名前・NIP-05。押すとプロフィール）。フォロワー一覧・リストタブでも使う */
export function UserRow({ pubkey }: { pubkey: string }) {
  const profile = useProfile(pubkey);
  const picture = pictureOf(profile);
  const name = displayName(profile, pubkey);
  const nip05 =
    typeof profile?.nip05 === "string" && profile.nip05.trim() !== "" ? profile.nip05.trim() : null;
  return (
    <Link to={hrefForProfile(pubkey)} className={styles.row}>
      <Avatar key={picture} url={picture} size="lg" seed={name} pubkey={pubkey} />
      <span className={styles.texts}>
        <span className={styles.name}>{name}</span>
        {nip05 && <span className={styles.nip05}>{nip05Label(nip05)}</span>}
      </span>
    </Link>
  );
}
