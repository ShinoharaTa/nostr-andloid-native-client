import { type ReactNode, useId } from "react";
import { Navigate, useLocation, useNavigate, useParams } from "react-router";
import { useT } from "../../i18n";
import { ScreenHeader } from "../../ui/ScreenHeader";
import { useLayoutMode } from "../../ui/useLayoutMode";
import { WalletSection } from "../wallet/WalletSection";
import { AboutSection } from "./AboutSection";
import { AccountSection, AccountSummary } from "./AccountSection";
import { BookmarksSection } from "./BookmarksSection";
import { DataSection } from "./DataSection";
import { DisplaySection } from "./DisplaySection";
import { DmRelaySection } from "./DmRelaySection";
import { EmojiSection } from "./EmojiSection";
import { FavsSection } from "./FavsSection";
import { HashtagSection } from "./HashtagSection";
import { MediaSection } from "./MediaSection";
import { MuteSection } from "./MuteSection";
import { ProfileEditSection } from "./ProfileEditSection";
import { ReactionSection } from "./ReactionSection";
import { RelaySection } from "./RelaySection";
import styles from "./SettingsScreen.module.css";
import {
  canGoBackFromSection,
  DEFAULT_SECTION_ID,
  findSection,
  renamedSectionId,
  SETTINGS_FROM_LIST,
  SETTINGS_GROUPS,
  type SettingsSection,
} from "./sections";

/**
 * 設定（ネイティブ SettingsScreen + TwoPane）。URL は /settings/:section?。
 * Expanded = 左に項目の一覧・右に内容（未選択ならプロフィール編集）、Compact/Rail = 一覧 → 内容（「←」で一覧へ。
 * [#661] Rail は内容が Compact と同じ 1 ペイン）。
 * [#807] 自分のアイコンのメニュー・プロフィールの「編集」から開いた項目の「←」は、開く前の画面へ戻る。
 */
export function SettingsScreen() {
  const mode = useLayoutMode();
  const { section: param } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const opened = findSection(param) ?? null;
  const selected = opened ?? (mode === "expanded" ? (findSection(DEFAULT_SECTION_ID) ?? null) : null);

  // Compact は戻る対象にする（一覧 → 内容）、Expanded は項目の切り替えなので置き換える
  function select(id: string) {
    if (id === selected?.id) return;
    if (mode !== "expanded") void navigate(`/settings/${id}`, { state: { [SETTINGS_FROM_LIST]: true } });
    else void navigate(`/settings/${id}`, { replace: true });
  }

  // 一覧から・設定の外から積んだエントリは 1 つ戻る（一覧 / 開く前の画面）。直接開いたものは一覧へ置き換える
  function back() {
    if (canGoBackFromSection(location.state)) void navigate(-1);
    else void navigate("/settings", { replace: true });
  }

  const renamed = renamedSectionId(param);
  if (renamed) return <Navigate to={`/settings/${renamed}`} replace state={location.state} />;

  if (mode !== "expanded") {
    return (
      <div className={styles.single}>
        {selected ? (
          <SectionPane section={selected} onBack={back} />
        ) : (
          <SectionList selectedId={null} onSelect={select} />
        )}
      </div>
    );
  }
  return (
    <div className={styles.twoPane}>
      <div className={styles.listPane}>
        <SectionList selectedId={selected?.id ?? null} onSelect={select} />
      </div>
      <div className={styles.detailPane}>{selected && <SectionPane section={selected} />}</div>
    </div>
  );
}

/** 項目の一覧（ネイティブ SettingsMenu。グループ見出し + 行） */
function SectionList({ selectedId, onSelect }: { selectedId: string | null; onSelect(id: string): void }) {
  const t = useT();
  return (
    <div className={styles.list}>
      <ScreenHeader title={t("settings_title")} />
      <AccountSummary onOpen={() => onSelect("account")} />
      <nav className={styles.groups} aria-label={t("web_settings_nav_label")}>
        {SETTINGS_GROUPS.map((group) => (
          <SectionGroup key={group.title()} title={group.title()}>
            {group.sections.map((section) => (
              <li key={section.id}>
                <button
                  type="button"
                  className={styles.row}
                  aria-current={section.id === selectedId ? "page" : undefined}
                  onClick={() => onSelect(section.id)}
                >
                  <span className={styles.rowLabel}>{section.label()}</span>
                  {!section.ready && (
                    <span className={styles.badge}>{t("web_settings_coming_soon_badge")}</span>
                  )}
                </button>
              </li>
            ))}
          </SectionGroup>
        ))}
      </nav>
    </div>
  );
}

function SectionGroup({ title, children }: { title: string; children: ReactNode }) {
  const id = useId();
  return (
    <section aria-labelledby={id}>
      <h2 id={id} className={styles.groupTitle}>
        {title}
      </h2>
      <ul className={styles.rows}>{children}</ul>
    </section>
  );
}

/** 項目の内容。Compact は「←」つきのヘッダ、Expanded は見出しだけ */
function SectionPane({ section, onBack }: { section: SettingsSection; onBack?: () => void }) {
  return (
    <section className={styles.pane} aria-label={section.label()}>
      {onBack ? (
        <ScreenHeader title={section.label()} onBack={onBack} />
      ) : (
        <h2 className={styles.paneTitle}>{section.label()}</h2>
      )}
      <div className={styles.body}>
        <SectionBody id={section.id} />
      </div>
    </section>
  );
}

function SectionBody({ id }: { id: string }) {
  const t = useT();
  switch (id) {
    case "reaction":
      return <ReactionSection />;
    case "favs":
      return <FavsSection />;
    case "bookmarks":
      return <BookmarksSection />;
    case "mute":
      return <MuteSection />;
    case "emoji":
      return <EmojiSection />;
    case "hashtags":
      return <HashtagSection />;
    case "profile-edit":
      return <ProfileEditSection />;
    case "account":
      return <AccountSection />;
    case "relays":
      return <RelaySection />;
    case "dm-relays":
      return <DmRelaySection />;
    case "media":
      return <MediaSection />;
    case "wallet":
      return <WalletSection />;
    case "display":
      return <DisplaySection />;
    case "data":
      return <DataSection />;
    case "about":
      return <AboutSection />;
    default:
      return <p className={styles.comingSoon}>{t("web_settings_coming_soon")}</p>;
  }
}
