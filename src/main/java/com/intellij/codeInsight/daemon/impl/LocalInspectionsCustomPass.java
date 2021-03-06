package com.intellij.codeInsight.daemon.impl;


import com.alibaba.p3c.idea.inspection.AliBaseInspection;
import com.alibaba.p3c.idea.inspection.AliPmdInspection;
import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeHighlighting.Pass;
import com.intellij.codeInsight.daemon.DaemonBundle;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager;
import com.intellij.codeInsight.daemon.impl.quickfix.QuickFixAction;
import com.intellij.codeInsight.intention.EmptyIntentionAction;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.HintAction;
import com.intellij.codeInspection.InspectionEngine;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.LanguageInspectionSuppressors;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemDescriptorBase;
import com.intellij.codeInspection.ProblemDescriptorUtil;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.QuickFix;
import com.intellij.codeInspection.RedundantSuppressInspection;
import com.intellij.codeInspection.RedundantSuppressionDetector;
import com.intellij.codeInspection.ex.BatchModeDescriptorsUtil;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper;
import com.intellij.codeInspection.ex.InspectionElementsMerger;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionProfileWrapper;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.codeInspection.ex.ProblemDescriptorImpl;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.ui.InspectionToolPresentation;
import com.intellij.concurrency.JobLauncher;
import com.intellij.diagnostic.PluginException;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.Language;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.annotation.ProblemGroup;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementVisitor;
import com.intellij.util.AstLoadingFilter;
import com.intellij.util.CommonProcessors;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Processor;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Interner;
import com.intellij.util.containers.SmartHashSet;
import com.intellij.util.containers.WeakInterner;
import com.intellij.util.ui.StartupUiUtil;
import com.intellij.xml.util.XmlStringUtil;
import ex.ProblemTreeNodeData;
import ex.ScanRuleData;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import net.sourceforge.pmd.lang.rule.RuleReference;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.rule.Rule;
import org.sonarlint.intellij.analysis.DefaultClientInputFile;
import org.sonarlint.intellij.analysis.RuleData;
import org.sonarlint.intellij.issue.IssueMatcher;
import org.sonarlint.intellij.issue.LiveIssue;
import org.sonarsource.sonarlint.core.analyzer.issue.DefaultClientIssue;
import org.sonarsource.sonarlint.core.container.analysis.SonarLintRule;
import org.sonarsource.sonarlint.core.container.analysis.filesystem.DefaultTextPointer;
import org.sonarsource.sonarlint.core.container.analysis.filesystem.DefaultTextRange;

public class LocalInspectionsCustomPass extends ProgressableTextEditorHighlightingCustomPass {
    private static final Logger LOG = Logger.getInstance(LocalInspectionsCustomPass.class);
    public static final TextRange EMPTY_PRIORITY_RANGE = TextRange.EMPTY_RANGE;
    private static final Condition<PsiFile> SHOULD_INSPECT_FILTER = file -> HighlightingLevelManager.getInstance(file.getProject()).shouldInspect(file);
    private final TextRange myPriorityRange;
    private final boolean myIgnoreSuppressed;
    private final ConcurrentMap<PsiFile, List<InspectionResult>> result = ContainerUtil.newConcurrentMap();
    private volatile List<HighlightInfo> myInfos = Collections.emptyList();
    private final String myShortcutText;
    private final SeverityRegistrar mySeverityRegistrar;
    private final InspectionProfileWrapper myProfileWrapper;
    private final Map<String, Set<PsiElement>> mySuppressedElements = new ConcurrentHashMap<>();
    private final boolean myInspectInjectedPsi;

    public LocalInspectionsCustomPass(@NotNull PsiFile file,
                                      @Nullable Document document,
                                      int startOffset,
                                      int endOffset,
                                      @NotNull TextRange priorityRange,
                                      boolean ignoreSuppressed,
                                      @NotNull HighlightInfoProcessor highlightInfoProcessor, boolean inspectInjectedPsi) {
        super(file.getProject(), document, getPresentableNameText(), file, null, new TextRange(startOffset, endOffset), true, highlightInfoProcessor);
        assert file.isPhysical() : "can't inspect non-physical file: " + file + "; " + file.getVirtualFile();
        myPriorityRange = priorityRange;
        myIgnoreSuppressed = ignoreSuppressed;
        setId(Pass.LOCAL_INSPECTIONS);

        final KeymapManager keymapManager = KeymapManager.getInstance();
        if (keymapManager != null) {
            final Keymap keymap = keymapManager.getActiveKeymap();
            myShortcutText = "(" + KeymapUtil.getShortcutsText(keymap.getShortcuts(IdeActions.ACTION_SHOW_ERROR_DESCRIPTION)) + ")";
        }
        else {
            myShortcutText = "";
        }
        InspectionProfileImpl profileToUse = ProjectInspectionProfileManager.getInstance(myProject).getCurrentProfile();
        Function<InspectionProfileImpl, InspectionProfileWrapper> custom = file.getUserData(InspectionProfileWrapper.CUSTOMIZATION_KEY);
        myProfileWrapper = custom == null ? new InspectionProfileWrapper(profileToUse) : custom.apply(profileToUse);
        assert myProfileWrapper != null;
        mySeverityRegistrar = myProfileWrapper.getInspectionProfile().getProfileManager().getSeverityRegistrar();
        myInspectInjectedPsi = inspectInjectedPsi;

        // initial guess
        setProgressLimit(300 * 2);
    }

    @NotNull
    private PsiFile getFile() {
        return myFile;
    }

    @Override
    protected void collectInformationWithProgress(@NotNull ProgressIndicator progress) {
        try {
            if (!HighlightingLevelManager.getInstance(myProject).shouldInspect(getFile())) return;
            inspect(getInspectionTools(myProfileWrapper), InspectionManager.getInstance(myProject), true, progress);
        }
        finally {
            disposeDescriptors();
        }
    }

    private void disposeDescriptors() {
        result.clear();
    }

    private static final Set<String> ourToolsWithInformationProblems = new HashSet<>();

    public void doInspectInBatch(Document document,VirtualFile virtualFile,PsiFile psiFile,DefaultClientInputFile defaultClientInputFile, RuleData ruleData, @NotNull final GlobalInspectionContextImpl context,
                                 @NotNull final InspectionManager iManager,
                                 @NotNull final List<? extends LocalInspectionToolWrapper> toolWrappers, Project project,Map<VirtualFile, Collection<LiveIssue>> issues) {
        final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
        inspect(new ArrayList<>(toolWrappers), iManager, false, progress);
        addDescriptorsFromInjectedResults(context);
        List<InspectionResult> resultList = result.get(getFile());
        if (resultList == null) {
            return;
        }
        saveDataToSonarLint(document,virtualFile, psiFile, defaultClientInputFile, ruleData, context, project, issues, resultList);
        //TODO result??????
        List<ProblemTreeNodeData> problemTreeNodeDataList = transScanResultData(project, resultList);
        System.out.println(problemTreeNodeDataList);
    }

    private void saveDataToSonarLint(Document document,VirtualFile virtualFile, PsiFile psiFile, DefaultClientInputFile defaultClientInputFile, RuleData ruleData, @NotNull GlobalInspectionContextImpl context, Project project, Map<VirtualFile, Collection<LiveIssue>> issues, List<InspectionResult> resultList) {
        Map<String, Rule> ruleMap = ruleData.getRuleMap();
        Map<String, ActiveRule> activeRuleMap = ruleData.getActiveRuleMap();
        for (InspectionResult inspectionResult : resultList) {
            LocalInspectionToolWrapper toolWrapper = inspectionResult.tool;
            AliBaseInspection aliBaseInspection = (AliBaseInspection) (toolWrapper.getTool());
            InspectionToolPresentation presentation = context.getPresentation(toolWrapper);
            SonarLintRule sonarLintRule = (SonarLintRule) ruleMap.get(aliBaseInspection.ruleName());
            ActiveRule activeRule = activeRuleMap.get(aliBaseInspection.ruleName());
            for (ProblemDescriptor descriptor : inspectionResult.foundProblems) {
                TextRange textRange = descriptor.getStartElement().getTextRange();
                int startOffset;
                int endOffset;
                int startLineNum;
                int endLineNum;
                int lineStartOffset;
                int lineEndOffset;
                if (!activeRule.ruleKey().rule().equals("AvoidCommentBehindStatementRule")){
                     startOffset = textRange.getStartOffset();
                     endOffset = textRange.getEndOffset();
                     startLineNum = document.getLineNumber(startOffset);
                     endLineNum = document.getLineNumber(endOffset);
                     lineStartOffset = document.getLineStartOffset(startLineNum);
                     lineEndOffset = document.getLineStartOffset(endLineNum);
                }else{
                    int lineNumber = descriptor.getLineNumber();
                    startLineNum=lineNumber;
                    endLineNum=lineNumber;
                    lineStartOffset = document.getLineStartOffset(lineNumber);
                    startOffset=lineStartOffset;
                    endOffset = document.getLineEndOffset(lineNumber);
                    lineEndOffset=startOffset;
                }
                org.sonarsource.sonarlint.core.client.api.common.TextRange sonarTextRange = new org.sonarsource.sonarlint.core.client.api.common.TextRange(startLineNum+1,startOffset-lineStartOffset,endLineNum+1,endOffset-lineEndOffset);
                DefaultTextRange defaultTextRange = new DefaultTextRange(new DefaultTextPointer(startLineNum+1, startOffset-lineStartOffset), new DefaultTextPointer(endLineNum+1, endOffset-lineEndOffset));
                DefaultClientIssue defaultClientIssue = new DefaultClientIssue(sonarLintRule.severity(), sonarLintRule.type().name(), activeRule, sonarLintRule, descriptor.getDescriptionTemplate(), defaultTextRange, defaultClientInputFile, new ArrayList<>());
                IssueMatcher issueMatcher = new IssueMatcher(project);
                try {
                    RangeMarker match = issueMatcher.match(psiFile, sonarTextRange);
                    LiveIssue liveIssue = new LiveIssue(defaultClientIssue, psiFile, match, null);
                    Collection<LiveIssue> issueCollection = issues.get(virtualFile);
                    if (issueCollection != null){
                        issueCollection.add(liveIssue);
                    }else{
                        Collection<LiveIssue> liveIssues = new ArrayList<>();
                        liveIssues.add(liveIssue);
                        issues.put(virtualFile,liveIssues);
                    }
                    System.out.println(1);
                } catch (IssueMatcher.NoMatchException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * ??????p3c????????????????????????????????????
     *
     * @param project
     * @param resultList
     * @return
     */
    private List<ProblemTreeNodeData> transScanResultData(Project project, List<InspectionResult> resultList) {
        List<ProblemTreeNodeData> problemTreeNodeDataList = new ArrayList<>();
        String projectRoot = project.getBasePath().substring(0, project.getBasePath().lastIndexOf("/"));
        try {
            for (InspectionResult inspectionResult : resultList) {
                LocalInspectionToolWrapper toolWrapper = inspectionResult.tool;
                LocalInspectionTool tool = toolWrapper.getTool();
                Field aliPmdInspectionField = tool.getClass().getDeclaredField("aliPmdInspection");
                aliPmdInspectionField.setAccessible(true);
                AliPmdInspection aliPmdInspection = (AliPmdInspection)aliPmdInspectionField.get(tool);
                Field aliPmdInspectionRuleField = aliPmdInspection.getClass().getDeclaredField("rule");
                aliPmdInspectionRuleField.setAccessible(true);
                RuleReference aliPmdInspectionRule = (RuleReference)aliPmdInspectionRuleField.get(aliPmdInspection);
                for (ProblemDescriptor descriptor : inspectionResult.foundProblems) {
                    ProblemTreeNodeData problemTreeNodeData = new ProblemTreeNodeData();
                    problemTreeNodeData.setName(descriptor.getDescriptionTemplate());
                    problemTreeNodeData.setProblemType(tool.getDefaultLevel().getSeverity().getName());
                    problemTreeNodeData.setNodeType(ProblemTreeNodeData.NODE_TYPE_CHECK_ISSUE);
                    problemTreeNodeData.setProblemFromLine(descriptor.getLineNumber());
                    problemTreeNodeData.setProblemFromFileName(getFile().getVirtualFile().getName());
                    problemTreeNodeData.setProblemFromFilePath(getFile().getVirtualFile().getPath().replaceFirst(projectRoot, ""));
                    problemTreeNodeData.setStaticDescription(aliPmdInspection.getStaticDescription());
                    problemTreeNodeData.setLanguageName(aliPmdInspectionRule.getLanguage().getName());
                    ScanRuleData scanRuleData = new ScanRuleData();
                    scanRuleData.setName(aliPmdInspectionRule.getName());
                    scanRuleData.setRuleSetName(aliPmdInspectionRule.getRuleSetName());
                    scanRuleData.setPriority(aliPmdInspectionRule.getPriority());
                    scanRuleData.setMessage(aliPmdInspectionRule.getMessage());
                    scanRuleData.setExamples(aliPmdInspectionRule.getExamples());
                    scanRuleData.setDescription(aliPmdInspectionRule.getDescription());
                    problemTreeNodeData.setScanRuleData(scanRuleData);
                    problemTreeNodeDataList.add(problemTreeNodeData);
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return problemTreeNodeDataList;
    }

    private HighlightDisplayLevel calculatePreciseLevel(@Nullable RefEntity element,
                                                               @Nullable CommonProblemDescriptor descriptor,
                                                               @NotNull InspectionToolPresentation presentation) {
        if (element == null) {
            return null;
        }
        final InspectionProfileImpl profile = presentation.getContext().getCurrentProfile();
        String shortName = presentation.getToolWrapper().getShortName();
        if (descriptor instanceof ProblemDescriptor) {
            InspectionProfileManager inspectionProfileManager = profile.getProfileManager();
            RefElement refElement = (RefElement)element;
            SeverityRegistrar severityRegistrar = inspectionProfileManager.getSeverityRegistrar();
            HighlightSeverity severity = presentation.getSeverity(refElement);
            if (severity == null) {
                return null;
            }
            HighlightInfoType highlightInfoType = ProblemDescriptorUtil.highlightTypeFromDescriptor((ProblemDescriptor)descriptor, severity, severityRegistrar);
            HighlightSeverity highlightSeverity = highlightInfoType.getSeverity(refElement.getPsiElement());
            return HighlightDisplayLevel.find(highlightSeverity);
        }
        else {
            return profile.getTools(shortName, presentation.getContext().getProject()).getLevel();
        }
    }

    private void addDescriptors(@NotNull LocalInspectionToolWrapper toolWrapper,
                                @NotNull ProblemDescriptor descriptor,
                                @NotNull GlobalInspectionContextImpl context) {
        InspectionToolPresentation toolPresentation = context.getPresentation(toolWrapper);
        BatchModeDescriptorsUtil.addProblemDescriptors(Collections.singletonList(descriptor), toolPresentation, myIgnoreSuppressed,
                context,
                toolWrapper.getTool());
    }

    private void addDescriptorsFromInjectedResults(@NotNull GlobalInspectionContextImpl context) {
        for (Map.Entry<PsiFile, List<InspectionResult>> entry : result.entrySet()) {
            PsiFile file = entry.getKey();
            if (file == getFile()) continue; // not injected
            List<InspectionResult> resultList = entry.getValue();
            for (InspectionResult inspectionResult : resultList) {
                LocalInspectionToolWrapper toolWrapper = inspectionResult.tool;
                for (ProblemDescriptor descriptor : inspectionResult.foundProblems) {
                    PsiElement psiElement = descriptor.getPsiElement();
                    if (psiElement == null) continue;
                    if (toolWrapper.getTool().isSuppressedFor(psiElement)) continue;

                    addDescriptors(toolWrapper, descriptor, context);
                }
            }
        }
    }

    private void inspect(@NotNull List<? extends LocalInspectionToolWrapper> toolWrappers,
                         @NotNull final InspectionManager iManager,
                         final boolean isOnTheFly,
                         @NotNull final ProgressIndicator progress) {
        if (toolWrappers.isEmpty()) return;
        List<Divider.DividedElements> allDivided = new ArrayList<>();
        Divider.divideInsideAndOutsideAllRoots(myFile, myRestrictRange, myPriorityRange, SHOULD_INSPECT_FILTER, new CommonProcessors.CollectProcessor<>(allDivided));
        List<PsiElement> inside = ContainerUtil.concat((List<List<PsiElement>>)ContainerUtil.map(allDivided, d -> d.inside));
        List<PsiElement> outside = ContainerUtil.concat((List<List<PsiElement>>)ContainerUtil.map(allDivided, d -> ContainerUtil.concat(d.outside, d.parents)));

        setProgressLimit(toolWrappers.size() * 2L);
        final LocalInspectionToolSession session = new LocalInspectionToolSession(getFile(), myRestrictRange.getStartOffset(), myRestrictRange.getEndOffset());

        List<InspectionContext> init = visitPriorityElementsAndInit(
                InspectionEngine.filterToolsApplicableByLanguage(toolWrappers, InspectionEngine.calcElementDialectIds(inside, outside)),
                iManager, isOnTheFly, progress, inside, session);
        Set<PsiFile> alreadyVisitedInjected = inspectInjectedPsi(inside, isOnTheFly, progress, iManager, true, toolWrappers, Collections.emptySet());
        visitRestElementsAndCleanup(progress, outside, session, init);
        inspectInjectedPsi(outside, isOnTheFly, progress, iManager, false, toolWrappers, alreadyVisitedInjected);
        ProgressManager.checkCanceled();

        myInfos = new ArrayList<>();
        addHighlightsFromResults(myInfos);

        if (isOnTheFly) {
            highlightRedundantSuppressions(toolWrappers, iManager, inside, outside);
        }
    }

    private void highlightRedundantSuppressions(@NotNull List<? extends LocalInspectionToolWrapper> toolWrappers,
                                                @NotNull InspectionManager iManager,
                                                @NotNull List<? extends PsiElement> inside,
                                                @NotNull List<? extends PsiElement> outside) {
        HighlightDisplayKey key = HighlightDisplayKey.find(RedundantSuppressInspection.SHORT_NAME);
        final InspectionProfileImpl inspectionProfile = myProfileWrapper.getInspectionProfile();
        if (key != null && inspectionProfile.isToolEnabled(key, getFile())) {
            InspectionToolWrapper<?,?> toolWrapper = inspectionProfile.getToolById(RedundantSuppressInspection.SHORT_NAME, getFile());
            InspectionSuppressor suppressor = LanguageInspectionSuppressors.INSTANCE.forLanguage(getFile().getLanguage());
            if (suppressor instanceof RedundantSuppressionDetector) {
                if (toolWrappers.stream().anyMatch(LocalInspectionToolWrapper::runForWholeFile)) {
                    return;
                }
                Set<String> activeTools = new HashSet<>();
                for (LocalInspectionToolWrapper tool : toolWrappers) {
                    if (!tool.isUnfair()) {
                        activeTools.add(tool.getID());
                        ContainerUtil.addIfNotNull(activeTools, tool.getAlternativeID());
                        InspectionElementsMerger elementsMerger = InspectionElementsMerger.getMerger(tool.getShortName());
                        if (elementsMerger != null) {
                            activeTools.addAll(Arrays.asList(elementsMerger.getSuppressIds()));
                        }
                    }
                }
                LocalInspectionTool
                        localTool = ((RedundantSuppressInspection)toolWrapper.getTool()).createLocalTool((RedundantSuppressionDetector)suppressor, mySuppressedElements, activeTools);
                ProblemsHolder holder = new ProblemsHolder(iManager, getFile(), true);
                PsiElementVisitor visitor = localTool.buildVisitor(holder, true);
                InspectionEngine.acceptElements(inside, visitor);
                InspectionEngine.acceptElements(outside, visitor);

                HighlightSeverity severity = myProfileWrapper.getErrorLevel(key, getFile()).getSeverity();
                for (ProblemDescriptor descriptor : holder.getResults()) {
                    ProgressManager.checkCanceled();
                    PsiElement element = descriptor.getPsiElement();
                    if (element != null) {
                        Document thisDocument = documentManager.getDocument(getFile());
                        createHighlightsForDescriptor(myInfos, emptyActionRegistered, ilManager, getFile(), thisDocument,
                                new LocalInspectionToolWrapper(localTool), severity, descriptor, element, false);
                    }
                }
            }
        }
    }

    @NotNull
    private List<InspectionContext> visitPriorityElementsAndInit(@NotNull List<? extends LocalInspectionToolWrapper> wrappers,
                                                                 @NotNull final InspectionManager iManager,
                                                                 final boolean isOnTheFly,
                                                                 @NotNull final ProgressIndicator indicator,
                                                                 @NotNull final List<? extends PsiElement> elements,
                                                                 @NotNull final LocalInspectionToolSession session) {
        final List<InspectionContext> init = new ArrayList<>();

        PsiFile file = session.getFile();
        Processor<LocalInspectionToolWrapper> processor = toolWrapper ->
                AstLoadingFilter.disallowTreeLoading(() -> AstLoadingFilter.<Boolean, RuntimeException>forceAllowTreeLoading(file, () -> {
                    runToolOnElements(toolWrapper, iManager, isOnTheFly, indicator, elements, session, init);
                    return true;
                }));
        if (!JobLauncher.getInstance().invokeConcurrentlyUnderProgress(wrappers, indicator, processor)) {
            throw new ProcessCanceledException();
        }
        return init;
    }

    private void runToolOnElements(@NotNull final LocalInspectionToolWrapper toolWrapper,
                                   @NotNull final InspectionManager iManager,
                                   final boolean isOnTheFly,
                                   @NotNull final ProgressIndicator indicator,
                                   @NotNull final List<? extends PsiElement> elements,
                                   @NotNull final LocalInspectionToolSession session,
                                   @NotNull List<? super InspectionContext> init) {
        ProgressManager.checkCanceled();

        ApplicationManager.getApplication().assertReadAccessAllowed();
        final LocalInspectionTool tool = toolWrapper.getTool();
        final boolean[] applyIncrementally = {isOnTheFly};
        ProblemsHolder holder = new ProblemsHolder(iManager, getFile(), isOnTheFly) {
            @Override
            public void registerProblem(@NotNull ProblemDescriptor descriptor) {
                super.registerProblem(descriptor);
                if (applyIncrementally[0]) {
                    addDescriptorIncrementally(descriptor, toolWrapper, indicator);
                }
            }
        };

        PsiElementVisitor visitor = InspectionEngine.createVisitorAndAcceptElements(tool, holder, isOnTheFly, session, elements);
        // if inspection returned empty visitor then it should be skipped
        if (visitor != PsiElementVisitor.EMPTY_VISITOR) {
            synchronized (init) {
                init.add(new InspectionContext(toolWrapper, holder, holder.getResultCount(), visitor));
            }
        }
        advanceProgress(1);

        if (holder.hasResults()) {
            appendDescriptors(getFile(), holder.getResults(), toolWrapper);
        }
        applyIncrementally[0] = false; // do not apply incrementally outside visible range
    }

    private void visitRestElementsAndCleanup(@NotNull final ProgressIndicator indicator,
                                             @NotNull final List<? extends PsiElement> elements,
                                             @NotNull final LocalInspectionToolSession session,
                                             @NotNull List<? extends InspectionContext> init) {
        Processor<InspectionContext> processor =
                context -> {
                    ProgressManager.checkCanceled();
                    ApplicationManager.getApplication().assertReadAccessAllowed();
                    AstLoadingFilter.disallowTreeLoading(() -> InspectionEngine.acceptElements(elements, context.visitor));
                    advanceProgress(1);
                    context.tool.getTool().inspectionFinished(session, context.holder);

                    if (context.holder.hasResults()) {
                        List<ProblemDescriptor> allProblems = context.holder.getResults();
                        List<ProblemDescriptor> restProblems = allProblems.subList(context.problemsSize, allProblems.size());
                        appendDescriptors(getFile(), restProblems, context.tool);
                    }
                    return true;
                };
        if (!JobLauncher.getInstance().invokeConcurrentlyUnderProgress(init, indicator, processor)) {
            throw new ProcessCanceledException();
        }
    }

    @NotNull
    private Set<PsiFile> inspectInjectedPsi(@NotNull final List<? extends PsiElement> elements,
                                            final boolean onTheFly,
                                            @NotNull final ProgressIndicator indicator,
                                            @NotNull final InspectionManager iManager,
                                            final boolean inVisibleRange,
                                            @NotNull final List<? extends LocalInspectionToolWrapper> wrappers,
                                            @NotNull Set<? extends PsiFile> alreadyVisitedInjected) {
        if (!myInspectInjectedPsi) return Collections.emptySet();
        Set<PsiFile> injected = new THashSet<>();
        for (PsiElement element : elements) {
            PsiFile containingFile = getFile();
            InjectedLanguageManager.getInstance(containingFile.getProject()).enumerateEx(element, containingFile, false,
                    (injectedPsi, places) -> injected.add(injectedPsi));
        }
        injected.removeAll(alreadyVisitedInjected);
        if (!injected.isEmpty()) {
            Processor<PsiFile> processor = injectedPsi -> {
                doInspectInjectedPsi(injectedPsi, onTheFly, indicator, iManager, inVisibleRange, wrappers);
                return true;
            };
            if (!JobLauncher.getInstance().invokeConcurrentlyUnderProgress(new ArrayList<>(injected), indicator, processor)) {
                throw new ProcessCanceledException();
            }
        }
        return injected;
    }

    private static final TextAttributes NONEMPTY_TEXT_ATTRIBUTES = new TextAttributes() {
        @Override
        public boolean isEmpty() {
            return false;
        }
    };

    @Nullable
    private HighlightInfo highlightInfoFromDescriptor(@NotNull ProblemDescriptor problemDescriptor,
                                                      @NotNull HighlightInfoType highlightInfoType,
                                                      @NotNull String message,
                                                      @Nullable String toolTip,
                                                      @NotNull PsiElement psiElement,
                                                      @NotNull List<IntentionAction> quickFixes,
                                                      @NotNull String toolID) {
        TextRange textRange = ((ProblemDescriptorBase)problemDescriptor).getTextRange();
        if (textRange == null) return null;
        boolean isFileLevel = psiElement instanceof PsiFile && textRange.equals(psiElement.getTextRange());

        final HighlightSeverity severity = highlightInfoType.getSeverity(psiElement);
        TextAttributesKey attributesKey = ((ProblemDescriptorBase)problemDescriptor).getEnforcedTextAttributes();
        TextAttributes attributes = attributesKey == null || getColorsScheme() == null
                ? mySeverityRegistrar.getTextAttributesBySeverity(severity)
                : getColorsScheme().getAttributes(attributesKey);
        HighlightInfo.Builder b = HighlightInfo.newHighlightInfo(highlightInfoType)
                .range(psiElement, textRange.getStartOffset(), textRange.getEndOffset())
                .description(message)
                .severity(severity)
                .inspectionToolId(toolID);
        if (toolTip != null) b.escapedToolTip(toolTip);
        if (HighlightSeverity.INFORMATION.equals(severity) && attributes == null && toolTip == null && !quickFixes.isEmpty()) {
            // Hack to avoid filtering this info out in HighlightInfoFilterImpl even though its attributes are empty.
            // But it has quick fixes so it needs to be created.
            attributes = NONEMPTY_TEXT_ATTRIBUTES;
        }
        if (attributes != null) b.textAttributes(attributes);
        if (problemDescriptor.isAfterEndOfLine()) b.endOfLine();
        if (isFileLevel) b.fileLevelAnnotation();
        if (problemDescriptor.getProblemGroup() != null) b.problemGroup(problemDescriptor.getProblemGroup());

        return b.create();
    }

    private final Map<TextRange, RangeMarker> ranges2markersCache = new THashMap<>(); // accessed in EDT only
    private final InjectedLanguageManager ilManager = InjectedLanguageManager.getInstance(myProject);
    private final List<HighlightInfo> infos = new ArrayList<>(2); // accessed in EDT only
    private final PsiDocumentManager documentManager = PsiDocumentManager.getInstance(myProject);
    private final Set<Pair<TextRange, String>> emptyActionRegistered = Collections.synchronizedSet(new THashSet<>());

    private void addDescriptorIncrementally(@NotNull final ProblemDescriptor descriptor,
                                            @NotNull final LocalInspectionToolWrapper tool,
                                            @NotNull final ProgressIndicator indicator) {
        if (myIgnoreSuppressed && tool.getTool().isSuppressedFor(descriptor.getPsiElement())) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(()->{
            PsiElement psiElement = descriptor.getPsiElement();
            if (psiElement == null) return;
            PsiFile file = psiElement.getContainingFile();
            Document thisDocument = documentManager.getDocument(file);

            HighlightSeverity severity = myProfileWrapper.getErrorLevel(HighlightDisplayKey.find(tool.getShortName()), file).getSeverity();

            infos.clear();
            createHighlightsForDescriptor(infos, emptyActionRegistered, ilManager, file, thisDocument, tool, severity, descriptor, psiElement,
                    myIgnoreSuppressed);
            for (HighlightInfo info : infos) {
                final EditorColorsScheme colorsScheme = getColorsScheme();
                UpdateHighlightersUtil.addHighlighterToEditorIncrementally(myProject, myDocument, getFile(),
                        myRestrictRange.getStartOffset(),
                        myRestrictRange.getEndOffset(),
                        info, colorsScheme, getId(),
                        ranges2markersCache);
            }
        }, __->myProject.isDisposed() || indicator.isCanceled());
    }

    private void appendDescriptors(@NotNull PsiFile file, @NotNull List<? extends ProblemDescriptor> descriptors, @NotNull LocalInspectionToolWrapper tool) {
        for (ProblemDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                LOG.error("null descriptor. all descriptors(" + descriptors.size() +"): " +
                        descriptors + "; file: " + file + " (" + file.getVirtualFile() +"); tool: " + tool);
            }
        }
        InspectionResult result = new InspectionResult(tool, descriptors);
        appendResult(file, result);
    }

    private void appendResult(@NotNull PsiFile file, @NotNull InspectionResult result) {
        List<InspectionResult> resultList = this.result.get(file);
        if (resultList == null) {
            resultList = ConcurrencyUtil.cacheOrGet(this.result, file, new ArrayList<>());
        }
        synchronized (resultList) {
            resultList.add(result);
        }
    }

    @Override
    protected void applyInformationWithProgress() {
        UpdateHighlightersUtil.setHighlightersToEditor(myProject, myDocument, myRestrictRange.getStartOffset(), myRestrictRange.getEndOffset(), myInfos, getColorsScheme(), getId());
    }

    private void addHighlightsFromResults(@NotNull List<? super HighlightInfo> outInfos) {
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(myProject);
        InjectedLanguageManager ilManager = InjectedLanguageManager.getInstance(myProject);
        Set<Pair<TextRange, String>> emptyActionRegistered = new THashSet<>();

        for (Map.Entry<PsiFile, List<InspectionResult>> entry : result.entrySet()) {
            ProgressManager.checkCanceled();
            PsiFile file = entry.getKey();
            Document documentRange = documentManager.getDocument(file);
            if (documentRange == null) continue;
            List<InspectionResult> resultList = entry.getValue();
            synchronized (resultList) {
                for (InspectionResult inspectionResult : resultList) {
                    ProgressManager.checkCanceled();
                    LocalInspectionToolWrapper tool = inspectionResult.tool;
                    HighlightSeverity severity = myProfileWrapper.getErrorLevel(HighlightDisplayKey.find(tool.getShortName()), file).getSeverity();
                    for (ProblemDescriptor descriptor : inspectionResult.foundProblems) {
                        ProgressManager.checkCanceled();
                        PsiElement element = descriptor.getPsiElement();
                        if (element != null) {
                            createHighlightsForDescriptor(outInfos, emptyActionRegistered, ilManager, file, documentRange, tool, severity, descriptor, element,
                                    myIgnoreSuppressed);
                        }
                    }
                }
            }
        }
    }

    private void createHighlightsForDescriptor(@NotNull List<? super HighlightInfo> outInfos,
                                               @NotNull Set<? super Pair<TextRange, String>> emptyActionRegistered,
                                               @NotNull InjectedLanguageManager ilManager,
                                               @NotNull PsiFile file,
                                               @NotNull Document documentRange,
                                               @NotNull LocalInspectionToolWrapper toolWrapper,
                                               @NotNull HighlightSeverity severity,
                                               @NotNull ProblemDescriptor descriptor,
                                               @NotNull PsiElement element, boolean ignoreSuppressed) {
        if (descriptor instanceof ProblemDescriptorWithReporterName) {
            String reportingToolName = ((ProblemDescriptorWithReporterName)descriptor).getReportingToolName();
            final InspectionToolWrapper<?, ?> reportingTool = myProfileWrapper.getInspectionTool(reportingToolName, element);
            assert reportingTool instanceof LocalInspectionToolWrapper;
            toolWrapper = (LocalInspectionToolWrapper)reportingTool;
            severity = myProfileWrapper.getErrorLevel(HighlightDisplayKey.find(reportingToolName), file).getSeverity();
        }
        LocalInspectionTool tool = toolWrapper.getTool();
        if (ignoreSuppressed && tool.isSuppressedFor(element)) {
            registerSuppressedElements(element, toolWrapper.getID(), toolWrapper.getAlternativeID());
            return;
        }
        HighlightInfoType level = ProblemDescriptorUtil.highlightTypeFromDescriptor(descriptor, severity, mySeverityRegistrar);
        @NonNls String message = ProblemDescriptorUtil.renderDescriptionMessage(descriptor, element);

        ProblemGroup problemGroup = descriptor.getProblemGroup();
        String problemName = problemGroup != null ? problemGroup.getProblemName() : null;
        String shortName = problemName != null ? problemName : toolWrapper.getShortName();
        final HighlightDisplayKey key = HighlightDisplayKey.find(shortName);
        final InspectionProfile inspectionProfile = myProfileWrapper.getInspectionProfile();
        if (!inspectionProfile.isToolEnabled(key, getFile())) return;

        HighlightInfoType type = new InspectionHighlightInfoType(level, element);
        final String plainMessage = message.startsWith("<html>") ? StringUtil.unescapeXmlEntities(XmlStringUtil.stripHtml(message).replaceAll("<[^>]*>", "")) : message;
        @NonNls String link = "";
        if (showToolDescription(toolWrapper)) {
            link = " <a "
                    + "href=\"#inspection/" + shortName + "\""
                    + (StartupUiUtil.isUnderDarcula() ? " color=\"7AB4C9\" " : "")
                    + ">" + DaemonBundle.message("inspection.extended.description")
                    + "</a> " + myShortcutText;
        }

        @NonNls String tooltip = null;
        if (descriptor.showTooltip()) {
            tooltip = tooltips.intern(XmlStringUtil.wrapInHtml((message.startsWith("<html>") ? XmlStringUtil.stripHtml(message): XmlStringUtil.escapeString(message)) + link));
        }
        List<IntentionAction> fixes = getQuickFixes(key, descriptor, emptyActionRegistered);
        HighlightInfo info = highlightInfoFromDescriptor(descriptor, type, plainMessage, tooltip, element, fixes, key.getID());
        if (info == null) return;
        registerQuickFixes(info, fixes, shortName);

        PsiFile context = getTopLevelFileInBaseLanguage(element);
        PsiFile myContext = getTopLevelFileInBaseLanguage(getFile());
        if (context != getFile()) {
            String errorMessage = "Reported element " + element +
                    " is not from the file '" + file.getVirtualFile().getPath() +
                    "' the inspection '" + shortName +
                    "' (" + tool.getClass() +
                    ") was invoked for. Message: '" + descriptor + "'.\nElement containing file: " +
                    context + "\nInspection invoked for file: " + myContext + "\n";
            PluginException.logPluginError(LOG, errorMessage, null, tool.getClass());
        }
        boolean isOutsideInjected = !myInspectInjectedPsi || file == getFile();
        if (isOutsideInjected) {
            outInfos.add(info);
            return;
        }
        injectToHost(outInfos, ilManager, file, documentRange, element, fixes, info, shortName);
    }

    private void registerSuppressedElements(@NotNull PsiElement element, String id, String alternativeID) {
        mySuppressedElements.computeIfAbsent(id, shortName -> new HashSet<>()).add(element);
        if (alternativeID != null) {
            mySuppressedElements.computeIfAbsent(alternativeID, shortName -> new HashSet<>()).add(element);
        }
    }

    private static void injectToHost(@NotNull List<? super HighlightInfo> outInfos,
                                     @NotNull InjectedLanguageManager ilManager,
                                     @NotNull PsiFile file,
                                     @NotNull Document documentRange,
                                     @NotNull PsiElement element,
                                     @NotNull List<? extends IntentionAction> fixes,
                                     @NotNull HighlightInfo info,
                                     String shortName) {
        // todo we got to separate our "internal" prefixes/suffixes from user-defined ones
        // todo in the latter case the errors should be highlighted, otherwise not
        List<TextRange> editables = ilManager.intersectWithAllEditableFragments(file, new TextRange(info.startOffset, info.endOffset));
        for (TextRange editable : editables) {
            TextRange hostRange = ((DocumentWindow)documentRange).injectedToHost(editable);
            int start = hostRange.getStartOffset();
            int end = hostRange.getEndOffset();
            HighlightInfo.Builder builder = HighlightInfo.newHighlightInfo(info.type).range(element, start, end);
            String description = info.getDescription();
            if (description != null) {
                builder.description(description);
            }
            String toolTip = info.getToolTip();
            if (toolTip != null) {
                builder.escapedToolTip(toolTip);
            }
            HighlightInfo patched = builder.createUnconditionally();
            if (patched.startOffset != patched.endOffset || info.startOffset == info.endOffset) {
                patched.setFromInjection(true);
                registerQuickFixes(patched, fixes, shortName);
                outInfos.add(patched);
            }
        }
    }

    private PsiFile getTopLevelFileInBaseLanguage(@NotNull PsiElement element) {
        PsiFile file = InjectedLanguageManager.getInstance(myProject).getTopLevelFile(element);
        FileViewProvider viewProvider = file.getViewProvider();
        return viewProvider.getPsi(viewProvider.getBaseLanguage());
    }


    private static final Interner<String> tooltips = new WeakInterner<>();

    private static boolean showToolDescription(@NotNull LocalInspectionToolWrapper tool) {
        String staticDescription = tool.getStaticDescription();
        return staticDescription == null || !staticDescription.isEmpty();
    }

    private static void registerQuickFixes(@NotNull HighlightInfo highlightInfo,
                                           @NotNull List<? extends IntentionAction> quickFixes,
                                           String shortName) {
        final HighlightDisplayKey key = HighlightDisplayKey.find(shortName);
        for (IntentionAction quickFix : quickFixes) {
            QuickFixAction.registerQuickFixAction(highlightInfo, quickFix, key);
        }
    }

    @NotNull
    private static List<IntentionAction> getQuickFixes(@NotNull HighlightDisplayKey key,
                                                       @NotNull ProblemDescriptor descriptor,
                                                       @NotNull Set<? super Pair<TextRange, String>> emptyActionRegistered) {
        List<IntentionAction> result = new SmartList<>();
        boolean needEmptyAction = true;
        final QuickFix[] fixes = descriptor.getFixes();
        if (fixes != null && fixes.length != 0) {
            for (int k = 0; k < fixes.length; k++) {
                QuickFix fix = fixes[k];
                if (fix == null) throw new IllegalStateException("Inspection " + key + " returns null quick fix in its descriptor: " + descriptor + "; array: " +
                        Arrays.toString(fixes));
                result.add(QuickFixWrapper.wrap(descriptor, k));
                needEmptyAction = false;
            }
        }
        HintAction hintAction = descriptor instanceof ProblemDescriptorImpl ? ((ProblemDescriptorImpl)descriptor).getHintAction() : null;
        if (hintAction != null) {
            result.add(hintAction);
            needEmptyAction = false;
        }
        if (((ProblemDescriptorBase)descriptor).getEnforcedTextAttributes() != null) {
            needEmptyAction = false;
        }
        if (needEmptyAction && emptyActionRegistered.add(Pair.create(((ProblemDescriptorBase)descriptor).getTextRange(), key.toString()))) {
            String displayNameByKey = HighlightDisplayKey.getDisplayNameByKey(key);
            LOG.assertTrue(displayNameByKey != null, key.toString());
            IntentionAction emptyIntentionAction = new EmptyIntentionAction(displayNameByKey);
            result.add(emptyIntentionAction);
        }
        return result;
    }

    private static void getElementsAndDialectsFrom(@NotNull PsiFile file,
                                                   @NotNull List<? super PsiElement> outElements,
                                                   @NotNull Set<? super String> outDialects) {
        final FileViewProvider viewProvider = file.getViewProvider();
        Set<Language> processedLanguages = new SmartHashSet<>();
        final PsiElementVisitor visitor = new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                ProgressManager.checkCanceled();
                PsiElement child = element.getFirstChild();
                while (child != null) {
                    outElements.add(child);
                    child.accept(this);
                    appendDialects(child, processedLanguages, outDialects);
                    child = child.getNextSibling();
                }
            }
        };
        for (Language language : viewProvider.getLanguages()) {
            final PsiFile psiRoot = viewProvider.getPsi(language);
            if (psiRoot == null || !HighlightingLevelManager.getInstance(file.getProject()).shouldInspect(psiRoot)) {
                continue;
            }
            outElements.add(psiRoot);
            psiRoot.accept(visitor);
            appendDialects(psiRoot, processedLanguages, outDialects);
        }
    }

    private static void appendDialects(@NotNull PsiElement element,
                                       @NotNull Set<? super Language> outProcessedLanguages,
                                       @NotNull Set<? super String> outDialectIds) {
        Language language = element.getLanguage();
        outDialectIds.add(language.getID());
        if (outProcessedLanguages.add(language)) {
            for (Language dialect : language.getDialects()) {
                outDialectIds.add(dialect.getID());
            }
        }
    }

    @NotNull
    List<LocalInspectionToolWrapper> getInspectionTools(@NotNull InspectionProfileWrapper profile) {
        final InspectionToolWrapper[] toolWrappers = profile.getInspectionProfile().getInspectionTools(getFile());
        InspectionProfileWrapper.checkInspectionsDuplicates(toolWrappers);
        List<LocalInspectionToolWrapper> enabled = new ArrayList<>();
        for (InspectionToolWrapper toolWrapper : toolWrappers) {
            ProgressManager.checkCanceled();
            if (toolWrapper instanceof LocalInspectionToolWrapper && !isAcceptableLocalTool((LocalInspectionToolWrapper)toolWrapper)) {
                continue;
            }
            final HighlightDisplayKey key = HighlightDisplayKey.find(toolWrapper.getShortName());
            if (!profile.isToolEnabled(key, getFile())) continue;
            if (HighlightDisplayLevel.DO_NOT_SHOW.equals(profile.getErrorLevel(key, getFile()))) continue;
            LocalInspectionToolWrapper wrapper;
            if (toolWrapper instanceof LocalInspectionToolWrapper) {
                wrapper = (LocalInspectionToolWrapper)toolWrapper;
            }
            else {
                wrapper = ((GlobalInspectionToolWrapper)toolWrapper).getSharedLocalInspectionToolWrapper();
                if (wrapper == null || !isAcceptableLocalTool(wrapper)) continue;
            }
            String language = wrapper.getLanguage();
            if (language != null && Language.findLanguageByID(language) == null) {
                continue; // filter out at least unknown languages
            }
            if (myIgnoreSuppressed && wrapper.getTool().isSuppressedFor(getFile())) {
                continue;
            }
            enabled.add(wrapper);
        }
        return enabled;
    }

    protected boolean isAcceptableLocalTool(@NotNull LocalInspectionToolWrapper wrapper) {
        return true;
    }

    private void doInspectInjectedPsi(@NotNull PsiFile injectedPsi,
                                      final boolean isOnTheFly,
                                      @NotNull final ProgressIndicator indicator,
                                      @NotNull InspectionManager iManager,
                                      final boolean inVisibleRange,
                                      @NotNull List<? extends LocalInspectionToolWrapper> wrappers) {
        final PsiElement host = InjectedLanguageManager.getInstance(injectedPsi.getProject()).getInjectionHost(injectedPsi);

        List<PsiElement> elements = new ArrayList<>();
        Set<String> elementDialectIds = new SmartHashSet<>();
        getElementsAndDialectsFrom(injectedPsi, elements, elementDialectIds);
        if (elements.isEmpty()) {
            return;
        }
        List<LocalInspectionToolWrapper> applicableTools = InspectionEngine.filterToolsApplicableByLanguage(wrappers, elementDialectIds);
        for (LocalInspectionToolWrapper wrapper : applicableTools) {
            ProgressManager.checkCanceled();
            final LocalInspectionTool tool = wrapper.getTool();
            ProblemsHolder holder = new ProblemsHolder(iManager, injectedPsi, isOnTheFly) {
                @Override
                public void registerProblem(@NotNull ProblemDescriptor descriptor) {
                    if (host != null && myIgnoreSuppressed && tool.isSuppressedFor(host)) {
                        registerSuppressedElements(host, wrapper.getID(), wrapper.getAlternativeID());
                        return;
                    }
                    super.registerProblem(descriptor);
                    if (isOnTheFly && inVisibleRange) {
                        addDescriptorIncrementally(descriptor, wrapper, indicator);
                    }
                }
            };

            LocalInspectionToolSession injSession = new LocalInspectionToolSession(injectedPsi, 0, injectedPsi.getTextLength());
            InspectionEngine.createVisitorAndAcceptElements(tool, holder, isOnTheFly, injSession, elements);
            tool.inspectionFinished(injSession, holder);
            List<ProblemDescriptor> problems = holder.getResults();
            if (!problems.isEmpty()) {
                appendDescriptors(injectedPsi, problems, wrapper);
            }
        }
    }

    @Override
    @NotNull
    public List<HighlightInfo> getInfos() {
        return myInfos;
    }

    public class InspectionResult {
        @NotNull
        private final LocalInspectionToolWrapper tool;
        @NotNull
        private final List<? extends ProblemDescriptor> foundProblems;

        private InspectionResult(@NotNull LocalInspectionToolWrapper tool, @NotNull List<? extends ProblemDescriptor> foundProblems) {
            this.tool = tool;
            this.foundProblems = new ArrayList<>(foundProblems);
        }
    }

    private static class InspectionContext {
        private InspectionContext(@NotNull LocalInspectionToolWrapper tool,
                                  @NotNull ProblemsHolder holder,
                                  int problemsSize, // need this to diff between found problems in visible part and the rest
                                  @NotNull PsiElementVisitor visitor) {
            this.tool = tool;
            this.holder = holder;
            this.problemsSize = problemsSize;
            this.visitor = visitor;
        }

        @NotNull private final LocalInspectionToolWrapper tool;
        @NotNull private final ProblemsHolder holder;
        private final int problemsSize;
        @NotNull private final PsiElementVisitor visitor;
    }

    public static class InspectionHighlightInfoType extends HighlightInfoType.HighlightInfoTypeImpl {
        InspectionHighlightInfoType(@NotNull HighlightInfoType level, @NotNull PsiElement element) {
            super(level.getSeverity(element), level.getAttributesKey());
        }
    }

    private static String getPresentableNameText() {
        return DaemonBundle.message("pass.inspection");
    }
}

