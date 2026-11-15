package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.AgentSessionRegistry;
import com.github.claudecodegui.session.SessionRuntime;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Backend for the Agent View panel (double Left-arrow in an empty composer).
 *
 * <p>Messages: {@code agent_view_list}, {@code agent_view_attach},
 * {@code agent_view_stop} and {@code agent_view_new}. List can run on the
 * dispatching thread; every mutation of the active runtime is marshalled to the
 * EDT because it swaps the window's session and replays the transcript.</p>
 */
public class AgentViewHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(AgentViewHandler.class);
    private static final Gson GSON = new Gson();

    private static final String[] SUPPORTED_TYPES = {
            "agent_view_list",
            "agent_view_attach",
            "agent_view_stop",
            "agent_view_new",
    };

    private final AgentSessionRegistry registry;

    public AgentViewHandler(HandlerContext context, AgentSessionRegistry registry) {
        super(context);
        this.registry = registry;
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "agent_view_list":
                handleList(content);
                return true;
            case "agent_view_attach":
                handleAttach(content);
                return true;
            case "agent_view_stop":
                handleStop(content);
                return true;
            case "agent_view_new":
                handleNew(content);
                return true;
            default:
                return false;
        }
    }

    private void handleList(String content) {
        JsonObject request = parse(content);
        String requestId = getString(request, "requestId");
        if (registry == null || registry.isDisposed()) {
            respondError(requestId, null, "unavailable", "Agent View is not available");
            return;
        }
        try {
            callJavaScript("onAgentViewSessions", escapeJs(registry.listJson(requestId)));
        } catch (Exception e) {
            LOG.warn("[AgentViewHandler] Failed to list sessions: " + e.getMessage(), e);
            respondError(requestId, null, "list_failed", e.getMessage());
        }
    }

    private void handleAttach(String content) {
        JsonObject request = parse(content);
        String requestId = getString(request, "requestId");
        String key = getString(request, "key");
        if (key == null || key.isEmpty()) {
            respondError(requestId, key, "invalid_request", "Missing session key");
            return;
        }
        runOnEdt(requestId, key, () -> {
            if (registry == null || registry.isDisposed()) {
                respondError(requestId, key, "unavailable", "Agent View is not available");
                return;
            }
            SessionRuntime runtime = registry.attach(key);
            if (runtime == null) {
                respondError(requestId, key, "not_found", "Session is no longer available");
                return;
            }
            respondAttached(requestId, runtime);
        });
    }

    private void handleStop(String content) {
        JsonObject request = parse(content);
        String requestId = getString(request, "requestId");
        String key = getString(request, "key");
        if (key == null || key.isEmpty()) {
            respondError(requestId, key, "invalid_request", "Missing session key");
            return;
        }
        runOnEdt(requestId, key, () -> {
            if (registry == null || registry.isDisposed()) {
                respondError(requestId, key, "unavailable", "Agent View is not available");
                return;
            }
            SessionRuntime active = registry.getActiveRuntime();
            boolean wasActive = active != null && active.getKey().equals(key);
            if (!registry.stop(key)) {
                respondError(requestId, key, "not_found", "Session is no longer available");
                return;
            }
            JsonObject ack = new JsonObject();
            ack.addProperty("requestId", requestId);
            ack.addProperty("key", key);
            ack.addProperty("ok", true);
            ack.addProperty("wasActive", wasActive);
            callJavaScript("onAgentViewStopped", escapeJs(ack.toString()));
        });
    }

    private void handleNew(String content) {
        JsonObject request = parse(content);
        String requestId = getString(request, "requestId");
        runOnEdt(requestId, null, () -> {
            if (registry == null || registry.isDisposed()) {
                respondError(requestId, null, "unavailable", "Agent View is not available");
                return;
            }
            SessionRuntime runtime = registry.createAndAttach();
            if (runtime == null) {
                respondError(requestId, null, "limit",
                        "At most " + AgentSessionRegistry.MAX_SESSIONS + " sessions can run at once");
                return;
            }
            respondAttached(requestId, runtime);
        });
    }

    private void respondAttached(String requestId, SessionRuntime runtime) {
        JsonObject ack = new JsonObject();
        ack.addProperty("requestId", requestId);
        ack.addProperty("key", runtime.getKey());
        ack.addProperty("ok", true);
        ack.addProperty("sessionId", runtime.getSessionId());
        callJavaScript("onAgentViewAttached", escapeJs(ack.toString()));
    }

    private void respondError(String requestId, String key, String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("requestId", requestId);
        error.addProperty("key", key);
        error.addProperty("code", code);
        error.addProperty("message", message != null ? message : "Agent View request failed");
        callJavaScript("onAgentViewError", escapeJs(error.toString()));
    }

    private void runOnEdt(String requestId, String key, Runnable action) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                action.run();
            } catch (Exception e) {
                LOG.warn("[AgentViewHandler] Agent View operation failed: " + e.getMessage(), e);
                respondError(requestId, key, "internal_error", e.getMessage());
            }
        });
    }

    private static JsonObject parse(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonObject parsed = GSON.fromJson(content, JsonObject.class);
            return parsed != null ? parsed : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }
}
