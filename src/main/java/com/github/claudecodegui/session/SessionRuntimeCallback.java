package com.github.claudecodegui.session;

import com.github.claudecodegui.permission.PermissionRequest;

import java.util.List;

/**
 * Stable callback installed on a runtime's {@link ClaudeSession} for its whole life.
 *
 * <p>Attach swaps the window-bound {@link SessionCallbackAdapter} underneath instead
 * of replacing the session callback: the callback field on
 * {@link CallbackHandler} is not volatile, so reassigning it on the EDT could be
 * missed by the daemon reader thread. This indirection also lets a detached
 * session keep recording unread/state instead of silently dropping every event
 * the way a deactivated adapter does.</p>
 */
final class SessionRuntimeCallback implements ClaudeSession.SessionCallback {

    private final SessionRuntime runtime;

    SessionRuntimeCallback(SessionRuntime runtime) {
        this.runtime = runtime;
    }

    private ClaudeSession.SessionCallback ui() {
        return runtime.isActive() ? runtime.getUiAdapter() : null;
    }

    @Override
    public void onMessageUpdate(List<ClaudeSession.Message> messages) {
        runtime.recordMessages(messages);
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onMessageUpdate(messages);
        }
    }

    @Override
    public void onStateChange(boolean busy, boolean loading, String error) {
        runtime.recordState(busy, loading, error);
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onStateChange(busy, loading, error);
        }
    }

    @Override
    public void onStatusMessage(String message) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onStatusMessage(message);
        }
    }

    @Override
    public void onSessionIdReceived(String sessionId) {
        runtime.recordSessionId(sessionId);
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onSessionIdReceived(sessionId);
        }
    }

    @Override
    public void onPermissionRequested(PermissionRequest request) {
        // Detached requests stay parked on the runtime and are revealed on attach;
        // the request routes through the owning session's PermissionManager, so
        // showing it while another session is bound would resolve the wrong owner.
        runtime.recordPermission(request);
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onPermissionRequested(request);
        }
    }

    @Override
    public void onThinkingStatusChanged(boolean isThinking) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onThinkingStatusChanged(isThinking);
        }
    }

    @Override
    public void onSlashCommandsReceived(List<String> slashCommands) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onSlashCommandsReceived(slashCommands);
        }
    }

    @Override
    public void onNodeLog(String log) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onNodeLog(log);
        }
    }

    @Override
    public void onSummaryReceived(String summary) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onSummaryReceived(summary);
        }
    }

    @Override
    public void onStreamStart() {
        runtime.recordStreamActivity();
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onStreamStart();
        }
    }

    @Override
    public void onStreamEnd() {
        runtime.recordStreamActivity();
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onStreamEnd();
        }
    }

    @Override
    public void onContentDelta(String delta) {
        runtime.recordStreamActivity();
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onContentDelta(delta);
        }
    }

    @Override
    public void onThinkingDelta(String delta) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onThinkingDelta(delta);
        }
    }

    @Override
    public void onBlockReset() {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onBlockReset();
        }
    }

    @Override
    public void onUsageUpdate(int usedTokens, int maxTokens) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onUsageUpdate(usedTokens, maxTokens);
        }
    }

    @Override
    public void onUserMessageUuidPatched(String content, String uuid) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onUserMessageUuidPatched(content, uuid);
        }
    }

    @Override
    public void onTaskEvent(String eventJson) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onTaskEvent(eventJson);
        }
    }

    @Override
    public void onCodexRuntimeEvent(String eventJson) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onCodexRuntimeEvent(eventJson);
        }
    }

    @Override
    public void onClaudeHistoryPageInfo(String sessionId, int fromTurn, int totalTurns, boolean hasMore, boolean cursorReset, String sessionTitle) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onClaudeHistoryPageInfo(sessionId, fromTurn, totalTurns, hasMore, cursorReset, sessionTitle);
        }
    }

    @Override
    public void onClaudeHistoryPageError(String sessionId, String message) {
        ClaudeSession.SessionCallback ui = ui();
        if (ui != null) {
            ui.onClaudeHistoryPageError(sessionId, message);
        }
    }
}
