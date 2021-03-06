package com.intellij.codeInsight.daemon.impl;


import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeInspection.ex.GlobalInspectionContextBase;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.util.ProgressWrapper;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author cdr
 */
public abstract class ProgressableTextEditorHighlightingCustomPass extends TextEditorHighlightingPass {
    private volatile boolean myFinished;
    private volatile long myProgressLimit;
    private final AtomicLong myProgressCount = new AtomicLong();
    private volatile long myNextChunkThreshold; // the value myProgressCount should exceed to generate next fireProgressAdvanced event
    private final String myPresentableName;
    protected final PsiFile myFile;
    @Nullable private final Editor myEditor;
    @NotNull final TextRange myRestrictRange;
    @NotNull final HighlightInfoProcessor myHighlightInfoProcessor;
    HighlightingSession myHighlightingSession;

    protected ProgressableTextEditorHighlightingCustomPass(@NotNull Project project,
                                                           @Nullable final Document document,
                                                           @NotNull String presentableName,
                                                           @Nullable PsiFile file,
                                                           @Nullable Editor editor,
                                                           @NotNull TextRange restrictRange,
                                                           boolean runIntentionPassAfter,
                                                           @NotNull HighlightInfoProcessor highlightInfoProcessor) {
        super(project, document, runIntentionPassAfter);
        myPresentableName = presentableName;
        myFile = file;
        myEditor = editor;
        myRestrictRange = restrictRange;
        myHighlightInfoProcessor = highlightInfoProcessor;
    }

    @Override
    protected boolean isValid() {
        return super.isValid() && (myFile == null || myFile.isValid());
    }

    private void sessionFinished() {
        advanceProgress(Math.max(1, myProgressLimit - myProgressCount.get()));
    }

    @Override
    public final void doCollectInformation(@NotNull final ProgressIndicator progress) {
        GlobalInspectionContextBase.assertUnderDaemonProgress();
        myFinished = false;
        if (myFile != null) {
            myHighlightingSession =
                    HighlightingSessionImpl.getOrCreateHighlightingSession(myFile, (DaemonProgressIndicator)ProgressWrapper.unwrapAll(progress), getColorsScheme());
        }
        try {
            collectInformationWithProgress(progress);
        }
        finally {
            if (myFile != null) {
                sessionFinished();
            }
        }
    }

    protected abstract void collectInformationWithProgress(@NotNull ProgressIndicator progress);

    @Override
    public final void doApplyInformationToEditor() {
        myFinished = true;
        applyInformationWithProgress();
        DaemonCodeAnalyzerEx daemonCodeAnalyzer = DaemonCodeAnalyzerEx.getInstanceEx(myProject);
        daemonCodeAnalyzer.getFileStatusMap().markFileUpToDate(myDocument, getId());
    }

    protected abstract void applyInformationWithProgress();

    /**
     * @return number in the [0..1] range;
     * <0 means progress is not available
     */
    public double getProgress() {
        long progressLimit = getProgressLimit();
        if (progressLimit == 0) return -1;
        long progressCount = getProgressCount();
        return progressCount > progressLimit ? 1 : (double)progressCount / progressLimit;
    }

    private long getProgressLimit() {
        return myProgressLimit;
    }

    private long getProgressCount() {
        return myProgressCount.get();
    }

    public boolean isFinished() {
        return myFinished;
    }

    @Nullable("null means do not show progress")
    @Nls
    protected String getPresentableName() {
        return myPresentableName;
    }

    protected Editor getEditor() {
        return myEditor;
    }

    public void setProgressLimit(long limit) {
        myProgressLimit = limit;
        myNextChunkThreshold = Math.max(1, limit / 100); // 1% precision
    }

    public void advanceProgress(long delta) {
        if (myHighlightingSession != null) {
            // session can be null in e.g. inspection batch mode
            long current = myProgressCount.addAndGet(delta);
            if (current >= myNextChunkThreshold) {
                double progress = getProgress();
                myNextChunkThreshold += Math.max(1, myProgressLimit / 100);
                myHighlightInfoProcessor.progressIsAdvanced(myHighlightingSession, getEditor(), progress);
            }
        }
    }

    void waitForHighlightInfosApplied() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        HighlightingSessionImpl session = (HighlightingSessionImpl)myHighlightingSession;
        if (session != null) {
            session.waitForHighlightInfosApplied();
        }
    }

    static class EmptyPass extends TextEditorHighlightingPass {
        EmptyPass(final Project project, @Nullable final Document document) {
            super(project, document, false);
        }

        @Override
        public void doCollectInformation(@NotNull final ProgressIndicator progress) {
        }

        @Override
        public void doApplyInformationToEditor() {
            FileStatusMap statusMap = DaemonCodeAnalyzerEx.getInstanceEx(myProject).getFileStatusMap();
            statusMap.markFileUpToDate(getDocument(), getId());
        }
    }
}
