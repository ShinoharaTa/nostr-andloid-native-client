// scripts/build-licenses.mjs の型（src/ のテストから import するため。#688）
export type LicenseEntry = {
  name: string;
  version?: string;
  license: string;
  copyright: string | null;
  url: string;
  text: string | null;
};

export type LockfileEntry = {
  dependencies?: Record<string, string>;
  devDependencies?: Record<string, string>;
  peerDependencies?: Record<string, string>;
  peerDependenciesMeta?: Record<string, { optional?: boolean }>;
  optionalDependencies?: Record<string, string>;
};

export type Lockfile = { packages?: Record<string, LockfileEntry> };

export function normalizeLicense(value: unknown): string;
export function extractCopyright(text: unknown): string | null;
export function authorName(author: unknown): string | null;
export function repositoryUrl(repository: unknown): string | null;
export const BUNDLED_DEV_ROOTS: readonly string[];
export function productionKeys(lock: Lockfile, extraRoots?: readonly string[]): string[];
export function collectPackages(dir?: string): LicenseEntry[];
export function collectFonts(dir?: string): LicenseEntry[];
export function licenseCounts(entries: readonly LicenseEntry[]): [string, number][];
export function renderLicensesPage(input: { packages: LicenseEntry[]; fonts: LicenseEntry[] }): string;
export function writeLicensesPage(options?: { dir?: string; outDir?: string }): {
  path: string;
  packages: LicenseEntry[];
  fonts: LicenseEntry[];
};
