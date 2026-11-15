import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SetStateAction } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage } from '../types';
import { useLocalSlashCommands, type UseLocalSlashCommandsOptions } from './useLocalSlashCommands';

const t = ((key: string, options?: { command?: string }) =>
  options?.command ?? key) as unknown as TFunction;

const sendToJava = vi.fn();

function setup(overrides: Partial<UseLocalSlashCommandsOptions> = {}) {
  let messages: ClaudeMessage[] = [];
  const setMessages = vi.fn((update: SetStateAction<ClaudeMessage[]>) => {
    messages = typeof update === 'function'
      ? (update as (previous: ClaudeMessage[]) => ClaudeMessage[])(messages)
      : update;
  });
  const addToast = vi.fn();
  const setSettingsInitialTab = vi.fn();
  const setCurrentView = vi.fn();
  const exportSession = vi.fn();

  const hook = renderHook(() => useLocalSlashCommands({
    t,
    addToast,
    currentProvider: 'claude',
    currentSessionId: 'session-1',
    sessionTitle: 'My chat',
    exportSession,
    setMessages,
    setSettingsInitialTab,
    setCurrentView,
    ...overrides,
  }));

  return {
    hook,
    getMessages: () => messages,
    addToast,
    setSettingsInitialTab,
    setCurrentView,
    exportSession,
  };
}

function localRaw(index: number) {
  return (messages: ClaudeMessage[]) =>
    messages[index].raw as Record<string, unknown>;
}

beforeEach(() => {
  window.sendToJava = sendToJava;
});

afterEach(() => {
  cleanup();
  delete window.sendToJava;
  delete window.onStatusInfo;
  delete window.__pendingStatusInfo;
  sendToJava.mockReset();
  vi.useRealTimers();
});

describe('useLocalSlashCommands', () => {
  it('navigates settings shortcuts to the right tab', () => {
    const { hook, setSettingsInitialTab, setCurrentView } = setup();
    let handled = false;
    act(() => { handled = hook.result.current.handleLocalCommand('/config'); });
    expect(handled).toBe(true);
    expect(setSettingsInitialTab).toHaveBeenCalledWith('basic');
    expect(setCurrentView).toHaveBeenCalledWith('settings');
  });

  it('rejects arguments with a usage toast', () => {
    const { hook, addToast, getMessages } = setup();
    let handled = false;
    act(() => { handled = hook.result.current.handleLocalCommand('/status now'); });
    expect(handled).toBe(true);
    expect(addToast).toHaveBeenCalledWith('/status', 'info');
    expect(getMessages()).toHaveLength(0);
    expect(sendToJava).not.toHaveBeenCalled();
  });

  it('inserts a loading card and replaces it when the backend responds', () => {
    const { hook, getMessages } = setup();
    act(() => { hook.result.current.handleLocalCommand('/status'); });

    const messages = getMessages();
    expect(messages).toHaveLength(1);
    const raw = localRaw(0)(messages);
    expect(raw.isLocalCommandOutput).toBe(true);
    expect((raw.localCommandOutput as { state: string }).state).toBe('loading');

    expect(sendToJava).toHaveBeenCalledTimes(1);
    const payload = sendToJava.mock.calls[0][0] as string;
    const request = JSON.parse(payload.slice('get_status_info:'.length)) as { requestId: string; kind: string };
    expect(request.kind).toBe('status');

    act(() => {
      window.onStatusInfo?.(JSON.stringify({ requestId: request.requestId, status: { provider: 'claude' } }));
    });

    const updated = getMessages();
    expect(updated).toHaveLength(1);
    const updatedRaw = localRaw(0)(updated);
    expect((updatedRaw.localCommandOutput as { state: string }).state).toBe('ready');
  });

  it('replaces the card with an error after the timeout', () => {
    vi.useFakeTimers();
    const { hook, getMessages } = setup();
    act(() => { hook.result.current.handleLocalCommand('/doctor'); });
    act(() => { vi.advanceTimersByTime(10_000); });
    const raw = localRaw(0)(getMessages());
    expect((raw.localCommandOutput as { state: string }).state).toBe('error');
  });

  it('shows an error card when the bridge is unavailable', () => {
    delete window.sendToJava;
    const { hook, getMessages } = setup();
    act(() => { hook.result.current.handleLocalCommand('/memory'); });
    const raw = localRaw(0)(getMessages());
    expect((raw.localCommandOutput as { state: string }).state).toBe('error');
  });

  it('exports the current session and warns when there is none', () => {
    const { hook, exportSession, addToast } = setup();
    let handled = false;
    act(() => { handled = hook.result.current.handleLocalCommand('/export'); });
    expect(handled).toBe(true);
    expect(exportSession).toHaveBeenCalledWith('session-1', 'My chat');
    expect(addToast).toHaveBeenCalledWith('chat.localCommand.export.started', 'success');

    const none = setup({ currentSessionId: null });
    act(() => { none.hook.result.current.handleLocalCommand('/export'); });
    expect(none.exportSession).not.toHaveBeenCalled();
    expect(none.addToast).toHaveBeenCalledWith('chat.localCommand.export.noSession', 'warning');
  });

  it('leaves unknown commands untouched', () => {
    const { hook } = setup();
    let handled = true;
    act(() => { handled = hook.result.current.handleLocalCommand('/compact'); });
    expect(handled).toBe(false);
  });

  it('inserts the help card directly', () => {
    const { hook, getMessages } = setup();
    act(() => { hook.result.current.handleLocalCommand('/help'); });
    const raw = localRaw(0)(getMessages());
    expect((raw.localCommandOutput as { command: string }).command).toBe('/help');
    expect(sendToJava).not.toHaveBeenCalled();
  });
});
