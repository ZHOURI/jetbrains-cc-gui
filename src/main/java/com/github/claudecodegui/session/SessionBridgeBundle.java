package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.BaseSDKBridge;
import com.github.claudecodegui.provider.common.MarkerCliBridge;
import com.github.claudecodegui.provider.dsh.DshCliBridge;
import com.github.claudecodegui.provider.grok.GrokSDKBridge;
import com.github.claudecodegui.provider.kimi.KimiCliBridge;
import com.github.claudecodegui.provider.minimax.MiniMaxCliBridge;
import com.github.claudecodegui.provider.omp.OmpCliBridge;
import com.github.claudecodegui.provider.opencode.OpenCodeCliBridge;
import com.github.claudecodegui.provider.pi.PiCliBridge;
import com.github.claudecodegui.provider.zcode.ZcodeSDKBridge;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One provider bridge set for a session runtime.
 *
 * <p>A {@link com.github.claudecodegui.provider.claude.ClaudeDaemonCoordinator daemon}
 * serializes commands per bridge, so concurrent background sessions need their own
 * bridges rather than sharing the window's. The initial runtime adopts the window
 * bridges ({@link #adopt}) and never owns them; runtimes created from the Agent View
 * panel allocate an {@link #createOwned owned} bundle that is torn down with them.</p>
 */
public final class SessionBridgeBundle {

    private final ClaudeSDKBridge claudeSDKBridge;
    private final CodexSDKBridge codexSDKBridge;
    private final GrokSDKBridge grokSDKBridge;
    private final ZcodeSDKBridge zcodeSDKBridge;
    private final Map<String, MarkerCliBridge> cliBridges;
    private final boolean owned;

    private SessionBridgeBundle(
            ClaudeSDKBridge claudeSDKBridge,
            CodexSDKBridge codexSDKBridge,
            GrokSDKBridge grokSDKBridge,
            ZcodeSDKBridge zcodeSDKBridge,
            Map<String, MarkerCliBridge> cliBridges,
            boolean owned
    ) {
        this.claudeSDKBridge = claudeSDKBridge;
        this.codexSDKBridge = codexSDKBridge;
        this.grokSDKBridge = grokSDKBridge;
        this.zcodeSDKBridge = zcodeSDKBridge;
        this.cliBridges = cliBridges;
        this.owned = owned;
    }

    /**
     * Wrap the bridges a window already created. The bundle is a routing convenience
     * only: the window keeps ownership and disposes the bridges itself.
     */
    public static SessionBridgeBundle adopt(
            ClaudeSDKBridge claudeSDKBridge,
            CodexSDKBridge codexSDKBridge,
            GrokSDKBridge grokSDKBridge,
            ZcodeSDKBridge zcodeSDKBridge,
            Map<String, MarkerCliBridge> cliBridges
    ) {
        return new SessionBridgeBundle(claudeSDKBridge, codexSDKBridge, grokSDKBridge, zcodeSDKBridge, cliBridges, false);
    }

    /**
     * Allocate a private bridge set so the session's daemon is not shared with any
     * other runtime in the window.
     *
     * @param permissionSessionId window permission routing key; the daemon reports
     *                            permission requests under this id so they land in
     *                            the window's watched permission directory
     */
    public static SessionBridgeBundle createOwned(String permissionSessionId) {
        ClaudeSDKBridge claudeSDKBridge = new ClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = new CodexSDKBridge();
        GrokSDKBridge grokSDKBridge = new GrokSDKBridge();
        ZcodeSDKBridge zcodeSDKBridge = new ZcodeSDKBridge();
        Map<String, MarkerCliBridge> cliBridges = SessionProviderRouter.registerCliBridges(
                new KimiCliBridge(), new OpenCodeCliBridge(), new PiCliBridge(),
                new OmpCliBridge(), new DshCliBridge(), new MiniMaxCliBridge());
        SessionBridgeBundle bundle = new SessionBridgeBundle(
                claudeSDKBridge, codexSDKBridge, grokSDKBridge, zcodeSDKBridge, cliBridges, true);
        bundle.applySessionId(permissionSessionId);
        return bundle;
    }

    /**
     * Point every bridge's permission session id at the window key. Permission files
     * are matched by this id, not by the AI transcript id.
     */
    public void applySessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        for (BaseSDKBridge bridge : bridges()) {
            bridge.setSessionId(sessionId);
        }
    }

    public ClaudeSession newSession(Project project) {
        return new ClaudeSession(project, claudeSDKBridge, codexSDKBridge, cliBridges, grokSDKBridge, zcodeSDKBridge);
    }

    public boolean isOwned() {
        return owned;
    }

    /**
     * Stop every daemon/process this bundle started. No-op for adopted bundles: the
     * window's dispose path owns those.
     */
    public void dispose() {
        if (!owned) {
            return;
        }
        for (BaseSDKBridge bridge : bridges()) {
            try {
                bridge.cleanupAllProcesses();
            } catch (Exception ignored) {
                // One provider failing to stop must not keep the others alive.
            }
        }
    }

    private List<BaseSDKBridge> bridges() {
        List<BaseSDKBridge> bridges = new ArrayList<>();
        if (claudeSDKBridge != null) {
            bridges.add(claudeSDKBridge);
        }
        if (codexSDKBridge != null) {
            bridges.add(codexSDKBridge);
        }
        if (grokSDKBridge != null) {
            bridges.add(grokSDKBridge);
        }
        if (zcodeSDKBridge != null) {
            bridges.add(zcodeSDKBridge);
        }
        if (cliBridges != null) {
            for (MarkerCliBridge bridge : cliBridges.values()) {
                if (bridge != null) {
                    bridges.add(bridge);
                }
            }
        }
        return bridges;
    }
}
