import { useCallback, useEffect, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import type {
  AgentViewAttached,
  AgentViewError,
  AgentViewListResponse,
  AgentViewSession,
  AgentViewStopped,
} from '../types';
import { sendToJava } from '../utils/bridge';

type ToastType = 'info' | 'success' | 'warning' | 'error';

/** Poll cadence while the panel is open; backend pushes keep it fresh between ticks. */
const DEFAULT_POLL_INTERVAL_MS = 2000;

type PendingKind = 'attach' | 'new' | 'stop';

interface UseAgentViewSessionsOptions {
  /** Registers handlers and polls only while the panel is visible. */
  open: boolean;
  /** Starts the frontend session-transition guard before a swap request. */
  beginTransition: (title: string | null) => void;
  addToast: (message: string, type?: ToastType) => void;
  t: TFunction;
  onClose: () => void;
  pollIntervalMs?: number;
}

export interface UseAgentViewSessionsReturn {
  sessions: AgentViewSession[];
  activeKey: string | null;
  loading: boolean;
  refresh: () => void;
  attach: (session: AgentViewSession) => void;
  stop: (session: AgentViewSession) => void;
  create: () => void;
}

const createRequestId = () => `agent-view-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;

function parseJson<T>(json: string): T | null {
  try {
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

/**
 * Bridge state for the Agent View panel: subscribes to the four backend
 * callbacks, polls the session list while open, and sends attach/stop/new
 * requests. Any list payload (a poll reply or an unsolicited push with an
 * empty requestId) replaces the rows; identifiers keep stale replies out.
 */
export function useAgentViewSessions({
  open,
  beginTransition,
  addToast,
  t,
  onClose,
  pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
}: UseAgentViewSessionsOptions): UseAgentViewSessionsReturn {
  const [sessions, setSessions] = useState<AgentViewSession[]>([]);
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const latestListRequestRef = useRef<string | null>(null);
  const pendingRef = useRef(new Map<string, PendingKind>());
  const openRef = useRef(open);
  openRef.current = open;

  const applyPayload = useCallback((payload: AgentViewListResponse | null) => {
    if (!payload || !Array.isArray(payload.sessions)) return;
    const requestId = payload.requestId ?? '';
    // Empty ids are backend pushes (registry changed); only an explicit older
    // poll reply is discarded.
    if (requestId && requestId !== latestListRequestRef.current) return;
    setSessions(payload.sessions);
    setActiveKey(payload.activeKey ?? null);
    setLoading(false);
  }, []);

  const refresh = useCallback(() => {
    const requestId = createRequestId();
    latestListRequestRef.current = requestId;
    setLoading(true);
    sendToJava('agent_view_list', { requestId });
  }, []);

  const settle = useCallback((requestId?: string) => {
    if (requestId) {
      pendingRef.current.delete(requestId);
    }
  }, []);

  useEffect(() => {
    if (!open) return;

    const handleSessions = (json: string) => applyPayload(parseJson<AgentViewListResponse>(json));
    const handleAttached = (json: string) => {
      const payload = parseJson<AgentViewAttached>(json);
      if (!payload) return;
      settle(payload.requestId);
      onClose();
    };
    const handleStopped = (json: string) => {
      const payload = parseJson<AgentViewStopped>(json);
      if (!payload) return;
      settle(payload.requestId);
      refresh();
    };
    const handleError = (json: string) => {
      const payload = parseJson<AgentViewError>(json);
      if (!payload) return;
      settle(payload.requestId ?? undefined);
      const message = t(`agentView.errors.${payload.code}`, { defaultValue: payload.message });
      addToast(message, 'error');
      refresh();
    };

    window.onAgentViewSessions = handleSessions;
    window.onAgentViewAttached = handleAttached;
    window.onAgentViewStopped = handleStopped;
    window.onAgentViewError = handleError;
    refresh();
    const interval = window.setInterval(() => {
      if (openRef.current) refresh();
    }, pollIntervalMs);

    return () => {
      window.clearInterval(interval);
      if (window.onAgentViewSessions === handleSessions) window.onAgentViewSessions = undefined;
      if (window.onAgentViewAttached === handleAttached) window.onAgentViewAttached = undefined;
      if (window.onAgentViewStopped === handleStopped) window.onAgentViewStopped = undefined;
      if (window.onAgentViewError === handleError) window.onAgentViewError = undefined;
      pendingRef.current.clear();
    };
  }, [open, applyPayload, refresh, settle, onClose, addToast, t, pollIntervalMs]);

  const send = useCallback((type: string, payload: Record<string, unknown>, kind: PendingKind) => {
    const requestId = createRequestId();
    pendingRef.current.set(requestId, kind);
    sendToJava(type, { ...payload, requestId });
  }, []);

  const attach = useCallback((session: AgentViewSession) => {
    beginTransition(session.title ?? null);
    send('agent_view_attach', { key: session.key }, 'attach');
  }, [beginTransition, send]);

  const create = useCallback(() => {
    beginTransition(null);
    send('agent_view_new', {}, 'new');
  }, [beginTransition, send]);

  const stop = useCallback((session: AgentViewSession) => {
    if (session.isActive) {
      // Stopping the displayed runtime swaps in an empty replacement; clear the
      // transcript before the backend replays the new session.
      beginTransition(null);
    }
    send('agent_view_stop', { key: session.key }, 'stop');
  }, [beginTransition, send]);

  return { sessions, activeKey, loading, refresh, attach, stop, create };
}
