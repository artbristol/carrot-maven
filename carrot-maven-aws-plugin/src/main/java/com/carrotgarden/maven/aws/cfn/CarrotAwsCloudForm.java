/**
 * Copyright (C) 2010-2012 Andrei Pozolotin <Andrei.Pozolotin@gmail.com>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.carrotgarden.maven.aws.cfn;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.io.FileUtils;
import org.apache.maven.settings.Server;
import org.slf4j.Logger;

import com.amazonaws.auth.AWSCredentials;
import com.carrotgarden.maven.aws.CarrotAws;
import com.carrotgarden.maven.aws.util.AWSCredentialsImpl;
import com.carrotgarden.maven.aws.util.Util;

/**
 * 
 */
public abstract class CarrotAwsCloudForm extends CarrotAws {

	/** amazon template entry */
	public static final String PARAMETERS = "Parameters";

	/**
	 * AWS CloudFormation stack name; must be unique under your aws account /
	 * region
	 * 
	 * @required
	 * @parameter default-value="amazon-builder"
	 */
	private String stackName;

	/**
	 * @parameter
	 */
	private String stackNameProperty;

	protected String stackName() {
		if (stackNameProperty == null) {
			return stackName;
		} else {
			return (String) project().getProperties().get(stackNameProperty);
		}
	}

	/**
	 * AWS CloudFormation
	 * 
	 * <a href=
	 * "http://docs.amazonwebservices.com/AWSSecurityCredentials/1.0/AboutAWSCredentials.html"
	 * >amazon security credentials</a>
	 * 
	 * stored in
	 * 
	 * <a href=
	 * "http://www.sonatype.com/books/mvnref-book/reference/appendix-settings-sect-details.html"
	 * >maven settings.xml</a>
	 * 
	 * under server id entry; username="Access Key ID",
	 * password="Secret Access Key";
	 * 
	 * @required
	 * @parameter default-value="com.example.aws.stack"
	 */
	protected String stackServerId;

	/**
	 * AWS CloudFormation operation timeout; seconds
	 * 
	 * @required
	 * @parameter default-value="600"
	 */
	protected String stackTimeout;

	/**
	 * AWS CloudFormation
	 * 
	 * <a href=
	 * "http://docs.amazonwebservices.com/general/latest/gr/rande.html#cfn_region"
	 * >optional api end point url</a>
	 * 
	 * which controls amazon region selection;
	 * 
	 * when omitted, will be constructed from {@link #amazonRegion}
	 * 
	 * @parameter
	 */
	private String stackEndpoint;

	protected String stackEndpoint() {
		if (stackEndpoint == null) {
			return "https://cloudformation." + amazonRegion()
					+ ".amazonaws.com";
		} else {
			return stackEndpoint;
		}
	}

	//

	protected Map<String, String> loadPluginParams(final Properties inputProps,
			final Map<String, String> inputParams) throws Exception {

		/** merge template parameters */

		final Map<String, String> pluginParams = new TreeMap<String, String>();

		/** from properties file */
		pluginParams.putAll(safeMap(inputProps));

		/** from maven pom.xml */
		pluginParams.putAll(safeMap(inputParams));

		return pluginParams;

	}

	protected CloudFormation newCloudFormation(final File templateFile,
			final Map<String, String> stackParams) throws Exception {

		/** */

		final String stackTemplate = safeTemplate(templateFile);

		/** */

		final Server server = settings().getServer(stackServerId);

		if (server == null) {
			throw new IllegalArgumentException(
					"settings.xml : server definition is missing for serverId="
							+ stackServerId);
		}

		final AWSCredentials credentials = new AWSCredentialsImpl(server);

		/** */

		final Logger logger = getLogger(CloudFormation.class);

		/** */

		final long stackTimeout = safeNumber(this.stackTimeout, 600);

		final CloudFormation formation = new CloudFormation(logger,
				stackName(), stackTemplate, stackParams, stackTimeout,
				credentials, stackEndpoint());

		return formation;

	}

	protected String safeTemplate(final File templateFile) throws Exception {
		if (templateFile == null || !templateFile.exists()) {
			return "{}";
		} else {
			return FileUtils.readFileToString(templateFile);
		}
	}

	protected long safeNumber(final String numberText, final long numberDefault) {
		try {
			return Long.parseLong(numberText);
		} catch (final Throwable e) {
			getLog().warn("using numberDefault=" + numberDefault);
			return numberDefault;
		}
	}

	@SuppressWarnings("unused")
	private void overridePluginProperties(final Map<String, String> stackParams) {

		final Set<Map.Entry<String, String>> entrySet = //
		new HashSet<Entry<String, String>>(stackParams.entrySet());

		for (final Map.Entry<String, String> entry : entrySet) {

			final String key = entry.getKey();
			final String value = entry.getValue();

			if (!key.startsWith("stack")) {
				continue;
			}

			stackParams.remove(key);

			try {

				final Field field = Util.findField(this.getClass(), key);

				field.set(this, value);

				getLog().info("override : " + key + "=" + value);

			} catch (final Exception e) {

				getLog().warn("override : invalid stack param=" + key, e);

			}

		}

	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected Set<String> loadTemplateParamNames(final File templateFile)
			throws Exception {

		final Set<String> nameSet = new TreeSet<String>();

		if (templateFile == null || !templateFile.exists()) {
			return nameSet;
		}

		final Map templateMap = Util.loadJson(templateFile, Map.class);

		final Map paramMap = (Map) templateMap.get(PARAMETERS);

		if (paramMap == null) {
			return nameSet;
		}

		nameSet.addAll(paramMap.keySet());

		return nameSet;

	}

	protected Map<String, String> loadTemplateParameters(
			final File templateFile, final Map<String, String> pluginParams)
			throws Exception {

		final Map<String, String> stackParams = new TreeMap<String, String>();

		final Set<String> nameSet = loadTemplateParamNames(templateFile);

		final Properties propsProject = project().getProperties();
		final Properties propsCommand = session().getUserProperties();
		final Properties propsSystem = session().getSystemProperties();

		for (final String name : nameSet) {

			if (pluginParams.containsKey(name)) {
				stackParams.put(name, pluginParams.get(name));
				continue;
			}

			if (propsProject.containsKey(name)) {
				stackParams.put(name, propsProject.get(name).toString());
				continue;
			}

			if (propsCommand.containsKey(name)) {
				stackParams.put(name, propsCommand.get(name).toString());
				continue;
			}

			if (propsSystem.containsKey(name)) {
				stackParams.put(name, propsSystem.get(name).toString());
				continue;
			}

		}

		return stackParams;

	}

}
