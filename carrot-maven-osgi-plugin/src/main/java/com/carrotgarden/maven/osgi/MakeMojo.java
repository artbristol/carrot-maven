package com.carrotgarden.maven.osgi;

/*
 * Copyright 2007 Stuart McCulloch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.settings.Mirror;
import org.codehaus.plexus.util.IOUtil;
import org.ops4j.pax.construct.util.PomUtils;
import org.ops4j.pax.construct.util.StreamFactory;

/**
 * @goal make
 * @aggregator true
 * 
 * @requiresProject false
 * @requiresDependencyResolution test
 */
public class MakeMojo extends BaseMojo {
	/**
	 * Maven groupId for the new Pax-Runner
	 */
	private static final String PAX_RUNNER_GROUP = "org.ops4j.pax.runner";

	/**
	 * Maven artifactId for the new Pax-Runner
	 */
	private static final String PAX_RUNNER_ARTIFACT = "pax-runner";

	/**
	 * Main entry-point for the new Pax-Runner
	 */
	private static final String PAX_RUNNER_METHOD = "org.ops4j.pax.runner.Run";

	/**
	 * Name of the OSGi framework to deploy onto.
	 * 
	 * @parameter expression="${framework}"
	 */
	private String framework;

	/**
	 * When true, start the OSGi framework and deploy the provisioned bundles.
	 * 
	 * @parameter expression="${deploy}" default-value="true"
	 */
	private boolean deploy;

	/**
	 * Comma separated list of additional Pax-Runner profiles to deploy.
	 * 
	 * @parameter expression="${profiles}"
	 */
	private String profiles;

	/**
	 * URL of file containing additional Pax-Runner arguments.
	 * 
	 * @parameter expression="${args}"
	 */
	private String args;

	/**
	 * Ignore bundle dependencies when deploying project.
	 * 
	 * @parameter expression="${noDeps}"
	 */
	private boolean noDependencies;

	/**
	 * Comma separated list of additional POMs with bundles as dependencies.
	 * 
	 * @parameter expression="${deployPoms}"
	 */
	private String deployPoms;

	/**
	 * Comma separated list of additional bundle URLs to deploy.
	 * 
	 * @parameter expression="${deployURLs}"
	 */
	private String deployURLs;

	/**
	 * The version of Pax-Runner to use for provisioning.
	 * 
	 * @parameter expression="${runner}" default-value="RELEASE"
	 */
	private String runner;

	/**
	 * A set of provision commands for Pax-Runner.
	 * 
	 * @parameter expression="${provision}"
	 */
	private String[] provision;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void execute() throws MojoExecutionException {

		bundleIdSet = new HashSet<String>();

		// if (deployPoms != null) {
		// addAdditionalPoms();
		// }

		// if (m_project.getFile() != null) {
		// for (Iterator i = m_reactorProjects.iterator(); i.hasNext();) {
		// addProjectBundles((MavenProject) i.next(),
		// false == noDependencies);
		// }
		// }

		for (MavenProject project : m_reactorProjects) {
			addProjectBundles(project, TAB);
		}

		// setupRuntimeHelpers();

		deployBundles();

	}

	/**
	 * Use reflection to find if certain runtime helper methods are available.
	 */
	private void setupRuntimeHelpers() {
		try {
			m_getMirrorRepository = m_wagonManager.getClass().getMethod(
					"getMirrorRepository",
					new Class[] { ArtifactRepository.class });
		} catch (RuntimeException e) {
			m_getMirrorRepository = null;
		} catch (NoSuchMethodException e) {
			m_getMirrorRepository = null;
		}
	}

	/**
	 * Does this look like a provisioning POM? ie. artifactId of 'provision',
	 * packaging type 'pom', with dependencies
	 * 
	 * @param project
	 *            a Maven project
	 * @return true if this looks like a provisioning POM, otherwise false
	 */
	public static boolean isProvisioningPom(MavenProject project) {
		// ignore POMs which don't have provision as their artifactId
		if (!"provision".equals(project.getArtifactId())) {
			return false;
		}

		// ignore POMs that produce actual artifacts
		if (!"pom".equals(project.getPackaging())) {
			return false;
		}

		// ignore POMs with no dependencies at all
		List dependencies = project.getDependencies();
		if (dependencies == null || dependencies.size() == 0) {
			return false;
		}

		return true;
	}

	/**
	 * Add user supplied POMs as if they were in the Maven reactor
	 */
	private void addAdditionalPoms() {
		String[] pomPaths = deployPoms.split(",");
		for (int i = 0; i < pomPaths.length; i++) {
			File pomFile = new File(pomPaths[i].trim());
			if (pomFile.exists()) {
				try {
					addProjectBundles(
							//
							m_projectBuilder.build(pomFile, m_localRepo, null),
							TAB);
				} catch (ProjectBuildingException e) {
					getLog().warn(
							"Unable to build Maven project for " + pomFile);
				}
			}
		}
	}

	/**
	 * Create deployment POM and pass it onto Pax-Runner for provisioning
	 * 
	 * @throws MojoExecutionException
	 */
	private void deployBundles() throws MojoExecutionException {

		if (bundleIdSet.size() == 0) {
			getLog().info("~~~~~~~~~~~~~~~~~~~");
			getLog().info(" No bundles found! ");
			getLog().info("~~~~~~~~~~~~~~~~~~~");
		}

		List<Dependency> bundles = resolveProvisionedBundles();

		MavenProject deployProject = createDeploymentProject(bundles);

		installDeploymentPom(deployProject);

		// if (!deploy) {
		if (1 == 1) {
			getLog().info("Skipping OSGi deployment");
			return;
		}

		// can remove this once runner is on central
		// m_remoteRepos.add(getOps4jRepository());

		String delim = "";
		StringBuffer repoListBuilder = new StringBuffer("+");
		for (Iterator i = m_remoteRepos.iterator(); i.hasNext();) {
			repoListBuilder.append(delim);

			ArtifactRepository repo = (ArtifactRepository) i.next();
			repoListBuilder.append(getRepositoryURL(repo));

			if (repo.getSnapshots().isEnabled()) {
				repoListBuilder.append("@snapshots");
			}

			if (false == repo.getReleases().isEnabled()) {
				repoListBuilder.append("@noreleases");
			}

			delim = ",";
		}

		if (PomUtils.needReleaseVersion(runner)) {
			// find the latest release of Pax-Runner by querying the local and
			// remote repos...
			Artifact runnerProject = m_factory.createProjectArtifact(
					PAX_RUNNER_GROUP, PAX_RUNNER_ARTIFACT, runner);
			runner = PomUtils.getReleaseVersion(runnerProject, m_source,
					m_remoteRepos, m_localRepo, null);
		}

		/*
		 * Dynamically load the correct Pax-Runner code
		 */
		Pattern classicVersion = Pattern.compile("0\\.[1-4]\\.\\d");
		if (classicVersion.matcher(runner).matches()) {
			Class clazz = loadRunnerClass("org.ops4j.pax", "runner",
					PAX_RUNNER_METHOD, false);
			deployRunnerClassic(clazz, deployProject,
					repoListBuilder.toString());
		} else {
			Class clazz = loadRunnerClass(PAX_RUNNER_GROUP,
					PAX_RUNNER_ARTIFACT, PAX_RUNNER_METHOD, true);
			deployRunnerNG(clazz, deployProject, repoListBuilder.toString());
		}

	}

	/**
	 * @param repo
	 *            remote Maven repository
	 * @return repository (or mirror) URL
	 */
	private String getRepositoryURL(ArtifactRepository repo) {
		if (null != m_getMirrorRepository) {
			try {
				ArtifactRepository mirror = (ArtifactRepository) m_getMirrorRepository
						.invoke(m_wagonManager, new Object[] { repo });

				if (null != mirror) {
					return mirror.getUrl();
				}
			} catch (RuntimeException e) {
				getLog().warn(e);
			} catch (IllegalAccessException e) {
				getLog().warn(e);
			} catch (InvocationTargetException e) {
				getLog().warn(e);
			}
		}

		Mirror mirror = m_settings.getMirrorOf(repo.getId());
		if (null != mirror) {
			return mirror.getUrl();
		}

		mirror = m_settings.getMirrorOf("*");
		if (null != mirror) {
			return mirror.getUrl();
		}

		return repo.getUrl();
	}

	/**
	 * Attempt to resolve each provisioned bundle, and warn about any we can't
	 * find
	 * 
	 * @return list of bundles to be deployed (as Maven dependencies)
	 */
	private List<Dependency> resolveProvisionedBundles() {

		List<Dependency> dependencies = new ArrayList<Dependency>();

		for (String id : bundleIdSet) {

			String[] fields = id.split(":");

			Dependency dep = new Dependency();
			dep.setGroupId(fields[0]);
			dep.setArtifactId(fields[1]);
			dep.setVersion(fields[2]);
			dep.setType(fields[3]);
			dep.setScope(Artifact.SCOPE_PROVIDED);

			dependencies.add(dep);

		}

		return dependencies;

	}

	/**
	 * Create new POM (based on the root POM) which lists the deployed bundles
	 * as dependencies
	 * 
	 * @param bundles
	 *            list of bundles to be deployed
	 * @return deployment project
	 * @throws MojoExecutionException
	 */
	private MavenProject createDeploymentProject(List<Dependency> bundles)
			throws MojoExecutionException {
		MavenProject deployProject;

		if (null == m_project.getFile()) {
			deployProject = new MavenProject();
			deployProject.setGroupId("examples");
			deployProject.setArtifactId("pax-provision");
			deployProject.setVersion("1.0-SNAPSHOT");
		} else {
			deployProject = new MavenProject(m_project);
		}

		String internalId = PomUtils.getCompoundId(deployProject.getGroupId(),
				deployProject.getArtifactId());
		deployProject.setGroupId(internalId + ".build");
		deployProject.setArtifactId("deployment");

		// remove unnecessary cruft
		deployProject.setPackaging("pom");
		deployProject.getModel().setModules(null);
		deployProject.getModel().setDependencies(bundles);
		deployProject.getModel().setPluginRepositories(null);
		deployProject.getModel().setReporting(null);
		deployProject.setBuild(null);

		File deployFile = new File(deployProject.getBasedir(),
				"runner/deploy-pom.xml");

		deployFile.getParentFile().mkdirs();
		deployProject.setFile(deployFile);

		try {
			Writer writer = StreamFactory.newXmlWriter(deployFile);
			deployProject.writeModel(writer);
			IOUtil.close(writer);
		} catch (IOException e) {
			throw new MojoExecutionException("Unable to write deployment POM "
					+ deployFile);
		}

		return deployProject;
	}

	/**
	 * Install deployment POM in the local Maven repository
	 * 
	 * @param project
	 *            deployment project
	 * @throws MojoExecutionException
	 */
	private void installDeploymentPom(MavenProject project)
			throws MojoExecutionException {
		String groupId = project.getGroupId();
		String artifactId = project.getArtifactId();
		String version = project.getVersion();

		Artifact pomArtifact = m_factory.createProjectArtifact(groupId,
				artifactId, version);

		try {
			m_installer.install(project.getFile(), pomArtifact, m_localRepo);
		} catch (ArtifactInstallationException e) {
			throw new MojoExecutionException(
					"Unable to install deployment POM " + pomArtifact);
		}
	}

	/**
	 * Dynamically resolve and load the Pax-Runner class
	 * 
	 * @param groupId
	 *            pax-runner group id
	 * @param artifactId
	 *            pax-runner artifact id
	 * @param mainClass
	 *            main pax-runner classname
	 * @param needClassifier
	 *            classify pax-runner artifact according to current JVM
	 * @return main pax-runner class
	 * @throws MojoExecutionException
	 */
	private Class loadRunnerClass(String groupId, String artifactId,
			String mainClass, boolean needClassifier)
			throws MojoExecutionException {
		String jdk = null;
		if (needClassifier
				&& System.getProperty("java.class.version").compareTo("49.0") < 0) {
			jdk = "jdk14";
		}

		Artifact jarArtifact = m_factory.createArtifactWithClassifier(groupId,
				artifactId, runner, "jar", jdk);
		if (!PomUtils.downloadFile(jarArtifact, m_resolver, m_remoteRepos,
				m_localRepo)) {
			throw new MojoExecutionException("Unable to find Pax-Runner "
					+ jarArtifact);
		}

		URL[] urls = new URL[1];
		try {
			urls[0] = jarArtifact.getFile().toURI().toURL();
		} catch (MalformedURLException e) {
			throw new MojoExecutionException("Bad Jar location "
					+ jarArtifact.getFile());
		}

		try {
			ClassLoader loader = new URLClassLoader(urls);
			Thread.currentThread().setContextClassLoader(loader);
			return Class.forName(mainClass, true, loader);
		} catch (ClassNotFoundException e) {
			throw new MojoExecutionException("Unable to find entry point "
					+ mainClass + " in " + urls[0]);
		}
	}

	/**
	 * Deploy bundles using the 'classic' Pax-Runner
	 * 
	 * @param mainClass
	 *            main Pax-Runner class
	 * @param project
	 *            deployment project
	 * @param repositories
	 *            comma separated list of Maven repositories
	 * @throws MojoExecutionException
	 */
	private void deployRunnerClassic(Class mainClass, MavenProject project,
			String repositories) throws MojoExecutionException {
		String workDir = project.getBasedir() + "/runner";

		String cachedPomName = project.getArtifactId() + '_'
				+ project.getVersion() + ".pom";
		File cachedPomFile = new File(workDir + "/lib/" + cachedPomName);

		// Force reload of pom
		cachedPomFile.delete();

		if (PomUtils.isEmpty(framework)) {
			framework = "felix";
		}

		String[] deployAppCmds = { "--dir=" + workDir, "--no-md5",
				"--platform=" + framework, "--profile=default",
				"--repository=" + repositories,
				"--localRepository=" + m_localRepo.getBasedir(),
				project.getGroupId(), project.getArtifactId(),
				project.getVersion() };

		invokePaxRunner(mainClass, deployAppCmds);
	}

	/**
	 * This method allows subclasses to specify additional commands for
	 * provisioning. By default provide no initial deploy commands.
	 * 
	 * @return A List of Strings that represent the deploy commands.
	 */
	protected List getDeployCommands() {
		return new ArrayList();
	}

	/**
	 * Deploy bundles using the new Pax-Runner codebase
	 * 
	 * @param mainClass
	 *            main Pax-Runner class
	 * @param project
	 *            deployment project
	 * @param repositories
	 *            comma separated list of Maven repositories
	 * @throws MojoExecutionException
	 */
	private void deployRunnerNG(Class mainClass, MavenProject project,
			String repositories) throws MojoExecutionException {
		List deployAppCmds = getDeployCommands();

		// only apply if explicitly configured
		if (PomUtils.isNotEmpty(framework)) {
			deployAppCmds.add("--platform=" + framework);
		}
		if (PomUtils.isNotEmpty(profiles)) {
			deployAppCmds.add("--profiles=" + profiles);
		}

		if (PomUtils.isNotEmpty(args)) {
			try {
				new URL(args); // check syntax
			} catch (MalformedURLException e) {
				// assume it's a local filename
				File argsFile = new File(args);
				args = argsFile.toURI().toString();
			}

			// custom Pax-Runner arguments file
			deployAppCmds.add("--args=" + args);
		}

		// apply project provision settings before defaults
		deployAppCmds.addAll(Arrays.asList(provision));

		// main deployment pom with project bundles as dependencies
		deployAppCmds.add(project.getFile().getAbsolutePath());

		if (PomUtils.isNotEmpty(deployURLs)) {
			// additional (external) bundle URLs
			String[] urls = deployURLs.split(",");
			for (int i = 0; i < urls.length; i++) {
				deployAppCmds.add(urls[i].trim());
			}
		}

		// use project settings to access remote/local repositories
		deployAppCmds.add("--localRepository=" + m_localRepo.getBasedir());
		deployAppCmds.add("--repositories=" + repositories);
		deployAppCmds.add("--overwriteUserBundles");

		getLog().debug(
				"Starting Pax-Runner " + runner + " with: "
						+ deployAppCmds.toString());
		invokePaxRunner(mainClass,
				(String[]) deployAppCmds.toArray(new String[deployAppCmds
						.size()]));
	}

	/**
	 * Invoke Pax-Runner in-process
	 * 
	 * @param mainClass
	 *            main Pax-Runner class
	 * @param commands
	 *            array of command-line options
	 * @throws MojoExecutionException
	 */
	private void invokePaxRunner(Class mainClass, String[] commands)
			throws MojoExecutionException {
		Class[] paramTypes = new Class[1];
		paramTypes[0] = String[].class;

		Object[] paramValues = new Object[1];
		paramValues[0] = commands;

		try {
			Method entryPoint = mainClass.getMethod("main", paramTypes);
			entryPoint.invoke(null, paramValues);
		} catch (NoSuchMethodException e) {
			throw new MojoExecutionException(
					"Unable to find Pax-Runner entry point");
		} catch (IllegalAccessException e) {
			throw new MojoExecutionException(
					"Unable to access Pax-Runner entry point");
		} catch (InvocationTargetException e) {
			throw new MojoExecutionException("Pax-Runner exception", e);
		}
	}

	/**
	 * @return backup OPS4J remote repository
	 */
	ArtifactRepository getOps4jRepository() {
		ArtifactRepositoryPolicy noSnapshots = new ArtifactRepositoryPolicy(
				false, ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, null);
		ArtifactRepositoryPolicy releases = new ArtifactRepositoryPolicy(true,
				ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER, null);

		return m_repoFactory.createArtifactRepository("ops4j.releases",
				"http://repository.ops4j.org/maven2/", m_defaultLayout,
				noSnapshots, releases);
	}
}
