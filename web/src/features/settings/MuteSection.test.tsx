import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { shortNpub } from "../../lib/npub";
import { publishEvent } from "../../nostr/publish";
import { currentSigner, useSession } from "../../signer/session";
import { createCipherSigner } from "../../test/cipherSigner";
import { useToast } from "../../ui/toast";
import { blockNoticeText, notifyDeveloper } from "../mute/developerNotice";
import { EMPTY_MUTE_LIST, type MuteList, setMuteList } from "../mute/muteList";
import { addMuteWord, MuteListError, saveMuteList } from "../mute/muteSync";
import { MuteSection, newlyBlocked } from "./MuteSection";

// 取り直し・発行は muteSync.test.ts。ここは呼び出しと画面だけ
vi.mock("../mute/muteSync", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../mute/muteSync")>()),
  addMuteWord: vi.fn(async () => "done" as const),
  saveMuteList: vi.fn(async () => "done" as const),
}));

// [#961] ブロックにしても通報（kind:1984）を出さないことを見る（ミュートリストの発行は上で差し替えている）
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async () => ({})),
}));

// [#961] 開発者への知らせ（暗号化した DM）は developerNotice.test.ts。ここは呼ばれることだけ見る
vi.mock("../mute/developerNotice", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../mute/developerNotice")>()),
  notifyDeveloper: vi.fn(async () => {}),
}));

// 署名者は既定で NIP-44 / NIP-04 とも使える（no-cipher のテストだけ差し替える）
vi.mock("../../signer/session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../signer/session")>()),
  currentSigner: vi.fn(),
}));

const me = getPublicKey(generateSecretKey());
const muted = getPublicKey(generateSecretKey());
const blocked = getPublicKey(generateSecretKey());

const LIST: MuteList = {
  ...EMPTY_MUTE_LIST,
  eventId: "shown-version",
  createdAt: 1_000,
  entries: [
    { category: "p", value: muted, isPublic: false, isPrivate: true },
    { category: "word", value: "Spam", isPublic: true, isPrivate: true },
    { category: "t", value: "nsfw", isPublic: true, isPrivate: false },
  ],
};

beforeEach(() => {
  useSession.setState({ status: "in", method: "local", pubkey: me });
  vi.mocked(currentSigner).mockReturnValue(createCipherSigner().signer);
  vi.mocked(addMuteWord).mockClear();
  vi.mocked(saveMuteList).mockClear();
  vi.mocked(publishEvent).mockClear();
  vi.mocked(notifyDeveloper).mockClear();
});

afterEach(() => {
  setMuteList(null);
  useToast.setState({ queue: [] });
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

function publicCheck(label: string): HTMLElement {
  return screen.getByRole("checkbox", { name: `${label} を公開でミュート` });
}

function privateCheck(label: string): HTMLElement {
  return screen.getByRole("checkbox", { name: `${label} を非公開でミュート` });
}

/** [#963] ユーザーの行の「ミュート | ブロック」 */
function modeButton(pubkey: string, mode: "ミュート" | "ブロック"): HTMLElement {
  return within(screen.getByRole("group", { name: shortNpub(pubkey) })).getByRole("button", { name: mode });
}

it("読み込み中 → 空 → 種別ごとの一覧（件数・公開 / 非公開のチェック状態）", () => {
  render(<MuteSection />);
  expect(screen.getByRole("status")).toHaveTextContent("リレーから取得中…");
  expect(screen.getByRole("button", { name: "追加" })).toBeDisabled();

  act(() => setMuteList(EMPTY_MUTE_LIST));
  expect(screen.getByText("ミュートしている項目はありません")).toBeInTheDocument();

  act(() => setMuteList(LIST));
  const users = screen.getByRole("region", { name: "ユーザー" });
  expect(within(users).getByRole("heading", { name: "ユーザー (1)" })).toBeInTheDocument();
  expect(within(users).getAllByText(shortNpub(muted)).length).toBeGreaterThan(0);
  // [#963] ユーザーは公開 / 非公開のチェックではなく「ミュート | ブロック」（非公開だけ = ミュート）
  expect(within(users).getByText(/ミュートは非公開（自分だけが分かる）/)).toBeInTheDocument();
  expect(within(users).queryByRole("checkbox")).toBeNull();
  expect(modeButton(muted, "ミュート")).toHaveAttribute("aria-pressed", "true");
  expect(modeButton(muted, "ブロック")).toHaveAttribute("aria-pressed", "false");
  expect(publicCheck("Spam")).toBeChecked();
  expect(privateCheck("Spam")).toBeChecked();
  expect(publicCheck("#nsfw")).toBeChecked();
  expect(privateCheck("#nsfw")).not.toBeChecked();
  expect(screen.queryByRole("region", { name: "スレッド" })).toBeNull();
  // 保存バーはまだ出ない
  expect(screen.queryByText("変更があります")).toBeNull();
});

it("チェックの変更で保存バーが出る", async () => {
  const user = userEvent.setup();
  setMuteList(LIST);
  render(<MuteSection />);

  await user.click(publicCheck("#nsfw"));
  expect(screen.getByText("変更があります")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
});

it("保存は下書きをまとめて 1 回だけ渡し、成功したら保存バーが消えてトーストを出す", async () => {
  const user = userEvent.setup();
  setMuteList(LIST);
  render(<MuteSection />);

  // #nsfw を非公開にも、Spam の公開を外す
  await user.click(privateCheck("#nsfw"));
  await user.click(publicCheck("Spam"));
  await user.click(screen.getByRole("button", { name: "保存" }));

  await waitFor(() => expect(saveMuteList).toHaveBeenCalledTimes(1));
  expect(saveMuteList).toHaveBeenCalledWith(
    me,
    [
      { category: "p", value: muted, isPublic: false, isPrivate: true },
      { category: "word", value: "Spam", isPublic: false, isPrivate: true },
      { category: "t", value: "nsfw", isPublic: true, isPrivate: true },
    ],
    "shown-version",
  );
  await waitFor(() => expect(screen.queryByText("変更があります")).toBeNull());
  expect(useToast.getState().queue).toEqual(["ミュートリストを保存しました"]);
});

it("[#963] ユーザーの ✕ で外すと解除として保存に渡す（通報はしない）", async () => {
  const user = userEvent.setup();
  setMuteList(LIST);
  render(<MuteSection />);

  await user.click(screen.getByRole("button", { name: `${shortNpub(muted)} を外す` }));
  expect(modeButton(muted, "ミュート")).toHaveAttribute("aria-pressed", "false");
  expect(modeButton(muted, "ブロック")).toHaveAttribute("aria-pressed", "false");
  expect(screen.getByRole("heading", { name: "ユーザー (0)" })).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "保存" }));
  await waitFor(() => expect(saveMuteList).toHaveBeenCalledTimes(1));
  const [, draft] = vi.mocked(saveMuteList).mock.calls[0];
  expect(draft).toContainEqual({ category: "p", value: muted, isPublic: false, isPrivate: false });
  expect(publishEvent).not.toHaveBeenCalled();
  expect(notifyDeveloper).not.toHaveBeenCalled();
});

it("[#961] ミュートからブロックに変えて保存したら、開発者に暗号化した DM で知らせる（通報の kind:1984 は出さない）", async () => {
  const user = userEvent.setup();
  setMuteList(LIST);
  render(<MuteSection />);

  await user.click(modeButton(muted, "ブロック"));
  await user.click(screen.getByRole("button", { name: "保存" }));
  await waitFor(() => expect(notifyDeveloper).toHaveBeenCalledTimes(1));
  expect(notifyDeveloper).toHaveBeenCalledWith(blockNoticeText(muted));
  expect(publishEvent).not.toHaveBeenCalled();
});

it("[#961] newlyBlocked: 保存で新たに公開の p になった人だけ（もとからブロックの人・外した人・ミュートは除く）", () => {
  const before = [
    { category: "p" as const, value: muted, isPublic: false, isPrivate: true },
    { category: "p" as const, value: blocked, isPublic: true, isPrivate: false },
  ];
  expect(newlyBlocked(before, [{ ...before[0], isPublic: true, isPrivate: false }, before[1]])).toEqual([
    muted,
  ]);
  expect(newlyBlocked(before, before)).toEqual([]);
  expect(newlyBlocked(before, [{ ...before[1], isPublic: false, isPrivate: false }])).toEqual([]);
  expect(newlyBlocked([], [{ category: "word", value: "spam", isPublic: true, isPrivate: false }])).toEqual(
    [],
  );
});

it("[#963] 選んでいる方（ミュート）をもう一度押すと解除になり、ブロックを押せばブロックに切り替わる", async () => {
  const user = userEvent.setup();
  setMuteList(LIST);
  render(<MuteSection />);

  await user.click(modeButton(muted, "ミュート"));
  expect(modeButton(muted, "ミュート")).toHaveAttribute("aria-pressed", "false");
  expect(modeButton(muted, "ブロック")).toHaveAttribute("aria-pressed", "false");
  expect(screen.getByRole("heading", { name: "ユーザー (0)" })).toBeInTheDocument();

  await user.click(modeButton(muted, "ブロック"));
  expect(modeButton(muted, "ブロック")).toHaveAttribute("aria-pressed", "true");
  await user.click(modeButton(muted, "ブロック"));
  expect(modeButton(muted, "ブロック")).toHaveAttribute("aria-pressed", "false");

  await user.click(modeButton(muted, "ミュート"));
  expect(modeButton(muted, "ミュート")).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("heading", { name: "ユーザー (1)" })).toBeInTheDocument();
});

it("ワードなどは両方のチェックを外すと解除として保存に渡す", async () => {
  const user = userEvent.setup();
  setMuteList(LIST);
  render(<MuteSection />);

  await user.click(publicCheck("#nsfw"));
  expect(publicCheck("#nsfw")).not.toBeChecked();
  expect(privateCheck("#nsfw")).not.toBeChecked();

  await user.click(screen.getByRole("button", { name: "保存" }));
  await waitFor(() => expect(saveMuteList).toHaveBeenCalledTimes(1));
  const [, draft] = vi.mocked(saveMuteList).mock.calls[0];
  expect(draft).toContainEqual({ category: "t", value: "nsfw", isPublic: false, isPrivate: false });
});

it("[#963] ミュート → ブロックに変えて保存すると公開の p だけにする（#961: 通報はしない）", async () => {
  const user = userEvent.setup();
  const both = getPublicKey(generateSecretKey());
  setMuteList({
    ...LIST,
    entries: [
      ...LIST.entries,
      { category: "p", value: blocked, isPublic: true, isPrivate: false },
      { category: "p", value: both, isPublic: true, isPrivate: true },
    ],
  });
  render(<MuteSection />);
  // 両方に入っている項目はブロックとして出す
  expect(modeButton(both, "ブロック")).toHaveAttribute("aria-pressed", "true");
  expect(modeButton(both, "ミュート")).toHaveAttribute("aria-pressed", "false");

  // 一度ブロック → ミュートに戻して → またブロック。もとからブロックの人はミュートにする
  await user.click(modeButton(muted, "ブロック"));
  await user.click(modeButton(muted, "ミュート"));
  await user.click(modeButton(muted, "ブロック"));
  expect(modeButton(muted, "ブロック")).toHaveAttribute("aria-pressed", "true");
  await user.click(modeButton(blocked, "ミュート"));
  await user.click(screen.getByRole("button", { name: "保存" }));

  await waitFor(() => expect(saveMuteList).toHaveBeenCalledTimes(1));
  const [, draft] = vi.mocked(saveMuteList).mock.calls[0];
  expect(draft).toContainEqual({ category: "p", value: muted, isPublic: true, isPrivate: false });
  expect(draft).toContainEqual({ category: "p", value: blocked, isPublic: false, isPrivate: true });
  expect(draft).toContainEqual({ category: "p", value: both, isPublic: true, isPrivate: true });
  await waitFor(() => expect(useToast.getState().queue).toEqual(["ミュートリストを保存しました"]));
  expect(publishEvent).not.toHaveBeenCalled();
});

it("保存で最新版と食い違っていたら、その旨をトーストで出し下書きを捨てる", async () => {
  const user = userEvent.setup();
  vi.mocked(saveMuteList).mockRejectedValueOnce(new MuteListError("stale"));
  setMuteList(LIST);
  render(<MuteSection />);

  await user.click(publicCheck("#nsfw"));
  await user.click(screen.getByRole("button", { name: "保存" }));

  await waitFor(() =>
    expect(useToast.getState().queue).toEqual([
      "ミュートリストが更新されていたため、変更しませんでした。最新の内容を表示したので、確認してもう一度操作してください",
    ]),
  );
  expect(screen.queryByText("変更があります")).toBeNull();
});

it("ロック中は案内を出し、変更できない", () => {
  setMuteList({ ...LIST, locked: true });
  render(<MuteSection />);
  expect(
    screen.getByText("復号できない非公開項目があるため編集できません（上書きで失うのを防いでいます）"),
  ).toBeInTheDocument();
  expect(publicCheck("#nsfw")).toBeDisabled();
  expect(privateCheck("#nsfw")).toBeDisabled();
  expect(modeButton(muted, "ミュート")).toBeDisabled();
  expect(modeButton(muted, "ブロック")).toBeDisabled();
  expect(screen.getByRole("button", { name: `${shortNpub(muted)} を外す` })).toBeDisabled();
  expect(screen.getByRole("button", { name: "追加" })).toBeDisabled();
});

it("署名者が暗号を使えないときは非公開のチェックを無効にし、案内を出す（公開は操作できる）", () => {
  vi.mocked(currentSigner).mockReturnValue(createCipherSigner({ nip44: false, nip04: false }).signer);
  setMuteList(LIST);
  render(<MuteSection />);

  expect(
    screen.getByText(
      "この署名方式は暗号化に対応していないため、非公開でミュートできません（公開では追加しません）",
    ),
  ).toBeInTheDocument();
  expect(privateCheck("#nsfw")).toBeDisabled();
  expect(publicCheck("#nsfw")).toBeEnabled();
  // [#963] ユーザーは「ミュート」（非公開）を選べない。「ブロック」（公開）は選べる
  expect(modeButton(muted, "ミュート")).toBeDisabled();
  expect(modeButton(muted, "ブロック")).toBeEnabled();
});

it("ワードの追加は表示している版の id を付けてその場で発行し、トーストを出す", async () => {
  const user = userEvent.setup();
  setMuteList(LIST);
  render(<MuteSection />);

  // 大文字小文字を無視して追加済みなら呼ばない
  const input = screen.getByRole("textbox", { name: "ミュートするワード" });
  await user.type(input, "spam");
  await user.click(screen.getByRole("button", { name: "追加" }));
  expect(screen.getByRole("alert")).toHaveTextContent("このワードは追加済みです");
  expect(addMuteWord).not.toHaveBeenCalled();

  await user.clear(input);
  await user.type(input, " /^buy/ ");
  await user.click(screen.getByRole("button", { name: "追加" }));
  expect(addMuteWord).toHaveBeenCalledWith(me, "/^buy/", "shown-version");
  await waitFor(() => expect(useToast.getState().queue).toContain("ミュートワードを追加しました"));
});
