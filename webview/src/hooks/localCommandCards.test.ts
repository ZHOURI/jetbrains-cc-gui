import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import {
  buildCardForKind,
  buildDoctorCard,
  buildErrorCard,
  buildHelpCard,
  buildHooksCard,
  buildLoadingCard,
  buildMemoryCard,
  buildStatusCard,
} from './localCommandCards';

const t = ((key: string) => key) as unknown as TFunction;

describe('buildStatusCard', () => {
  it('maps environment and session rows', () => {
    const card = buildStatusCard({
      status: { pluginVersion: '0.6.5', provider: 'claude', model: 'opus', permissionMode: 'default' },
    }, t);
    expect(card.state).toBe('ready');
    expect(card.sections.map((section) => section.id)).toEqual(['environment', 'session']);
    const provider = card.sections[1].rows.find((row) => row.id === 'provider');
    expect(provider?.value).toBe('claude');
    const pluginVersion = card.sections[0].rows.find((row) => row.id === 'pluginVersion');
    expect(pluginVersion?.value).toBe('0.6.5');
  });

  it('renders missing values as a dash', () => {
    const card = buildStatusCard({ status: {} }, t);
    expect(card.sections[0].rows[0].value).toBe('—');
  });

  it('falls back to an error card when status is missing', () => {
    const card = buildStatusCard({ error: 'boom' }, t);
    expect(card.state).toBe('error');
    expect(card.error).toBe('boom');
  });
});

describe('buildMemoryCard / buildHooksCard', () => {
  it('marks existing and missing memory files', () => {
    const card = buildMemoryCard({
      memoryFiles: [
        { id: 'userMemory', path: '/home/u/.claude/CLAUDE.md', exists: true },
        { id: 'projectMemory', path: '/p/CLAUDE.md', exists: false },
      ],
    }, t);
    const rows = card.sections[0].rows;
    expect(rows[0].status).toBe('ok');
    expect(rows[0].value).toBe('chat.localCommand.found');
    expect(rows[1].status).toBeUndefined();
    expect(rows[1].value).toBe('chat.localCommand.notFound');
  });

  it('marks hook files without hooks as warnings', () => {
    const card = buildHooksCard({
      hookFiles: [
        { id: 'userSettings', path: '/s.json', exists: true, hasHooks: true },
        { id: 'projectSettings', path: '/p.json', exists: true, hasHooks: false },
      ],
    }, t);
    const rows = card.sections[0].rows;
    expect(rows[0].status).toBe('ok');
    expect(rows[0].value).toBe('chat.localCommand.hooksConfigured');
    expect(rows[1].status).toBe('warn');
    expect(rows[1].value).toBe('chat.localCommand.hooksEmpty');
  });

  it('adds an empty note when no files were returned', () => {
    expect(buildMemoryCard({ memoryFiles: [] }, t).note).toBe('chat.localCommand.memory.empty');
    expect(buildHooksCard({}, t).note).toBe('chat.localCommand.hooks.empty');
  });
});

describe('buildDoctorCard', () => {
  it('builds node, SDK and CLI sections', () => {
    const card = buildDoctorCard({
      status: { nodeVersion: 'v20.0.0' },
      diagnostics: {
        nodeAvailable: true,
        sdks: {
          claude: { id: 'claude', name: 'Claude', installed: false, minimumVersion: '1.0.0' },
        },
        clis: [{ id: 'git', name: 'Git', installed: true, version: '2.40' }],
      },
    }, t);
    expect(card.sections.map((section) => section.id)).toEqual(['node', 'sdk', 'cli']);
    expect(card.sections[0].rows[0].status).toBe('ok');
    expect(card.sections[1].rows[0].status).toBe('error');
    expect(card.sections[2].rows[0].value).toBe('2.40');
    expect(card.actions?.[0]).toMatchObject({ kind: 'openDependencySettings' });
  });

  it('reports no CLI tools when the list is empty', () => {
    const card = buildDoctorCard({ diagnostics: { nodeAvailable: false, clis: [] } }, t);
    expect(card.sections[0].rows[0].status).toBe('error');
    expect(card.sections[2].rows[0].value).toBe('chat.localCommand.doctor.none');
  });
});

describe('loading / error / help cards', () => {
  it('builds loading and error states', () => {
    expect(buildLoadingCard('status', '/status', t)).toMatchObject({ state: 'loading', command: '/status' });
    expect(buildErrorCard('hooks', '/hooks', t, 'nope')).toMatchObject({ state: 'error', error: 'nope' });
  });

  it('lists every local command in the help card', () => {
    const card = buildHelpCard(t);
    expect(card.sections[0].rows.map((row) => row.id)).toEqual([
      '/status', '/doctor', '/memory', '/hooks', '/export',
      '/config', '/model', '/permissions', '/agents', '/cost',
    ]);
  });

  it('dispatches by kind', () => {
    expect(buildCardForKind('status', {}, t).command).toBe('/status');
    expect(buildCardForKind('doctor', {}, t).command).toBe('/doctor');
    expect(buildCardForKind('memory', {}, t).command).toBe('/memory');
    expect(buildCardForKind('hooks', {}, t).command).toBe('/hooks');
  });
});
