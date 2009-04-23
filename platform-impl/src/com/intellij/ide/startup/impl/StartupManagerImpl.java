package com.intellij.ide.startup.impl;

import com.intellij.ide.startup.FileSystemSynchronizer;
import com.intellij.ide.startup.StartupManagerEx;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.containers.CollectionFactory;
import com.intellij.util.io.storage.HeavyProcessLatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * @author mike
 */
public class StartupManagerImpl extends StartupManagerEx {
  private final List<Runnable> myActivities = new ArrayList<Runnable>();
  private final List<Runnable> myPostStartupActivities = Collections.synchronizedList(new ArrayList<Runnable>());
  private final List<Runnable> myPreStartupActivities = Collections.synchronizedList(new ArrayList<Runnable>());

  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.startup.impl.StartupManagerImpl");

  private FileSystemSynchronizer myFileSystemSynchronizer = new FileSystemSynchronizer();
  private boolean myStartupActivityRunning = false;
  private boolean myStartupActivityPassed = false;

  private final Project myProject;

  public StartupManagerImpl(Project project) {
    myProject = project;
  }

  public void registerStartupActivity(Runnable runnable) {
    myActivities.add(runnable);
  }

  public synchronized void registerPostStartupActivity(Runnable runnable) {
    myPostStartupActivities.add(runnable);
  }

  public boolean startupActivityRunning() {
    return myStartupActivityRunning;
  }

  public boolean startupActivityPassed() {
    return myStartupActivityPassed;
  }

  public void registerPreStartupActivity(Runnable runnable) {
    myPreStartupActivities.add(runnable);
  }

  public FileSystemSynchronizer getFileSystemSynchronizer() {
    return myFileSystemSynchronizer;
  }

  public void runStartupActivities() {
    ApplicationManager.getApplication().runReadAction(
      new Runnable() {
        public void run() {
          HeavyProcessLatch.INSTANCE.processStarted();
          try {
            runActivities(myPreStartupActivities);
            if (myFileSystemSynchronizer != null || !ApplicationManager.getApplication().isUnitTestMode()) {
              myFileSystemSynchronizer.setCancelable(true);
              myFileSystemSynchronizer.execute();
              myFileSystemSynchronizer = null;
            }
            myStartupActivityRunning = true;
            runActivities(myActivities);

            myStartupActivityRunning = false;
            myStartupActivityPassed = true;
          }
          finally {
            HeavyProcessLatch.INSTANCE.processFinished();
          }
        }
      }
    );
  }

  public synchronized void runPostStartupActivities() {
    final Application app = ApplicationManager.getApplication();
    app.assertIsDispatchThread();
    if (DumbService.UPDATE_IN_BACKGROUND) {
      final List<Runnable> dumbAware = CollectionFactory.arrayList();
      for (Iterator<Runnable> iterator = myPostStartupActivities.iterator(); iterator.hasNext();) {
        Runnable runnable = iterator.next();
        if (runnable instanceof DumbAware) {
          dumbAware.add(runnable);
          iterator.remove();
        }
      }
      runActivities(dumbAware);
      DumbService.getInstance().runWhenSmart(new Runnable() {
        public void run() {
          if (myProject.isDisposed()) return;
          
          runActivities(myPostStartupActivities);
          myPostStartupActivities.clear();
        }
      });
    } else {
      runActivities(myPostStartupActivities);
      myPostStartupActivities.clear();
    }

    if (app.isUnitTestMode()) return;

    VirtualFileManager.getInstance().refresh(!app.isHeadlessEnvironment());
  }

  private static void runActivities(final List<Runnable> activities) {
    final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    while (!activities.isEmpty()) {
      final Runnable runnable = activities.remove(0);
      if (indicator != null) indicator.checkCanceled();

      try {
        runnable.run();
      }
      catch (ProcessCanceledException e) {
        throw e;
      }
      catch (Throwable ex) {
        LOG.error(ex);
      }
    }
  }

  public void runWhenProjectIsInitialized(final Runnable action) {
    final Runnable runnable = new Runnable() {
      public void run() {
        ApplicationManager.getApplication().runWriteAction(action);
      }
    };

    if (myProject.isInitialized()) {
      runnable.run();
    }
    else {
      registerPostStartupActivity(new Runnable(){
        public void run() {
          runnable.run();
        }
      });
    }
  }
}
