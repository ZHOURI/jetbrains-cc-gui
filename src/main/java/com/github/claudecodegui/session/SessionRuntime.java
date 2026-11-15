package com.github.claudecodegui.session;

import com.github.claudecodegui.permission.PermissionRequest;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * One live conversation owned by an Agent View panel row.
 *
 * <p>The runtime outlives attach switches: its {@link ClaudeSession} keeps running
 * (and keeps accumulating messages in {@link SessionState}) while another runtime is
 * displayed. {@link #getUiAdapter()} is the only window-bound piece and is swapped
 * on attach/detach; the stable {@link SessionRuntimeCallback} records state while
 * detached and forwards to the adapter while attached.</p>
 */
public final class SessionRuntime {

    private static final int TITLE_MAX_LENGTH = 60;

    private final String key;
    private final ClaudeSession session;
    private final SessionBridgeBundle bridgeBundle;
    private final boolean owned;
    private final SessionRuntimeCallback callback;

    private volatile SessionCallbackAdapter uiAdapter;
    private volatile boolean active;
    private volatile boolean busy;
    private volatile boolean loading;
    private volatile boolean unread;
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile String sessionId;
    private volatile String title;
    private volatile PermissionRequest pendingPermission;

    public SessionRuntime(String key, ClaudeSession session, SessionBridgeBundle bridgeBundle, boolean owned) {
        this.key = key;
        this.session = session;
        this.bridgeBundle = bridgeBundle;
        this.owned = owned;
        this.sessionId = session.getSessionId();
        this.callback = new SessionRuntimeCallback(this);
        session.setCallback(callback);
    }

    public String getKey() {
        return key;
    }

    public ClaudeSession getSession() {
        return session;
    }

    public SessionBridgeBundle getBridgeBundle() {
        return bridgeBundle;
    }

    public boolean isOwned() {
        return owned;
    }

    public SessionCallbackAdapter getUiAdapter() {
        return uiAdapter;
    }

    void setUiAdapter(SessionCallbackAdapter uiAdapter) {
        this.uiAdapter = uiAdapter;
    }

    public boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    public boolean isBusy() {
        return busy;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isUnread() {
        return unread;
    }

    public String getSessionId() {
        return sessionId != null ? sessionId : session.getSessionId();
    }

    public String getTitle() {
        String cached = title;
        if (cached != null) {
            return cached;
        }
        String derived = deriveTitle(session.getState().getMessages());
        if (derived != null) {
            title = derived;
        }
        return derived;
    }

    public String getProvider() {
        return session.getProvider();
    }

    public String getModel() {
        return session.getModel();
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public String getStatus() {
        PermissionRequest pending = pendingPermission;
        if (pending != null && !pending.isResolved()) {
            return "waiting";
        }
        return busy || loading ? "streaming" : "idle";
    }

    public JsonObject toJsonRow() {
        JsonObject row = new JsonObject();
        row.addProperty("key", key);
        row.addProperty("sessionId", getSessionId());
        row.addProperty("title", getTitle());
        row.addProperty("provider", getProvider());
        row.addProperty("model", getModel());
        row.addProperty("status", getStatus());
        row.addProperty("busy", busy);
        row.addProperty("unread", unread);
        row.addProperty("messageCount", session.getState().getMessages().size());
        row.addProperty("lastActivity", lastActivity);
        row.addProperty("isActive", active);
        row.addProperty("owned", owned);
        return row;
    }

    void markRead() {
        this.unread = false;
    }

    void recordMessages(List<ClaudeSession.Message> messages) {
        this.lastActivity = System.currentTimeMillis();
        if (title == null && messages != null) {
            title = deriveTitle(messages);
        }
        if (!active) {
            this.unread = true;
        }
    }

    void recordState(boolean busy, boolean loading, String error) {
        this.busy = busy;
        this.loading = loading;
        if (!active && (busy || loading)) {
            this.lastActivity = System.currentTimeMillis();
        }
    }

    void recordStreamActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    void recordSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) {
            this.sessionId = sessionId;
        }
    }

    void recordPermission(PermissionRequest request) {
        if (request == null || request.isResolved()) {
            return;
        }
        this.pendingPermission = request;
        this.lastActivity = System.currentTimeMillis();
    }

    PermissionRequest takePendingPermission() {
        PermissionRequest pending = pendingPermission;
        pendingPermission = null;
        return pending != null && !pending.isResolved() ? pending : null;
    }

    /**
     * Stop the session's current turn and tear down anything this runtime owns. Used
     * when a row is stopped or the window closes.
     */
    public void dispose() {
        release(true);
    }

    /**
     * @param interruptWhenStopping abort the provider turn; false when the caller
     *                              already interrupted the session (window-level
     *                              rebuild), which avoids a second abort reaching a
     *                              channel the replacement session may now own
     */
    void release(boolean interruptWhenStopping) {
        SessionCallbackAdapter adapter = uiAdapter;
        this.uiAdapter = null;
        this.active = false;
        if (adapter != null) {
            adapter.deactivate();
            adapter.dispose();
        }
        try {
            session.getPermissionManager().cancelAllPendingRequests();
        } catch (Exception ignored) {
            // Permission teardown is best-effort; never block window disposal.
        }
        if (interruptWhenStopping) {
            try {
                session.interrupt();
            } catch (Exception ignored) {
                // Interrupting a provider that never started is a no-op.
            }
        }
        if (owned && bridgeBundle != null) {
            bridgeBundle.dispose();
        }
    }

    private static String deriveTitle(List<ClaudeSession.Message> messages) {
        if (messages == null) {
            return null;
        }
        for (ClaudeSession.Message message : messages) {
            if (message == null || message.type != ClaudeSession.Message.Type.USER || message.content == null) {
                continue;
            }
            String content = message.content.trim();
            if (content.isEmpty()) {
                continue;
            }
            int lineBreak = content.indexOf('\n');
            String firstLine = (lineBreak >= 0 ? content.substring(0, lineBreak) : content).trim();
            if (firstLine.isEmpty()) {
                continue;
            }
            return firstLine.length() <= TITLE_MAX_LENGTH ? firstLine : firstLine.substring(0, TITLE_MAX_LENGTH) + "…";
        }
        return null;
    }
}
