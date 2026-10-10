import { use$ } from "applesauce-react/hooks/use-$";
import { npubEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useT } from "../../i18n";
import { markProxyBlocked, originOf, proxied } from "../../lib/imageProxy";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { eventStore } from "../../nostr/store";
import { BoltIcon, ContentCopyIcon } from "../../ui/icons";
import { showToast } from "../../ui/toast";
import { Lightbox } from "../media/Lightbox";
import { useMuteMatcher } from "../mute/muteList";
import { useOpenSettingsSection } from "../settings/useOpenSettingsSection";
import { RichText } from "../timeline/NoteContent";
import { Avatar } from "../timeline/NoteItem";
import { payInvoiceWithNwc, useNwc } from "../wallet/nwcManager";
import { ZapDialog } from "../zap/ZapDialog";
import { parseAbout } from "./about";
import { FollowButton } from "./FollowButton";
import { Nip05Handle } from "./Nip05Handle";
import styles from "./ProfileHeaderCard.module.css";
import { ProfileMenu } from "./ProfileMenu";
import { ProfileRelays } from "./ProfileRelays";

/** フォロー失敗の案内を出しておく時間 */
const ERROR_MS = 4_000;

const WEB_URL = /^https?:\/\//i;

/** kind:0 の値のうち、空でない文字列だけ（前後の空白は落とす） */
function textOf(value: unknown): string | null {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : null;
}

/**
 * kind:0 に書かれたままの website。applesauce の解析（useProfile）はスキームの無い値に https:// を足すので、
 * 元の JSON から読む（スキームの無い値は文字のまま出す = ネイティブと同じ）。
 */
function rawWebsite(profileEvent: NostrEvent | undefined): string | null {
  if (!profileEvent) return null;
  try {
    const content: unknown = JSON.parse(profileEvent.content);
    return typeof content === "object" && content !== null
      ? textOf((content as Record<string, unknown>).website)
      : null;
  } catch {
    return null;
  }
}

/** 一定時間で消える 1 行の案内 */
function useTimedMessage(durationMs: number): [string | null, (message: string) => void] {
  const [message, setMessage] = useState<string | null>(null);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  const show = useCallback(
    (next: string) => {
      setMessage(next);
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => setMessage(null), durationMs);
    },
    [durationMs],
  );
  return [message, show];
}

/**
 * プロフィールのヘッダ（ネイティブ ProfileHeaderCard）。バナー・アバター・⋯・フォロー / 編集、名前・NIP-05・npub・
 * フォロー中の件数と「フォロワーを確認」・自己紹介・lud16・website・使用リレー。バナーとアバターは押すと原寸で開く。
 * 他人の lud16 は押すとプロフィール Zap。ミュート中なら名前の横に「ミュート中」。
 */
export function ProfileHeaderCard({
  pubkey,
  isMe,
  me,
  following,
  followsMe,
  followingCount,
  onShowFollowing,
  onShowFollowers,
}: {
  pubkey: string;
  isMe: boolean;
  me: string;
  following: boolean;
  followsMe: boolean;
  followingCount: number;
  onShowFollowing: () => void;
  onShowFollowers: () => void;
}) {
  const t = useT();
  // [#807] 「編集」は設定のプロフィール編集を積んで開く（「←」でこのプロフィールへ戻る。ネイティブ openSettingsSection）
  const openSettingsSection = useOpenSettingsSection();
  const profile = useProfile(pubkey);
  const profileEvent = use$(() => eventStore.replaceable(0, pubkey), [pubkey]);
  const picture = pictureOf(profile);
  const name = displayName(profile, pubkey);
  const zoomablePicture = picture && WEB_URL.test(picture.trim()) ? picture.trim() : null;
  const banner = textOf(profile?.banner);
  const nip05 = textOf(profile?.nip05);
  const about = textOf(profile?.about);
  const lud16 = textOf(profile?.lud16);
  const website = useMemo(() => rawWebsite(profileEvent), [profileEvent]);
  const npub = useMemo(() => npubEncode(pubkey), [pubkey]);
  const muted = useMuteMatcher().users.has(pubkey);
  // kind:0 のイベントが手元に来るまでは about を出さない（カスタム絵文字は kind:0 のタグから引く）
  const aboutRoot = useMemo(
    () => (profileEvent && about ? parseAbout(profileEvent, about) : null),
    [profileEvent, about],
  );

  const [error, showError] = useTimedMessage(ERROR_MS);
  const [zoom, setZoom] = useState<string | null>(null);
  const [zapping, setZapping] = useState(false);
  // [#537] ウォレット接続（NWC）済みなら Zap ダイアログの受け口へアプリ内送金を渡す
  const walletConnected = useNwc((s) => s.connection !== null);

  async function copyNpub() {
    try {
      await navigator.clipboard.writeText(npub);
    } catch {
      showToast(t("web_copy_failed"));
      return;
    }
    showToast(t("npub_copied"));
  }

  return (
    <div className={styles.card}>
      <div className={styles.top}>
        {banner && WEB_URL.test(banner) ? (
          <Banner key={banner} url={banner} onOpen={() => setZoom(banner)} />
        ) : (
          <div className={styles.banner} />
        )}
        <div className={styles.band} />
        {zoomablePicture ? (
          <button
            type="button"
            className={styles.ring}
            aria-label={t("img_view")}
            onClick={() => setZoom(zoomablePicture)}
          >
            <Avatar key={picture} url={picture} size="xxl" seed={name} pubkey={pubkey} />
          </button>
        ) : (
          <div className={styles.ring}>
            <Avatar key={picture} url={picture} size="xxl" seed={name} pubkey={pubkey} />
          </div>
        )}
        <div className={styles.actions}>
          {/* [#592] プロフィール Zap: lud16 がある他人にだけ、⋯ の左に丸いボタン（ネイティブ ProfileScreen.kt:715-719） */}
          {!isMe && lud16 && (
            <button type="button" className={styles.circle} aria-label="Zap" onClick={() => setZapping(true)}>
              <BoltIcon className={`${styles.icon} ${styles.zap}`} />
            </button>
          )}
          <ProfileMenu pubkey={pubkey} me={isMe ? null : me} muted={muted} onCopied={showToast} />
          {isMe ? (
            <button
              type="button"
              className={`${styles.pill} ${styles.ghost}`}
              onClick={() => openSettingsSection("profile-edit")}
            >
              {t("edit")}
            </button>
          ) : (
            <FollowButton me={me} target={pubkey} following={following} onError={showError} />
          )}
        </div>
      </div>
      <div className={styles.text}>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <div className={styles.nameRow}>
          <h2 className={styles.name}>{name}</h2>
          {followsMe && <span className={styles.badge}>{t("follows_you")}</span>}
          {muted && <span className={styles.badge}>{t("muted_badge")}</span>}
        </div>
        {nip05 && (
          <div className={styles.line}>
            <Nip05Handle pubkey={pubkey} nip05={nip05} size="sub" />
          </div>
        )}
        <div className={`${styles.line} ${styles.npubRow}`}>
          <span className={styles.npub} title={npub}>{`${npub.slice(0, 20)}…${npub.slice(-6)}`}</span>
          <button
            type="button"
            className={`${styles.circle} ${styles.copy}`}
            aria-label={t("npub_copy")}
            onClick={copyNpub}
          >
            <ContentCopyIcon className={styles.icon} />
          </button>
        </div>
        <div className={styles.counts}>
          <button type="button" className={styles.count} onClick={onShowFollowing}>
            <span className={styles.countNum}>{followingCount}</span>
            <span className={styles.hint}>{t("tpl_following")}</span>
          </button>
          <button type="button" className={styles.followers} onClick={onShowFollowers}>
            {t("followers_check")}
          </button>
        </div>
        {aboutRoot && (
          <div className={styles.about}>
            <RichText root={aboutRoot} size="sub" />
          </div>
        )}
        {lud16 &&
          (isMe ? (
            <p className={styles.lud16}>{`⚡ ${lud16}`}</p>
          ) : (
            // 他人の lud16 は押すとプロフィール Zap（e タグ無し。ネイティブ ProfileZapSheet）
            <button type="button" className={styles.lud16} onClick={() => setZapping(true)}>
              {`⚡ ${lud16}`}
            </button>
          ))}
        {website &&
          (WEB_URL.test(website) ? (
            <a
              className={styles.website}
              href={website}
              target="_blank"
              rel="noopener noreferrer nofollow ugc"
            >
              {website}
            </a>
          ) : (
            <span className={styles.website}>{website}</span>
          ))}
        <ProfileRelays pubkey={pubkey} />
      </div>
      {zoom && <Lightbox items={[{ url: zoom }]} index={0} onClose={() => setZoom(null)} />}
      {zapping && lud16 && (
        <ZapDialog
          recipient={pubkey}
          recipientName={name}
          lud16={lud16}
          payWithWallet={walletConnected ? payInvoiceWithNwc : undefined}
          onClose={() => setZapping(false)}
        />
      )}
    </div>
  );
}

/**
 * バナー画像。プロキシが読めなければ元 URL（https のみ）で 1 度だけ取り直し、それも読めなければ帯だけにする
 * （Avatar と同じ）。url が変わったら呼び出し側の key で作り直す。
 */
function Banner({ url, onOpen }: { url: string; onOpen: () => void }) {
  const t = useT();
  const [src, setSrc] = useState<string | null>(() => proxied(url, 900, 80, true));
  if (!src) return <div className={styles.banner} />;

  function onError() {
    const origin = originOf(src);
    if (origin) {
      markProxyBlocked(origin);
      setSrc(/^https:\/\//i.test(origin) ? origin : null);
    } else {
      setSrc(null);
    }
  }

  return (
    <button type="button" className={styles.banner} aria-label={t("img_view")} onClick={onOpen}>
      <img src={src} alt="" decoding="async" referrerPolicy="no-referrer" onError={onError} />
    </button>
  );
}
