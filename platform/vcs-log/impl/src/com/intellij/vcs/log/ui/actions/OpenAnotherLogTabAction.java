/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.vcs.log.ui.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.ObjectUtils;
import com.intellij.vcs.log.VcsLogDataKeys;
import com.intellij.vcs.log.VcsLogFilterCollection;
import com.intellij.vcs.log.VcsLogUi;
import com.intellij.vcs.log.impl.VcsLogManager;
import com.intellij.vcs.log.impl.VcsProjectLog;
import com.intellij.vcs.log.statistics.VcsLogUsageTriggerCollector;
import com.intellij.vcs.log.ui.VcsLogInternalDataKeys;
import com.intellij.vcs.log.util.VcsLogUtil;
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject;
import org.jetbrains.annotations.NotNull;

public class OpenAnotherLogTabAction extends DumbAwareAction {
  protected OpenAnotherLogTabAction() {
    super(getText("Vcs"), getDescription("Vcs"), AllIcons.Actions.OpenNewTab);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      e.getPresentation().setEnabledAndVisible(false);
      return;
    }

    VcsProjectLog projectLog = VcsProjectLog.getInstance(project);
    VcsLogManager logManager = ObjectUtils.chooseNotNull(e.getData(VcsLogInternalDataKeys.LOG_MANAGER), projectLog.getLogManager());
    if (logManager == null) {
      e.getPresentation().setEnabledAndVisible(false);
      return;
    }

    // only for main log (it is a question, how and where we want to open tabs for external logs)
    e.getPresentation().setEnabledAndVisible(projectLog.getLogManager() == logManager);

    String vcsName = VcsLogUtil.getVcsDisplayName(project, logManager.getDataManager().getLogProviders().values());
    e.getPresentation().setText(getText(vcsName));
    e.getPresentation().setDescription(getDescription(vcsName));
  }

  @NotNull
  private static String getDescription(@NotNull String vcsName) {
    return "Open new tab with " + vcsName + " Log";
  }

  @NotNull
  private static String getText(@NotNull String vcsName) {
    return "Open New " + vcsName + " Log Tab";
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    VcsLogUsageTriggerCollector.triggerUsage(e, this);

    Project project = e.getRequiredData(CommonDataKeys.PROJECT);
    VcsLogUi logUi = e.getData(VcsLogDataKeys.VCS_LOG_UI);

    VcsLogFilterCollection filters;
    if (Registry.is("vcs.log.copy.filters.to.new.tab") && logUi != null) {
      filters = logUi.getFilterUi().getFilters();
    }
    else {
      filters = VcsLogFilterObject.collection();
    }

    VcsProjectLog.getInstance(project).openLogTab(filters);
  }
}
