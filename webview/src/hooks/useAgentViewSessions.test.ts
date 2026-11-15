import { act, renderHook } from '@testing-library/react';
import type { AgentViewSession } from '../types';
import { sendToJava } from '../utils/bridge';
import { useAgentViewSessions } from './useAgentViewSessions';

vi.mock('../utils/bridge', () => ({ sendToJava: vi.fn() }));

const sendToJavaMock = vi.mocked(sendToJava);

const t = ((key: string) => key) as unknown as Parameters<typeof useAgentViewSessions>[0]['t'];

const row = (overrides: Partial<AgentViewSession> = {}): AgentViewSession => ({
  key: 'k1',
  title: 'Inspect the parser',
  provider: 'claude',
  model: 'claude-sonnet-5',
  status: 'idle',
  busy: false,
  unread: false,
  messageCount: 3,
  lastActivity: 1,
  isActive: true,
  owned: false,
  ...overrides,
});

function options(overrides: Partial<Parameters<typeof useAgentViewSessions>[0]> = {}) {
  return {
    open: true,
    beginTransition: vi.fn(),
    addToast: vi.fn(),
    t,
    onClose: vi.fn(),
    ...overrides,
  };
}

describe('useAgentViewSessions', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sendToJavaMock.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('requests the list on open and applies poll replies in order', () => {
    const opts = options();
    const { result, unmount } = renderHook(() => useAgentViewSessions(opts));

    expect(sendToJavaMock).toHaveBeenCalledWith('agent_view_list', expect.objectContaining({ requestId: expect.any(String) }));
    const firstId = sendToJavaMock.mock.calls[0][1].requestId as string;

    act(() => {
      window.onAgentViewSessions!(JSON.stringify({ requestId: firstId, activeKey: 'k1', sessions: [row()] }));
    });
    expect(result.current.sessions).toHaveLength(1);
    expect(result.current.activeKey).toBe('k1');

    act(() => result.current.refresh());
    const secondId = sendToJavaMock.mock.calls[1][1].requestId as string;

    // A late reply to the first request must not replace the newer view.
    act(() => {
      window.onAgentViewSessions!(JSON.stringify({ requestId: firstId, activeKey: null, sessions: [] }));
    });
    expect(result.current.sessions).toHaveLength(1);

    act(() => {
      window.onAgentViewSessions!(JSON.stringify({ requestId: secondId, activeKey: 'k2', sessions: [row({ key: 'k2' })] }));
    });
    expect(result.current.activeKey).toBe('k2');

    unmount();
    expect(window.onAgentViewSessions).toBeUndefined();
    expect(window.onAgentViewAttached).toBeUndefined();
    expect(window.onAgentViewStopped).toBeUndefined();
    expect(window.onAgentViewError).toBeUndefined();
  });

  it('polls while open and stops after unmount', () => {
    const opts = options();
    const { unmount } = renderHook(() => useAgentViewSessions(opts));
    expect(sendToJavaMock).toHaveBeenCalledTimes(1);

    act(() => vi.advanceTimersByTime(2000));
    expect(sendToJavaMock).toHaveBeenCalledTimes(2);

    unmount();
    act(() => vi.advanceTimersByTime(4000));
    expect(sendToJavaMock).toHaveBeenCalledTimes(2);
  });

  it('accepts unsolicited pushes with an empty requestId', () => {
    const { result } = renderHook(() => useAgentViewSessions(options()));

    act(() => {
      window.onAgentViewSessions!(JSON.stringify({ requestId: '', activeKey: 'k9', sessions: [row({ key: 'k9' })] }));
    });

    expect(result.current.activeKey).toBe('k9');
    expect(result.current.sessions[0].key).toBe('k9');
  });

  it('starts a transition before attach and closes once acknowledged', () => {
    const opts = options();
    const { result } = renderHook(() => useAgentViewSessions(opts));

    act(() => result.current.attach(row({ key: 'k2', title: 'Second', isActive: false })));

    expect(opts.beginTransition).toHaveBeenCalledWith('Second');
    const [type, payload] = sendToJavaMock.mock.calls.at(-1)!;
    expect(type).toBe('agent_view_attach');
    expect(payload.key).toBe('k2');

    act(() => {
      window.onAgentViewAttached!(JSON.stringify({ requestId: payload.requestId, key: 'k2', ok: true }));
    });
    expect(opts.onClose).toHaveBeenCalledTimes(1);
  });

  it('only clears the frontend before stopping the displayed session', () => {
    const opts = options();
    const { result } = renderHook(() => useAgentViewSessions(opts));

    act(() => result.current.stop(row({ key: 'background', isActive: false })));
    expect(opts.beginTransition).not.toHaveBeenCalled();

    act(() => result.current.stop(row({ key: 'k1', isActive: true })));
    expect(opts.beginTransition).toHaveBeenCalledWith(null);
  });

  it('creates a session without a title and refreshes on ack', () => {
    const opts = options();
    const { result } = renderHook(() => useAgentViewSessions(opts));
    sendToJavaMock.mockClear();

    act(() => result.current.create());
    expect(opts.beginTransition).toHaveBeenCalledWith(null);
    expect(sendToJavaMock).toHaveBeenCalledWith('agent_view_new', expect.objectContaining({ requestId: expect.any(String) }));
    const requestId = sendToJavaMock.mock.calls[0][1].requestId as string;

    act(() => {
      window.onAgentViewStopped!(JSON.stringify({ requestId: 'other', key: 'k1', ok: true, wasActive: false }));
    });
    expect(sendToJavaMock).toHaveBeenCalledTimes(2);

    act(() => {
      window.onAgentViewAttached!(JSON.stringify({ requestId, key: 'fresh', ok: true }));
    });
    expect(opts.onClose).toHaveBeenCalled();
  });

  it('localizes backend errors and refreshes the list', () => {
    const opts = options();
    renderHook(() => useAgentViewSessions(opts));

    act(() => {
      window.onAgentViewError!(JSON.stringify({
        requestId: 'r', key: 'k1', code: 'limit', message: 'raw backend text',
      }));
    });

    expect(opts.addToast).toHaveBeenCalledWith('agentView.errors.limit', 'error');
    expect(sendToJavaMock.mock.calls.at(-1)![0]).toBe('agent_view_list');
  });
});
