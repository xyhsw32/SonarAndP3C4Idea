package org.sonarlint.intellij.analysis;

import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.rule.Rule;

import java.util.Map;

public class RuleData {
    private Map<String, ActiveRule> activeRuleMap;
    private Map<String, Rule> ruleMap;

    public Map<String, ActiveRule> getActiveRuleMap() {
        return activeRuleMap;
    }

    public void setActiveRuleMap(Map<String, ActiveRule> activeRuleMap) {
        this.activeRuleMap = activeRuleMap;
    }

    public Map<String, Rule> getRuleMap() {
        return ruleMap;
    }

    public void setRuleMap(Map<String, Rule> ruleMap) {
        this.ruleMap = ruleMap;
    }
}
