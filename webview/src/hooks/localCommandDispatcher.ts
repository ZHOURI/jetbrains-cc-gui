import type { LocalCommandId } from '../types/localCommand';

export interface ParsedLocalCommand {
  id: LocalCommandId;
  /** Lowercased command token including the leading slash. */
  raw: string;
  args: string;
}

const COMMAND_IDS: Record<string, LocalCommandId> = {
  '/status': 'status',
  '/doctor': 'doctor',
  '/memory': 'memory',
  '/hooks': 'hooks',
  '/help': 'help',
  '/export': 'export',
  '/config': 'config',
  '/model': 'model',
  '/permissions': 'permissions',
  '/agents': 'agents',
  '/cost': 'cost',
};

/**
 * Parse a GUI-local slash command. Returns null for commands that must keep
 * flowing through the normal SDK/handler pipeline.
 */
export function parseLocalCommand(text: string): ParsedLocalCommand | null {
  const trimmed = text.trim();
  if (!trimmed.startsWith('/')) return null;

  const match = trimmed.match(/^(\S+)(?:\s+([\s\S]*))?$/);
  if (!match) return null;

  const token = match[1].toLowerCase();
  const id = COMMAND_IDS[token];
  if (!id) return null;

  return { id, raw: token, args: (match[2] ?? '').trim() };
}

export function isLocalCommandText(text: string): boolean {
  return parseLocalCommand(text) !== null;
}
