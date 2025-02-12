// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.log.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.messages.MessageBus;
import com.intellij.vcs.log.VcsLogFilterCollection;
import com.intellij.vcs.log.VcsLogUi;
import com.intellij.vcs.log.data.VcsLogData;
import com.intellij.vcs.log.ui.MainVcsLogUi;
import com.intellij.vcs.log.visible.filters.VcsLogFiltersKt;
import org.jetbrains.annotations.CalledInAwt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

public class VcsLogTabsManager {
  @NotNull private final Project myProject;
  @NotNull private final VcsLogProjectTabsProperties myUiProperties;
  private boolean myIsLogDisposing = false;

  VcsLogTabsManager(@NotNull Project project,
                    @NotNull MessageBus messageBus,
                    @NotNull VcsLogProjectTabsProperties uiProperties,
                    @NotNull Disposable parent) {
    myProject = project;
    myUiProperties = uiProperties;

    messageBus.connect(parent).subscribe(VcsProjectLog.VCS_PROJECT_LOG_CHANGED, new VcsProjectLog.ProjectLogListener() {
      @Override
      public void logCreated(@NotNull VcsLogManager manager) {
        myIsLogDisposing = false;
        createLogTabs(manager);
      }

      @Override
      public void logDisposed(@NotNull VcsLogManager manager) {
        myIsLogDisposing = true;
      }
    });
  }

  @CalledInAwt
  private void createLogTabs(@NotNull VcsLogManager manager) {
    List<String> tabIds = myUiProperties.getTabs();
    for (String tabId : tabIds) {
      openLogTab(manager, tabId, false, null);
    }
  }

  // for statistics
  @NotNull
  public List<String> getTabs() {
    return myUiProperties.getTabs();
  }

  @NotNull
  MainVcsLogUi openAnotherLogTab(@NotNull VcsLogManager manager, @Nullable VcsLogFilterCollection filters) {
    return openLogTab(manager, generateTabId(myProject), true, filters);
  }

  @NotNull
  private MainVcsLogUi openLogTab(@NotNull VcsLogManager manager, @NotNull String tabId, boolean focus,
                                  @Nullable VcsLogFilterCollection filters) {
    if (filters != null) myUiProperties.resetState(tabId);

    VcsLogManager.VcsLogUiFactory<? extends MainVcsLogUi> factory =
      new PersistentVcsLogUiFactory(manager.getMainLogUiFactory(tabId, filters));
    MainVcsLogUi ui = VcsLogContentUtil.openLogTab(myProject, manager, VcsLogContentProvider.TAB_NAME, tabId, factory, focus);
    updateTabName(ui);
    ui.addFilterListener(() -> updateTabName(ui));
    return ui;
  }

  private void updateTabName(@NotNull VcsLogUi ui) {
    VcsLogContentUtil.renameLogUi(myProject, ui, generateDisplayName(ui));
  }

  @NotNull
  public static String generateDisplayName(@NotNull VcsLogUi ui) {
    VcsLogFilterCollection filters = ui.getFilterUi().getFilters();
    if (filters.isEmpty()) return "all";
    return StringUtil.shortenTextWithEllipsis(VcsLogFiltersKt.getPresentation(filters), 150, 20);
  }

  @NotNull
  private static String generateTabId(@NotNull Project project) {
    Set<String> existingIds = VcsLogContentUtil.getExistingLogIds(project);
    for (int i = 1; ; i++) {
      String idString = Integer.toString(i);
      if (!existingIds.contains(idString)) {
        return idString;
      }
    }
  }

  private class PersistentVcsLogUiFactory implements VcsLogManager.VcsLogUiFactory<MainVcsLogUi> {
    private final VcsLogManager.VcsLogUiFactory<? extends MainVcsLogUi> myFactory;

    PersistentVcsLogUiFactory(@NotNull VcsLogManager.VcsLogUiFactory<? extends MainVcsLogUi> factory) {
      myFactory = factory;
    }

    @Override
    public MainVcsLogUi createLogUi(@NotNull Project project,
                                    @NotNull VcsLogData logData) {
      MainVcsLogUi ui = myFactory.createLogUi(project, logData);
      myUiProperties.addTab(ui.getId());
      Disposer.register(ui, () -> {
        if (Disposer.isDisposing(myProject) || myIsLogDisposing) return; // need to restore the tab after project/log is recreated

        myUiProperties.removeTab(ui.getId()); // tab is closed by a user
      });
      return ui;
    }
  }
}
