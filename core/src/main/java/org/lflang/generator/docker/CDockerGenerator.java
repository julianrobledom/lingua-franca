package org.lflang.generator.docker;

import org.eclipse.xtext.xbase.lib.IterableExtensions;
import org.lflang.generator.LFGeneratorContext;
import org.lflang.target.Target;
import org.lflang.target.property.BuildCommandsProperty;
import org.lflang.target.property.DockerProperty;
import org.lflang.target.property.DockerProperty.DockerOptions;
import org.lflang.util.StringUtil;

/**
 * Generate the docker file related code for the C and CCpp target.
 *
 * @author Hou Seng Wong
 */
public class CDockerGenerator extends DockerGenerator {

  /**
   * The constructor for the base docker file generation class.
   *
   * @param context The context of the code generator.
   */
  public CDockerGenerator(LFGeneratorContext context) {
    super(context);
  }

  /** Generate the contents of the docker file. */
  @Override
  protected String generateDockerFileContent() {
    var lfModuleName = context.getFileConfig().name;
    var config = context.getTargetConfig();
    var compileCommand =
        IterableExtensions.isNullOrEmpty(config.get(BuildCommandsProperty.INSTANCE))
            ? generateDefaultCompileCommand()
            : StringUtil.joinObjects(config.get(BuildCommandsProperty.INSTANCE), " ");
    var compiler = config.target == Target.CCPP ? "g++" : "gcc";
    var baseImage = DockerOptions.DEFAULT_BASE_IMAGE;
    var dockerConf = config.get(DockerProperty.INSTANCE);
    if (dockerConf.enabled() && dockerConf.from() != null) {
      baseImage = dockerConf.from();
    }
    return String.join(
        "\n",
        "# For instructions, see: https://www.lf-lang.org/docs/handbook/containerized-execution",
        "FROM " + baseImage + " AS builder",
        "WORKDIR /lingua-franca/" + lfModuleName,
        "RUN set -ex && apk add --no-cache " + compiler + " musl-dev cmake make",
        "COPY . src-gen",
        compileCommand,
        "",
        "FROM " + baseImage,
        "WORKDIR /lingua-franca",
        "RUN mkdir bin",
        "COPY --from=builder /lingua-franca/"
            + lfModuleName
            + "/bin/"
            + lfModuleName
            + " ./bin/"
            + lfModuleName,
        "",
        "# Use ENTRYPOINT not CMD so that command-line arguments go through",
        "ENTRYPOINT [\"./bin/" + lfModuleName + "\"]",
        "");
  }

  /** Return the default compile command for the C docker container. */
  protected String generateDefaultCompileCommand() {
    return String.join(
        "\n",
        "RUN set -ex && \\",
        "mkdir bin && \\",
        "cmake -S src-gen -B bin && \\",
        "cd bin && \\",
        "make all");
  }
}