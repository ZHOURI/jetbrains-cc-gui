import { describe, expect, it } from 'vitest';
import { isLocalCommandText, parseLocalCommand } from './localCommandDispatcher';

describe('parseLocalCommand', () => {
  it('recognizes every GUI-local command', () => {
    const ids: Array<[string, string]> = [
      ['/status', 'status'],
      ['/doctor', 'doctor'],
      ['/memory', 'memory'],
      ['/hooks', 'hooks'],
      ['/help', 'help'],
      ['/export', 'export'],
      ['/config', 'config'],
      ['/model', 'model'],
      ['/permissions', 'permissions'],
      ['/agents', 'agents'],
      ['/cost', 'cost'],
    ];
    for (const [command, id] of ids) {
      expect(parseLocalCommand(command)).toMatchObject({ id, raw: command, args: '' });
    }
  });

  it('is case-insensitive and trims surrounding whitespace', () => {
    expect(parseLocalCommand('  /STATUS  ')).toMatchObject({ id: 'status', raw: '/status' });
  });

  it('keeps trailing arguments', () => {
    expect(parseLocalCommand('/status now')).toMatchObject({ id: 'status', args: 'now' });
  });

  it('ignores commands handled by other layers', () => {
    expect(parseLocalCommand('/compact')).toBeNull();
    expect(parseLocalCommand('/new')).toBeNull();
    expect(parseLocalCommand('/context')).toBeNull();
    expect(parseLocalCommand('hello')).toBeNull();
    expect(parseLocalCommand('')).toBeNull();
  });
});

describe('isLocalCommandText', () => {
  it('matches only registered commands', () => {
    expect(isLocalCommandText('/config')).toBe(true);
    expect(isLocalCommandText('/unknown')).toBe(false);
    expect(isLocalCommandText('plain text')).toBe(false);
  });
});
