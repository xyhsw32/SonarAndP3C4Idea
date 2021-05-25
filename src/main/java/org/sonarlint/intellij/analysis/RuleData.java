package org.sonarlint.intellij.analysis;

import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.rule.Rule;

import java.util.Map;

public class RuleData {
    private Map<String, ActiveRule> activeRuleMap;
    private Map<String, Rule> ruleMap;
    private boolean isConnection;
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

    public boolean isConnection() {
        return isConnection;
    }

    public void setConnection(boolean connection) {
        isConnection = connection;
    }
}
