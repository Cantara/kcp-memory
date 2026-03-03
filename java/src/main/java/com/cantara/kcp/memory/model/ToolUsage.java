package com.cantara.kcp.memory.model;

/**
 * One tool invocation recorded within a session.
 */
public class ToolUsage {

    private String sessionId;
    private String toolName;
    private String toolInput;   // JSON blob
    private String occurredAt;  // ISO-8601

    public ToolUsage() {}

    public ToolUsage(String sessionId, String toolName, String toolInput, String occurredAt) {
        this.sessionId  = sessionId;
        this.toolName   = toolName;
        this.toolInput  = toolInput;
        this.occurredAt = occurredAt;
    }

    public String getSessionId()          { return sessionId; }
    public void setSessionId(String v)    { this.sessionId = v; }

    public String getToolName()           { return toolName; }
    public void setToolName(String v)     { this.toolName = v; }

    public String getToolInput()          { return toolInput; }
    public void setToolInput(String v)    { this.toolInput = v; }

    public String getOccurredAt()         { return occurredAt; }
    public void setOccurredAt(String v)   { this.occurredAt = v; }
}
