/*
 * Copyright 2014 the original author or authors.
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

package org.springframework.xd.dirt.server;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.utils.ThreadUtils;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.core.convert.converter.Converter;
import org.springframework.util.Assert;
import org.springframework.xd.dirt.cluster.Container;
import org.springframework.xd.dirt.cluster.ContainerRepository;
import org.springframework.xd.dirt.module.ModuleDefinitionRepository;
import org.springframework.xd.dirt.stream.StreamDefinitionRepository;
import org.springframework.xd.dirt.util.MapBytesUtility;
import org.springframework.xd.dirt.zookeeper.ChildPathIterator;
import org.springframework.xd.dirt.zookeeper.Paths;
import org.springframework.xd.dirt.zookeeper.ZooKeeperConnection;
import org.springframework.xd.dirt.zookeeper.ZooKeeperConnectionListener;
import org.springframework.xd.module.options.ModuleOptionsMetadataResolver;

/**
 * Process that watches ZooKeeper for Container arrivals and departures from
 * the XD cluster. Each {@code DeploymentSupervisor} instance will attempt
 * to request leadership, but at any given time only one {@code DeploymentSupervisor}
 * instance in the cluster will have leadership status.
 *
 * @author Patrick Peralta
 * @author Mark Fisher
 *
 * @see org.apache.curator.framework.recipes.leader.LeaderSelector
 */
public class DeploymentSupervisor implements ContainerRepository, ApplicationListener<ApplicationEvent>, DisposableBean {

	/**
	 * Logger.
	 */
	private static final Logger LOG = LoggerFactory.getLogger(DeploymentSupervisor.class);

	/**
	 * ZooKeeper connection.
	 */
	private final ZooKeeperConnection zkConnection;

	/**
	 * Repository to load streams.
	 */
	private final StreamDefinitionRepository streamDefinitionRepository;

	/**
	 * Repository to load module definitions.
	 */
	private final ModuleDefinitionRepository moduleDefinitionRepository;

	/**
	 * Resolver for module options metadata.
	 */
	private final ModuleOptionsMetadataResolver moduleOptionsMetadataResolver;

	/**
	 * {@link ApplicationContext} for this admin server. This reference is updated
	 * via an application context event and read via {@link #getId()}.
	 */
	private volatile ApplicationContext applicationContext;

	/**
	 * Cache of children under the containers path. This path is used to track containers in the cluster.
	 * This atomic reference is updated by:
	 * <ul>
	 *     <li>the thread that handles leadership election</li>
	 *     <li>the thread that raises Curator/ZooKeeper disconnect events</li>
	 * </ul>
	 * This atomic reference is read by public method {@link #getContainerIterator}.
	 * <p />
	 * Note that if this reference is not null, this indicates that the cache
	 * has been started and has not been shut down via {@link PathChildrenCache#close}.
	 */
	private final AtomicReference<PathChildrenCache> containers = new AtomicReference<PathChildrenCache>();

	/**
	 * Converter from {@link ChildData} types to {@link Container}.
	 */
	private final ContainerConverter containerConverter = new ContainerConverter();

	/**
	 * Utility to convert maps to byte arrays.
	 */
	private final MapBytesUtility mapBytesUtility = new MapBytesUtility();

	/**
	 * Leader selector to elect admin server that will handle stream deployment requests. Marked volatile because this
	 * reference is written and read by the Curator event dispatch threads - there is no guarantee that the same thread
	 * will do the reading and writing.
	 */
	private volatile LeaderSelector leaderSelector;

	/**
	 * Listener that is invoked when this admin server is elected leader.
	 */
	private final LeaderSelectorListener leaderListener = new LeaderListener();

	/**
	 * ZooKeeper connection listener that attempts to obtain leadership when
	 * the ZooKeeper connection is established.
	 */
	private final ConnectionListener connectionListener = new ConnectionListener();

	/**
	 * Construct a {@code DeploymentSupervisor}.
	 *
	 * @param zkConnection                   ZooKeeper connection
	 * @param streamDefinitionRepository     repository for streams
	 * @param moduleDefinitionRepository     repository for modules
	 * @param moduleOptionsMetadataResolver  resolver for module options metadata
	 */
	public DeploymentSupervisor(ZooKeeperConnection zkConnection,
			StreamDefinitionRepository streamDefinitionRepository,
			ModuleDefinitionRepository moduleDefinitionRepository,
			ModuleOptionsMetadataResolver moduleOptionsMetadataResolver) {
		Assert.notNull(zkConnection, "ZooKeeperConnection must not be null");
		Assert.notNull(streamDefinitionRepository, "StreamDefinitionRepository must not be null");
		Assert.notNull(moduleDefinitionRepository, "ModuleDefinitionRepository must not be null");
		Assert.notNull(moduleOptionsMetadataResolver, "moduleOptionsMetadataResolver must not be null");
		this.zkConnection = zkConnection;
		this.streamDefinitionRepository = streamDefinitionRepository;
		this.moduleDefinitionRepository = moduleDefinitionRepository;
		this.moduleOptionsMetadataResolver = moduleOptionsMetadataResolver;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof ContextRefreshedEvent) {
			this.applicationContext = ((ContextRefreshedEvent) event).getApplicationContext();
			if (this.zkConnection.isConnected()) {
				requestLeadership(this.zkConnection.getClient());
			}
			this.zkConnection.addListener(connectionListener);
		}
		else if (event instanceof ContextStoppedEvent) {
			if (this.leaderSelector != null) {
				this.leaderSelector.close();
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Iterator<Container> getContainerIterator() {
		PathChildrenCache cache = containers.get();
		return cache == null
				? Collections.<Container>emptyIterator()
				: new ChildPathIterator<Container>(containerConverter, cache);
	}

	/**
	 * Return the UUID for this admin server.
	 *
	 * @return id for this admin server
	 */
	private String getId() {
		return this.applicationContext.getId();
	}

	/**
	 * Register the {@link LeaderListener} if not already registered. This method is {@code synchronized} because it may
	 * be invoked either by the thread starting the {@link ApplicationContext} or the thread that raises the ZooKeeper
	 * connection event.
	 *
	 * @param client the {@link CuratorFramework} client
	 */
	private synchronized void requestLeadership(CuratorFramework client) {
		try {
			Paths.ensurePath(client, Paths.MODULE_DEPLOYMENTS);
			Paths.ensurePath(client, Paths.STREAM_DEPLOYMENTS);
			Paths.ensurePath(client, Paths.JOB_DEPLOYMENTS);
			Paths.ensurePath(client, Paths.CONTAINERS);
			Paths.ensurePath(client, Paths.STREAMS);
			Paths.ensurePath(client, Paths.JOBS);

			if (leaderSelector == null) {
				leaderSelector = new LeaderSelector(client, Paths.build(Paths.ADMIN), leaderListener);
				leaderSelector.setId(getId());
				leaderSelector.start();
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void destroy() {
		if (leaderSelector != null) {
			leaderSelector.close();
			leaderSelector = null;
		}
	}

	/**
	 * {@link ZooKeeperConnectionListener} implementation that requests leadership
	 * upon connection to ZooKeeper.
	 */
	private class ConnectionListener implements ZooKeeperConnectionListener {

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void onConnect(CuratorFramework client) {
			LOG.info("Admin {} CONNECTED", getId());
			requestLeadership(client);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void onDisconnect(CuratorFramework client) {
			try {
				destroy();
			}
			catch (Exception e) {
				LOG.warn("exception occurred while closing leader selector", e);
			}
		}
	}

	/**
	 * Converts a {@link ChildData} node to a {@link Container}.
	 */
	public class ContainerConverter implements Converter<ChildData, Container> {

		@Override
		public Container convert(ChildData source) {
			// This converter will be invoked upon every iteration of the
			// iterator returned by getContainerIterator. While elegant,
			// this isn't exactly efficient. TODO - revisit
			return new Container(Paths.stripPath(source.getPath()), mapBytesUtility.toMap(source.getData()));
		}
	}

	/**
	 * Listener implementation that is invoked when this server becomes the leader.
	 */
	class LeaderListener extends LeaderSelectorListenerAdapter {

		/**
		 * {@inheritDoc}
		 * <p/>
		 * Upon leadership election, this Admin server will create a {@link PathChildrenCache}
		 * for {@link Paths#STREAMS} and {@link Paths#JOBS}. These caches will have
		 * {@link PathChildrenCacheListener PathChildrenCacheListeners} attached to them
		 * that will react to stream and job creation and deletion. Upon leadership
		 * relinquishment, the listeners will be removed and the caches shut down.
		 */
		@Override
		public void takeLeadership(CuratorFramework client) throws Exception {
			LOG.info("Leader Admin {} is watching for stream/job deployment requests.", getId());

			cleanupDeployments(client);

			PathChildrenCache streams = null;
			PathChildrenCache streamDeployments = null;
			PathChildrenCache jobDeployments = null;
			PathChildrenCacheListener streamListener;
			PathChildrenCacheListener jobListener;
			PathChildrenCacheListener containerListener;

			try {
				streamListener = new StreamDeploymentListener(DeploymentSupervisor.this,
						streamDefinitionRepository,
						moduleDefinitionRepository,
						moduleOptionsMetadataResolver);

				streams = new PathChildrenCache(client, Paths.STREAMS, true,
						ThreadUtils.newThreadFactory("StreamDefinitionPathChildrenCache"));
				streams.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);

				streamDeployments = new PathChildrenCache(client, Paths.STREAM_DEPLOYMENTS, true,
						ThreadUtils.newThreadFactory("StreamDeploymentsPathChildrenCache"));
				streamDeployments.getListenable().addListener(streamListener);
				streamDeployments.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);

				jobListener = new JobDeploymentListener(DeploymentSupervisor.this, moduleDefinitionRepository,
						moduleOptionsMetadataResolver);

				jobDeployments = new PathChildrenCache(client, Paths.JOB_DEPLOYMENTS, true,
						ThreadUtils.newThreadFactory("JobDeploymentsPathChildrenCache"));
				jobDeployments.getListenable().addListener(jobListener);
				jobDeployments.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);

				containerListener = new ContainerListener(DeploymentSupervisor.this,
						streamDefinitionRepository,
						moduleDefinitionRepository,
						moduleOptionsMetadataResolver, streamDeployments, streams, jobDeployments);

				PathChildrenCache containersCache = new PathChildrenCache(client, Paths.CONTAINERS, true,
						ThreadUtils.newThreadFactory("ContainersPathChildrenCache"));
				containersCache.getListenable().addListener(containerListener);
				containersCache.start();

				containers.set(containersCache);

				Thread.sleep(Long.MAX_VALUE);
			}
			catch (InterruptedException e) {
				LOG.info("Leadership canceled due to thread interrupt");
				Thread.currentThread().interrupt();
			}
			finally {
				PathChildrenCache containersCache = containers.getAndSet(null);
				if (containersCache != null) {
					containersCache.close();
				}

				if (streams != null) {
					streams.close();
				}

				if (streamDeployments != null) {
					streamDeployments.close();
				}

				if (jobDeployments != null) {
					jobDeployments.close();
				}
			}
		}

		/**
		 * Remove module deployments targeted to containers that are no longer running.
		 *
		 * @param client    the {@link CuratorFramework} client
		 *
		 * @throws Exception
		 */
		private void cleanupDeployments(CuratorFramework client) throws Exception {
			Set<String> containerDeployments = new HashSet<String>();

			try {
				containerDeployments.addAll(client.getChildren().forPath(Paths.build(Paths.MODULE_DEPLOYMENTS)));
				containerDeployments.removeAll(client.getChildren().forPath(Paths.build(Paths.CONTAINERS)));
			}
			catch (KeeperException.NoNodeException e) {
				// ignore
			}

			for (String oldContainer : containerDeployments) {
				try {
					client.delete().deletingChildrenIfNeeded().forPath(Paths.build(Paths.MODULE_DEPLOYMENTS, oldContainer));
				}
				catch (KeeperException.NoNodeException e) {
					// ignore
				}
			}
		}
	}

}
