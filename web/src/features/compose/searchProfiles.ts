import { getProfileContent } from "applesauce-core/helpers/profile";
import { eventStore } from "../../nostr/store";
import { nip05Label } from "../profile/nip05";

/** メンション候補の 1 行 */
export type ProfileHit = { pubkey: string; name: string; handle: string; picture?: string };

function firstText(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim() !== "") return value.trim();
  }
  return "";
}

/**
 * ストアにあるプロフィールを 名前 / NIP-05 の前方一致（大小無視）で探す（ネイティブ searchProfiles）。
 * 名前のあるものを先に、同順位は kind:0 の新しい順。
 * [#939] `_@domain` は画面にドメインだけを出すので、表示どおりの値（nip05Label）の前方一致でも当てる。
 */
export function searchProfiles(prefix: string, limit = 8): ProfileHit[] {
  const p = prefix.trim().toLowerCase();
  if (p === "") return [];
  const hits: (ProfileHit & { createdAt: number })[] = [];
  for (const event of eventStore.getByFilters({ kinds: [0] })) {
    let content: unknown;
    try {
      content = getProfileContent(event);
    } catch {
      continue;
    }
    if (typeof content !== "object" || content === null || Array.isArray(content)) continue;
    const c = content as Record<string, unknown>;
    const name = firstText(c.display_name, c.displayName, c.name);
    const handle = firstText(c.nip05);
    if (
      !name.toLowerCase().startsWith(p) &&
      !handle.toLowerCase().startsWith(p) &&
      !nip05Label(handle).toLowerCase().startsWith(p)
    )
      continue;
    const picture = firstText(c.picture);
    hits.push({
      pubkey: event.pubkey,
      name,
      handle,
      ...(picture ? { picture } : {}),
      createdAt: event.created_at,
    });
  }
  hits.sort((a, b) => Number(a.name === "") - Number(b.name === "") || b.createdAt - a.createdAt);
  return hits.slice(0, limit).map(({ createdAt: _, ...hit }) => hit);
}
