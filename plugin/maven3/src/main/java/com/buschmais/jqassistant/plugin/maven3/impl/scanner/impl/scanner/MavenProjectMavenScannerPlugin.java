package com.buschmais.jqassistant.plugin.maven3.impl.scanner.impl.scanner;

import static com.buschmais.jqassistant.core.scanner.api.iterable.IterableConsumer.Consumer;
import static com.buschmais.jqassistant.core.scanner.api.iterable.IterableConsumer.consume;
import static com.buschmais.jqassistant.plugin.java.api.JavaScope.CLASSPATH;
import static com.buschmais.jqassistant.plugin.junit4.api.JunitScope.TESTREPORTS;
import static java.util.Collections.emptyList;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.core.store.api.descriptor.FileDescriptor;
import com.buschmais.jqassistant.plugin.common.impl.store.descriptor.ArtifactDescriptor;
import com.buschmais.jqassistant.plugin.common.impl.store.descriptor.DependsOnDescriptor;
import com.buschmais.jqassistant.plugin.maven3.impl.scanner.api.AbstractMavenProjectScannerPlugin;
import com.buschmais.jqassistant.plugin.maven3.impl.scanner.impl.store.MavenProjectDescriptor;
import com.buschmais.jqassistant.plugin.maven3.impl.scanner.impl.store.MavenProjectDirectoryDescriptor;

/**
 * A project scanner plugin for maven projects.
 */
public class MavenProjectMavenScannerPlugin extends AbstractMavenProjectScannerPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenProjectMavenScannerPlugin.class);

    @Override
    public boolean accepts(MavenProject item, String path, Scope scope) throws IOException {
        return true;
    }

    @Override
    public Iterable<FileDescriptor> scan(MavenProject project, String path, Scope scope, Scanner scanner) throws IOException {
        Store store = getStore();
        store.beginTransaction();
        MavenProjectDirectoryDescriptor projectDescriptor;
        try {
            projectDescriptor = resolveProject(project, MavenProjectDirectoryDescriptor.class);
            projectDescriptor.setFileName(project.getFile().getAbsolutePath());
            projectDescriptor.setPackaging(project.getPackaging());
        } finally {
            store.commitTransaction();
        }
        Artifact artifact = project.getArtifact();
        ArtifactDescriptor mainArtifactDescriptor = scanDirectory(projectDescriptor, artifact, false, project.getBuild().getOutputDirectory(), scanner);
        ArtifactDescriptor testArtifactDescriptor = scanDirectory(projectDescriptor, artifact, true, project.getBuild().getTestOutputDirectory(), scanner);
        addProjectDetails(project, projectDescriptor, mainArtifactDescriptor, testArtifactDescriptor);
        scanTestReports(scanner, project.getBuild().getDirectory() + "/surefire-reports");
        scanTestReports(scanner, project.getBuild().getDirectory() + "/failsafe-reports");
        return emptyList();
    }

    /**
     * Add project specific information.
     * 
     * @param project
     *            The project.
     * @param projectDescriptor
     *            The project descriptor.
     * @param mainArtifactDescriptor
     *            The artifact descriptor representing the main artifact.
     * @param testArtifactDescriptor
     *            The artifact descriptor representing the test artifact.
     */
    private void addProjectDetails(MavenProject project, MavenProjectDirectoryDescriptor projectDescriptor, ArtifactDescriptor mainArtifactDescriptor,
            ArtifactDescriptor testArtifactDescriptor) {
        Store store = getStore();
        store.beginTransaction();
        try {
            addParent(project, projectDescriptor);
            addModules(project, projectDescriptor);
            addDependencies(project, mainArtifactDescriptor, testArtifactDescriptor, store);
        } finally {
            store.commitTransaction();
        }
    }

    /**
     * Add the relation to the parent project.
     * 
     * @param project
     *            The project.
     * @param projectDescriptor
     *            The project descriptor.
     */
    private void addParent(MavenProject project, MavenProjectDirectoryDescriptor projectDescriptor) {
        MavenProject parent = project.getParent();
        if (parent != null) {
            MavenProjectDescriptor parentDescriptor = resolveProject(parent, MavenProjectDescriptor.class);
            projectDescriptor.setParent(parentDescriptor);
        }
    }

    /**
     * Add relations to the modules.
     * 
     * @param project
     *            The project.
     * @param projectDescriptor
     *            The project descriptor.
     */
    private void addModules(MavenProject project, MavenProjectDirectoryDescriptor projectDescriptor) {
        File projectDirectory = project.getBasedir();
        Set<File> modules = new HashSet<>();
        for (String moduleName : (List<String>) project.getModules()) {
            File module = new File(projectDirectory, moduleName);
            modules.add(module);
        }
        for (MavenProject module : (List<MavenProject>) project.getCollectedProjects()) {
            if (modules.contains(module.getBasedir())) {
                MavenProjectDescriptor moduleDescriptor = resolveProject(module, MavenProjectDescriptor.class);
                projectDescriptor.getModules().add(moduleDescriptor);
            }
        }
    }

    /**
     * Add dependency relations to the artifacts.
     * 
     * @param project
     *            The project.
     * @param mainArtifactDescriptor
     *            The artifact descriptor representing the main artifact.
     * @param testArtifactDescriptor
     *            The artifact descriptor representing the test artifact.
     * @param store
     *            The store.
     */
    private void addDependencies(MavenProject project, ArtifactDescriptor mainArtifactDescriptor, ArtifactDescriptor testArtifactDescriptor, Store store) {
        if (mainArtifactDescriptor != null && testArtifactDescriptor != null) {
            DependsOnDescriptor dependsOnDescriptor = store.create(testArtifactDescriptor, DependsOnDescriptor.class, mainArtifactDescriptor);
            dependsOnDescriptor.setScope(Artifact.SCOPE_TEST);
        }
        for (Artifact dependency : (Set<Artifact>) project.getDependencyArtifacts()) {
            ArtifactDescriptor dependencyDescriptor = resolveArtifact(dependency);
            DependsOnDescriptor dependsOnDescriptor;
            ArtifactDescriptor dependentDescriptor;
            String scope = dependency.getScope();
            if (Artifact.SCOPE_TEST.equals(scope)) {
                dependentDescriptor = testArtifactDescriptor;
            } else {
                dependentDescriptor = mainArtifactDescriptor;
            }
            if (dependentDescriptor != null) {
                dependsOnDescriptor = store.create(dependentDescriptor, DependsOnDescriptor.class, dependencyDescriptor);
                dependsOnDescriptor.setScope(scope);
                dependsOnDescriptor.setOptional(dependency.isOptional());
            }
        }
    }

    /**
     * Scan the given directory for classes.
     * 
     * @param directoryName
     *            The directory.
     * @throws java.io.IOException
     *             If scanning fails.
     */
    private ArtifactDescriptor scanDirectory(MavenProjectDirectoryDescriptor projectDescriptor, Artifact artifact, boolean testJar, final String directoryName,
            Scanner scanner) throws IOException {
        final File directory = new File(directoryName);
        if (!directory.exists()) {
            LOGGER.info("Directory '" + directory.getAbsolutePath() + "' does not exist, skipping scan.");
        } else {
            Store store = getStore();
            store.beginTransaction();
            try {
                final ArtifactDescriptor artifactDescriptor = resolveArtifact(artifact, testJar);
                consume(scanner.scan(directory, CLASSPATH), new Consumer<FileDescriptor>() {
                    @Override
                    public void next(FileDescriptor fileDescriptor) {
                        artifactDescriptor.addContains(fileDescriptor);
                    }
                });
                projectDescriptor.getCreatesArtifacts().add(artifactDescriptor);
                return artifactDescriptor;
            } finally {
                store.commitTransaction();
            }
        }
        return null;
    }

    /**
     * Scans a directory for test reports.
     * 
     * @param directoryName
     *            The directory name.
     * @throws java.io.IOException
     *             If scanning fails.
     */
    private void scanTestReports(Scanner scanner, String directoryName) throws IOException {
        final File directory = new File(directoryName);
        Store store = getStore();
        if (directory.exists()) {
            store.beginTransaction();
            try {
                consume(scanner.scan(directory, TESTREPORTS), new Consumer<FileDescriptor>() {
                    @Override
                    public void next(FileDescriptor fileDescriptor) {
                    }
                });
            } finally {
                store.commitTransaction();
            }
        }
    }
}
