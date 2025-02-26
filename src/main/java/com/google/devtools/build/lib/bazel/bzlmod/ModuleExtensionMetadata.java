// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;

/** The Starlark object passed to the implementation function of module extension metadata. */
@StarlarkBuiltin(
    name = "extension_metadata",
    category = DocCategory.BUILTIN,
    doc =
        "Return values of this type from a module extension's implementation function to "
            + "provide metadata about the repositories generated by the extension to Bazel.")
@AutoValue
@GenerateTypeAdapter
public abstract class ModuleExtensionMetadata implements StarlarkValue {

  static final ModuleExtensionMetadata REPRODUCIBLE =
      create(
          /* explicitRootModuleDirectDeps= */ null,
          /* explicitRootModuleDirectDevDeps= */ null,
          UseAllRepos.NO,
          /* reproducible= */ true);

  @Nullable
  abstract ImmutableSet<String> getExplicitRootModuleDirectDeps();

  @Nullable
  abstract ImmutableSet<String> getExplicitRootModuleDirectDevDeps();

  abstract UseAllRepos getUseAllRepos();

  abstract boolean getReproducible();

  private static ModuleExtensionMetadata create(
      @Nullable Set<String> explicitRootModuleDirectDeps,
      @Nullable Set<String> explicitRootModuleDirectDevDeps,
      UseAllRepos useAllRepos,
      boolean reproducible) {
    return new AutoValue_ModuleExtensionMetadata(
        explicitRootModuleDirectDeps != null
            ? ImmutableSet.copyOf(explicitRootModuleDirectDeps)
            : null,
        explicitRootModuleDirectDevDeps != null
            ? ImmutableSet.copyOf(explicitRootModuleDirectDevDeps)
            : null,
        useAllRepos,
        reproducible);
  }

  static ModuleExtensionMetadata create(
      Object rootModuleDirectDepsUnchecked,
      Object rootModuleDirectDevDepsUnchecked,
      boolean reproducible)
      throws EvalException {
    if (rootModuleDirectDepsUnchecked == Starlark.NONE
        && rootModuleDirectDevDepsUnchecked == Starlark.NONE) {
      return create(null, null, UseAllRepos.NO, reproducible);
    }

    // When root_module_direct_deps = "all", accept both root_module_direct_dev_deps = None and
    // root_module_direct_dev_deps = [], but not root_module_direct_dev_deps = ["some_repo"].
    if (rootModuleDirectDepsUnchecked.equals("all")
        && rootModuleDirectDevDepsUnchecked.equals(StarlarkList.immutableOf())) {
      return create(null, null, UseAllRepos.REGULAR, reproducible);
    }

    if (rootModuleDirectDevDepsUnchecked.equals("all")
        && rootModuleDirectDepsUnchecked.equals(StarlarkList.immutableOf())) {
      return create(null, null, UseAllRepos.DEV, reproducible);
    }

    if (rootModuleDirectDepsUnchecked.equals("all")
        || rootModuleDirectDevDepsUnchecked.equals("all")) {
      throw Starlark.errorf(
          "if one of root_module_direct_deps and root_module_direct_dev_deps is "
              + "\"all\", the other must be an empty list");
    }

    if (rootModuleDirectDepsUnchecked instanceof String
        || rootModuleDirectDevDepsUnchecked instanceof String) {
      throw Starlark.errorf(
          "root_module_direct_deps and root_module_direct_dev_deps must be "
              + "None, \"all\", or a list of strings");
    }
    if ((rootModuleDirectDepsUnchecked == Starlark.NONE)
        != (rootModuleDirectDevDepsUnchecked == Starlark.NONE)) {
      throw Starlark.errorf(
          "root_module_direct_deps and root_module_direct_dev_deps must both be "
              + "specified or both be unspecified");
    }

    Sequence<String> rootModuleDirectDeps =
        Sequence.cast(rootModuleDirectDepsUnchecked, String.class, "root_module_direct_deps");
    Sequence<String> rootModuleDirectDevDeps =
        Sequence.cast(
            rootModuleDirectDevDepsUnchecked, String.class, "root_module_direct_dev_deps");

    Set<String> explicitRootModuleDirectDeps = new LinkedHashSet<>();
    for (String dep : rootModuleDirectDeps) {
      try {
        RepositoryName.validateUserProvidedRepoName(dep);
      } catch (EvalException e) {
        throw Starlark.errorf("in root_module_direct_deps: %s", e.getMessage());
      }
      if (!explicitRootModuleDirectDeps.add(dep)) {
        throw Starlark.errorf("in root_module_direct_deps: duplicate entry '%s'", dep);
      }
    }

    Set<String> explicitRootModuleDirectDevDeps = new LinkedHashSet<>();
    for (String dep : rootModuleDirectDevDeps) {
      try {
        RepositoryName.validateUserProvidedRepoName(dep);
      } catch (EvalException e) {
        throw Starlark.errorf("in root_module_direct_dev_deps: %s", e.getMessage());
      }
      if (explicitRootModuleDirectDeps.contains(dep)) {
        throw Starlark.errorf(
            "in root_module_direct_dev_deps: entry '%s' is also in " + "root_module_direct_deps",
            dep);
      }
      if (!explicitRootModuleDirectDevDeps.add(dep)) {
        throw Starlark.errorf("in root_module_direct_dev_deps: duplicate entry '%s'", dep);
      }
    }

    return create(
        explicitRootModuleDirectDeps,
        explicitRootModuleDirectDevDeps,
        UseAllRepos.NO,
        reproducible);
  }

  public Optional<RootModuleFileFixup> generateFixup(
      ModuleExtensionUsage rootUsage, Set<String> allRepos) throws EvalException {
    var rootModuleDirectDevDeps = getRootModuleDirectDevDeps(allRepos);
    var rootModuleDirectDeps = getRootModuleDirectDeps(allRepos);
    if (rootModuleDirectDevDeps.isEmpty() && rootModuleDirectDeps.isEmpty()) {
      return Optional.empty();
    }
    Preconditions.checkState(
        rootModuleDirectDevDeps.isPresent() && rootModuleDirectDeps.isPresent());

    if (!rootUsage.getHasNonDevUseExtension() && !rootModuleDirectDeps.get().isEmpty()) {
      throw Starlark.errorf(
          "root_module_direct_deps must be empty if the root module contains no "
              + "usages with dev_dependency = False");
    }
    if (!rootUsage.getHasDevUseExtension() && !rootModuleDirectDevDeps.get().isEmpty()) {
      throw Starlark.errorf(
          "root_module_direct_dev_deps must be empty if the root module contains no "
              + "usages with dev_dependency = True");
    }

    return generateFixup(
        rootUsage, allRepos, rootModuleDirectDeps.get(), rootModuleDirectDevDeps.get());
  }

  private static Optional<RootModuleFileFixup> generateFixup(
      ModuleExtensionUsage rootUsage,
      Set<String> allRepos,
      Set<String> expectedImports,
      Set<String> expectedDevImports) {
    var actualDevImports =
        rootUsage.getProxies().stream()
            .filter(p -> p.isDevDependency())
            .flatMap(p -> p.getImports().values().stream())
            .collect(toImmutableSet());
    var actualImports =
        rootUsage.getProxies().stream()
            .filter(p -> !p.isDevDependency())
            .flatMap(p -> p.getImports().values().stream())
            .collect(toImmutableSet());

    String extensionBzlFile = rootUsage.getExtensionBzlFile();
    String extensionName = rootUsage.getExtensionName();

    var importsToAdd = ImmutableSortedSet.copyOf(Sets.difference(expectedImports, actualImports));
    var importsToRemove =
        ImmutableSortedSet.copyOf(Sets.difference(actualImports, expectedImports));
    var devImportsToAdd =
        ImmutableSortedSet.copyOf(Sets.difference(expectedDevImports, actualDevImports));
    var devImportsToRemove =
        ImmutableSortedSet.copyOf(Sets.difference(actualDevImports, expectedDevImports));

    if (importsToAdd.isEmpty()
        && importsToRemove.isEmpty()
        && devImportsToAdd.isEmpty()
        && devImportsToRemove.isEmpty()) {
      return Optional.empty();
    }

    var message =
        String.format(
            "The module extension %s defined in %s reported incorrect imports "
                + "of repositories via use_repo():\n\n",
            extensionName, extensionBzlFile);

    var allActualImports = ImmutableSortedSet.copyOf(Sets.union(actualImports, actualDevImports));
    var allExpectedImports =
        ImmutableSortedSet.copyOf(Sets.union(expectedImports, expectedDevImports));

    var invalidImports = ImmutableSortedSet.copyOf(Sets.difference(allActualImports, allRepos));
    if (!invalidImports.isEmpty()) {
      message +=
          String.format(
              "Imported, but not created by the extension (will cause the build to fail):\n"
                  + "    %s\n\n",
              String.join(", ", invalidImports));
    }

    var missingImports =
        ImmutableSortedSet.copyOf(Sets.difference(allExpectedImports, allActualImports));
    if (!missingImports.isEmpty()) {
      message +=
          String.format(
              "Not imported, but reported as direct dependencies by the extension (may cause the"
                  + " build to fail):\n"
                  + "    %s\n\n",
              String.join(", ", missingImports));
    }

    var nonDevImportsOfDevDeps =
        ImmutableSortedSet.copyOf(Sets.intersection(expectedDevImports, actualImports));
    if (!nonDevImportsOfDevDeps.isEmpty()) {
      message +=
          String.format(
              "Imported as a regular dependency, but reported as a dev dependency by the "
                  + "extension (may cause the build to fail when used by other modules):\n"
                  + "    %s\n\n",
              String.join(", ", nonDevImportsOfDevDeps));
    }

    var devImportsOfNonDevDeps =
        ImmutableSortedSet.copyOf(Sets.intersection(expectedImports, actualDevImports));
    if (!devImportsOfNonDevDeps.isEmpty()) {
      message +=
          String.format(
              "Imported as a dev dependency, but reported as a regular dependency by the "
                  + "extension (may cause the build to fail when used by other modules):\n"
                  + "    %s\n\n",
              String.join(", ", devImportsOfNonDevDeps));
    }

    var indirectDepImports =
        ImmutableSortedSet.copyOf(
            Sets.difference(Sets.intersection(allActualImports, allRepos), allExpectedImports));
    if (!indirectDepImports.isEmpty()) {
      message +=
          String.format(
              "Imported, but reported as indirect dependencies by the extension:\n    %s\n\n",
              String.join(", ", indirectDepImports));
    }

    message += "Fix the use_repo calls by running 'bazel mod tidy'.";

    var moduleFilePathToCommandsBuilder = ImmutableListMultimap.<PathFragment, String>builder();
    // Repos to add are easy: always add them to the first proxy of the correct type.
    if (!importsToAdd.isEmpty()) {
      Proxy firstNonDevProxy =
          rootUsage.getProxies().stream().filter(p -> !p.isDevDependency()).findFirst().get();
      moduleFilePathToCommandsBuilder.put(
          firstNonDevProxy.getContainingModuleFilePath(),
          makeUseRepoCommand("use_repo_add", firstNonDevProxy.getProxyName(), importsToAdd));
    }
    if (!devImportsToAdd.isEmpty()) {
      Proxy firstDevProxy =
          rootUsage.getProxies().stream().filter(p -> p.isDevDependency()).findFirst().get();
      moduleFilePathToCommandsBuilder.put(
          firstDevProxy.getContainingModuleFilePath(),
          makeUseRepoCommand("use_repo_add", firstDevProxy.getProxyName(), devImportsToAdd));
    }
    // Repos to remove are a bit trickier: remove them from the proxy that actually imported them.
    for (Proxy proxy : rootUsage.getProxies()) {
      var toRemove =
          ImmutableSortedSet.copyOf(
              Sets.intersection(
                  proxy.getImports().values(),
                  proxy.isDevDependency() ? devImportsToRemove : importsToRemove));
      if (!toRemove.isEmpty()) {
        moduleFilePathToCommandsBuilder.put(
            proxy.getContainingModuleFilePath(),
            makeUseRepoCommand("use_repo_remove", proxy.getProxyName(), toRemove));
      }
    }

    return Optional.of(
        new RootModuleFileFixup(
            moduleFilePathToCommandsBuilder.build(),
            rootUsage,
            Event.warn(rootUsage.getProxies().getFirst().getLocation(), message)));
  }

  private static String makeUseRepoCommand(String cmd, String proxyName, Collection<String> repos) {
    var commandParts = new ArrayList<String>();
    commandParts.add(cmd);
    commandParts.add(proxyName.isEmpty() ? "_unnamed_usage" : proxyName);
    commandParts.addAll(repos);
    return String.join(" ", commandParts);
  }

  private Optional<ImmutableSet<String>> getRootModuleDirectDeps(Set<String> allRepos)
      throws EvalException {
    return switch (getUseAllRepos()) {
      case NO -> {
        if (getExplicitRootModuleDirectDeps() != null) {
          Set<String> invalidRepos = Sets.difference(getExplicitRootModuleDirectDeps(), allRepos);
          if (!invalidRepos.isEmpty()) {
            throw Starlark.errorf(
                "root_module_direct_deps contained the following repositories "
                    + "not generated by the extension: %s",
                String.join(", ", invalidRepos));
          }
        }
        yield Optional.ofNullable(getExplicitRootModuleDirectDeps());
      }
      case REGULAR -> Optional.of(ImmutableSet.copyOf(allRepos));
      case DEV -> Optional.of(ImmutableSet.of());
    };
  }

  private Optional<ImmutableSet<String>> getRootModuleDirectDevDeps(Set<String> allRepos)
      throws EvalException {
    switch (getUseAllRepos()) {
      case NO:
        if (getExplicitRootModuleDirectDevDeps() != null) {
          Set<String> invalidRepos =
              Sets.difference(getExplicitRootModuleDirectDevDeps(), allRepos);
          if (!invalidRepos.isEmpty()) {
            throw Starlark.errorf(
                "root_module_direct_dev_deps contained the following "
                    + "repositories not generated by the extension: %s",
                String.join(", ", invalidRepos));
          }
        }
        return Optional.ofNullable(getExplicitRootModuleDirectDevDeps());
      case REGULAR:
        return Optional.of(ImmutableSet.of());
      case DEV:
        return Optional.of(ImmutableSet.copyOf(allRepos));
    }
    throw new IllegalStateException("not reached");
  }

  enum UseAllRepos {
    NO,
    REGULAR,
    DEV,
  }
}
