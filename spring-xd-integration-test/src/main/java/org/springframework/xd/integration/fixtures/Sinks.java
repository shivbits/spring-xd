/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.xd.integration.fixtures;

import org.springframework.util.Assert;
import org.springframework.xd.integration.util.XdEnvironment;
import org.springframework.xd.test.fixtures.JdbcSink;
import org.springframework.xd.test.fixtures.LogSink;
import org.springframework.xd.test.fixtures.MqttSink;
import org.springframework.xd.test.fixtures.SimpleFileSink;
import org.springframework.xd.test.fixtures.TcpSink;


/**
 * A factory that provides fully instantiated sink fixtures based on the environment selected at test startup.
 *
 * @author Glenn Renfro
 */
public class Sinks {

	private TcpSink tcpSink;

	private JdbcSink jdbcSink;

	private XdEnvironment environment;

	/**
	 * Initializes the instance with xdEnvironment
	 *
	 * @param environment
	 */
	public Sinks(XdEnvironment environment) {
		Assert.notNull(environment, "xdEnvironment must not be null");
		this.environment = environment;
	}

	/**
	 * Construct a new TcpSink with the default target host taken from the environment and default port (1234)
	 *
	 * @return an instance of TcpSink
	 */
	public TcpSink tcp() {
		if (tcpSink == null) {
			tcpSink = TcpSink.withDefaultPort();
		}
		return tcpSink;
	}

	/**
	 * Construct a new TcpSink with the default target host taken from the environment and the provided port.
	 *
	 * @param port the port to connect to
	 * @return an instance of TcpSink
	 */
	public TcpSink tcp(int port) {
		return new TcpSink(port);
	}

	/**
	 * Construct a new Mqttsink using the default RabbitMQ (MQTT-enbaled) broker host as specified in the environment.
	 *
	 * @return a mqtt sink
	 */
	public MqttSink mqtt() {
		return new MqttSink(environment.getAdminServerUrl().getHost(), 1883);
	}

	/**
	 * Construct a new fileSink that will write the result to the dir and filename specified.
	 *
	 * @param dir the directory the file will be written.
	 * @param fileName the name of file to be written.
	 * @return an instantiated file sink
	 */
	public SimpleFileSink file(String dir, String fileName) {
		return new SimpleFileSink(dir, fileName);
	}

	/**
	 * Construct a new fileSink that will write the result to the xd output directory using the stream name as the
	 * filename.
	 *
	 * @return an instantiated file sink
	 */
	public SimpleFileSink file() {
		return new SimpleFileSink();
	}

	/**
	 * Construct a new logSink that will write output to the XD log.
	 *
	 * @return a LogSink instance.
	 */
	public LogSink log() {
		return new LogSink("logsink");
	}

	/**
	 * Construct a new jdbcSink that will write the output to a table .
	 *
	 * @return a JdbcSink instance.
	 */
	public JdbcSink jdbc() {
		if (environment.getDataSource() == null) {
			return null;
		}
		jdbcSink = new JdbcSink(environment.getDataSource());

		if (!jdbcSink.isReady()) {
			throw new IllegalStateException("Unable to connecto to database.");
		}
		return jdbcSink;
	}

}
