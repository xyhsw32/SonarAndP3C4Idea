package org.sonarlint.intellij.core;

import com.alibaba.p3c.idea.action.PmdGlobalInspectionContextImpl;
import com.alibaba.p3c.idea.compatible.inspection.InspectionProfileService;
import com.alibaba.p3c.idea.compatible.inspection.Inspections;
import com.alibaba.p3c.idea.inspection.AliBaseInspection;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.InspectionManager;
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
import org.sonarlint.intellij.actions.SonarConfigureProject;
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
        GlobalInspectionContextImpl globalInspectionContext = new PmdGlobalInspectionContextImpl(inspectionManagerEx.getProject(), inspectionManagerEx.getContentManager(),false);
        List<InspectionToolWrapper<?, ?>> inspectionToolWrappers = Inspections.INSTANCE.aliInspections(project, inspectionToolWrapper -> inspectionToolWrapper.getTool() instanceof AliBaseInspection);
        inspectionToolWrappers = filtSonarActiveRule(project, inspectionToolWrappers);
        analysisScope.setIncludeTestSource(false);
        analysisScope.setSearchInLibraries(true);
        if (inspectionToolWrappers.isEmpty()){
            Notifications.Bus.notify(new Notification("Sonarlint", SonarLintIcons.ICON_SONARQUBE_16,"Sonarlint","Sonar server does not contain p3c rules","Please check the sonar server configuration", NotificationType.ERROR,null).addAction(new SonarConfigureProject("Sonarqube config")));
            return;
        }
        InspectionProfileImpl profile = InspectionProfileService.INSTANCE.createSimpleProfile(inspectionToolWrappers, inspectionManagerEx, null);
        InspectionProfileService.INSTANCE.setExternalProfile(profile, globalInspectionContext);
        globalInspectionContext.doInspections(analysisScope);
    }

    private static List<InspectionToolWrapper<?, ?>> filtSonarActiveRule(Project project, List<InspectionToolWrapper<?, ?>> inspectionToolWrappers) {
        try {
            ProjectBindingManager projectBindingManager = SonarLintUtils.getService(project, ProjectBindingManager.class);
            SonarLintFacade sonarLintFacade = projectBindingManager.getFacade(true);
            if (sonarLintFacade instanceof ConnectedSonarLintFacade){
                ConnectedSonarLintFacade connectedSonarLintFacade = (ConnectedSonarLintFacade) sonarLintFacade;
                SonarLintProjectSettings sonarLintProjectSettings = getSettingsFor(project);
                List<String> ruleList = connectedSonarLintFacade.getActiveList(sonarLintProjectSettings.getProjectKey());
                inspectionToolWrappers = inspectionToolWrappers.stream().filter(inspectionToolWrapper -> {
                    AliBaseInspection aliBaseInspection = (AliBaseInspection) inspectionToolWrapper.getTool();
                    String ruleName = aliBaseInspection.ruleName();
                    return ruleList.contains(ruleName);
                }).collect(Collectors.toList());
            }else{
                Notifications.Bus.notify(new Notification("Sonarlint", SonarLintIcons.ICON_SONARQUBE_16,"Sonarlint","Sonar server is not configured","Please configure the sonar server", NotificationType.ERROR,null).addAction(new SonarConfigureProject("Sonarqube config")));
            }
        } catch (InvalidBindingException e) {
            e.printStackTrace();
        }
        return inspectionToolWrappers;
    }
}
