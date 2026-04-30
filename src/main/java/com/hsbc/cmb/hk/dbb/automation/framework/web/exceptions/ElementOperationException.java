package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * Element操作异常类
 *
 * Enterprise-grade element operation exception with detailed diagnostic information.
 *
 * @since 1.0.0
 */
public class ElementOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String selector;
    private final String operation;
    private final String pageUrl;
    private final String elementState;
    private final DiagnosticInfo diagnosticInfo;

    private ElementOperationException(String message, Throwable cause,
            String selector, String operation, String pageUrl,
            String elementState, DiagnosticInfo diagnosticInfo) {
        super(message, cause);
        this.selector = selector;
        this.operation = operation;
        this.pageUrl = pageUrl;
        this.elementState = elementState;
        this.diagnosticInfo = diagnosticInfo;
    }

    // Getters
    public String getSelector() { return selector; }
    public String getOperation() { return operation; }
    public String getPageUrl() { return pageUrl; }
    public String getElementState() { return elementState; }
    public DiagnosticInfo getDiagnosticInfo() { return diagnosticInfo; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append(" Element Operation Failed\n");
        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append(String.format("  Operation : %s%n", operation));
        sb.append(String.format("  Selector  : %s%n", selector));
        sb.append(String.format("  Page URL  : %s%n", pageUrl));
        sb.append(String.format("  Element   : %s%n", elementState));

        if (diagnosticInfo != null) {
            sb.append("───────────────────────────────────────────────────────────\n");
            sb.append(" Diagnostic Information\n");
            sb.append("───────────────────────────────────────────────────────────\n");
            sb.append(String.format("  Exists in DOM  : %s%n", diagnosticInfo.existsInDom));
            sb.append(String.format("  Is Visible     : %s%n", diagnosticInfo.isVisible));
            sb.append(String.format("  Is Enabled     : %s%n", diagnosticInfo.isEnabled));
            sb.append(String.format("  Is Editable    : %s%n", diagnosticInfo.isEditable));
            sb.append(String.format("  Element Count  : %d%n", diagnosticInfo.elementCount));
            sb.append(String.format("  Retry Count    : %d%n", diagnosticInfo.retryCount));
            if (diagnosticInfo.tagName != null) {
                sb.append(String.format("  Tag Name       : %s%n", diagnosticInfo.tagName));
            }
            if (diagnosticInfo.attributes != null && !diagnosticInfo.attributes.isEmpty()) {
                sb.append("  Attributes     :\n");
                diagnosticInfo.attributes.forEach((k, v) ->
                    sb.append(String.format("    %s = %s%n", k, v)));
            }
        }

        if (getCause() != null) {
            sb.append("───────────────────────────────────────────────────────────\n");
            sb.append(" Root Cause\n");
            sb.append("───────────────────────────────────────────────────────────\n");
            sb.append(String.format("  %s%n", getCause().getClass().getSimpleName()));
            sb.append(String.format("  %s%n", getCause().getMessage()));
        }

        sb.append("═══════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    // ==================== Builder Pattern ====================

    public static class Builder {
        private String selector;
        private String operation;
        private String pageUrl = "unknown";
        private String elementState = "unknown";
        private DiagnosticInfo diagnosticInfo;
        private Throwable cause;
        private String customMessage;

        public Builder selector(String selector) { this.selector = selector; return this; }
        public Builder operation(String operation) { this.operation = operation; return this; }
        public Builder pageUrl(String pageUrl) { this.pageUrl = pageUrl; return this; }
        public Builder elementState(String elementState) { this.elementState = elementState; return this; }
        public Builder diagnosticInfo(DiagnosticInfo info) { this.diagnosticInfo = info; return this; }
        public Builder cause(Throwable cause) { this.cause = cause; return this; }
        public Builder customMessage(String message) { this.customMessage = message; return this; }

        public ElementOperationException build() {
            String message = customMessage != null ? customMessage :
                String.format("Operation [%s] failed on element [%s]", operation, selector);
            return new ElementOperationException(message, cause, selector, operation,
                pageUrl, elementState, diagnosticInfo);
        }
    }

    public static Builder builder() { return new Builder(); }

    /**
     * 快速创建异常（用于 waitFor* 等方法，无需 Builder）
     */
    public ElementOperationException(String operation, String selector, String message) {
        this(message, null, selector, operation, "unknown", "unknown", null);
    }

    /**
     * 快速创建异常（带原因）
     */
    public ElementOperationException(String operation, String selector, String message, Throwable cause) {
        this(message, cause, selector, operation, "unknown", "unknown", null);
    }

    // ==================== Diagnostic Info ====================

    public static class DiagnosticInfo {
        private boolean existsInDom;
        private boolean isVisible;
        private boolean isEnabled;
        private boolean isEditable;
        private int elementCount;
        private int retryCount;
        private String tagName;
        private java.util.Map<String, String> attributes;

        public DiagnosticInfo existsInDom(boolean val) { this.existsInDom = val; return this; }
        public DiagnosticInfo isVisible(boolean val) { this.isVisible = val; return this; }
        public DiagnosticInfo isEnabled(boolean val) { this.isEnabled = val; return this; }
        public DiagnosticInfo isEditable(boolean val) { this.isEditable = val; return this; }
        public DiagnosticInfo elementCount(int val) { this.elementCount = val; return this; }
        public DiagnosticInfo retryCount(int val) { this.retryCount = val; return this; }
        public DiagnosticInfo tagName(String val) { this.tagName = val; return this; }
        public DiagnosticInfo attributes(java.util.Map<String, String> val) { this.attributes = val; return this; }

        // Getters
        public boolean existsInDom() { return existsInDom; }
        public boolean isVisible() { return isVisible; }
        public boolean isEnabled() { return isEnabled; }
        public boolean isEditable() { return isEditable; }
        public int elementCount() { return elementCount; }
        public int retryCount() { return retryCount; }
        public String tagName() { return tagName; }
        public java.util.Map<String, String> attributes() { return attributes; }

        public static DiagnosticInfo create() { return new DiagnosticInfo(); }
    }
}
