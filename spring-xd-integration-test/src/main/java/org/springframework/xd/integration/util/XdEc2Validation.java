/*
 * Copyright 2011-2014 the original author or authors.
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

package org.springframework.xd.integration.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.xd.integration.util.jmxresult.JMXResult;
import org.springframework.xd.integration.util.jmxresult.Module;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates that all instances of the cluster is up and running. Also verifies that streams are running and available.
 * 
 * @author Glenn Renfro
 */
@Configuration
public class XdEc2Validation {

	private static final Logger LOGGER = LoggerFactory.getLogger(XdEc2Validation.class);

	private final RestTemplate restTemplate;

	/**
	 * Construct a new instance of XdEc2Validation
	 */
	public XdEc2Validation() {
		restTemplate = new RestTemplate();
		((SimpleClientHttpRequestFactory) restTemplate.getRequestFactory())
				.setConnectTimeout(2000);
	}

	/**
	 * Assert is the admin server is available.
	 * 
	 * @param adminServer the location of the admin server
	 */
	public void verifyXDAdminReady(final URL adminServer) {
		Assert.notNull(adminServer, "adminServer can not be null");
		boolean result = verifyAdminConnection(adminServer);
		assertTrue("XD Admin Server is not available at "
				+ adminServer.toString(), result);
	}

	/**
	 * Assert that at least one server the user specified is available.
	 * 
	 * @param containers the location of xd-containers
	 * @param jmxPort the JMX port to connect to the container
	 */
	public void verifyAtLeastOneContainerAvailable(final List<URL> containers,
			int jmxPort) {
		boolean result = false;
		Assert.notNull(containers, "the container list passed in should not be null");
		final Iterator<URL> containerIter = containers.iterator();
		while (containerIter.hasNext()) {
			final URL container = containerIter.next();
			try {
				verifyContainerConnection(StreamUtils.replacePort(container,
						jmxPort));
				result = true;
			}
			catch (ResourceAccessException rae) {
				LOGGER.error("XD Container is not available at "
						+ StreamUtils.replacePort(container, jmxPort));
			}
			catch (HttpClientErrorException hcee) {
				LOGGER.debug(hcee.getMessage());
				result = true;
			}
			catch (IOException ioe) {
				LOGGER.warn("XD Container is not available at "
						+ StreamUtils.replacePort(container, jmxPort));
			}
		}
		assertTrue("No XD Containers are available", result);
	}

	/**
	 * Verifies that the module has in fact processed the data. Keep in mind that any module name must be suffixed with
	 * index number for example .1. So if I have a stream of http|file, to access the modules I will need to have a
	 * module name of http.1 for the source and file.1 for the sink.
	 * 
	 * @param url The server where the stream is deployed
	 * @param streamName The stream to analyze.
	 * @param moduleName The name of the module
	 */
	public void assertReceived(URL url, String streamName,
			String moduleName, int msgCountExpected) {
		Assert.notNull(url, "The url should not be null");
		Assert.hasText(moduleName, "The modulName can not be empty nor null");
		Assert.hasText(streamName, "The streamName can not be empty nor null");
		String request = buildJMXRequest(url, streamName, moduleName);
		try {
			List<Module> modules = getModuleList(StreamUtils.httpGet(new URL(request)));
			verifySendCounts(modules, msgCountExpected);
		}
		catch (IOException ioException) {
			throw new IllegalStateException(ioException.getMessage(), ioException);
		}

	}

	/**
	 * Retrieves the stream and verifies that all modules in the stream processed the data.
	 * 
	 * @param url The server where the stream is deployed
	 * @param streamName The stream to analyze.
	 * @throws Exception Error processing JSON or making HTTP GET request
	 */
	public void assertReceived(URL url, String streamName,
			int msgCountExpected) {
		Assert.notNull(url, "The url should not be null");
		Assert.hasText(streamName, "streamName can not be empty nor null");

		try {
			String request = buildJMXRequest(url, streamName, "*");
			List<Module> modules = getModuleList(StreamUtils.httpGet(new URL(
					request)));
			verifySendCounts(modules, msgCountExpected);
		}
		catch (IOException ioException) {
			throw new IllegalStateException(ioException.getMessage(), ioException);
		}
	}


	/**
	 * Verifies that the data user gave us is what was stored after the stream has processed the flow.
	 * 
	 * @param xdEnvironment the Acceptance Test Environment.
	 * @param url The server that the stream is deployed.
	 * @param fileName The file that contains the data to check.
	 * @param data The data used to evaluate the results of the stream.
	 */
	public void verifyTestContent(XdEnvironment xdEnvironment, URL url, String fileName,
			String data) {
		Assert.notNull(xdEnvironment, "xdEnvironment should not be null");
		Assert.notNull(url, "url should not be null");
		Assert.hasText(fileName, "fileName can not be empty nor null");
		Assert.hasText(data, "data can not be empty nor null");
		String resultFileName = fileName;
		if (xdEnvironment.isOnEc2()) {
			resultFileName = StreamUtils.transferResultsToLocal(xdEnvironment, url, fileName);
		}
		File file = new File(resultFileName);
		try {
			Reader fileReader = new InputStreamReader(new FileInputStream(resultFileName));
			String result = FileCopyUtils.copyToString(fileReader);
			fileReader.close();
			assertEquals("Data in the result file is not what was sent. Read \""
					+ result + "\"\n but expected \"" + data + "\"", data, result);
		}
		catch (IOException ioException) {
			throw new IllegalStateException(ioException.getMessage(), ioException);
		}
		finally {
			if (file.exists()) {
				file.delete();
			}
		}

	}

	/**
	 * Verifies that the data user gave us is contained in the result.
	 * 
	 * @param xdEnvironment the Acceptance Test Environment.
	 * @param url The server that the stream is deployed.
	 * @param fileName The file that contains the data to check.
	 * @param data The data used to evaluate the results of the stream.
	 */
	public void verifyContentContains(XdEnvironment xdEnvironment, URL url, String fileName,
			String data) {
		Assert.notNull(xdEnvironment, "xdEnvironment can not be null");
		Assert.notNull(url, "url can not be null");
		Assert.hasText(fileName, "fileName can not be empty nor null");
		Assert.hasText(data, "data can not be empty nor null");

		String resultFileName = fileName;
		File file = new File(resultFileName);
		try {

			if (xdEnvironment.isOnEc2()) {
				resultFileName = StreamUtils.transferResultsToLocal(xdEnvironment, url, fileName);
				file = new File(resultFileName);
			}

			Reader fileReader = new InputStreamReader(new FileInputStream(resultFileName));
			String result = FileCopyUtils.copyToString(fileReader);
			assertTrue("Could not find data in result file.. Read \""
					+ result + "\"\n but didn't see \"" + data + "\"", result.contains(data));
			fileReader.close();
		}
		catch (IOException ioException) {
			throw new IllegalStateException(ioException.getMessage(), ioException);
		}
		finally {
			if (file.exists()) {
				file.delete();
			}
		}
	}

	/**
	 * generates the JMX query string for getting module data.
	 * 
	 * @param url the container url where the stream is deployed
	 * @param streamName the name of the stream
	 * @param moduleName the module to evaluate on the stream
	 * @return
	 */
	private String buildJMXRequest(URL url, String streamName,
			String moduleName) {
		String result = url.toString() + "/management/jolokia/read/xd." + streamName
				+ ":module=" + moduleName + ",component=MessageChannel,name=*";
		return result;
	}

	private String buildJMXList(URL url) {
		String result = url.toString() + "/management/jolokia/list";
		return result;
	}

	/**
	 * retrieves a list of modules from the json result that was returned by Jolokia.
	 * 
	 * @param json raw json response string from jolokia
	 * @return A list of module information
	 * @throws Exception error parsing JSON
	 */
	private List<Module> getModuleList(String json) throws JsonMappingException, JsonParseException, IOException {
		ObjectMapper mapper = new ObjectMapper();
		JMXResult jmxResult = mapper.readValue(json,
				new TypeReference<JMXResult>() {
				});
		List<Module> result = jmxResult.getValue().getModules();
		return result;
	}

	/**
	 * Asserts that the expected number of messages were processed by the modules in the stream and that no errors
	 * occurred.
	 * 
	 * @param modules The list of modules in the stream
	 * @param msgCountExpected The expected count
	 */
	private void verifySendCounts(List<Module> modules, int msgCountExpected) {
		verifySendCounts(modules, msgCountExpected, false);
	}

	/**
	 * Asserts that the expected number (or greater than or equal to the expected number) of messages were processed by
	 * the modules in the stream. Also asserts that no errors occurred.
	 * 
	 * @param modules The list of modules to evaluate.
	 * @param msgCountExpected The expected count
	 * @param greaterThanOrEqualTo true if should use greaterThanOrEqualToComparison
	 */
	private void verifySendCounts(List<Module> modules, int msgCountExpected, boolean greaterThanOrEqualTo) {
		Iterator<Module> iter = modules.iterator();
		while (iter.hasNext()) {
			Module module = iter.next();
			if (!module.getModuleChannel().equals("output")
					&& !module.getModuleChannel().equals("input")) {
				continue;
			}
			int sendCount = Integer.parseInt(module.getSendCount());
			if (greaterThanOrEqualTo) {
				assertThat("Module " + module.getModuleName() + " for channel " + module.getModuleChannel() +
						" did not have at least expected count ",
						sendCount, greaterThanOrEqualTo(msgCountExpected));
			}
			else {
				assertEquals("Module "
						+ module.getModuleName() + " for channel "
						+ module.getModuleChannel()
						+ " did not have expected count ", msgCountExpected, sendCount);
			}
			int errorCount = Integer.parseInt(module.getSendErrorCount());
			assertFalse("Module "
					+ module.getModuleName() + " for channel "
					+ module.getModuleChannel() + " had an error count of "
					+ errorCount + ",  expected 0.", errorCount > 0);
		}
	}

	private boolean verifyAdminConnection(final URL host)
			throws ResourceAccessException {
		boolean result = true;
		try {
			restTemplate.getForObject(host.toString(), String.class);
		}
		catch (ResourceAccessException rae) {
			LOGGER.error("XD Admin Server is not available at "
					+ host.getHost());
			result = false;
		}
		return result;
	}

	private void verifyContainerConnection(final URL host) throws IOException {
		String request = buildJMXList(host);
		StreamUtils.httpGet(new URL(request));

	}

}
