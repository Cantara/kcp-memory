package com.cantara.kcp.memory.model;

import java.util.List;

/**
 * Represents one Claude Code subagent session transcript,
 * indexed from a subagents/agent-*.jsonl file.
 */
public class AgentSession {

    private String agentId;
    private String parentSessionId;
    private String agentSlug;
    private String projectDir;
    private String cwd;
    private String model;
    private int turnCount;
    private int toolCallCount;
    private List<String> toolNames;
    private String firstMessage;
    private String allUserText;
    private String firstSeenAt;
    private String lastUpdatedAt;
    private int messageCount;
    private String scannedAt;

    public AgentSession() {}

    public String getAgentId()                  { return agentId; }
    public void setAgentId(String v)            { this.agentId = v; }

    public String getParentSessionId()          { return parentSessionId; }
    public void setParentSessionId(String v)    { this.parentSessionId = v; }

    public String getAgentSlug()                { return agentSlug; }
    public void setAgentSlug(String v)          { this.agentSlug = v; }

    public String getProjectDir()               { return projectDir; }
    public void setProjectDir(String v)         { this.projectDir = v; }

    public String getCwd()                      { return cwd; }
    public void setCwd(String v)                { this.cwd = v; }

    public String getModel()                    { return model; }
    public void setModel(String v)              { this.model = v; }

    public int getTurnCount()                   { return turnCount; }
    public void setTurnCount(int v)             { this.turnCount = v; }

    public int getToolCallCount()               { return toolCallCount; }
    public void setToolCallCount(int v)         { this.toolCallCount = v; }

    public List<String> getToolNames()          { return toolNames; }
    public void setToolNames(List<String> v)    { this.toolNames = v; }

    public String getFirstMessage()             { return firstMessage; }
    public void setFirstMessage(String v)       { this.firstMessage = v; }

    public String getAllUserText()               { return allUserText; }
    public void setAllUserText(String v)        { this.allUserText = v; }

    public String getFirstSeenAt()              { return firstSeenAt; }
    public void setFirstSeenAt(String v)        { this.firstSeenAt = v; }

    public String getLastUpdatedAt()            { return lastUpdatedAt; }
    public void setLastUpdatedAt(String v)      { this.lastUpdatedAt = v; }

    public int getMessageCount()                { return messageCount; }
    public void setMessageCount(int v)          { this.messageCount = v; }

    public String getScannedAt()                { return scannedAt; }
    public void setScannedAt(String v)          { this.scannedAt = v; }
}
