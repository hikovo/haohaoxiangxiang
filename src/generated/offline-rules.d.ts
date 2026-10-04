import type { ArchiveItem, ArchiveRange } from '../offline-blindbox';
export function splitCaption(value: string): string[];
export function correctCaption(value: string): string;
export function conversationalCaption(value: string): string;
export function reviewedCaption(item: ArchiveItem): string;
export function reviewedBubbles(item: ArchiveItem): string[];
export function draw(rows: ArchiveItem[], message: string, range: ArchiveRange, recent?: string[], random?: () => number): { reply: string; item: ArchiveItem | null };
export function rank(rows: ArchiveItem[], message: string, range: ArchiveRange, recent?: string[]): Array<{ item: ArchiveItem; score: number; recent: boolean }>;
export function exactDate(value: string): string | null;
