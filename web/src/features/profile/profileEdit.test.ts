import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY, Observable, throwError } from "rxjs";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { INDEXER_RELAYS } from "../../lib/columnRequest";
import { requestOnce, resetRelays } from "../../nostr/pool";
import { PublishError, publishEvent } from "../../nostr/publish";
import { addVerified } from "../../nostr/store";
import {
  buildProfileContent,
  changedFields,
  PROFILE_FIELDS,
  ProfileEditError,
  profileFieldsOf,
  publishProfile,
} from "./profileEdit";

// リレーには繋がない（取り直しはテストごとに完了 / 失敗を返す）
vi.mock("../../nostr/pool", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/pool")>()),
  requestOnce: vi.fn(),
}));

// 署名・送信はしない（送信キューの入口だけ差し替える）
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async () => ({})),
}));

let key: Uint8Array;
let me: string;

beforeEach(() => {
  key = generateSecretKey();
  me = getPublicKey(key);
  vi.mocked(requestOnce).mockReset();
  vi.mocked(publishEvent).mockClear();
  localStorage.clear();
  resetRelays();
});

function profile(
  content: Record<string, unknown> | string,
  createdAt: number,
  tags: string[][] = [],
): NostrEvent {
  return finalizeEvent(
    {
      kind: 0,
      created_at: createdAt,
      tags,
      content: typeof content === "string" ? content : JSON.stringify(content),
    },
    key,
  );
}

/** 取り直しで latest が届いて完了する */
function respondWith(latest: NostrEvent) {
  vi.mocked(requestOnce).mockImplementation(
    () =>
      new Observable<NostrEvent>((subscriber) => {
        addVerified(latest, "wss://indexer.example");
        subscriber.next(latest);
        subscriber.complete();
      }),
  );
}

function published(): {
  kind: number;
  content: Record<string, unknown>;
  tags: string[][];
  created_at?: number;
} {
  expect(vi.mocked(publishEvent)).toHaveBeenCalledTimes(1);
  const [draft, opts] = vi.mocked(publishEvent).mock.calls[0];
  // 送り先は write リレー ∪ インデクサ
  for (const url of INDEXER_RELAYS) expect(opts?.relays).toContain(url);
  return { ...draft, content: JSON.parse(draft.content) as Record<string, unknown> };
}

/**
 * [#922] ネイティブ ProfileEditTest（#920）と同じケース・同じ名前。name と display_name を別の項目として扱い
 * （NIP-24）、片方を変えてももう片方を書き換えないことを守る。
 */
describe("ProfileEdit（ネイティブ ProfileEditTest と同じケース）", () => {
  /** content 文字列の kind:0（土台・初期値の読み取り用） */
  const ev = (content: string) => profile(content, 1_000);
  const obj = (content: string) => JSON.parse(content) as Record<string, unknown>;

  // ---- 初期値 ----

  it("fields_keep_name_and_display_name_apart", () => {
    const f = profileFieldsOf(ev(`{"name":"shino3","display_name":"しの","about":"hi"}`));
    expect(f.name).toBe("shino3");
    expect(f.display_name).toBe("しの");
    expect(f.about).toBe("hi");
  });

  it("display_name_falls_back_to_legacy_displayName", () => {
    const f = profileFieldsOf(ev(`{"name":"shino3","display_name":"","displayName":"しの"}`));
    expect(f.display_name).toBe("しの");
    expect(f.name).toBe("shino3");
  });

  it("name_does_not_fill_display_name", () => {
    // 表示名が無い人の表示名の欄は空のまま（name を表示名の欄に出さない）。
    const f = profileFieldsOf(ev(`{"name":"shino3"}`));
    expect(f.display_name).toBe("");
    expect(f.name).toBe("shino3");
  });

  it("non_string_values_and_broken_json_are_blank", () => {
    const f = profileFieldsOf(ev(`{"name":123,"about":null}`));
    expect(f.name).toBe("");
    expect(f.about).toBe("");
    expect(Object.values(profileFieldsOf(ev("not json"))).every((v) => v === "")).toBe(true);
    expect(Object.values(profileFieldsOf(null)).every((v) => v === "")).toBe(true);
    expect(new Set(Object.keys(profileFieldsOf(null)))).toEqual(new Set(PROFILE_FIELDS));
  });

  // ---- 変えた欄 ----

  it("changed_has_only_edited_fields_trimmed", () => {
    const initial = profileFieldsOf(ev(`{"name":"shino3","display_name":"しの","about":"hi"}`));
    const current = { ...initial, display_name: " しの🌸 ", about: "hi  " };
    expect(changedFields(initial, current)).toEqual({ display_name: "しの🌸" });
  });

  // ---- 発行する content ----

  it("editing_display_name_keeps_name", () => {
    // 不具合の再現: 以前は name も「しの🌸」になって「shino3」が消えていた。
    const out = obj(
      buildProfileContent(ev(`{"name":"shino3","display_name":"しの"}`), { display_name: "しの🌸" }),
    );
    expect(out.name).toBe("shino3");
    expect(out.display_name).toBe("しの🌸");
  });

  it("editing_name_keeps_display_name", () => {
    const out = obj(buildProfileContent(ev(`{"name":"shino3","display_name":"しの"}`), { name: "shino" }));
    expect(out.name).toBe("shino");
    expect(out.display_name).toBe("しの");
  });

  it("editing_name_does_not_create_display_name", () => {
    const out = obj(buildProfileContent(ev(`{"name":"shino3"}`), { name: "shino" }));
    expect(out.name).toBe("shino");
    expect("display_name" in out).toBe(false);
  });

  it("unknown_keys_stay_and_blank_removes_key", () => {
    const out = obj(
      buildProfileContent(ev(`{"name":"shino3","about":"hi","custom_app_field":{"x":1},"lud06":"lnurl1"}`), {
        about: "  ",
      }),
    );
    expect("about" in out).toBe(false);
    expect(out.name).toBe("shino3");
    expect("custom_app_field" in out).toBe(true);
    expect(out.lud06).toBe("lnurl1");
  });

  it("legacy_displayName_moves_to_display_name_when_missing", () => {
    const out = obj(buildProfileContent(ev(`{"name":"shino3","displayName":"しの"}`), { about: "hi" }));
    expect("displayName" in out).toBe(false);
    expect(out.display_name).toBe("しの");
    expect(out.name).toBe("shino3");
  });

  it("legacy_displayName_is_dropped_when_display_name_exists", () => {
    const out = obj(buildProfileContent(ev(`{"display_name":"しの","displayName":"old"}`), { about: "hi" }));
    expect("displayName" in out).toBe(false);
    expect(out.display_name).toBe("しの");
  });

  it("clearing_display_name_does_not_revive_legacy_value", () => {
    // 表示名の欄（displayName から読んだ値）を空にして保存したら、表示名は消えたまま。
    const out = obj(buildProfileContent(ev(`{"name":"shino3","displayName":"しの"}`), { display_name: "" }));
    expect("displayName" in out).toBe(false);
    expect("display_name" in out).toBe(false);
    expect(out.name).toBe("shino3");
  });

  it("no_base_publishes_only_changed_fields", () => {
    const out = obj(buildProfileContent(null, { name: "shino3", display_name: "しの", about: "" }));
    expect(new Set(Object.keys(out))).toEqual(new Set(["name", "display_name"]));
  });
});

describe("編集欄の値", () => {
  it("name と display_name（無ければ displayName）は別々に読む。他は同名のキー、文字列でなければ空欄", () => {
    expect(
      profileFieldsOf(
        profile(
          {
            display_name: " ",
            displayName: "Alice D",
            name: "alice",
            about: "hello",
            picture: "https://img.example/a.png",
            banner: 123,
            lud16: "alice@ln.example",
            nip05: "alice@example.com",
            website: "example.com",
          },
          1_000,
        ),
      ),
    ).toEqual({
      name: "alice",
      display_name: "Alice D",
      about: "hello",
      picture: "https://img.example/a.png",
      banner: "",
      lud16: "alice@ln.example",
      nip05: "alice@example.com",
      website: "example.com",
    });
    expect(profileFieldsOf(null)).toEqual({
      name: "",
      display_name: "",
      about: "",
      picture: "",
      banner: "",
      lud16: "",
      nip05: "",
      website: "",
    });
    // JSON でない content は空欄
    expect(profileFieldsOf(profile("not json", 1_000)).about).toBe("");
  });

  it("変えた項目だけを前後の空白を落として返す（空白だけの違いは変更にしない）", () => {
    const initial = profileFieldsOf(profile({ name: "a", about: "x" }, 1_000));
    expect(
      changedFields(initial, { ...initial, name: " a ", about: "", website: " https://w.example " }),
    ).toEqual({ about: "", website: "https://w.example" });
    expect(changedFields(initial, initial)).toEqual({});
  });
});

describe("content", () => {
  it("未知のキー（bot・pronouns）は残し、変えた項目だけ上書きし、空欄にしたキーは消す", () => {
    const base = profile(
      { name: "alice", about: "old", website: "https://old.example", bot: false, pronouns: "she/her" },
      1_000,
    );
    expect(JSON.parse(buildProfileContent(base, { about: "new", website: "" }))).toEqual({
      name: "alice",
      about: "new",
      bot: false,
      pronouns: "she/her",
    });
  });

  it("土台が無い・JSON で読めなければ変えた項目だけ（空欄は入れない）", () => {
    expect(JSON.parse(buildProfileContent(null, { name: "Bob", about: "hi", website: "" }))).toEqual({
      name: "Bob",
      about: "hi",
    });
    expect(JSON.parse(buildProfileContent(profile("[1]", 1_000), { about: "hi" }))).toEqual({ about: "hi" });
  });
});

describe("publishProfile（#478 の規則）", () => {
  it("取り直しでどのリレーからも応答が無ければ発行せず unreachable（手元に版があっても）", async () => {
    const cached = profile({ name: "alice", about: "old" }, 1_000);
    addVerified(cached);
    vi.mocked(requestOnce).mockReturnValue(throwError(() => new Error("timeout")));

    const error = await publishProfile(me, { about: "new" }, cached.id).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ProfileEditError);
    expect(error).toMatchObject({ reason: "unreachable" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("取り直した最新版の id が編集開始時の id と違えば発行せず stale", async () => {
    const cached = profile({ name: "alice", about: "old" }, 1_000);
    addVerified(cached);
    respondWith(profile({ name: "alice", about: "from another device" }, 2_000));

    const fromCached = await publishProfile(me, { about: "new" }, cached.id).catch((e: unknown) => e);
    // 自分の kind:0 を読み込む前（null）に編集していた場合も止める
    const fromNothing = await publishProfile(me, { about: "new" }, null).catch((e: unknown) => e);

    expect(fromCached).toMatchObject({ name: "ProfileEditError", reason: "stale" });
    expect(fromNothing).toMatchObject({ name: "ProfileEditError", reason: "stale" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("同じなら取り直した最新版の未知タグ・未知フィールドを保って発行する（created_at は前の版より後）", async () => {
    const latest = profile(
      {
        name: "alice",
        display_name: "Alice",
        about: "old",
        bot: true,
        pronouns: "they/them",
        lud16: "a@ln.example",
      },
      2_000,
      [
        ["emoji", "wave", "https://emoji.example/wave.png"],
        ["x", "unknown", "value"],
      ],
    );
    addVerified(latest);
    respondWith(latest);

    await publishProfile(me, { about: "new", lud16: "" }, latest.id);

    const draft = published();
    expect(draft.kind).toBe(0);
    expect(draft.content).toEqual({
      name: "alice",
      display_name: "Alice",
      about: "new",
      bot: true,
      pronouns: "they/them",
    });
    expect(draft.tags).toEqual([
      ["emoji", "wave", "https://emoji.example/wave.png"],
      ["x", "unknown", "value"],
    ]);
    expect(draft.created_at).toBeGreaterThan(2_000);
  });

  it("応答はあったが kind:0 が無く、編集開始時も無ければ入力した項目だけで発行する", async () => {
    vi.mocked(requestOnce).mockReturnValue(EMPTY);

    await publishProfile(me, { name: "Bob", about: "hi", website: "" }, null);

    const draft = published();
    expect(draft.content).toEqual({ name: "Bob", about: "hi" });
    expect(draft.tags).toEqual([]);
  });

  it("署名に失敗したら同じ reason の ProfileEditError", async () => {
    vi.mocked(requestOnce).mockReturnValue(EMPTY);
    vi.mocked(publishEvent).mockRejectedValueOnce(new PublishError("sign-failed"));

    const error = await publishProfile(me, { name: "Bob" }, null).catch((e: unknown) => e);

    expect(error).toMatchObject({ name: "ProfileEditError", reason: "sign-failed" });
  });
});
