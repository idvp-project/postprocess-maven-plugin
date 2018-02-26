package com.idvp.plugins.postprocess.aop.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Oleg Zinoviev
 * @since 26.02.18.
 */
@Mojo(name = "aop", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class AopMojo extends AbstractMojo {
    private final Logger logger = LoggerFactory.getLogger(AopMojo.class);

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(property = "aop.skip", defaultValue = "false")
    private boolean skipAopPostprocessing;

    @Override
    public void execute() throws MojoExecutionException {

        if (skipAopPostprocessing) {
            logger.info("AOP processing in skipped");
        }

        String outputDirectory = project.getBuild().getOutputDirectory();

        Set<Path> classFiles;
        try {
            classFiles = getClassFiles(outputDirectory);
            if (logger.isDebugEnabled()) {
                for (Path path : classFiles) {
                    logger.debug(path.toString());
                }
            }
        } catch (IOException e) {
            logger.error("Error reading file list", e);
            throw new MojoExecutionException("Error reading file list", e);
        }

        AopClassProcessor processor = new AopClassProcessor(classFiles);

        try {
            processor = processor.loadClasses();
        } catch (Exception e) {
            logger.error("Error loading classes", e);
            throw new MojoExecutionException("Error loading classes", e);
        }

        try {
            processor = processor.filterClasses();
        } catch (Exception e) {
            logger.error("Error filtering classes", e);
            throw new MojoExecutionException("Error filtering classes", e);
        }

        try {
            processor.process();
        } catch (Exception e) {
            logger.error("Error transforming classes", e);
            throw new MojoExecutionException("Error transforming classes", e);
        }

    }

    private Set<Path> getClassFiles(String outputDirectory) throws IOException {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:*.class");
        Path rootPath = Paths.get(outputDirectory).toAbsolutePath();

        return Files.walk(rootPath)
                .filter(Files::isRegularFile)
                .filter(Files::isReadable)
                .filter(p -> matcher.matches(p.getFileName()))
                .collect(Collectors.toSet());
    }
}
