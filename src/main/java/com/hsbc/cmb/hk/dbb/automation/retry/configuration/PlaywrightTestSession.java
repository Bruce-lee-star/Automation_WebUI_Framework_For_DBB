package com.hsbc.cmb.hk.dbb.automation.retry.configuration;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PlaywrightTestSession {
    private static final Logger logger = LoggerFactory.getLogger(PlaywrightTestSession.class);
    private static final ThreadLocal<PlaywrightTestSession> currentSession = new ThreadLocal<>();
    private static final ConcurrentMap<String, Boolean> sessionStates = new ConcurrentHashMap<>();

    private final String sessionId;
    private boolean isCleanedUp = false;

    private PlaywrightTestSession() {
        this.sessionId = generateSessionId();
    }

    public static PlaywrightTestSession startNewSession() {
        PlaywrightTestSession session = new PlaywrightTestSession();
        session.start();
        currentSession.set(session);
        return session;
    }

    public static PlaywrightTestSession getCurrentSession() {
        return currentSession.get();
    }

    public static boolean hasActiveSession() {
        return currentSession.get() != null;
    }

    public static void endSession() {
        PlaywrightTestSession session = currentSession.get();
        if (session != null) {
            session.cleanup();
            currentSession.remove();
        }
    }

    public void start() {
        logger.info("🚀 Starting new PlaywrightTestSession: {}", sessionId);
        sessionStates.put(sessionId, true);
        try {
            StepEventBus.getEventBus().clear();
        } catch (Exception e) {
            logger.debug("Could not clear StepEventBus: {}", e.getMessage());
        }
    }

    public void resetForRetry() {
        logger.info("🔄 Resetting session {} for retry...", sessionId);

        try {
            clearStepEventBus();
            logger.info("✅ StepEventBus cleared");
        } catch (Exception e) {
            logger.warn("⚠️ Failed to clear StepEventBus: {}", e.getMessage());
        }

        try {
            resetPlaywrightResources();
            logger.info("✅ Playwright resources reset");
        } catch (Exception e) {
            logger.warn("⚠️ Failed to reset Playwright resources: {}", e.getMessage());
        }

        logger.info("✅ Session {} reset for retry", sessionId);
    }

    private void clearStepEventBus() {
        StepEventBus.getEventBus().clear();
    }

    private void resetPlaywrightResources() {
        try {
            PlaywrightManager.closePage();
        } catch (Exception e) {
            logger.debug("Could not close page: {}", e.getMessage());
        }

        try {
            PlaywrightManager.closeContext();
        } catch (Exception e) {
            logger.debug("Could not close context: {}", e.getMessage());
        }

        try {
            boolean restartBrowser = Boolean.parseBoolean(
                    System.getProperty("serenity.retry.restart.browser", "true"));
            if (restartBrowser) {
                PlaywrightManager.restartBrowser();
                logger.info("🔄 Browser restarted for retry");
            }
        } catch (Exception e) {
            logger.debug("Could not restart browser: {}", e.getMessage());
        }
    }

    public void cleanup() {
        if (isCleanedUp) {
            return;
        }

        logger.info("🧹 Cleaning up PlaywrightTestSession: {}", sessionId);
        isCleanedUp = true;

        try {
            StepEventBus.getEventBus().testSuiteFinished();
        } catch (Exception e) {
            logger.debug("Could not fire testSuiteFinished event: {}", e.getMessage());
        }

        sessionStates.remove(sessionId);
        logger.info("✅ PlaywrightTestSession {} cleaned up", sessionId);
    }

    private String generateSessionId() {
        return String.format("session_%s_%d",
                Thread.currentThread().getName(),
                System.currentTimeMillis());
    }

    public String getSessionId() {
        return sessionId;
    }

    public static void resetAllSessions() {
        logger.info("🔄 Resetting all Playwright test sessions...");
        currentSession.remove();
        logger.info("✅ All sessions reset");
    }
}
