package com.cantara.kcp.memory.model;

import java.util.List;

/**
 * Represents one Claude Code session transcript, indexed from a .jsonl file.
 */
public class Session {

    private String sessionId;
    private String projectDir;
    private String gitBranch;
    private String slug;
    private String model;
    private String startedAt;
    private String endedAt;
    private int turnCount;
    private int toolCallCount;
    private List<String> toolNames;
    private List<String> files;
    private String firstMessage;
    private String allUserText;
    private String scannedAt;

    public Session() {}

    public String getSessionId()             { return sessionId; }
    public void setSessionId(String v)       { this.sessionId = v; }

    public String getProjectDir()            { return projectDir; }
    public void setProjectDir(String v)      { this.projectDir = v; }

    public String getGitBranch()             { return gitBranch; }
    public void setGitBranch(String v)       { this.gitBranch = v; }

    public String getSlug()                  { return slug; }
    public void setSlug(String v)            { this.slug = v; }

    public String getModel()                 { return model; }
    public void setModel(String v)           { this.model = v; }

    public String getStartedAt()             { return startedAt; }
    public void setStartedAt(String v)       { this.startedAt = v; }

    public String getEndedAt()               { return endedAt; }
    public void setEndedAt(String v)         { this.endedAt = v; }

    public int getTurnCount()                { return turnCount; }
    public void setTurnCount(int v)          { this.turnCount = v; }

    public int getToolCallCount()            { return toolCallCount; }
    public void setToolCallCount(int v)      { this.toolCallCount = v; }

    public List<String> getToolNames()       { return toolNames; }
    public void setToolNames(List<String> v) { this.toolNames = v; }

    public List<String> getFiles()           { return files; }
    public void setFiles(List<String> v)     { this.files = v; }

    public String getFirstMessage()          { return firstMessage; }
    public void setFirstMessage(String v)    { this.firstMessage = v; }

    public String getAllUserText()            { return allUserText; }
    public void setAllUserText(String v)     { this.allUserText = v; }

    public String getScannedAt()             { return scannedAt; }
    public void setScannedAt(String v)       { this.scannedAt = v; }
}
