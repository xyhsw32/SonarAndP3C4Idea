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
import com.intellij.codeInspection.reference.RefManager;
import com.intellij.codeInspection.reference.RefManagerImpl;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.encoding.EncodingProjectManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import ex.ProblemTreeNodeData;
import icons.SonarLintIcons;
import org.jetbrains.annotations.NotNull;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.rule.Rule;
import org.sonarlint.intellij.actions.SonarConfigureProject;
import org.sonarlint.intellij.analysis.DefaultClientInputFile;
import org.sonarlint.intellij.analysis.P3cAnalysisUtils;
import org.sonarlint.intellij.analysis.RuleData;
import org.sonarlint.intellij.analysis.SonarLintJob;
import org.sonarlint.intellij.config.project.SonarLintProjectSettings;
import org.sonarlint.intellij.exception.InvalidBindingException;
import org.sonarlint.intellij.issue.LiveIssue;
import org.sonarlint.intellij.issue.SonarIssue;
import org.sonarlint.intellij.issue.SonarResult;
import org.sonarlint.intellij.messages.P3cAnalysisResultListener;
import org.sonarlint.intellij.util.SonarLintAppUtils;
import org.sonarlint.intellij.util.SonarLintUtils;
import org.sonarsource.sonarlint.core.analyzer.issue.DefaultClientIssue;
import org.sonarsource.sonarlint.core.client.api.exceptions.CanceledException;
import org.sonarsource.sonarlint.core.container.analysis.filesystem.DefaultTextPointer;
import org.sonarsource.sonarlint.core.container.analysis.filesystem.DefaultTextRange;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
//        inspectionToolWrappers = filtSonarActiveRule(project, inspectionToolWrappers);
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

    private static List<InspectionToolWrapper<?, ?>> filtSonarActiveRule(Collection<String> ruleList, List<InspectionToolWrapper<?, ?>> inspectionToolWrappers) {
        inspectionToolWrappers = inspectionToolWrappers.stream().filter(inspectionToolWrapper -> {
            AliBaseInspection aliBaseInspection = (AliBaseInspection) inspectionToolWrapper.getTool();
            String ruleName = aliBaseInspection.ruleName();
            return ruleList.contains(ruleName);
        }).collect(Collectors.toList());
        return inspectionToolWrappers;
    }

    public static void scanFile(Project project, SonarLintJob job, ProgressIndicator indicator,Map<VirtualFile, Collection<LiveIssue>> issues)  {
        List<VirtualFile> virtualFiles = job.allFiles().collect(Collectors.toList());
        indicator.setText("Running P3c Analysis for "+ virtualFiles.size()+ "files");
        AnalysisScope analysisScope= new AnalysisScope(project, new ArrayList<>(virtualFiles));
        InspectionManagerEx inspectionManagerEx = (InspectionManagerEx) InspectionManager.getInstance(project);
        GlobalInspectionContextImpl globalInspectionContext = new PmdGlobalInspectionContextImpl(inspectionManagerEx.getProject(), inspectionManagerEx.getContentManager(),false);
        RefManager refManager = globalInspectionContext.getRefManager();
        ((RefManagerImpl)refManager).inspectionReadActionStarted();
        List<InspectionToolWrapper<?, ?>> inspectionToolWrappers = Inspections.INSTANCE.aliInspections(project, inspectionToolWrapper -> inspectionToolWrapper.getTool() instanceof AliBaseInspection);
        RuleData ruleData = getRuleData(project);
        if (ruleData==null){
            return;
        }
        Set<String> ruleSet = ruleData.getActiveRuleMap().keySet();
        inspectionToolWrappers = filtSonarActiveRule(ruleSet, inspectionToolWrappers);
        analysisScope.setIncludeTestSource(false);
        analysisScope.setSearchInLibraries(true);
        if (inspectionToolWrappers.isEmpty()){
            if (ruleData.isConnection()){
                Notifications.Bus.notify(new Notification("Sonarlint", SonarLintIcons.ICON_SONARQUBE_16,"Sonarlint","Sonar server does not contain p3c rules","Please check the sonar server configuration", NotificationType.ERROR,null).addAction(new SonarConfigureProject("Sonarqube config")));
            }
            return;
        }
        InspectionProfileImpl profile = InspectionProfileService.INSTANCE.createSimpleProfile(inspectionToolWrappers, inspectionManagerEx, null);
        InspectionProfileService.INSTANCE.setExternalProfile(profile, globalInspectionContext);
        analysisScope.setIncludeTestSource(false);
        analysisScope.setSearchInLibraries(true);
        SearchScope searchScope = ReadAction.compute(analysisScope::toSearchScope);
        List<Tools> toolsList = globalInspectionContext.getUsedTools();
        for (Map.Entry<Module, Collection<VirtualFile>> e : job.filesPerModule().entrySet()) {
            Module module = e.getKey();
            for (VirtualFile virtualFile:e.getValue()){
                String relativePath = SonarLintAppUtils.getRelativePathForAnalysis(module, virtualFile);
                DefaultClientInputFile defaultClientInputFile = P3cAnalysisUtils.createClientInputFile(virtualFile, relativePath, getEncoding(virtualFile, project));
                Runnable runnable = () -> {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
                    Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
                    TextRange textRange = getEffectiveRange(searchScope, psiFile);
                    LocalInspectionsCustomPass pass = new LocalInspectionsCustomPass(psiFile, document, textRange.getStartOffset(),
                            textRange.getEndOffset(), LocalInspectionsCustomPass.EMPTY_PRIORITY_RANGE, true,
                            HighlightInfoProcessor.getEmpty(), INSPECT_INJECTED_PSI);
                    pass.doInspectInBatch(document,virtualFile,psiFile,defaultClientInputFile,ruleData,globalInspectionContext,inspectionManagerEx, getWrappersFromTools(toolsList, psiFile, true, wrapper -> !(wrapper.getTool() instanceof ExternalAnnotatorBatchInspection)),project,issues);
                };
                indicator.setText("P3c Scan Current file name: " + virtualFile.getName());
                ApplicationManager.getApplication().runReadAction(runnable);
                if (indicator.isCanceled() || project.isDisposed() || Thread.currentThread().isInterrupted()) {
                    project.getMessageBus().syncPublisher(P3cAnalysisResultListener.P3C_ANALYSIS_TOPIC).canceled(project);
                    throw new CanceledException();
                }
            }
        }
        ((RefManagerImpl)refManager).inspectionReadActionFinished();
        project.getMessageBus().syncPublisher(P3cAnalysisResultListener.P3C_ANALYSIS_TOPIC).completed(project);
    }

    private static RuleData getRuleData(Project project) {
        ProjectBindingManager projectBindingManager = SonarLintUtils.getService(project, ProjectBindingManager.class);
        SonarLintFacade sonarLintFacade = null;
        try {
            sonarLintFacade = projectBindingManager.getFacade(true);
        } catch (InvalidBindingException e) {
            e.printStackTrace();
        }
        SonarLintProjectSettings sonarLintProjectSettings = getSettingsFor(project);
        if (sonarLintFacade instanceof ConnectedSonarLintFacade){
            ConnectedSonarLintFacade connectedSonarLintFacade = (ConnectedSonarLintFacade) sonarLintFacade;
            return connectedSonarLintFacade.getRuleData(sonarLintProjectSettings.getProjectKey());
        }else{
            StandaloneSonarLintFacade standaloneSonarLintFacade = (StandaloneSonarLintFacade) sonarLintFacade;
            Notifications.Bus.notify(new Notification("Sonarlint", SonarLintIcons.ICON_SONARQUBE_16,"Sonarlint","Sonar server is not configured","Please configure the sonar server", NotificationType.ERROR,null).addAction(new SonarConfigureProject("Sonarqube config")));
            return standaloneSonarLintFacade.getRuleData();
        }
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


    public static List<ProblemTreeNodeData> transCommonScanResultFromP3cResult() {
        return null;
    }

    private static Charset getEncoding(VirtualFile f,Project project) {
        EncodingProjectManager encodingProjectManager = EncodingProjectManager.getInstance(project);
        Charset encoding = encodingProjectManager.getEncoding(f, true);
        if (encoding != null) {
            return encoding;
        }
        return Charset.defaultCharset();
    }

    public static List<SonarResult> transformLiveIssueMapToSonarResult(Map<VirtualFile, Collection<LiveIssue>> issues){
        List<SonarResult> sonarResultList = new ArrayList<>();
        for (Map.Entry<VirtualFile, Collection<LiveIssue>> entry:issues.entrySet()){
            VirtualFile v = entry.getKey();
            Collection<LiveIssue> issueList = entry.getValue();
            SonarResult sonarResult = new SonarResult();
            sonarResult.setFilePath(v.getPath());
            List<SonarIssue> sonarIssueList=new ArrayList<>();
            sonarResult.setSonarIssueList(sonarIssueList);
            for (LiveIssue liveIssue:issueList){
                SonarIssue sonarIssue = new SonarIssue();
                RangeMarker range = liveIssue.getRange();
                sonarIssue.setType(liveIssue.getType());
                sonarIssue.setSeverity(liveIssue.getSeverity());
                sonarIssue.setDescriptionTemplate(liveIssue.getMessage());
                sonarIssue.setRuleName(liveIssue.getRuleName());
                sonarIssue.setRuleKey(liveIssue.getRuleKey());
                sonarIssue.setStartOffset(range.getStartOffset());
                sonarIssue.setEndOffset(range.getEndOffset());
                sonarIssueList.add(sonarIssue);
            }
            sonarResultList.add(sonarResult);
        }
        return sonarResultList;
    }
    public static Map<VirtualFile, Collection<LiveIssue>>  transformSonarResultToLiveIssueMap(Project project,List<SonarResult> sonarResultList){
        Map<VirtualFile, Collection<LiveIssue>> issues=new HashMap<>();
        DefaultClientInputFile defaultClientInputFile=null;
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        PsiManager psiManager = PsiManager.getInstance(project);
        RuleData ruleData = getRuleData(project);
        Map<String, Rule> ruleMap = ruleData.getRuleMap();
        Map<String, ActiveRule> activeRuleMap = ruleData.getActiveRuleMap();
        for (SonarResult sonarResult:sonarResultList){
            String filePath = sonarResult.getFilePath();
            List<SonarIssue> sonarIssueList = sonarResult.getSonarIssueList();
            VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath);
            if (virtualFile!=null){
                if (defaultClientInputFile==null){
                    defaultClientInputFile= P3cAnalysisUtils.createClientInputFile(virtualFile, sonarResult.getRelativePath(), getEncoding(virtualFile, project));
                }
                PsiFile psiFile = psiManager.findFile(virtualFile);
                List<LiveIssue> liveIssueList=new ArrayList<>();
                for (SonarIssue sonarIssue:sonarIssueList){
                    Document doc = docManager.getDocument(psiFile);
                    org.sonarsource.sonarlint.core.client.api.common.TextRange sonarTextRange = new org.sonarsource.sonarlint.core.client.api.common.TextRange(0,0,1,1);
                    DefaultTextRange defaultTextRange = new DefaultTextRange(new DefaultTextPointer(0, 0), new DefaultTextPointer(1, 1));
                    String ruleKey;
                    if (sonarIssue.getRuleKey().contains("pmd")){
                        String[] split = sonarIssue.getRuleKey().split(":");
                        ruleKey=split[1];
                    }else{
                        ruleKey=sonarIssue.getRuleKey();
                    }
                    DefaultClientIssue defaultClientIssue = new DefaultClientIssue(sonarIssue.getSeverity(), sonarIssue.getType(), activeRuleMap.get(ruleKey), ruleMap.get(ruleKey), sonarIssue.getDescriptionTemplate(), defaultTextRange, defaultClientInputFile, new ArrayList<>());
                    RangeMarker rangeMarker = doc.createRangeMarker(sonarIssue.getStartOffset(), sonarIssue.getEndOffset());
                    LiveIssue liveIssue = new LiveIssue(defaultClientIssue, psiFile, rangeMarker, null);
                    liveIssueList.add(liveIssue);
                }
                issues.put(virtualFile,liveIssueList);
            }
        }
        return issues;
    }
}
