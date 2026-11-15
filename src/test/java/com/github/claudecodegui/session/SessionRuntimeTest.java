package com.github.claudecodegui.session;

import com.github.claudecodegui.permission.PermissionRequest;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SessionRuntimeTest {

    private static SessionRuntime runtime() {
        ClaudeSession session = new ClaudeSession(null, null, null, null) {
            @Override public CompletableFuture<Void> interrupt() { return CompletableFuture.completedFuture(null); }
        };
        session.setProvider("claude");
        session.setModelVerbatim("claude-opus-4-8");
        return new SessionRuntime("key-1", session, null, false);
    }

    @Test
    public void detachedActivityMarksUnreadAndAttachingClearsIt() {
        SessionRuntime runtime = runtime();
        runtime.setActive(false);

        runtime.recordMessages(List.of(new ClaudeSession.Message(ClaudeSession.Message.Type.ASSISTANT, "hello")));
        assertTrue(runtime.isUnread());
        assertTrue(runtime.toJsonRow().get("unread").getAsBoolean());

        runtime.setActive(true);
        runtime.markRead();
        runtime.recordMessages(List.of(new ClaudeSession.Message(ClaudeSession.Message.Type.ASSISTANT, "hello")));
        assertFalse(runtime.isUnread());
    }

    @Test
    public void titleComesFromTheFirstUserLine() {
        SessionRuntime runtime = runtime();
        runtime.recordMessages(List.of(
                new ClaudeSession.Message(ClaudeSession.Message.Type.SYSTEM, "context"),
                new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "  Inspect the parser\nsecond line  "),
                new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "another prompt")));

        assertEquals("Inspect the parser", runtime.getTitle());
        assertEquals("Inspect the parser", runtime.toJsonRow().get("title").getAsString());
    }

    @Test
    public void longTitlesAreTruncated() {
        SessionRuntime runtime = runtime();
        String longPrompt = "x".repeat(120);
        runtime.recordMessages(List.of(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, longPrompt)));

        String title = runtime.getTitle();
        assertEquals(61, title.length());
        assertTrue(title.endsWith("…"));
    }

    @Test
    public void jsonRowReportsProviderModelAndStatus() {
        SessionRuntime runtime = runtime();
        runtime.setActive(true);

        JsonObject idle = runtime.toJsonRow();
        assertEquals("claude", idle.get("provider").getAsString());
        assertEquals("claude-opus-4-8", idle.get("model").getAsString());
        assertEquals("idle", idle.get("status").getAsString());
        assertTrue(idle.get("isActive").getAsBoolean());

        runtime.recordState(true, true, null);
        assertEquals("streaming", runtime.toJsonRow().get("status").getAsString());

        PermissionRequest request = new PermissionRequest("channel", "Bash", null, null, null);
        runtime.recordPermission(request);
        runtime.recordState(false, false, null);
        assertEquals("waiting", runtime.getStatus());
    }

    @Test
    public void pendingPermissionIsHandedToTheFirstAttach() {
        SessionRuntime runtime = runtime();
        PermissionRequest request = new PermissionRequest("channel", "Bash", null, null, null);
        runtime.recordPermission(request);

        assertSame(request, runtime.takePendingPermission());
        assertNull(runtime.takePendingPermission());
    }

    @Test
    public void resolvedPermissionIsNotReplayedOnAttach() {
        SessionRuntime runtime = runtime();
        PermissionRequest request = new PermissionRequest("channel", "Bash", null, null, null);
        runtime.recordPermission(request);
        request.accept();

        assertNull(runtime.takePendingPermission());
        assertEquals("idle", runtime.getStatus());
    }
}
