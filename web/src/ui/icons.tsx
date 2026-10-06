import type { ColumnKind } from "../lib/columns";

/**
 * アイコン（Material Symbols Outlined, weight 400 / 24px。Apache License 2.0, © Google）。
 * 使う分だけ SVG のパスを写す。viewBox は Material Symbols の "0 -960 960 960"。
 */
const PATHS = {
  people:
    "M40-160v-112q0-34 17.5-62.5T104-378q62-31 126-46.5T360-440q66 0 130 15.5T616-378q29 15 46.5 43.5T680-272v112H40Zm720 0v-120q0-44-24.5-84.5T666-434q51 6 96 20.5t84 35.5q36 20 55 44.5t19 53.5v120H760ZM247-527q-47-47-47-113t47-113q47-47 113-47t113 47q47 47 47 113t-47 113q-47 47-113 47t-113-47Zm466 0q-47 47-113 47-11 0-28-2.5t-28-5.5q27-32 41.5-71t14.5-81q0-42-14.5-81T544-792q14-5 28-6.5t28-1.5q66 0 113 47t47 113q0 66-47 113ZM120-240h480v-32q0-11-5.5-20T580-306q-54-27-109-40.5T360-360q-56 0-111 13.5T140-306q-9 5-14.5 14t-5.5 20v32Zm296.5-343.5Q440-607 440-640t-23.5-56.5Q393-720 360-720t-56.5 23.5Q280-673 280-640t23.5 56.5Q327-560 360-560t56.5-23.5ZM360-240Zm0-400Z",
  tag: "m240-160 40-160H120l20-80h160l40-160H180l20-80h160l40-160h80l-40 160h160l40-160h80l-40 160h160l-20 80H660l-40 160h160l-20 80H600l-40 160h-80l40-160H360l-40 160h-80Zm140-240h160l40-160H420l-40 160Z",
  notifications:
    "M160-200v-80h80v-280q0-83 50-147.5T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v280h80v80H160Zm320-300Zm0 420q-33 0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80ZM320-280h320v-280q0-66-47-113t-113-47q-66 0-113 47t-47 113v280Z",
  mailOutline:
    "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm320-280L160-640v400h640v-400L480-440Zm0-80 320-200H160l320 200ZM160-640v-80 480-400Z",
  public:
    "M324-111.5Q251-143 197-197t-85.5-127Q80-397 80-480t31.5-156Q143-709 197-763t127-85.5Q397-880 480-880t156 31.5Q709-817 763-763t85.5 127Q880-563 880-480t-31.5 156Q817-251 763-197t-127 85.5Q563-80 480-80t-156-31.5ZM440-162v-78q-33 0-56.5-23.5T360-320v-40L168-552q-3 18-5.5 36t-2.5 36q0 121 79.5 212T440-162Zm276-102q41-45 62.5-100.5T800-480q0-98-54.5-179T600-776v16q0 33-23.5 56.5T520-680h-80v80q0 17-11.5 28.5T400-560h-80v80h240q17 0 28.5 11.5T600-440v120h40q26 0 47 15.5t29 40.5Z",
  person:
    "M367-527q-47-47-47-113t47-113q47-47 113-47t113 47q47 47 47 113t-47 113q-47 47-113 47t-113-47ZM160-160v-112q0-34 17.5-62.5T224-378q62-31 126-46.5T480-440q66 0 130 15.5T736-378q29 15 46.5 43.5T800-272v112H160Zm80-80h480v-32q0-11-5.5-20T700-306q-54-27-109-40.5T480-360q-56 0-111 13.5T260-306q-9 5-14.5 14t-5.5 20v32Zm296.5-343.5Q560-607 560-640t-23.5-56.5Q513-720 480-720t-56.5 23.5Q400-673 400-640t23.5 56.5Q447-560 480-560t56.5-23.5ZM480-640Zm0 400Z",
  starBorder:
    "m354-287 126-76 126 77-33-144 111-96-146-13-58-136-58 135-146 13 111 97-33 143ZM233-120l65-281L80-590l288-25 112-265 112 265 288 25-218 189 65 281-247-149-247 149Zm247-350Z",
  list: "M280-600v-80h560v80H280Zm0 160v-80h560v80H280Zm0 160v-80h560v80H280ZM160-600q-17 0-28.5-11.5T120-640q0-17 11.5-28.5T160-680q17 0 28.5 11.5T200-640q0 17-11.5 28.5T160-600Zm0 160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520q17 0 28.5 11.5T200-480q0 17-11.5 28.5T160-440Zm0 160q-17 0-28.5-11.5T120-320q0-17 11.5-28.5T160-360q17 0 28.5 11.5T200-320q0 17-11.5 28.5T160-280Z",
  reply:
    "M760-200v-160q0-50-35-85t-85-35H273l144 144-57 56-240-240 240-240 57 56-144 144h367q83 0 141.5 58.5T840-360v160h-80Z",
  chat: "M240-400h320v-80H240v80Zm0-120h480v-80H240v80Zm0-120h480v-80H240v80ZM80-80v-720q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H240L80-80Zm126-240h594v-480H160v525l46-45Zm-46 0v-480 480Z",
  moreHoriz:
    "M240-400q-33 0-56.5-23.5T160-480q0-33 23.5-56.5T240-560q33 0 56.5 23.5T320-480q0 33-23.5 56.5T240-400Zm240 0q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm240 0q-33 0-56.5-23.5T640-480q0-33 23.5-56.5T720-560q33 0 56.5 23.5T800-480q0 33-23.5 56.5T720-400Z",
  chevronLeft: "M560-240 320-480l240-240 56 56-184 184 184 184-56 56Z",
  chevronRight: "M504-480 320-664l56-56 240 240-240 240-56-56 184-184Z",
  close: "m256-200-56-56 224-224-224-224 56-56 224 224 224-224 56 56-224 224 224 224-56 56-224-224-224 224Z",
  add: "M440-440H200v-80h240v-240h80v240h240v80H520v240h-80v-240Z",
  tune: "M440-120v-240h80v80h320v80H520v80h-80Zm-320-80v-80h240v80H120Zm160-160v-80H120v-80h160v-80h80v240h-80Zm160-80v-80h400v80H440Zm160-160v-240h80v80h160v80H680v80h-80Zm-480-80v-80h400v80H120Z",
  visibility:
    "M480-320q75 0 127.5-52.5T660-500q0-75-52.5-127.5T480-680q-75 0-127.5 52.5T300-500q0 75 52.5 127.5T480-320Zm0-72q-45 0-76.5-31.5T372-500q0-45 31.5-76.5T480-608q45 0 76.5 31.5T588-500q0 45-31.5 76.5T480-392Zm0 192q-146 0-266-81.5T40-500q54-137 174-218.5T480-800q146 0 266 81.5T920-500q-54 137-174 218.5T480-200Zm0-300Zm0 220q113 0 207.5-59.5T832-500q-50-101-144.5-160.5T480-720q-113 0-207.5 59.5T128-500q50 101 144.5 160.5T480-280Z",
  visibilityOff:
    "m644-428-58-58q9-47-27-88t-93-32l-58-58q17-8 34.5-12t37.5-4q75 0 127.5 52.5T660-500q0 20-4 37.5T644-428Zm128 126-58-56q38-29 67.5-63.5T832-500q-50-101-143.5-160.5T480-720q-29 0-57 4t-55 12l-62-62q41-17 84-25.5t90-8.5q151 0 269 83.5T920-500q-23 59-60.5 109.5T772-302Zm20 246L624-222q-35 11-70.5 16.5T480-200q-151 0-269-83.5T40-500q21-53 53-98.5t73-81.5L56-792l56-56 736 736-56 56ZM222-624q-29 26-53 57t-41 67q50 101 143.5 160.5T480-280q20 0 39-2.5t39-5.5l-36-38q-11 3-21 4.5t-21 1.5q-75 0-127.5-52.5T300-500q0-11 1.5-21t4.5-21l-84-82Zm319 93Zm-151 75Z",
  pushPin:
    "m640-480 80 80v80H520v240l-40 40-40-40v-240H240v-80l80-80v-280h-40v-80h400v80h-40v280Zm-286 80h252l-46-46v-314H400v314l-46 46Zm126 0Z",
  checkBox:
    "m424-312 282-282-56-56-226 226-114-114-56 56 170 170ZM200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H200Zm0-80h560v-560H200v560Zm0-560v560-560Z",
  checkBoxOutlineBlank:
    "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H200Zm0-80h560v-560H200v560Z",
  refresh:
    "M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z",
  // ステータス（NIP-38。#767）: music の印 / general の印 / ステータスカラム
  musicNote:
    "M287-167q-47-47-47-113t47-113q47-47 113-47 23 0 42.5 5.5T480-418v-422h240v160H560v400q0 66-47 113t-113 47q-66 0-113-47Z",
  chatBubble:
    "M80-80v-720q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H240L80-80Zm126-240h594v-480H160v525l46-45Zm-46 0v-480 480Z",
  mood: "M620-520q25 0 42.5-17.5T680-580q0-25-17.5-42.5T620-640q-25 0-42.5 17.5T560-580q0 25 17.5 42.5T620-520Zm-280 0q25 0 42.5-17.5T400-580q0-25-17.5-42.5T340-640q-25 0-42.5 17.5T280-580q0 25 17.5 42.5T340-520Zm263.5 221.5Q659-337 684-400H276q25 63 80.5 101.5T480-260q68 0 123.5-38.5ZM324-111.5Q251-143 197-197t-85.5-127Q80-397 80-480t31.5-156Q143-709 197-763t127-85.5Q397-880 480-880t156 31.5Q709-817 763-763t85.5 127Q880-563 880-480t-31.5 156Q817-251 763-197t-127 85.5Q563-80 480-80t-156-31.5ZM480-480Zm227 227q93-93 93-227t-93-227q-93-93-227-93t-227 93q-93 93-93 227t93 227q93 93 227 93t227-93Z",
} as const;

export type IconName = keyof typeof PATHS;

const SIZES = { sm: "var(--icon-sm)", md: "var(--icon-md)", lg: "var(--icon-lg)" } as const;

/** 装飾用のアイコン（読み上げない。ボタンの名前は aria-label 側で付ける）。色は currentColor */
export function Icon({
  name,
  size = "md",
  className,
}: {
  name: IconName;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  return (
    <svg
      className={className}
      viewBox="0 -960 960 960"
      style={{ width: SIZES[size], height: SIZES[size], flex: "none" }}
      fill="currentColor"
      aria-hidden="true"
      focusable="false"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}

/** カラム種別 → ヘッダ/一覧のアイコン（ネイティブの ColumnIcons.kt と同じ対応） */
export function columnIcon(kind: ColumnKind): IconName {
  switch (kind) {
    case "FOLLOWING":
      return "people";
    case "HASHTAG":
      return "tag";
    case "NOTIFICATIONS":
      return "notifications";
    case "DM":
      return "mailOutline";
    case "GLOBAL":
      return "public";
    case "PROFILE":
      return "person";
    case "FAVS":
      return "starBorder";
    case "LIST":
      return "list";
    case "THREAD":
      return "reply";
    case "CHANNEL_LIST":
      return "tag";
    case "CHANNEL_ROOM":
      return "chat";
    case "STATUS":
      return "mood";
  }
}

/*
 * 大きさを className（CSS）で決める名前付きアイコン（レール・下部ナビ・ヘッダ用。レールのグリフは 24px で、
 * 上の Icon の sm / md / lg に無い）。title が無ければ装飾扱い（aria-hidden）、あれば role="img" + <title>。
 * 同じグリフが上の PATHS にあればそれを使う。
 */
type NamedIconProps = { className?: string; title?: string };

function PathIcon({ className, title, d }: NamedIconProps & { d: string }) {
  if (title) {
    return (
      <svg className={className} viewBox="0 -960 960 960" fill="currentColor" role="img">
        <title>{title}</title>
        <path d={d} />
      </svg>
    );
  }
  return (
    <svg
      className={className}
      viewBox="0 -960 960 960"
      fill="currentColor"
      aria-hidden="true"
      focusable="false"
    >
      <path d={d} />
    </svg>
  );
}

export function HomeIcon(props: NamedIconProps) {
  return (
    <PathIcon
      {...props}
      d="M240-200h120v-240h240v240h120v-360L480-740 240-560v360Zm-80 80v-480l320-240 320 240v480H520v-240h-80v240H160Zm320-350Z"
    />
  );
}

export function SearchIcon(props: NamedIconProps) {
  return (
    <PathIcon
      {...props}
      d="M784-120 532-372q-30 24-69 38t-83 14q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l252 252-56 56ZM380-400q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z"
    />
  );
}

export function ArrowBackIcon(props: NamedIconProps) {
  return <PathIcon {...props} d="m313-440 224 224-57 56-320-320 320-320 57 56-224 224h487v80H313Z" />;
}

export function ChatIcon(props: NamedIconProps) {
  return <PathIcon {...props} d={PATHS.chat} />;
}

export function NotificationsIcon(props: NamedIconProps) {
  return <PathIcon {...props} d={PATHS.notifications} />;
}

export function AddIcon(props: NamedIconProps) {
  return <PathIcon {...props} d={PATHS.add} />;
}

/** 検索履歴の行（#461） */
export function HistoryIcon(props: NamedIconProps) {
  return (
    <PathIcon
      {...props}
      d="M480-120q-138 0-240.5-91.5T122-440h82q14 104 92.5 172T480-200q117 0 198.5-81.5T760-480q0-117-81.5-198.5T480-760q-69 0-129 32t-101 88h110v80H120v-240h80v94q51-64 124.5-99T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480q0 75-28.5 140.5t-77 114q-48.5 48.5-114 77T480-120Zm112-192L440-464v-216h80v184l128 128-56 56Z"
    />
  );
}

/** カラム種別のアイコン（columnIcon と同じ対応）を className の大きさで描く */
export function ColumnKindIcon({ kind, ...props }: NamedIconProps & { kind: ColumnKind }) {
  return <PathIcon {...props} d={PATHS[columnIcon(kind)]} />;
}

/*
 * ---- 以下: 投稿表示・メディア用（#454 / #455）。Material Icons Outlined（Apache-2.0）のパス。
 * 色は currentColor、大きさは className（CSS）で決める。title が無ければ装飾扱い（aria-hidden）。
 */
type MaterialIconProps = { className?: string; title?: string };

function MaterialIcon({ className, title, path }: MaterialIconProps & { path: string }) {
  if (title) {
    return (
      <svg className={className} viewBox="0 0 24 24" role="img">
        <title>{title}</title>
        <path d={path} fill="currentColor" />
      </svg>
    );
  }
  return (
    <svg className={className} viewBox="0 0 24 24" aria-hidden="true">
      <path d={path} fill="currentColor" />
    </svg>
  );
}

export function RepeatIcon(props: MaterialIconProps) {
  return <MaterialIcon {...props} path="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z" />;
}

export function ReplyIcon(props: MaterialIconProps) {
  return <MaterialIcon {...props} path="M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z" />;
}

export function VisibilityOffIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M12 6a9.77 9.77 0 0 1 8.82 5.5 9.647 9.647 0 0 1-2.41 3.12l1.41 1.41c1.39-1.23 2.49-2.77 3.18-4.53C21.27 7.11 17 4 12 4c-1.27 0-2.49.2-3.64.57l1.65 1.65C10.66 6.09 11.32 6 12 6zm-1.07 1.14L13 9.21c.57.25 1.03.71 1.28 1.28l2.07 2.07c.08-.34.14-.7.14-1.07C16.5 9.01 14.48 7 12 7c-.37 0-.72.05-1.07.14zM2.01 3.87l2.68 2.68A11.738 11.738 0 0 0 1 11.5C2.73 15.89 7 19 12 19c1.52 0 2.98-.29 4.32-.82l3.42 3.42 1.41-1.41L3.42 2.45 2.01 3.87zm7.5 7.5 2.61 2.61c-.04.01-.08.02-.12.02a2.5 2.5 0 0 1-2.5-2.5c0-.05.01-.08.01-.13zm-3.4-3.4 1.75 1.75a4.6 4.6 0 0 0-.36 1.78 4.507 4.507 0 0 0 6.27 4.14l.98.98c-.88.24-1.8.38-2.75.38a9.77 9.77 0 0 1-8.82-5.5c.7-1.43 1.72-2.61 2.93-3.53z"
    />
  );
}

export function ExpandMoreIcon(props: MaterialIconProps) {
  return <MaterialIcon {...props} path="M16.59 8.59 12 13.17 7.41 8.59 6 10l6 6 6-6-1.41-1.41z" />;
}

export function ExpandLessIcon(props: MaterialIconProps) {
  return <MaterialIcon {...props} path="m12 8-6 6 1.41 1.41L12 10.83l4.59 4.58L18 14l-6-6z" />;
}

export function PlayCircleIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm-2.5-3.5 7-4.5-7-4.5v9z"
    />
  );
}

export function PlayArrowIcon(props: MaterialIconProps) {
  return <MaterialIcon {...props} path="M8 5v14l11-7z" />;
}

export function ContentCopyIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"
    />
  );
}

export function OpenInNewIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"
    />
  );
}

export function DownloadIcon(props: MaterialIconProps) {
  return <MaterialIcon {...props} path="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" />;
}

export function CloseIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12 19 6.41z"
    />
  );
}

/** 投稿シートの添付（image・Outlined。ネイティブ Icons.Outlined.Image） */
export function ImageIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M19 5v14H5V5h14m0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-4.86 8.86-3 3.87L9 13.14 6 17h12l-3.86-5.14z"
    />
  );
}

/** DM の動画添付ボタン（ネイティブ Icons.Outlined.Videocam） */
export function VideocamIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4z"
    />
  );
}

/** 連投に追加（ネイティブ Icons.AutoMirrored.Outlined.PlaylistAdd） */
export function PlaylistAddIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M14 10H2v2h12v-2zm0-4H2v2h12V6zM2 16h8v-2H2v2zm16-2v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4z"
    />
  );
}

export function ChevronLeftIcon(props: MaterialIconProps) {
  return <MaterialIcon {...props} path="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z" />;
}

export function ChevronRightIcon(props: MaterialIconProps) {
  return <MaterialIcon {...props} path="M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z" />;
}

/** NIP-05 検証OK（verified・塗り。#457） */
export function VerifiedIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M23 12l-2.44-2.79.34-3.69-3.61-.82-1.89-3.2L12 2.96 8.6 1.5 6.71 4.69 3.1 5.5l.34 3.7L1 12l2.44 2.79-.34 3.7 3.61.82L8.6 22.5l3.4-1.47 3.4 1.46 1.89-3.19 3.61-.82-.34-3.69L23 12zm-12.91 4.72l-3.8-3.81 1.48-1.48 2.32 2.33 5.85-5.87 1.48 1.48-7.33 7.35z"
    />
  );
}

/** NIP-05 検証エラー（error・Outlined。#457） */
export function ErrorOutlineIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"
    />
  );
}

// ネイティブの ReplyBox（Icons.AutoMirrored.Outlined.Send）
export function SendIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M4.01 6.03l7.51 3.22-7.52-1 .01-2.22m7.5 8.72L4 17.97v-2.22l7.51-1M2.01 3L2 10l15 2-15 2 .01 7L23 12 2.01 3z"
    />
  );
}

/** 投稿ボタン（FAB, #458）。Material Symbols Outlined の edit */
export function EditIcon(props: NamedIconProps) {
  return (
    <PathIcon
      {...props}
      d="M200-200h57l391-391-57-57-391 391v57Zm-80 80v-170l528-527q12-11 26.5-17t30.5-6q16 0 31 6t26 18l55 56q12 11 17.5 26t5.5 30q0 16-5.5 30.5T817-647L290-120H120Zm640-584-56-56 56 56Zm-141 85-28-29 57 57-29-28Z"
    />
  );
}

/*
 * ---- 以下: リアクション・⋯ メニュー・絵文字ピッカー用（#459）。ネイティブ NoteItem.kt / ComposeSheet.kt の Icons.* と同じグリフ。
 * 上に同じグリフがあればそのパスを使う（StarBorder / MoreHoriz。検索は SearchIcon をそのまま使う）。
 */

/** ♡ 付与済み（Filled.Favorite） */
export function FavoriteIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"
    />
  );
}

/** ♡ 未付与（Outlined.FavoriteBorder） */
export function FavoriteBorderIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M16.5 3c-1.74 0-3.41.81-4.5 2.09C10.91 3.81 9.24 3 7.5 3 4.42 3 2 5.42 2 8.5c0 3.78 3.4 6.86 8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 16.5 3zm-4.4 15.55l-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05z"
    />
  );
}

/** ☆ 付与済み（塗り。StarBorderIcon の外形だけ） */
export function StarIcon(props: NamedIconProps) {
  return (
    <PathIcon
      {...props}
      d="m233-120 65-281L80-590l288-25 112-265 112 265 288 25-218 189 65 281-247-149-247 149Z"
    />
  );
}

/** ☆ 未付与 */
export function StarBorderIcon(props: NamedIconProps) {
  return <PathIcon {...props} d={PATHS.starBorder} />;
}

/** 絵文字でリアクション（Outlined.AddReaction） */
export function AddReactionIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M7 9.5C7 8.67 7.67 8 8.5 8S10 8.67 10 9.5 9.33 11 8.5 11 7 10.33 7 9.5zm5 8c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5zm3.5-6.5c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zM22 1h-2v2h-2v2h2v2h2V5h2V3h-2V1zm-2 11c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8c1.46 0 2.82.4 4 1.08V2.84C14.77 2.3 13.42 2 11.99 2 6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12c0-1.05-.17-2.05-.47-3h-2.13c.38.93.6 1.94.6 3z"
    />
  );
}

/** ⋯（投稿のその他の操作） */
export function MoreHorizIcon(props: NamedIconProps) {
  return <PathIcon {...props} d={PATHS.moreHoriz} />;
}

/** 投稿画面の絵文字ボタン（Outlined.Mood） */
export function MoodIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm3.5-9c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zm-7 0c.83 0 1.5-.67 1.5-1.5S9.33 8 8.5 8 7 8.67 7 9.5 7.67 11 8.5 11zm3.5 6.5c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5z"
    />
  );
}

/*
 * ---- 以下: 通知の種別マーク（#460）。ネイティブ NotificationsScreen.kt の Icons.Outlined.* と同じグリフ。
 * 返信・リポストは上の ReplyIcon / RepeatIcon を使う。
 */

/** メンション（Outlined.AlternateEmail） */
export function AlternateEmailIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M12 1.95c-5.52 0-10 4.48-10 10s4.48 10 10 10h5v-2h-5c-4.34 0-8-3.66-8-8s3.66-8 8-8 8 3.66 8 8v1.43c0 .79-.71 1.57-1.5 1.57s-1.5-.78-1.5-1.57v-1.43c0-2.76-2.24-5-5-5s-5 2.24-5 5 2.24 5 5 5c1.38 0 2.64-.56 3.54-1.47.65.89 1.77 1.47 2.96 1.47 1.97 0 3.5-1.6 3.5-3.57v-1.43c0-5.52-4.48-10-10-10zm0 13c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3z"
    />
  );
}

/** DM（Outlined.MailOutline） */
export function MailOutlineIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M22 6c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6zm-2 0-8 5-8-5h16zm0 12H4V8l8 5 8-5v10z"
    />
  );
}

/** Zap（Outlined.Bolt） */
export function BoltIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z"
    />
  );
}

export function RotateLeftIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M7.11 8.53 5.7 7.11A7.9 7.9 0 0 0 4.07 11h2.02c.14-.87.49-1.72 1.02-2.47zM6.09 13H4.07a7.9 7.9 0 0 0 1.61 3.89l1.43-1.43A5.9 5.9 0 0 1 6.09 13zm1.01 5.32c1.14.89 2.52 1.5 3.9 1.61v-2.02a5.9 5.9 0 0 1-2.46-1.03L7.1 18.32zM13 4.07V1L8.45 5.55 13 10V6.09c2.84.48 5 2.94 5 5.91s-2.16 5.43-5 5.91v2.02c3.95-.49 7-3.85 7-7.93s-3.05-7.44-7-7.93z"
    />
  );
}

export function RotateRightIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M15.55 5.55 11 1v3.07a8 8 0 0 0 0 15.86v-2.02a6 6 0 0 1 0-11.82V10l4.55-4.45zM19.93 11a7.9 7.9 0 0 0-1.62-3.89l-1.42 1.42a5.94 5.94 0 0 1 1.02 2.47h2.02zM13 17.9v2.02a7.92 7.92 0 0 0 3.9-1.61l-1.44-1.44c-.75.54-1.59.89-2.46 1.03zm3.89-2.42 1.42 1.41A7.9 7.9 0 0 0 19.93 13h-2.02a5.9 5.9 0 0 1-1.02 2.48z"
    />
  );
}

export function FlipIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M15 21h2v-2h-2v2zm4-12h2V7h-2v2zM3 5v14c0 1.1.9 2 2 2h4v-2H5V5h4V3H5c-1.1 0-2 .9-2 2zm16-2v2h2c0-1.1-.9-2-2-2zm-8 20h2V1h-2v22zm8-6h2v-2h-2v2zM15 5h2V3h-2v2zm4 8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2z"
    />
  );
}

export function RestoreIcon(props: MaterialIconProps) {
  return (
    <MaterialIcon
      {...props}
      path="M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z"
    />
  );
}
