// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.module;

import com.intellij.ide.util.frameworkSupport.FrameworkRole;
import com.intellij.ide.util.projectWizard.ModuleBuilder;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.SettingsStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ui.configuration.ModuleConfigurationEditorProvider;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;

import javax.swing.*;

/**
 * Defines a technology (language, framework, etc) to which a particular {@link Module} instance is related. Each {@link Module} belongs to
 * some type (see {@link ModuleType#get(Module)}) and it can be used to provide custom UI for New Project and Project Structure dialogs, and
 * to customize other feature of the IDE (e.g. enable some actions only for files in modules of a specific type).
 * <p>
 * <strong>Module Type concept is considered as outdated.</strong> Enabling some features only in modules of a specific type makes it harder
 * to mix different technologies in the same source directory, and cause conflicts in configuration files when the same directory is opened
 * in different IDEs.
 * </p>
 * If you need
 * to show special kinds of projects in New Project wizard, register an implemention of {@link ModuleBuilder} as an extension instead. If you
 * need to allow users to configure something related to some technology in the IDE, use {@link com.intellij.openapi.options.Configurable projectConfigurable}
 * for project-level settings and {@link com.intellij.facet.Facet} or {@link ModuleConfigurationEditorProvider} for module-level settings.
 * If you need to make an action enabled in presence of a specific technology only, do this by looking for required files in the project
 * directories, not by checking type of the current module.
 */
public abstract class ModuleType<T extends ModuleBuilder> {
  public static final ModuleType<?> EMPTY;

  @NotNull
  private final String myId;
  private final FrameworkRole myFrameworkRole;

  protected ModuleType(@NotNull @NonNls String id) {
    myId = id;
    myFrameworkRole = new FrameworkRole(id);
  }

  @NotNull
  public abstract T createModuleBuilder();

  @NotNull
  @Nls(capitalization = Nls.Capitalization.Title)
  public abstract String getName();

  @NotNull
  @Nls(capitalization = Nls.Capitalization.Sentence)
  public abstract String getDescription();

  @NotNull
  public Icon getIcon() {
    return getNodeIcon(false);
  }

  @NotNull
  public abstract Icon getNodeIcon(@Deprecated boolean isOpened);

  @NotNull
  public ModuleWizardStep[] createWizardSteps(@NotNull WizardContext wizardContext, @NotNull T moduleBuilder, @NotNull ModulesProvider modulesProvider) {
    return ModuleWizardStep.EMPTY_ARRAY;
  }

  @Nullable
  public ModuleWizardStep modifySettingsStep(@NotNull SettingsStep settingsStep, @NotNull ModuleBuilder moduleBuilder) {
    return null;
  }

  @Nullable
  public ModuleWizardStep modifyProjectTypeStep(@NotNull SettingsStep settingsStep, @NotNull ModuleBuilder moduleBuilder) {
    return null;
  }

  @NotNull
  public final String getId() {
    return myId;
  }

  public final boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ModuleType)) return false;

    final ModuleType moduleType = (ModuleType)o;

    return myId.equals(moduleType.myId);
  }

  public final int hashCode() {
    return myId.hashCode();
  }

  public String toString() {
    return getName();
  }

  static {
    EMPTY = instantiate("com.intellij.openapi.module.EmptyModuleType");
  }

  @NotNull
  private static ModuleType instantiate(String className) {
    try {
      return (ModuleType)Class.forName(className).newInstance();
    }
    catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public boolean isValidSdk(@NotNull Module module, @Nullable final Sdk projectSdk) {
    return true;
  }

  public static boolean is(@NotNull Module module, @NotNull ModuleType moduleType) {
    return moduleType.getId().equals(module.getModuleTypeName());
  }

  public static boolean isInternal(@NotNull Module module) {
    return get(module) instanceof InternalModuleType;
  }

  @NotNull
  public static ModuleType get(@NotNull Module module) {
    final ModuleTypeManager instance = ModuleTypeManager.getInstance();
    if (instance == null) {
      return EMPTY;
    }
    return instance.findByID(module.getModuleTypeName());
  }

  @NotNull
  public FrameworkRole getDefaultAcceptableRole() {
    return myFrameworkRole;
  }

  public boolean isSupportedRootType(JpsModuleSourceRootType type) {
    return true;
  }

  public boolean isMarkInnerSupportedFor(JpsModuleSourceRootType type) {
    return false;
  }
}
