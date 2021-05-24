package ex;

import com.alibaba.p3c.idea.action.PmdGlobalInspectionContextImpl;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.Tools;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import p3c.InspectionResultsViewEx;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

public class P3cDemoUi extends SimpleToolWindowPanel implements DataProvider {

    private InspectionResultsViewEx inspectionResultsViewEx;

    public P3cDemoUi(Project project) {
        super(false, true);
        setLayout(new BorderLayout());
        InspectionManagerEx inspectionManagerEx = (InspectionManagerEx) InspectionManager.getInstance(project);
        GlobalInspectionContextImpl globalInspectionContext = new PmdGlobalInspectionContextImpl(inspectionManagerEx.getProject(), inspectionManagerEx.getContentManager(),false);
        inspectionResultsViewEx = new InspectionResultsViewEx(globalInspectionContext);
        this.add(inspectionResultsViewEx);
        final List<Tools> globalSimpleTools = new ArrayList<>();
        inspectionResultsViewEx.addTools(globalSimpleTools);
    }
}
