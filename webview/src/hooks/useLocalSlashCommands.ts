import { useCallback, useEffect, useRef } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage } from '../types';
import type {
  LocalCommandDataKind,
  LocalCommandId,
  LocalCommandOutputBlock,
  StatusInfoPayload,
} from '../types/localCommand';
import type { SettingsTab } from '../components/settings/SettingsSidebar';
import type { ViewMode } from './useModelProviderState';
import { parseLocalCommand } from './localCommandDispatcher';
import {
  buildCardForKind,
  buildErrorCard,
  buildHelpCard,
  buildLoadingCard,
} from './localCommandCards';
import { sendBridgeEvent } from '../utils/bridge';

const STATUS_REQUEST_TIMEOUT_MS = 10000;

const SETTINGS_TABS: Partial<Record<LocalCommandId, SettingsTab>> = {
  config: 'basic',
  model: 'providers',
  permissions: 'permissions',
  agents: 'agents',
  cost: 'usage',
};

interface PendingRequest {
  kind: LocalCommandDataKind;
  timer: number;
}

export interface UseLocalSlashCommandsOptions {
  t: TFunction;
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  currentProvider: string;
  currentSessionId?: string | null;
  sessionTitle?: string;
  exportSession: (sessionId: string, title: string) => void;
  setMessages: React.Dispatch<React.SetStateAction<ClaudeMessage[]>>;
  setSettingsInitialTab: (tab: SettingsTab) => void;
  setCurrentView: (view: ViewMode) => void;
}

interface LocalCommandAnchor {
  uuid: string | null;
  index: number;
}

function createRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `local-cmd-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function isLocalCommandMessage(message: ClaudeMessage): boolean {
  return typeof message.raw === 'object'
    && message.raw !== null
    && message.raw.isLocalCommandOutput === true;
}

function findAnchor(messages: ClaudeMessage[]): LocalCommandAnchor {
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i];
    if (isLocalCommandMessage(message)) continue;
    const raw = typeof message.raw === 'object' && message.raw !== null ? message.raw : null;
    const uuid = typeof raw?.uuid === 'string' && raw.uuid ? raw.uuid : null;
    return { uuid, index: i };
  }
  return { uuid: null, index: -1 };
}

export function useLocalSlashCommands({
  t,
  addToast,
  currentProvider,
  currentSessionId,
  sessionTitle,
  exportSession,
  setMessages,
  setSettingsInitialTab,
  setCurrentView,
}: UseLocalSlashCommandsOptions) {
  const pendingRef = useRef(new Map<string, PendingRequest>());

  const insertCard = useCallback((block: LocalCommandOutputBlock, requestId?: string) => {
    const uuid = requestId
      ? `local-cmd-${requestId}`
      : `local-cmd-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    setMessages((prev) => {
      const anchor = findAnchor(prev);
      const message: ClaudeMessage = {
        type: 'assistant',
        content: '',
        timestamp: new Date().toISOString(),
        raw: {
          uuid,
          isLocalCommandOutput: true,
          localCommandOutput: block,
          localCommandAnchorUuid: anchor.uuid,
          localCommandAnchorIndex: anchor.index,
          ...(requestId ? { localCommandRequestId: requestId } : {}),
        },
      };
      return [...prev, message];
    });
  }, [setMessages]);

  const replaceCard = useCallback((requestId: string, block: LocalCommandOutputBlock) => {
    setMessages((prev) => prev.map((message) => {
      const raw = typeof message.raw === 'object' && message.raw !== null ? message.raw : null;
      if (!raw || raw.localCommandRequestId !== requestId) return message;
      return { ...message, raw: { ...raw, localCommandOutput: block } };
    }));
  }, [setMessages]);

  const handlePayload = useCallback((json: string) => {
    let payload: StatusInfoPayload;
    try {
      payload = JSON.parse(json) as StatusInfoPayload;
    } catch {
      return;
    }
    const requestId = payload.requestId;
    if (!requestId) return;
    const pending = pendingRef.current.get(requestId);
    if (!pending) return;
    window.clearTimeout(pending.timer);
    pendingRef.current.delete(requestId);

    const hasData = Boolean(
      payload.status
      || payload.diagnostics
      || payload.memoryFiles
      || payload.hookFiles,
    );
    const card = payload.error && !hasData
      ? buildErrorCard(pending.kind, `/${pending.kind}`, t, payload.error)
      : buildCardForKind(pending.kind, payload, t);
    replaceCard(requestId, card);
  }, [replaceCard, t]);

  useEffect(() => {
    const previous = window.onStatusInfo;
    const handler = (json: string) => {
      handlePayload(json);
      previous?.(json);
    };
    window.onStatusInfo = handler;

    if (window.__pendingStatusInfo) {
      const pending = window.__pendingStatusInfo;
      window.__pendingStatusInfo = undefined;
      handler(pending);
    }

    return () => {
      if (window.onStatusInfo === handler) {
        window.onStatusInfo = previous;
      }
      pendingRef.current.forEach((pending) => window.clearTimeout(pending.timer));
      pendingRef.current.clear();
    };
  }, [handlePayload]);

  const failRequest = useCallback((
    requestId: string,
    kind: LocalCommandDataKind,
    error: string,
  ) => {
    const pending = pendingRef.current.get(requestId);
    if (pending) {
      window.clearTimeout(pending.timer);
      pendingRef.current.delete(requestId);
    }
    replaceCard(requestId, buildErrorCard(kind, `/${kind}`, t, error));
  }, [replaceCard, t]);

  const requestData = useCallback((kind: LocalCommandDataKind) => {
    const requestId = createRequestId();
    insertCard(buildLoadingCard(kind, `/${kind}`, t), requestId);

    const timer = window.setTimeout(() => {
      pendingRef.current.delete(requestId);
      replaceCard(requestId, buildErrorCard(
        kind,
        `/${kind}`,
        t,
        t('chat.localCommand.timeout'),
      ));
    }, STATUS_REQUEST_TIMEOUT_MS);
    pendingRef.current.set(requestId, { kind, timer });

    const sent = sendBridgeEvent('get_status_info', JSON.stringify({
      requestId,
      kind,
      provider: currentProvider,
    }));
    if (!sent) {
      failRequest(requestId, kind, t('chat.localCommand.bridgeUnavailable'));
    }
  }, [currentProvider, failRequest, insertCard, replaceCard, t]);

  const handleLocalCommand = useCallback((text: string): boolean => {
    const parsed = parseLocalCommand(text);
    if (!parsed) return false;

    if (parsed.args) {
      addToast(t('chat.localCommand.usage', {
        command: parsed.raw,
        defaultValue: `${parsed.raw} does not take arguments`,
      }), 'info');
      return true;
    }

    const settingsTab = SETTINGS_TABS[parsed.id];
    if (settingsTab) {
      setSettingsInitialTab(settingsTab);
      setCurrentView('settings');
      return true;
    }

    switch (parsed.id) {
      case 'status':
      case 'doctor':
      case 'memory':
      case 'hooks':
        requestData(parsed.id);
        return true;
      case 'help':
        insertCard(buildHelpCard(t));
        return true;
      case 'export':
        if (!currentSessionId) {
          addToast(t('chat.localCommand.export.noSession'), 'warning');
          return true;
        }
        exportSession(
          currentSessionId,
          sessionTitle || t('chat.newSession'),
        );
        addToast(t('chat.localCommand.export.started'), 'success');
        return true;
      default:
        return false;
    }
  }, [
    addToast,
    currentSessionId,
    exportSession,
    insertCard,
    requestData,
    sessionTitle,
    setCurrentView,
    setSettingsInitialTab,
    t,
  ]);

  return { handleLocalCommand };
}
