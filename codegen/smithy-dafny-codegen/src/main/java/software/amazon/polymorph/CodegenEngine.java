// Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.polymorph;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.polymorph.smithydafny.DafnyApiCodegen;
import software.amazon.polymorph.smithydafny.DafnyNameResolver;
import software.amazon.polymorph.smithydafny.DafnyVersion;
import software.amazon.polymorph.smithydotnet.AwsSdkShimCodegen;
import software.amazon.polymorph.smithydotnet.AwsSdkTypeConversionCodegen;
import software.amazon.polymorph.smithydotnet.DotNetNameResolver;
import software.amazon.polymorph.smithydotnet.ServiceCodegen;
import software.amazon.polymorph.smithydotnet.ShimCodegen;
import software.amazon.polymorph.smithydotnet.TypeConversionCodegen;
import software.amazon.polymorph.smithydotnet.TypeConversionDirection;
import software.amazon.polymorph.smithydotnet.localServiceWrapper.LocalServiceWrappedCodegen;
import software.amazon.polymorph.smithydotnet.localServiceWrapper.LocalServiceWrappedConversionCodegen;
import software.amazon.polymorph.smithydotnet.localServiceWrapper.LocalServiceWrappedShimCodegen;
import software.amazon.polymorph.smithyjava.generator.CodegenSubject.AwsSdkVersion;
import software.amazon.polymorph.smithyjava.generator.awssdk.v1.JavaAwsSdkV1;
import software.amazon.polymorph.smithyjava.generator.awssdk.v2.JavaAwsSdkV2;
import software.amazon.polymorph.smithyjava.generator.library.JavaLibrary;
import software.amazon.polymorph.smithyjava.generator.library.TestJavaLibrary;
import software.amazon.polymorph.traits.LocalServiceTrait;
import software.amazon.polymorph.utils.DafnyNameResolverHelpers;
import software.amazon.polymorph.utils.IOUtils;
import software.amazon.polymorph.utils.ModelUtils;
import software.amazon.polymorph.utils.TokenTree;
import software.amazon.smithy.aws.traits.ServiceTrait;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.Pair;

public class CodegenEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(
    CodegenEngine.class
  );

  // Used to distinguish different conventions between the CLI
  // and the Smithy build plugin, such as where .NET project files live.
  private final boolean fromSmithyBuildPlugin;
  private final Path libraryRoot;
  private final Path[] dependentModelPaths;
  private final String namespace;
  private final Map<TargetLanguage, Path> targetLangOutputDirs;
  private final Map<TargetLanguage, Path> targetLangTestOutputDirs;
  private final DafnyVersion dafnyVersion;
  private final Optional<Path> propertiesFile;
  private final Optional<Path> patchFilesDir;
  private final boolean updatePatchFiles;
  // refactor this to only be required if generating Java
  private final AwsSdkVersion javaAwsSdkVersion;
  private final Optional<Path> includeDafnyFile;
  private final boolean awsSdkStyle;
  private final boolean localServiceTest;
  private final Set<GenerationAspect> generationAspects;

  // To be initialized in constructor
  private final Model model;
  private final ServiceShape serviceShape;

  /**
   * This should only be called by {@link Builder#build()},
   * which is responsible for validating that the arguments are non-null,
   * are mutually compatible, etc.
   */
  private CodegenEngine(
    final boolean fromSmithyBuildPlugin,
    final Model serviceModel,
    final Path[] dependentModelPaths,
    final String namespace,
    final Map<TargetLanguage, Path> targetLangOutputDirs,
    final Map<TargetLanguage, Path> targetLangTestOutputDirs,
    final DafnyVersion dafnyVersion,
    final Optional<Path> propertiesFile,
    final AwsSdkVersion javaAwsSdkVersion,
    final Optional<Path> includeDafnyFile,
    final boolean awsSdkStyle,
    final boolean localServiceTest,
    final Set<GenerationAspect> generationAspects,
    final Path libraryRoot,
    final Optional<Path> patchFilesDir,
    final boolean updatePatchFiles
  ) {
    // To be provided to constructor
    this.fromSmithyBuildPlugin = fromSmithyBuildPlugin;
    this.dependentModelPaths = dependentModelPaths;
    this.namespace = namespace;
    this.targetLangOutputDirs = targetLangOutputDirs;
    this.targetLangTestOutputDirs = targetLangTestOutputDirs;
    this.dafnyVersion = dafnyVersion;
    this.propertiesFile = propertiesFile;
    this.javaAwsSdkVersion = javaAwsSdkVersion;
    this.includeDafnyFile = includeDafnyFile;
    this.awsSdkStyle = awsSdkStyle;
    this.localServiceTest = localServiceTest;
    this.generationAspects = generationAspects;
    this.libraryRoot = libraryRoot;
    this.patchFilesDir = patchFilesDir;
    this.updatePatchFiles = updatePatchFiles;

    this.model =
      this.awsSdkStyle
        // TODO: move this into a DirectedCodegen.customizeBeforeShapeGeneration implementation
        ? ModelUtils.addMissingErrorMessageMembers(serviceModel)
        : serviceModel;

    this.serviceShape =
      ModelUtils.serviceFromNamespace(this.model, this.namespace);
  }

  /**
   * Executes code generation for the configured language(s).
   * This method is designed to be internally stateless
   * and idempotent with respect to the file system.
   */
  public void run() {
    try {
      LOGGER.debug("Ensuring target-language output directories exist");
      for (final Path dir : this.targetLangOutputDirs.values()) {
        Files.createDirectories(dir);
      }
      for (final Path dir : this.targetLangTestOutputDirs.values()) {
        Files.createDirectories(dir);
      }
    } catch (IOException e) {
      e.printStackTrace();
      System.exit(1);
    }

    for (final TargetLanguage lang : targetLangOutputDirs.keySet()) {
      final Path outputDir = targetLangOutputDirs
        .get(lang)
        .toAbsolutePath()
        .normalize();
      final Path testOutputDir = Optional
        .ofNullable(targetLangTestOutputDirs.get(lang))
        .map(p -> p.toAbsolutePath().normalize())
        .orElse(null);
      switch (lang) {
        case DAFNY -> generateDafny(outputDir);
        case JAVA -> generateJava(outputDir, testOutputDir);
        case DOTNET -> generateDotnet(outputDir);
        case RUST -> generateRust(outputDir);
        default -> throw new UnsupportedOperationException(
          "Cannot generate code for target language %s".formatted(lang.name())
        );
      }
    }

    propertiesFile.ifPresent(this::generateProjectPropertiesFile);
  }

  private void generateProjectPropertiesFile(final Path outputPath)
    throws UncheckedIOException {
    // Drop the pre-release suffix, if any.
    // This means with the current Dafny pre-release naming convention,
    // we'll grab the most recent full release of a Dafny runtime.
    // This mapping may need to change in the future.
    // Ideally this would be handled by the Dafny CLI itself.
    String dafnyVersionString = new DafnyVersion(
      dafnyVersion.getMajor(),
      dafnyVersion.getMinor(),
      dafnyVersion.getPatch()
    )
      .unparse();
    final Map<String, String> parameters = Map.of(
      "dafnyVersion",
      dafnyVersionString
    );
    writeTemplatedFile("project.properties", parameters);
  }

  private void generateDafny(final Path outputDir) {
    // Validated by builder, but check again
    assert this.includeDafnyFile.isPresent();
    final DafnyApiCodegen dafnyApiCodegen = new DafnyApiCodegen(
      model,
      serviceShape,
      outputDir,
      this.includeDafnyFile.get(),
      this.dependentModelPaths,
      this.awsSdkStyle
    );

    if (this.localServiceTest) {
      IOUtils.writeTokenTreesIntoDir(
        dafnyApiCodegen.generateWrappedAbstractServiceModule(outputDir),
        outputDir
      );
      LOGGER.info(
        "Dafny that tests a local service generated in {}",
        outputDir
      );
    } else {
      IOUtils.writeTokenTreesIntoDir(dafnyApiCodegen.generate(), outputDir);
      LOGGER.info("Dafny code generated in {}", outputDir);
    }

    dafnyOtherGeneratedAspects(outputDir);

    LOGGER.info("Formatting Dafny code in {}", outputDir);
    runCommand(
      outputDir,
      "dafny",
      "format",
      "--function-syntax:3",
      "--unicode-char:false",
      "."
    );

    handlePatching(TargetLanguage.DAFNY, outputDir);
  }

  private void dafnyOtherGeneratedAspects(final Path outputDir) {
    final String service = serviceShape.getId().getName();
    final String namespace = serviceShape.getId().getNamespace();
    final String sdkID = awsSdkStyle
      ? serviceShape.expectTrait(ServiceTrait.class).getSdkId()
      : serviceShape.expectTrait(LocalServiceTrait.class).getSdkId();
    final String dafnyNamespace =
      DafnyNameResolverHelpers.packageNameForNamespace(
        serviceShape.getId().getNamespace()
      );
    final String dafnyModuleName = DafnyNameResolver.dafnyBaseModuleName(
      namespace
    );

    Map<String, String> parameters = new HashMap<>();
    parameters.put("dafnyVersion", dafnyVersion.unparse());
    parameters.put("service", service);
    parameters.put("sdkID", sdkID);
    parameters.put("namespace", namespace);
    parameters.put("dafnyNamespace", dafnyNamespace);
    parameters.put("dafnyModuleName", dafnyModuleName);
    parameters.put("stdLibPath", standardLibraryPath().toString());

    if (awsSdkStyle) {
      if (generationAspects.contains(GenerationAspect.CLIENT_CONSTRUCTORS)) {
        writeTemplatedFile("src/$forSDK:LIndex.dfy", parameters);
      }
    } else {
      final String serviceConfig = serviceShape
        .expectTrait(LocalServiceTrait.class)
        .getConfigId()
        .getName();
      parameters.put("serviceConfig", serviceConfig);

      if (generationAspects.contains(GenerationAspect.CLIENT_CONSTRUCTORS)) {
        writeTemplatedFile("src/$forLocalService:LIndex.dfy", parameters);
        if (localServiceTest) {
          writeTemplatedFile("src/Wrapped$service:LImpl.dfy", parameters);
        }
      }

      if (generationAspects.contains(GenerationAspect.IMPL_STUB)) {
        generateDafnySkeleton(outputDir);
        writeTemplatedFile("test/$sdkID:LImplTest.dfy", parameters);
        if (localServiceTest) {
          writeTemplatedFile("test/Wrapped$sdkID:LTest.dfy", parameters);
        }
      }
    }

    // TODO: It would be great to generate the Makefile too,
    // but that's currently a catch 22 because we normally use the shared makefile targets
    // to invoke polymorph in the first place.
    // Perhaps we can make a `smithy init` template for that instead?

    if (!generationAspects.isEmpty()) {
      Path srcDir = outputDir.resolve("../src");
      LOGGER.info("Formatting Dafny code in {}", srcDir);
      runCommand(
        srcDir,
        "dafny",
        "format",
        "--function-syntax:3",
        "--unicode-char:false",
        "."
      );
    }
  }

  /**
   * Generate a skeletal implementation of the local service operations,
   * with `expect false` statements to ensure tests will initially fail.
   */
  public void generateDafnySkeleton(Path outputDir) {
    final DafnyApiCodegen dafnyApiCodegen = new DafnyApiCodegen(
      model,
      serviceShape,
      outputDir,
      this.includeDafnyFile.get(),
      this.dependentModelPaths,
      this.awsSdkStyle
    );
    Map<Path, TokenTree> skeleton = dafnyApiCodegen.generateSkeleton();
    Path srcDir = outputDir.resolve("../src");
    IOUtils.writeTokenTreesIntoDir(skeleton, srcDir);
  }

  private void generateJava(final Path outputDir, final Path testOutputDir) {
    if (this.awsSdkStyle) {
      switch (this.javaAwsSdkVersion) {
        case V1 -> javaAwsSdkV1(outputDir);
        case V2 -> javaAwsSdkV2(outputDir);
      }
    } else if (this.localServiceTest) {
      javaWrappedLocalService(outputDir);
    } else {
      javaLocalService(outputDir, testOutputDir);
    }
    javaOtherGeneratedAspects();

    LOGGER.info("Formatting Java code in {}", outputDir);
    runCommand(
      outputDir,
      "npx",
      "prettier",
      "--plugin=prettier-plugin-java",
      outputDir.toString(),
      "--write"
    );

    handlePatching(TargetLanguage.JAVA, outputDir);
  }

  private void javaLocalService(
    final Path outputDir,
    final Path testOutputDir
  ) {
    final JavaLibrary javaLibrary = new JavaLibrary(
      this.model,
      this.serviceShape,
      this.javaAwsSdkVersion,
      this.dafnyVersion
    );
    IOUtils.writeTokenTreesIntoDir(javaLibrary.generate(), outputDir);
    LOGGER.info("Java code generated in {}", outputDir);

    if (testOutputDir != null) {
      IOUtils.writeTokenTreesIntoDir(
        javaLibrary.generateTests(),
        testOutputDir
      );
      LOGGER.info("Java test code generated in {}", testOutputDir);
    }
  }

  private void javaLocalService(final Path outputDir) {
    final JavaLibrary javaLibrary = new JavaLibrary(
      this.model,
      this.serviceShape,
      this.javaAwsSdkVersion,
      this.dafnyVersion
    );
    IOUtils.writeTokenTreesIntoDir(javaLibrary.generate(), outputDir);
    LOGGER.info("Java code generated in {}", outputDir);
  }

  private void javaWrappedLocalService(final Path outputDir) {
    final TestJavaLibrary testJavaLibrary = new TestJavaLibrary(
      model,
      serviceShape,
      this.javaAwsSdkVersion,
      this.dafnyVersion
    );
    IOUtils.writeTokenTreesIntoDir(testJavaLibrary.generate(), outputDir);
    LOGGER.info("Java that tests a local service generated in {}", outputDir);
  }

  private void javaAwsSdkV1(Path outputDir) {
    final JavaAwsSdkV1 javaShimCodegen = JavaAwsSdkV1.createJavaAwsSdkV1(
      this.serviceShape,
      this.model
    );
    IOUtils.writeTokenTreesIntoDir(javaShimCodegen.generate(), outputDir);
    LOGGER.info("Java V1 code generated in {}", outputDir);
  }

  private void javaAwsSdkV2(final Path outputDir) {
    final JavaAwsSdkV2 javaV2ShimCodegen = JavaAwsSdkV2.createJavaAwsSdkV2(
      serviceShape,
      model,
      dafnyVersion
    );
    IOUtils.writeTokenTreesIntoDir(javaV2ShimCodegen.generate(), outputDir);
    LOGGER.info("Java V2 code generated in {}", outputDir);
  }

  private void javaOtherGeneratedAspects() {
    final String service = serviceShape.getId().getName();
    final String sdkID = awsSdkStyle
      ? serviceShape.expectTrait(ServiceTrait.class).getSdkId()
      : serviceShape.expectTrait(LocalServiceTrait.class).getSdkId();
    final String namespace = serviceShape.getId().getNamespace();
    final String namespaceDir = namespace.replace(".", "/");

    final String gradleGroup = namespace;
    // TODO: This should be @title, but we have to actually add that to all services first
    final String gradleDescription = service;

    Map<String, String> parameters = new HashMap<>();
    parameters.put("dafnyVersion", dafnyVersion.unparse());
    parameters.put("service", service);
    parameters.put("sdkID", sdkID);
    parameters.put("namespace", namespace);
    parameters.put("namespaceDir", namespaceDir);
    parameters.put("gradleGroup", gradleGroup);
    parameters.put("gradleDescription", gradleDescription);

    if (awsSdkStyle) {
      if (generationAspects.contains(GenerationAspect.PROJECT_FILES)) {
        writeTemplatedFile(
          "runtimes/java/$forSDK:Lbuild.gradle.kts",
          parameters
        );
      }
      // TODO generate sdk constructor
    } else {
      final String serviceConfig = serviceShape
        .expectTrait(LocalServiceTrait.class)
        .getConfigId()
        .getName();
      parameters.put("serviceConfig", serviceConfig);

      if (generationAspects.contains(GenerationAspect.PROJECT_FILES)) {
        writeTemplatedFile(
          "runtimes/java/$forLocalService:Lbuild.gradle.kts",
          parameters
        );
      }

      if (generationAspects.contains(GenerationAspect.CLIENT_CONSTRUCTORS)) {
        writeTemplatedFile(
          "runtimes/java/src/main/java/Dafny/$namespaceDir:L/__default.java",
          parameters
        );
        if (localServiceTest) {
          writeTemplatedFile(
            "runtimes/java/src/test/java/$namespaceDir:L/internaldafny/wrapped/__default.java",
            parameters
          );
        }
      }
    }
  }

  private void generateDotnet(final Path outputDir) {
    if (this.awsSdkStyle) {
      netAwsSdk(outputDir);
    } else if (this.localServiceTest) {
      netWrappedLocalService(outputDir);
    } else {
      netLocalService(outputDir);
    }
    netOtherGeneratedAspects();

    Path dotnetRoot = libraryRoot.resolve("runtimes").resolve("net");
    LOGGER.info("Formatting .NET code in {}", dotnetRoot);
    // Locate all *.csproj files in the directory
    try {
      Stream<String> args = Streams.concat(
        Stream.of("dotnet", "format"),
        Files
          .list(dotnetRoot)
          .filter(path -> path.toFile().getName().endsWith(".csproj"))
          .map(Path::toString)
      );
      runCommand(dotnetRoot, args.toArray(String[]::new));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    handlePatching(TargetLanguage.DOTNET, outputDir);
  }

  private void netLocalService(final Path outputDir) {
    final ServiceCodegen service = new ServiceCodegen(model, serviceShape);
    IOUtils.writeTokenTreesIntoDir(service.generate(), outputDir);

    final ShimCodegen shim = new ShimCodegen(model, serviceShape);
    IOUtils.writeTokenTreesIntoDir(shim.generate(), outputDir);

    final TypeConversionCodegen conversion = new TypeConversionCodegen(
      model,
      serviceShape
    );
    IOUtils.writeTokenTreesIntoDir(conversion.generate(), outputDir);
    LOGGER.info(".NET code generated in {}", outputDir);
  }

  private void netWrappedLocalService(final Path outputDir) {
    final LocalServiceWrappedCodegen service = new LocalServiceWrappedCodegen(
      model,
      serviceShape
    );
    IOUtils.writeTokenTreesIntoDir(service.generate(), outputDir);

    final LocalServiceWrappedShimCodegen wrappedShim =
      new LocalServiceWrappedShimCodegen(model, serviceShape);
    IOUtils.writeTokenTreesIntoDir(wrappedShim.generate(), outputDir);

    final TypeConversionCodegen conversion =
      new LocalServiceWrappedConversionCodegen(model, serviceShape);
    IOUtils.writeTokenTreesIntoDir(conversion.generate(), outputDir);
    LOGGER.info(".NET that tests a local service generated in {}", outputDir);
  }

  private void netAwsSdk(final Path outputDir) {
    final AwsSdkShimCodegen dotnetShimCodegen = new AwsSdkShimCodegen(
      model,
      serviceShape
    );
    IOUtils.writeTokenTreesIntoDir(dotnetShimCodegen.generate(), outputDir);

    final TypeConversionCodegen conversion = new AwsSdkTypeConversionCodegen(
      model,
      serviceShape
    );
    IOUtils.writeTokenTreesIntoDir(conversion.generate(), outputDir);
    LOGGER.info(".NET code generated in {}", outputDir);
  }

  private void netOtherGeneratedAspects() {
    final DotNetNameResolver resolver = new DotNetNameResolver(
      model,
      serviceShape
    );
    final String service = serviceShape.getId().getName();
    final String sdkID = awsSdkStyle
      ? serviceShape.expectTrait(ServiceTrait.class).getSdkId()
      : serviceShape.expectTrait(LocalServiceTrait.class).getSdkId();
    final String namespace = serviceShape.getId().getNamespace();
    final String dotnetNamespace = resolver.namespaceForService();
    final String dafnyNamespace =
      DafnyNameResolverHelpers.packageNameForNamespace(namespace);
    final String namespaceDir = namespace.replace(".", "/");

    Map<String, String> parameters = new HashMap<>();
    parameters.put("dafnyVersion", dafnyVersion.unparse());
    parameters.put("service", service);
    parameters.put("sdkID", sdkID);
    parameters.put("namespace", dotnetNamespace);
    parameters.put("dafnyNamespace", dafnyNamespace);
    parameters.put("namespaceDir", namespaceDir);
    parameters.put("stdLibPath", standardLibraryPath().toString());

    if (awsSdkStyle) {
      if (generationAspects.contains(GenerationAspect.PROJECT_FILES)) {
        writeTemplatedFile("runtimes/net/$forSDK:L$sdkID:L.csproj", parameters);
      }
      if (generationAspects.contains(GenerationAspect.CLIENT_CONSTRUCTORS)) {
        writeTemplatedFile("runtimes/net/Extern/$sdkID:LClient.cs", parameters);
      }
    } else {
      final String serviceConfig = serviceShape
        .expectTrait(LocalServiceTrait.class)
        .getConfigId()
        .getName();
      final String configConversionMethod =
        DotNetNameResolver.typeConverterForShape(
          serviceShape.expectTrait(LocalServiceTrait.class).getConfigId(),
          TypeConversionDirection.FROM_DAFNY
        );
      parameters.put("serviceConfig", serviceConfig);
      parameters.put("configConversionMethod", configConversionMethod);

      if (generationAspects.contains(GenerationAspect.PROJECT_FILES)) {
        writeTemplatedFile(
          "runtimes/net/$forLocalService:L$sdkID:L.csproj",
          parameters
        );
      }
      if (localServiceTest) {
        if (generationAspects.contains(GenerationAspect.CLIENT_CONSTRUCTORS)) {
          writeTemplatedFile(
            "runtimes/net/Extern/Wrapped$sdkID:LService.cs",
            parameters
          );
        }
      }
    }

    if (generationAspects.contains(GenerationAspect.PROJECT_FILES)) {
      writeTemplatedFile("runtimes/net/tests/$sdkID:LTest.csproj", parameters);
    }
  }

  private void generateRust(final Path outputDir) {
    LOGGER.warn(
      "Rust code generation is incomplete and may not function correctly!"
    );

    // ...so incomplete it's starting out as a no-op and relying on 100% "patching" :)

    // Clear out all contents of src first to make sure if we didn't intend to generate it,
    // it doesn't show up as generated code. This ensures patching has the right baseline.
    // It would be great to do this for all languages,
    // but we're not currently precise enough and do multiple passes
    // to generate code for things like wrapped services.
    Path outputSrcDir = outputDir.resolve("src");
    software.amazon.smithy.utils.IoUtils.rmdir(outputSrcDir);
    outputSrcDir.toFile().mkdirs();

    handlePatching(TargetLanguage.RUST, outputDir);
  }

  private static final Pattern PATCH_FILE_PATTERN = Pattern.compile(
    "dafny-(.*).patch"
  );

  private DafnyVersion getDafnyVersionForPatchFile(Path file) {
    String fileName = file.getFileName().toString();
    Matcher matcher = PATCH_FILE_PATTERN.matcher(fileName);
    if (matcher.matches()) {
      String versionString = matcher.group(1);
      return DafnyVersion.parse(versionString);
    } else {
      throw new IllegalArgumentException(
        "Patch files must be of the form dafny-<version>.patch: " + file
      );
    }
  }

  private void handlePatching(TargetLanguage targetLanguage, Path outputDir) {
    if (patchFilesDir.isEmpty()) {
      return;
    }

    Path patchFilesForLanguage = patchFilesDir
      .get()
      .resolve(targetLanguage.name().toLowerCase());
    try {
      if (updatePatchFiles) {
        Files.createDirectories(patchFilesForLanguage);
        Path patchFile = patchFilesForLanguage.resolve(
          "dafny-%s.patch".formatted(dafnyVersion.unparse())
        );
        Path outputDirRelative = libraryRoot.relativize(outputDir);
        // Need to ignore the exit code because diff will return 1 if there is a diff
        String patchContent = runCommandIgnoringExitCode(
          libraryRoot,
          "git",
          "diff",
          "-R",
          outputDirRelative.toString()
        );
        if (!patchContent.isBlank()) {
          IOUtils.writeToFile(patchContent, patchFile.toFile());
        }
      }

      if (Files.exists(patchFilesForLanguage)) {
        List<Pair<DafnyVersion, Path>> sortedPatchFiles = Files
          .list(patchFilesForLanguage)
          .map(file -> Pair.of(getDafnyVersionForPatchFile(file), file))
          .sorted(Collections.reverseOrder(Map.Entry.comparingByKey()))
          .toList();
        for (Pair<DafnyVersion, Path> patchFilePair : sortedPatchFiles) {
          if (dafnyVersion.compareTo(patchFilePair.getKey()) >= 0) {
            Path patchFile = patchFilePair.getValue();
            LOGGER.info("Applying patch file {}", patchFile);
            runCommand(libraryRoot, "git", "apply", patchFile.toString());
            return;
          }
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String runCommand(Path workingDir, String... args) {
    List<String> argsList = List.of(args);
    StringBuilder output = new StringBuilder();
    int exitCode = IoUtils.runCommand(
      argsList,
      workingDir,
      output,
      Collections.emptyMap()
    );
    if (exitCode != 0) {
      throw new RuntimeException("Command failed: " + argsList + "\n" + output);
    }
    return output.toString();
  }

  private String runCommandIgnoringExitCode(Path workingDir, String... args) {
    List<String> argsList = List.of(args);
    StringBuilder output = new StringBuilder();
    IoUtils.runCommand(argsList, workingDir, output, Collections.emptyMap());
    return output.toString();
  }

  private Path standardLibraryPath() {
    final Path includeDafnyFile =
      this.includeDafnyFile.orElseThrow(() ->
          new IllegalStateException(
            "includeDafnyFile required when generating additional aspects (--generate)"
          )
        );

    // Assumes that includeDafnyFile is at StandardLibrary/src/Index.dfy
    // TODO be smarter about finding the StandardLibrary path
    return libraryRoot
      .resolve("runtimes/net")
      .relativize(includeDafnyFile.resolve("../.."));
  }

  private void writeTemplatedFile(
    String templatePath,
    Map<String, String> parameters
  ) {
    IOUtils.writeTemplatedFile(
      getClass(),
      libraryRoot,
      templatePath,
      parameters
    );
  }

  public static class Builder {

    private boolean fromSmithyBuildPlugin = false;
    private Model serviceModel;
    private Path[] dependentModelPaths;
    private String namespace;
    private Map<TargetLanguage, Path> targetLangOutputDirs =
      Collections.emptyMap();
    private Map<TargetLanguage, Path> targetLangTestOutputDirs =
      Collections.emptyMap();
    private DafnyVersion dafnyVersion = new DafnyVersion(4, 1, 0);
    private Path propertiesFile;
    private AwsSdkVersion javaAwsSdkVersion = AwsSdkVersion.V2;
    private Path includeDafnyFile;
    private boolean awsSdkStyle = false;
    private boolean localServiceTest = false;
    private Set<GenerationAspect> generationAspects = Collections.emptySet();
    private Path libraryRoot;
    private Path patchFilesDir;
    private boolean updatePatchFiles = false;

    public Builder() {}

    /**
     * Sets the directory in which to search for model files(s) containing the desired service.
     */
    public Builder withServiceModel(final Model serviceModel) {
      this.serviceModel = serviceModel;
      return this;
    }

    /**
     * Sets the directories in which to search for dependent model file(s).
     */
    public Builder withDependentModelPaths(final Path[] dependentModelPaths) {
      this.dependentModelPaths = dependentModelPaths;
      return this;
    }

    /**
     * Sets the Smithy namespace for which to generate code (e.g. "com.foo").
     */
    public Builder withNamespace(final String namespace) {
      this.namespace = namespace;
      return this;
    }

    /**
     * Sets the target language(s) for which to generate code,
     * along with the directory(-ies) into which to output each language's generated code.
     */
    public Builder withTargetLangOutputDirs(
      final Map<TargetLanguage, Path> targetLangOutputDirs
    ) {
      this.targetLangOutputDirs = targetLangOutputDirs;
      return this;
    }

    /**
     * Sets the target language(s) for which to generate testing code,
     * along with the directory(-ies) into which to output each language's generated testing code.
     */
    public Builder withTargetLangTestOutputDirs(
      final Map<TargetLanguage, Path> targetLangTestOutputDirs
    ) {
      this.targetLangTestOutputDirs = targetLangTestOutputDirs;
      return this;
    }

    /**
     * Sets the Dafny version for which generated code should be compatible.
     * This is used to ensure both Dafny source compatibility
     * and compatibility with the Dafny compiler and runtime internals,
     * which shim code generation currently depends on.
     */
    public Builder withDafnyVersion(final DafnyVersion dafnyVersion) {
      this.dafnyVersion = dafnyVersion;
      return this;
    }

    /**
     * Sets the path to generate a project.properties file at.
     * This will contain a dafnyVersion property that can be used to
     * select the correct version of the Dafny runtime in target language
     * project configurations, amonst other potential uses.
     * The properties file may contain other metadata in the future.
     */
    public Builder withPropertiesFile(final Path propertiesFile) {
      this.propertiesFile = propertiesFile;
      return this;
    }

    /**
     * Sets the version of the AWS SDK for Java for which generated code should be compatible.
     * This has no effect unless the engine is configured to generate Java code.
     */
    public Builder withJavaAwsSdkVersion(
      final AwsSdkVersion javaAwsSdkVersion
    ) {
      this.javaAwsSdkVersion = javaAwsSdkVersion;
      return this;
    }

    /**
     * Sets a file to be included in the generated Dafny code.
     */
    public Builder withIncludeDafnyFile(final Path includeDafnyFile) {
      this.includeDafnyFile = includeDafnyFile;
      return this;
    }

    /**
     * Sets whether codegen will generate AWS SDK-compatible API and shims.
     */
    public Builder withAwsSdkStyle(final boolean awsSdkStyle) {
      this.awsSdkStyle = awsSdkStyle;
      return this;
    }

    /**
     * Sets whether codegen will generate Dafny code to test a local service.
     */
    public Builder withLocalServiceTest(final boolean localServiceTest) {
      this.localServiceTest = localServiceTest;
      return this;
    }

    /**
     * Sets which aspects will be generated, such as project files.
     * See also {@link GenerationAspect}.
     */
    public Builder withGenerationAspects(
      final Set<GenerationAspect> generationAspects
    ) {
      this.generationAspects = generationAspects;
      return this;
    }

    /**
     * Sets the root directory of the library being built.
     * Used to locate any patch files (under ./codegen-patches)
     * and things like target language project roots.
     */
    public Builder withLibraryRoot(final Path libraryRoot) {
      this.libraryRoot = libraryRoot;
      return this;
    }

    /**
     * Indicates whether the engine is being used from the polymorph CLI
     * or the Smithy build plugin.
     * Needed because the two use cases have different library layout conventions.
     */
    public Builder withFromSmithyBuildPlugin(
      final boolean fromSmithyBuildPlugin
    ) {
      this.fromSmithyBuildPlugin = fromSmithyBuildPlugin;
      return this;
    }

    /**
     * The location of patch files.
     */
    public Builder withPatchFilesDir(final Path patchFilesDir) {
      this.patchFilesDir = patchFilesDir;
      return this;
    }

    /**
     * If true, updates the relevant patch files in (library-root)/codegen-patches
     * to change the generated code into the state of the code before generation.
     */
    public Builder withUpdatePatchFiles(final boolean updatePatchFiles) {
      this.updatePatchFiles = updatePatchFiles;
      return this;
    }

    public CodegenEngine build() {
      final Model serviceModel = Objects.requireNonNull(this.serviceModel);
      final Path[] dependentModelPaths = this.dependentModelPaths == null
        ? new Path[] {}
        : this.dependentModelPaths.clone();
      if (Strings.isNullOrEmpty(this.namespace)) {
        throw new IllegalStateException("No namespace provided");
      }

      final Map<TargetLanguage, Path> targetLangOutputDirsRaw =
        Objects.requireNonNull(this.targetLangOutputDirs);
      targetLangOutputDirsRaw.replaceAll((_lang, path) ->
        path.toAbsolutePath().normalize()
      );
      final Map<TargetLanguage, Path> targetLangOutputDirs =
        ImmutableMap.copyOf(targetLangOutputDirsRaw);

      final Map<TargetLanguage, Path> targetLangTestOutputDirsRaw =
        Objects.requireNonNull(this.targetLangTestOutputDirs);
      targetLangTestOutputDirsRaw.replaceAll((_lang, path) ->
        path.toAbsolutePath().normalize()
      );
      final Map<TargetLanguage, Path> targetLangTestOutputDirs =
        ImmutableMap.copyOf(targetLangTestOutputDirsRaw);

      final DafnyVersion dafnyVersion = Objects.requireNonNull(
        this.dafnyVersion
      );
      final Optional<Path> propertiesFile = Optional
        .ofNullable(this.propertiesFile)
        .map(path -> path.toAbsolutePath().normalize());
      final AwsSdkVersion javaAwsSdkVersion = Objects.requireNonNull(
        this.javaAwsSdkVersion
      );

      if (
        targetLangOutputDirs.containsKey(TargetLanguage.DAFNY) &&
        this.includeDafnyFile == null
      ) {
        throw new IllegalStateException(
          "includeDafnyFile is required when generating Dafny code"
        );
      }
      final Optional<Path> includeDafnyFile = Optional
        .ofNullable(this.includeDafnyFile)
        .map(path -> path.toAbsolutePath().normalize());

      if (this.awsSdkStyle && this.localServiceTest) {
        throw new IllegalStateException(
          "Cannot generate AWS SDK style code, and test a local service, at the same time"
        );
      }

      final Path libraryRoot = this.libraryRoot.toAbsolutePath().normalize();

      final Optional<Path> patchFilesDir = Optional
        .ofNullable(this.patchFilesDir)
        .map(path -> path.toAbsolutePath().normalize());
      if (updatePatchFiles && patchFilesDir.isEmpty()) {
        throw new IllegalStateException(
          "Cannot update patch files without specifying a patch files directory"
        );
      }

      return new CodegenEngine(
        fromSmithyBuildPlugin,
        serviceModel,
        dependentModelPaths,
        this.namespace,
        targetLangOutputDirs,
        targetLangTestOutputDirs,
        dafnyVersion,
        propertiesFile,
        javaAwsSdkVersion,
        includeDafnyFile,
        this.awsSdkStyle,
        this.localServiceTest,
        this.generationAspects,
        libraryRoot,
        patchFilesDir,
        updatePatchFiles
      );
    }
  }

  public enum TargetLanguage {
    DAFNY,
    JAVA,
    DOTNET,
    RUST,
  }

  public enum GenerationAspect {
    PROJECT_FILES {
      @Override
      public String description() {
        return "Project configuration files";
      }
    },

    CLIENT_CONSTRUCTORS {
      @Override
      public String description() {
        return "Top-level client constructor code";
      }
    },

    IMPL_STUB {
      @Override
      public String description() {
        return "Local service implementation/testing stubs";
      }
    };

    public static GenerationAspect fromOption(String option) {
      return GenerationAspect.valueOf(option.replace("-", "_").toUpperCase());
    }

    public String toOption() {
      return toString().replace("_", "-").toLowerCase();
    }

    public abstract String description();

    public static String helpText() {
      return Arrays
        .stream(values())
        .map(aspect -> aspect.toOption() + " - " + aspect.description())
        .collect(Collectors.joining("\n"));
    }
  }
}
