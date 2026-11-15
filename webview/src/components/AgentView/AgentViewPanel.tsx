import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { AgentViewSession } from '../../types';
import { useUIState } from '../../contexts/UIStateContext';
import { useAgentViewSessions } from '../../hooks/useAgentViewSessions';
import './AgentViewPanel.css';

interface AgentViewPanelProps {
  /** Starts the frontend transition guard before the backend swaps sessions. */
  onBeginSessionTransition: (title: string | null) => void;
}

const formatTime = (millis: number) => {
  if (!Number.isFinite(millis) || millis <= 0) return '';
  try {
    return new Date(millis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch {
    return '';
  }
};

/**
 * Agent View panel: lists the window's live conversation runtimes and lets the
 * user attach to one, stop one, or create a new one. Opened by pressing ← twice
 * in an empty composer; background sessions keep streaming while detached.
 */
export function AgentViewPanel({ onBeginSessionTransition }: AgentViewPanelProps) {
  const { t } = useTranslation();
  const { agentViewOpen, setAgentViewOpen, addToast } = useUIState();
  const titleId = useId();
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const lastFocusedRef = useRef<HTMLElement | null>(null);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [confirmStopKey, setConfirmStopKey] = useState<string | null>(null);

  const close = useCallback(() => setAgentViewOpen(false), [setAgentViewOpen]);

  const { sessions, activeKey, loading, refresh, attach, stop, create } = useAgentViewSessions({
    open: agentViewOpen,
    beginTransition: onBeginSessionTransition,
    addToast,
    t,
    onClose: close,
  });

  // Keep the cursor inside the list as rows come and go.
  useEffect(() => {
    setSelectedIndex((index) => Math.max(0, Math.min(index, sessions.length - 1)));
  }, [sessions.length]);

  useEffect(() => {
    if (!agentViewOpen) return undefined;
    lastFocusedRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const rafId = window.requestAnimationFrame(() => closeButtonRef.current?.focus());
    return () => {
      window.cancelAnimationFrame(rafId);
      lastFocusedRef.current?.focus();
    };
  }, [agentViewOpen]);

  useEffect(() => {
    if (!agentViewOpen) {
      setConfirmStopKey(null);
      setSelectedIndex(0);
    }
  }, [agentViewOpen]);

  const handleAttach = useCallback((session: AgentViewSession) => {
    if (session.isActive || session.key === activeKey) return;
    attach(session);
  }, [activeKey, attach]);

  const handleStop = useCallback((session: AgentViewSession) => {
    stop(session);
    setConfirmStopKey(null);
  }, [stop]);

  const handleKeyDown = useCallback((event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      if (sessions.length === 0) return;
      event.preventDefault();
      const delta = event.key === 'ArrowDown' ? 1 : -1;
      setSelectedIndex((index) => (index + delta + sessions.length) % sessions.length);
      return;
    }
    if (event.key === 'Enter') {
      const session = sessions[selectedIndex];
      if (!session) return;
      event.preventDefault();
      handleAttach(session);
    }
  }, [close, sessions, selectedIndex, handleAttach]);

  if (!agentViewOpen) return null;

  return (
    <div className="agent-view-overlay" onMouseDown={close}>
      <div
        className="agent-view-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
        onKeyDown={handleKeyDown}
      >
        <div className="agent-view-header">
          <h3 id={titleId} className="agent-view-title">{t('agentView.title')}</h3>
          <button
            ref={closeButtonRef}
            type="button"
            className="agent-view-close"
            onMouseDown={(event) => { event.stopPropagation(); close(); }}
            title={t('common.close')}
            aria-label={t('common.close')}
          >
            ×
          </button>
        </div>

        {sessions.length === 0 ? (
          <div className="agent-view-empty">{loading ? t('agentView.loading') : t('agentView.empty')}</div>
        ) : (
          <ul className="agent-view-list" role="listbox" aria-label={t('agentView.title')}>
            {sessions.map((session, index) => {
              const isCurrent = session.isActive || session.key === activeKey;
              const confirming = confirmStopKey === session.key;
              return (
                <li
                  key={session.key}
                  className={`agent-view-row${index === selectedIndex ? ' is-selected' : ''}${isCurrent ? ' is-current' : ''}`}
                  role="option"
                  aria-selected={index === selectedIndex}
                  onMouseEnter={() => setSelectedIndex(index)}
                  onClick={() => handleAttach(session)}
                >
                  <span className={`agent-view-status agent-view-status-${session.status}`}>
                    {t(`agentView.status.${session.status}`, { defaultValue: session.status })}
                  </span>
                  <span className="agent-view-main">
                    <span className="agent-view-row-title">
                      {session.title?.trim() || t('agentView.untitled')}
                      {session.unread && <span className="agent-view-unread" aria-label={t('agentView.unread')} />}
                      {isCurrent && <span className="agent-view-current">{t('agentView.current')}</span>}
                    </span>
                    <span className="agent-view-meta">
                      {[session.provider, session.model].filter(Boolean).join(' · ')}
                      {session.messageCount > 0 && (
                        <span className="agent-view-count">
                          {t('agentView.messageCount', { count: session.messageCount })}
                        </span>
                      )}
                      <span className="agent-view-time">{formatTime(session.lastActivity)}</span>
                    </span>
                  </span>
                  <span className="agent-view-actions" onClick={(event) => event.stopPropagation()}>
                    {confirming ? (
                      <>
                        <button
                          type="button"
                          className="agent-view-action agent-view-action-danger"
                          onClick={() => handleStop(session)}
                        >
                          {t('agentView.confirmStop')}
                        </button>
                        <button type="button" className="agent-view-action" onClick={() => setConfirmStopKey(null)}>
                          {t('common.cancel')}
                        </button>
                      </>
                    ) : (
                      <>
                        {!isCurrent && (
                          <button type="button" className="agent-view-action" onClick={() => handleAttach(session)}>
                            {t('agentView.attach')}
                          </button>
                        )}
                        <button
                          type="button"
                          className="agent-view-action agent-view-action-danger"
                          onClick={() => setConfirmStopKey(session.key)}
                        >
                          {t('agentView.stop')}
                        </button>
                      </>
                    )}
                  </span>
                </li>
              );
            })}
          </ul>
        )}

        <div className="agent-view-footer">
          <button type="button" className="agent-view-new" onClick={create}>
            {t('agentView.newSession')}
          </button>
          <button type="button" className="agent-view-refresh" onClick={refresh}>
            {t('agentView.refresh')}
          </button>
          <span className="agent-view-hint">{t('agentView.hint')}</span>
        </div>
      </div>
    </div>
  );
}

export default AgentViewPanel;
