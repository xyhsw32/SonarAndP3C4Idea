package ex;

import java.util.List;
import net.sourceforge.pmd.RulePriority;

public class ScanRuleData {
    private String name;
    private String ruleSetName;
    private String message;
    private String description;
    private RulePriority priority;
    private List<String> examples;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRuleSetName() {
        return ruleSetName;
    }

    public void setRuleSetName(String ruleSetName) {
        this.ruleSetName = ruleSetName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public RulePriority getPriority() {
        return priority;
    }

    public void setPriority(RulePriority priority) {
        this.priority = priority;
    }

    public List<String> getExamples() {
        return examples;
    }

    public void setExamples(List<String> examples) {
        this.examples = examples;
    }
}
