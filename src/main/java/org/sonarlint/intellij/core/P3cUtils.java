package org.sonarlint.intellij.core;

import com.alibaba.p3c.idea.action.PmdGlobalInspectionContextImpl;
import com.alibaba.p3c.idea.compatible.inspection.InspectionProfileService;
import com.alibaba.p3c.idea.compatible.inspection.Inspections;
import com.alibaba.p3c.idea.inspection.AliBaseInspection;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.daemon.impl.HighlightInfoProcessor;
import com.intellij.codeInsight.daemon.impl.LocalInspectionsCustomPass;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.Tools;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import icons.SonarLintIcons;
import org.jetbrains.annotations.NotNull;
import org.sonarlint.intellij.actions.SonarConfigureProject;
import org.sonarlint.intellij.config.project.SonarLintProjectSettings;
import org.sonarlint.intellij.exception.InvalidBindingException;
import org.sonarlint.intellij.util.SonarLintUtils;
import org.sonarsource.sonarlint.core.client.api.exceptions.CanceledException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.sonarlint.intellij.config.Settings.getSettingsFor;

public class P3cUtils {
    private static final boolean INSPECT_INJECTED_PSI = SystemProperties.getBooleanProperty("idea.batch.inspections.inspect.injected.psi", true);

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

    public  static void scanFile(Project project, Collection<VirtualFile> virtualFiles, ProgressIndicator indicator)  {
        indicator.setText("Running P3c Analysis for "+ virtualFiles.size()+ "files");
        AnalysisScope analysisScope= new AnalysisScope(project, new ArrayList<>(virtualFiles));
        InspectionManagerEx inspectionManagerEx = (InspectionManagerEx) InspectionManager.getInstance(project);
        GlobalInspectionContextImpl globalInspectionContext = new PmdGlobalInspectionContextImpl(inspectionManagerEx.getProject(), inspectionManagerEx.getContentManager(),false);
        List<InspectionToolWrapper<?, ?>> inspectionToolWrappers = Inspections.INSTANCE.aliInspections(project, inspectionToolWrapper -> inspectionToolWrapper.getTool() instanceof AliBaseInspection);
//        inspectionToolWrappers = filtSonarActiveRule(project, inspectionToolWrappers);
        analysisScope.setIncludeTestSource(false);
        analysisScope.setSearchInLibraries(true);
        if (inspectionToolWrappers.isEmpty()){
            Notifications.Bus.notify(new Notification("Sonarlint", SonarLintIcons.ICON_SONARQUBE_16,"Sonarlint","Sonar server does not contain p3c rules","Please check the sonar server configuration", NotificationType.ERROR,null).addAction(new SonarConfigureProject("Sonarqube config")));
            return;
        }
        InspectionProfileImpl profile = InspectionProfileService.INSTANCE.createSimpleProfile(inspectionToolWrappers, inspectionManagerEx, null);
        InspectionProfileService.INSTANCE.setExternalProfile(profile, globalInspectionContext);
        analysisScope.setIncludeTestSource(false);
        analysisScope.setSearchInLibraries(true);
        SearchScope searchScope = ReadAction.compute(analysisScope::toSearchScope);
        List<Tools> toolsList = globalInspectionContext.getUsedTools();
        for (VirtualFile virtualFile : virtualFiles) {
            Runnable runnable = () -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
                Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
                TextRange textRange = getEffectiveRange(searchScope, psiFile);
                LocalInspectionsCustomPass pass = new LocalInspectionsCustomPass(psiFile, document, textRange.getStartOffset(),
                        textRange.getEndOffset(), LocalInspectionsCustomPass.EMPTY_PRIORITY_RANGE, true,
                        HighlightInfoProcessor.getEmpty(), INSPECT_INJECTED_PSI);
                pass.doInspectInBatch(globalInspectionContext,inspectionManagerEx, getWrappersFromTools(toolsList, psiFile, true, wrapper -> !(wrapper.getTool() instanceof ExternalAnnotatorBatchInspection)));
            };
            indicator.setText("P3c Scan Current file name: " + virtualFile.getName());
            ApplicationManager.getApplication().runReadAction(runnable);
            if (indicator.isCanceled() || project.isDisposed() || Thread.currentThread().isInterrupted()) {
              //TODO 通知终止完成
                throw new CanceledException();
            }
        }
    }


    private static boolean process(PsiFile file) {
        return true;
    }

    private static TextRange getEffectiveRange(SearchScope searchScope, PsiFile file) {
        if (searchScope instanceof LocalSearchScope) {
            List<PsiElement> scopeFileElements = ContainerUtil.filter(((LocalSearchScope)searchScope).getScope(), e -> e.getContainingFile() == file);
            if (!scopeFileElements.isEmpty()) {
                int start = -1;
                int end = -1;
                for (PsiElement scopeElement : scopeFileElements) {
                    TextRange elementRange = scopeElement.getTextRange();
                    start = start == -1 ? elementRange.getStartOffset() : Math.min(elementRange.getStartOffset(), start);
                    end = end == -1 ? elementRange.getEndOffset() : Math.max(elementRange.getEndOffset(), end);
                }
                return new TextRange(start, end);
            }
        }
        return new TextRange(0, file.getTextLength());
    }

    private static <T extends InspectionToolWrapper> List<T> getWrappersFromTools(@NotNull List<? extends Tools> localTools,
                                                                                  @NotNull PsiFile file,
                                                                                  boolean includeDoNotShow,
                                                                                  @NotNull Predicate<? super T> filter) {
        return ContainerUtil.mapNotNull(localTools, tool -> {
            //noinspection unchecked
            T unwrapped = (T)tool.getEnabledTool(file, includeDoNotShow);
            if (unwrapped == null) return null;
            return filter.test(unwrapped) ? unwrapped : null;
        });
    }
}
