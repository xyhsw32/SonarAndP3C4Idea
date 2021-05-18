package org.sonarlint.intellij.core;

import com.alibaba.p3c.idea.compatible.inspection.InspectionProfileService;
import com.alibaba.p3c.idea.compatible.inspection.Inspections;
import com.alibaba.p3c.idea.inspection.AliBaseInspection;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import icons.SonarLintIcons;
import org.sonarlint.intellij.config.project.SonarLintProjectSettings;
import org.sonarlint.intellij.exception.InvalidBindingException;
import org.sonarlint.intellij.util.SonarLintUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.sonarlint.intellij.config.Settings.getSettingsFor;

public class P3cUtils {
    public static void executeInspection(Project project, Collection<VirtualFile> virtualFiles){
        AnalysisScope analysisScope= new AnalysisScope(project, new ArrayList<>(virtualFiles));
        InspectionManagerEx inspectionManagerEx = (InspectionManagerEx) InspectionManager.getInstance(project);
        GlobalInspectionContextImpl globalInspectionContext = new GlobalInspectionContextImpl(inspectionManagerEx.getProject(), inspectionManagerEx.getContentManager());
        List<InspectionToolWrapper<?, ?>> inspectionToolWrappers = Inspections.INSTANCE.aliInspections(project, inspectionToolWrapper -> inspectionToolWrapper.getTool() instanceof AliBaseInspection);
        ProjectBindingManager projectBindingManager = SonarLintUtils.getService(project, ProjectBindingManager.class);
        inspectionToolWrappers = filtSonarActiveRule(project, inspectionToolWrappers, projectBindingManager);
        analysisScope.setIncludeTestSource(false);
        analysisScope.setSearchInLibraries(true);
        if (inspectionToolWrappers.isEmpty()){
            Notifications.Bus.notify(new Notification("Sonarlint", SonarLintIcons.ICON_SONARQUBE_16,"Sonarlint","sonarqube server setting have no p3c rule","please confirm sonarqube setting", NotificationType.ERROR,null));
            return;
        }
        InspectionProfileImpl profile = InspectionProfileService.INSTANCE.createSimpleProfile(inspectionToolWrappers, inspectionManagerEx, null);
        InspectionProfileService.INSTANCE.setExternalProfile(profile, globalInspectionContext);
        globalInspectionContext.doInspections(analysisScope);
    }

    private static List<InspectionToolWrapper<?, ?>> filtSonarActiveRule(Project project, List<InspectionToolWrapper<?, ?>> inspectionToolWrappers, ProjectBindingManager projectBindingManager) {
        try {
            SonarLintFacade sonarLintFacade = projectBindingManager.getFacade(true);
            if (sonarLintFacade instanceof ConnectedSonarLintFacade){
                ConnectedSonarLintFacade connectedSonarLintFacade = (ConnectedSonarLintFacade) sonarLintFacade;
                SonarLintProjectSettings sonarLintProjectSettings = getSettingsFor(project);
                List<String> ruleList = connectedSonarLintFacade.getActiveList(sonarLintProjectSettings.getProjectKey());
                inspectionToolWrappers = inspectionToolWrappers.stream().filter(inspectionToolWrapper -> {
                    LocalInspectionTool localInspectionTool = (LocalInspectionTool) inspectionToolWrapper.getTool();
                    String shortName = localInspectionTool.getShortName();
                    if (shortName.startsWith("Alibaba")){
                        shortName=shortName.substring(7);
                    }
                    if (shortName.startsWith("Ali")){
                        shortName=shortName.substring(3);
                    }
                    return ruleList.contains(shortName);
                }).collect(Collectors.toList());
            }else{
                Notifications.Bus.notify(new Notification("Sonarlint", SonarLintIcons.ICON_SONARQUBE_16,"Sonarlint","sonarqube server not configure","please config sonarqube setting", NotificationType.ERROR,null));
            }
        } catch (InvalidBindingException e) {
            e.printStackTrace();
        }
        return inspectionToolWrappers;
    }
}
