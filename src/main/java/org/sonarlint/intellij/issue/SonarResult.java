package org.sonarlint.intellij.issue;

import java.util.List;

public class SonarResult {
    private String filePath;
    private List<SonarIssue> sonarIssueList;
    private String workOrderId;
    private String relativePath;

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public List<SonarIssue> getSonarIssueList() {
        return sonarIssueList;
    }

    public void setSonarIssueList(List<SonarIssue> sonarIssueList) {
        this.sonarIssueList = sonarIssueList;
    }

    public String getWorkOrderId() {
        return workOrderId;
    }

    public void setWorkOrderId(String workOrderId) {
        this.workOrderId = workOrderId;
    }
}
