import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { shortNpub } from "../../lib/npub";
import { currentSigner, useSession } from "../../signer/session";
import { createCipherSigner } from "../../test/cipherSigner";
import { useToast } from "../../ui/toast";
import { EMPTY_MUTE_LIST, type MuteList, setMuteList } from "../mute/muteList";
import { addMuteWord, MuteListError, saveMuteList } from "../mute/muteSync";
import { MuteSection } from "./MuteSection";

// 取り直し・発行は muteSync.test.ts。ここは呼び出しと画面だけ
vi.mock("../mute/muteSync", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../mute/muteSync")>()),
  addMuteWord: vi.fn(async () => "done" as const),
  saveMuteList: vi.fn(async () => "done" as const),
}));

// 署名者は既定で NIP-44 / NIP-04 とも使える（no-cipher のテストだけ差し替える）
vi.mock("../../signer/session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../signer/session")>()),
  currentSigner: vi.fn(),
}));

const me = getPublicKey(generateSecretKey());
const muted = getPublicKey(generateSecretKey());

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

it("読み込み中 → 空 → 種別ごとの一覧（件数・公開 / 非公開のチェック状態）", () => {
  render(<MuteSection />);
  expect(screen.getByRole("status")).toHaveTextContent("リレーから取得中…");
  expect(screen.getByRole("button", { name: "追加" })).toBeDisabled();

  act(() => setMuteList(EMPTY_MUTE_LIST));
  expect(screen.getByText("ミュートしている項目はありません")).toBeInTheDocument();

  act(() => setMuteList(LIST));
  const users = screen.getByRole("region", { name: "ブロック中のユーザー" });
  expect(within(users).getByRole("heading", { name: "ブロック中のユーザー (1)" })).toBeInTheDocument();
  expect(within(users).getAllByText(shortNpub(muted)).length).toBeGreaterThan(0);
  expect(publicCheck(shortNpub(muted))).not.toBeChecked();
  expect(privateCheck(shortNpub(muted))).toBeChecked();
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

it("両方のチェックを外すと解除として保存に渡す", async () => {
  const user = userEvent.setup();
  setMuteList(LIST);
  render(<MuteSection />);

  // muted は非公開のみ → それも外すと両方 false（解除）
  await user.click(privateCheck(shortNpub(muted)));
  expect(publicCheck(shortNpub(muted))).not.toBeChecked();
  expect(privateCheck(shortNpub(muted))).not.toBeChecked();

  await user.click(screen.getByRole("button", { name: "保存" }));
  await waitFor(() => expect(saveMuteList).toHaveBeenCalledTimes(1));
  const [, draft] = vi.mocked(saveMuteList).mock.calls[0];
  expect(draft).toContainEqual({ category: "p", value: muted, isPublic: false, isPrivate: false });
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
