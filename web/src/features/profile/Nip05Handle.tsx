import { useT } from "../../i18n";
import { ErrorOutlineIcon, VerifiedIcon } from "../../ui/icons";
import styles from "./Nip05Handle.module.css";
import { nip05Label, useNip05Status } from "./nip05";

/**
 * NIP-05 の文字 + 検証バッジ（ネイティブ Nip05Handle）。OK = 緑の ✓、不一致 = 赤の !、
 * 確認中・取得できなかったときは何も付けない。
 */
export function Nip05Handle({
  pubkey,
  nip05,
  size,
}: {
  pubkey: string;
  nip05: string;
  size: "sub" | "caption";
}) {
  const t = useT();
  const status = useNip05Status(pubkey, nip05);
  return (
    <span className={styles.handle} data-size={size}>
      <span className={styles.text}>{nip05Label(nip05)}</span>
      {status === "verified" && <VerifiedIcon className={styles.ok} title={t("nip05_ok")} />}
      {status === "invalid" && <ErrorOutlineIcon className={styles.bad} title={t("nip05_bad")} />}
    </span>
  );
}
