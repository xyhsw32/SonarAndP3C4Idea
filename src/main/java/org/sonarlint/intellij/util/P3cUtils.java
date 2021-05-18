package org.sonarlint.intellij.util;

import com.alibaba.p3c.idea.compatible.inspection.InspectionProfileService;
import com.alibaba.p3c.idea.compatible.inspection.Inspections;
import com.alibaba.p3c.idea.inspection.AliBaseInspection;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileSystemItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class P3cUtils {
    public static void executeInspection(Project project, Collection<VirtualFile> virtualFiles){
        AnalysisScope analysisScope= new AnalysisScope(project, new ArrayList<>(virtualFiles));
        InspectionManagerEx inspectionManagerEx = (InspectionManagerEx) InspectionManager.getInstance(project);
        GlobalInspectionContextImpl globalInspectionContext = new GlobalInspectionContextImpl(inspectionManagerEx.getProject(), inspectionManagerEx.getContentManager());
        List<InspectionToolWrapper<?, ?>> inspectionToolWrappers = Inspections.INSTANCE.aliInspections(project, inspectionToolWrapper -> inspectionToolWrapper.getTool() instanceof AliBaseInspection);
        analysisScope.setIncludeTestSource(false);
        analysisScope.setSearchInLibraries(true);
        InspectionProfileImpl profile = InspectionProfileService.INSTANCE.createSimpleProfile(inspectionToolWrappers, inspectionManagerEx, null);
        InspectionProfileService.INSTANCE.setExternalProfile(profile, globalInspectionContext);
        globalInspectionContext.doInspections(analysisScope);
    }

    private static boolean isBaseDir( VirtualFile file, Project project) {
        if (file.getCanonicalPath() == null || project.getBasePath() == null) {
            return false;
        }
        return project.getBasePath().equals(file.getCanonicalPath());
    }

    private static String getTitle(PsiElement element, Boolean isProjectScopeSelected) {
        if (element == null) {
            return null;
        }
        if (isProjectScopeSelected) {
            return "Project";
        }
        if (element instanceof PsiFileSystemItem) {
            return VfsUtilCore.getRelativePath(((PsiFileSystemItem) element).getVirtualFile(), element.getProject().getBaseDir());
        }
        return null;
    }
}
