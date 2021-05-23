package org.sonarlint.intellij.messages;

import com.intellij.codeInsight.daemon.impl.LocalInspectionsCustomPass;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;

import java.util.List;

public interface P3cAnalysisResultListener {
    Topic<P3cAnalysisResultListener> P3C_ANALYSIS_TOPIC = Topic.create("P3c Analysis", P3cAnalysisResultListener.class);


    void completed(Project project);

    void canceled(Project project);

    void fileResult(Project project,List<LocalInspectionsCustomPass.InspectionResult> resultList);
}
