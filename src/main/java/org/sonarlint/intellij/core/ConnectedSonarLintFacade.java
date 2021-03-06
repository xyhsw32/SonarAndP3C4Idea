/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015-2021 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.core;

import com.google.common.base.Preconditions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.rule.Rule;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.rule.RuleKey;
import org.sonarlint.intellij.analysis.RuleData;
import org.sonarlint.intellij.ui.SonarLintConsole;
import org.sonarlint.intellij.util.ProjectLogOutput;
import org.sonarlint.intellij.util.SonarLintAppUtils;
import org.sonarlint.intellij.util.SonarLintUtils;
import org.sonarsource.sonarlint.core.ConnectedSonarLintEngineImpl;
import org.sonarsource.sonarlint.core.analyzer.issue.DefaultFlow;
import org.sonarsource.sonarlint.core.client.api.common.PluginDetails;
import org.sonarsource.sonarlint.core.client.api.common.ProgressMonitor;
import org.sonarsource.sonarlint.core.client.api.common.analysis.AnalysisResults;
import org.sonarsource.sonarlint.core.client.api.common.analysis.ClientInputFile;
import org.sonarsource.sonarlint.core.client.api.common.analysis.IssueListener;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedAnalysisConfiguration;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedRuleDetails;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine;
import org.sonarsource.sonarlint.core.client.api.connected.ProjectBinding;
import org.sonarsource.sonarlint.core.client.api.exceptions.StorageException;
import org.sonarsource.sonarlint.core.container.storage.StorageActiveRuleAdapter;
import org.sonarsource.sonarlint.core.container.storage.StorageReader;
import org.sonarsource.sonarlint.core.container.storage.StorageRuleAdapter;
import org.sonarsource.sonarlint.core.proto.Sonarlint;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.stream.Collectors.toList;
import static org.sonarlint.intellij.config.Settings.getSettingsFor;

class ConnectedSonarLintFacade extends SonarLintFacade {
  private final ConnectedSonarLintEngine engine;
  private final String connectionId;

  ConnectedSonarLintFacade(String connectionId, ConnectedSonarLintEngine engine, Project project) {
    super(project);
    this.connectionId = connectionId;
    Preconditions.checkNotNull(project, "project");
    Preconditions.checkNotNull(project.getBasePath(), "project base path");
    Preconditions.checkNotNull(engine, "engine");
    this.engine = engine;
  }

  @Override
  protected AnalysisResults analyze(Path baseDir, Path workDir, Collection<ClientInputFile> inputFiles, Map<String, String> props,
    IssueListener issueListener, ProgressMonitor progressMonitor) {
    ConnectedAnalysisConfiguration config = ConnectedAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFiles(inputFiles)
      .setProjectKey(getSettingsFor(project).getProjectKey())
      .putAllExtraProperties(props)
      .build();
    SonarLintConsole console = SonarLintUtils.getService(project, SonarLintConsole.class);
    console.debug("Starting analysis with configuration:\n" + config.toString());

    final AnalysisResults analysisResults = engine.analyze(config, issueListener, new ProjectLogOutput(project), progressMonitor);
    AnalysisRequirementNotifications.notifyOnceForSkippedPlugins(analysisResults, engine.getPluginDetails(), project);
    return analysisResults;
  }

  @Override
  public Collection<VirtualFile> getExcluded(Module module, Collection<VirtualFile> files, Predicate<VirtualFile> testPredicate) {
    ModuleBindingManager bindingManager = SonarLintUtils.getService(module, ModuleBindingManager.class);
    ProjectBinding binding = bindingManager.getBinding();
    if (binding == null) {
      // should never happen since the project should be bound!
      return Collections.emptyList();
    }

    Function<VirtualFile, String> ideFilePathExtractor = s -> SonarLintAppUtils.getPathRelativeToProjectBaseDir(module.getProject(), s);
    return engine.getExcludedFiles(binding, files, ideFilePathExtractor, testPredicate);
  }

  @Override
  public Collection<PluginDetails> getPluginDetails() {
    return engine.getPluginDetails();
  }

  @Override
  public ConnectedRuleDetails getActiveRuleDetails(String ruleKey) {
    return engine.getActiveRuleDetails(ruleKey, getSettingsFor(project).getProjectKey());
  }

  @Override
  public String getDescription(String ruleKey) {
    ConnectedRuleDetails details = getActiveRuleDetails(ruleKey);
    if (details == null) {
      return null;
    }
    String extendedDescription = details.getExtendedDescription();
    if (extendedDescription.isEmpty()) {
      return details.getHtmlDescription();
    }
    return details.getHtmlDescription() + "<br/><br/>" + extendedDescription;
  }
  public RuleData getRuleData(String projectKey){
    RuleData ruleData = new RuleData();
    ruleData.setConnection(true);
    HashMap<String, ActiveRule> activeRuleMap = new HashMap<>();
    HashMap<String, Rule> ruleMap = new HashMap<>();
    ruleData.setRuleMap(ruleMap);
    ruleData.setActiveRuleMap(activeRuleMap);
    ConnectedSonarLintEngineImpl connectedSonarLintEngine = (ConnectedSonarLintEngineImpl) this.engine;
    StorageReader storageReader = connectedSonarLintEngine.getGlobalContainer().getComponentByType(StorageReader.class);
    Map<String, String> qProfilesByLanguage = storageReader.readProjectConfig(projectKey).getQprofilePerLanguageMap();
    for (Map.Entry<String, String> entry : qProfilesByLanguage.entrySet()) {
      String language = entry.getKey();
      String qProfileKey = entry.getValue();
      if (language.equals("java")){
        Sonarlint.ActiveRules activeRulesFromStorage = storageReader.readActiveRules(qProfileKey);
        Sonarlint.Rules rules = storageReader.readRules();
        for (Sonarlint.ActiveRules.ActiveRule activeRule : activeRulesFromStorage.getActiveRulesByKeyMap().values()) {
          if (activeRule.getRepo().equals("pmd")){
            ActiveRule activeRule1 = createNewActiveRule(activeRule, rules);
            activeRuleMap.put(activeRule.getKey(),activeRule1);
          }
        }
        for (Map.Entry<String, Sonarlint.Rules.Rule> rule : rules.getRulesByKeyMap().entrySet()) {
          if (rule.getValue().getRepo().equals("pmd")) {
            Sonarlint.Rules.Rule r = rule.getValue();
            StorageRuleAdapter storageRuleAdapter = new StorageRuleAdapter(r);
            ruleMap.put(storageRuleAdapter.key().rule(), storageRuleAdapter);
          }
        }
      }
    }
    return ruleData;
  }
  private static org.sonar.api.batch.rule.ActiveRule createNewActiveRule(Sonarlint.ActiveRules.ActiveRule activeRule, Sonarlint.Rules storageRules) {
    RuleKey ruleKey = RuleKey.of(activeRule.getRepo(), activeRule.getKey());
    Sonarlint.Rules.Rule storageRule;
    try {
      storageRule = storageRules.getRulesByKeyOrThrow(ruleKey.toString());
    } catch (IllegalArgumentException e) {
      throw new StorageException("Unknown active rule in the quality profile of the project. Please update the SonarQube server binding.", e);
    }

    return new StorageActiveRuleAdapter(activeRule, storageRule);
  }

  private static List<org.sonarsource.sonarlint.core.client.api.common.analysis.Issue.Flow> mapFlows(List<Issue.Flow> flows) {
    return flows.stream()
            .map(f -> new DefaultFlow(f.locations()
                    .stream()
                    .collect(toList())))
            .filter(f -> !f.locations().isEmpty())
            .collect(toList());
  }
}
