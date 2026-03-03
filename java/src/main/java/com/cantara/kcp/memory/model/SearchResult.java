package com.cantara.kcp.memory.model;

/**
 * A session returned by a full-text or filtered search.
 */
public class SearchResult {

    private String sessionId;
    private String projectDir;
    private String gitBranch;
    private String slug;
    private String model;
    private String startedAt;
    private String endedAt;
    private int turnCount;
    private int toolCallCount;
    private String firstMessage;
    private double rank;        // FTS rank (lower = more relevant), 0 if non-FTS

    public SearchResult() {}

    public String getSessionId()          { return sessionId; }
    public void setSessionId(String v)    { this.sessionId = v; }

    public String getProjectDir()         { return projectDir; }
    public void setProjectDir(String v)   { this.projectDir = v; }

    public String getGitBranch()          { return gitBranch; }
    public void setGitBranch(String v)    { this.gitBranch = v; }

    public String getSlug()               { return slug; }
    public void setSlug(String v)         { this.slug = v; }

    public String getModel()              { return model; }
    public void setModel(String v)        { this.model = v; }

    public String getStartedAt()          { return startedAt; }
    public void setStartedAt(String v)    { this.startedAt = v; }

    public String getEndedAt()            { return endedAt; }
    public void setEndedAt(String v)      { this.endedAt = v; }

    public int getTurnCount()             { return turnCount; }
    public void setTurnCount(int v)       { this.turnCount = v; }

    public int getToolCallCount()         { return toolCallCount; }
    public void setToolCallCount(int v)   { this.toolCallCount = v; }

    public String getFirstMessage()       { return firstMessage; }
    public void setFirstMessage(String v) { this.firstMessage = v; }

    public double getRank()               { return rank; }
    public void setRank(double v)         { this.rank = v; }
}
