/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.whirr.cluster.actions;

import static org.jclouds.compute.options.TemplateOptions.Builder.runScript;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.whirr.service.Cluster;
import org.apache.whirr.service.Cluster.Instance;
import org.apache.whirr.service.ClusterActionEvent;
import org.apache.whirr.service.ClusterActionHandler;
import org.apache.whirr.service.ClusterSpec;
import org.apache.whirr.service.ClusterSpec.InstanceTemplate;
import org.apache.whirr.service.ComputeServiceContextBuilder;
import org.apache.whirr.service.jclouds.StatementBuilder;
import org.apache.whirr.service.jclouds.TemplateBuilderStrategy;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.ComputeServiceContextFactory;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.io.Payload;
import org.jclouds.scriptbuilder.domain.AuthorizeRSAPublicKey;
import org.jclouds.scriptbuilder.domain.InstallRSAPrivateKey;
import org.jclouds.scriptbuilder.domain.OsFamily;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.domain.StatementList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link org.apache.whirr.service.ClusterAction} that starts instances in a cluster in parallel and
 * runs bootstrap scripts on them.
 */
public class BootstrapClusterAction extends ScriptBasedClusterAction {
  
  private static final Logger LOG =
    LoggerFactory.getLogger(BootstrapClusterAction.class);
  
  private final NodeStarterFactory nodeStarterFactory;
  
  public BootstrapClusterAction(final ComputeServiceContextFactory computeServiceContextFactory,
      final Map<String, ClusterActionHandler> handlerMap) {
    this(computeServiceContextFactory, handlerMap, new NodeStarterFactory());
  }
  
  BootstrapClusterAction(final ComputeServiceContextFactory computeServiceContextFactory,
      final Map<String, ClusterActionHandler> handlerMap, final NodeStarterFactory nodeStarterFactory) {
    super(computeServiceContextFactory, handlerMap);
    this.nodeStarterFactory = nodeStarterFactory;
  }
  
  @Override
  protected String getAction() {
    return ClusterActionHandler.BOOTSTRAP_ACTION;
  }
  
  @Override
  protected void doAction(Map<InstanceTemplate, ClusterActionEvent> eventMap)
      throws IOException, InterruptedException {
    LOG.info("Bootstrapping cluster");
    
    ExecutorService executorService = Executors.newCachedThreadPool();    
    Map<InstanceTemplate, Future<Set<? extends NodeMetadata>>> futures = Maps.newHashMap();
    
    // initialize startup processes per InstanceTemplates
    for (Entry<InstanceTemplate, ClusterActionEvent> entry : eventMap.entrySet()) {
      final InstanceTemplate instanceTemplate = entry.getKey();
      final ClusterSpec clusterSpec = entry.getValue().getClusterSpec();
      final int maxNumberOfRetries = clusterSpec.getMaxStartupRetries(); 
      StatementBuilder statementBuilder = entry.getValue().getStatementBuilder();
      ComputeServiceContext computeServiceContext =
        ComputeServiceContextBuilder.build(getComputeServiceContextFactory(), clusterSpec);
      final ComputeService computeService =
        computeServiceContext.getComputeService();
      final Template template = buildTemplate(clusterSpec, computeService,
          statementBuilder, entry.getValue().getTemplateBuilderStrategy());
      
      Future<Set<? extends NodeMetadata>> nodesFuture = executorService.submit(
          new StartupProcess(
              clusterSpec.getClusterName(),
              instanceTemplate.getNumberOfInstances(),
              instanceTemplate.getMinNumberOfInstances(),
              maxNumberOfRetries,
              instanceTemplate.getRoles(),
              computeService, template, executorService, nodeStarterFactory));
      futures.put(instanceTemplate, nodesFuture);
    }
    
    Set<Instance> instances = Sets.newLinkedHashSet();
    for (Entry<InstanceTemplate, Future<Set<? extends NodeMetadata>>> entry :
        futures.entrySet()) {
      Set<? extends NodeMetadata> nodes;
      try {
        nodes = entry.getValue().get();
      } catch (ExecutionException e) {
        // Some of the StartupProcess decided to throw IOException, 
        // to fail the cluster because of insufficient successfully started
        // nodes after retries
        throw new IOException(e);
      }
      Set<String> roles = entry.getKey().getRoles();
      instances.addAll(getInstances(roles, nodes));
    }
    Cluster cluster = new Cluster(instances);
    for (ClusterActionEvent event : eventMap.values()) {
      event.setCluster(cluster);
    }
  }

  private Template buildTemplate(ClusterSpec clusterSpec,
      ComputeService computeService, StatementBuilder statementBuilder,
      TemplateBuilderStrategy strategy)
      throws MalformedURLException {
    LOG.info("Configuring template");
    if (LOG.isDebugEnabled())
      LOG.debug("Running script:\n{}", statementBuilder.render(OsFamily.UNIX));
    Statement runScript = new StatementList(
          new AuthorizeRSAPublicKey(clusterSpec.getPublicKey()),
          statementBuilder,
          new InstallRSAPrivateKey(clusterSpec.getPrivateKey()));
    TemplateBuilder templateBuilder = computeService.templateBuilder()
      .options(runScript(runScript));
    strategy.configureTemplateBuilder(clusterSpec, templateBuilder);
    return templateBuilder.build();
    
  }

  private Set<Instance> getInstances(final Set<String> roles,
      Set<? extends NodeMetadata> nodes) {
    return Sets.newLinkedHashSet(Collections2.transform(Sets.newLinkedHashSet(nodes),
        new Function<NodeMetadata, Instance>() {
      @Override
      public Instance apply(NodeMetadata node) {
        try {
        return new Instance(node.getCredentials(), roles,
            InetAddress.getByName(Iterables.get(node.getPublicAddresses(), 0)),
            InetAddress.getByName(Iterables.get(node.getPrivateAddresses(), 0)),
            node.getId());
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
      }
    }));
  }

  class StartupProcess implements Callable<Set<? extends NodeMetadata>> {
   
    final private String clusterName;
    final private int numberOfNodes;
    final private int minNumberOfNodes;
    final private int maxStartupRetries;
    final private Set<String> roles;
    final private ComputeService computeService;
    final private Template template;
    final private ExecutorService executorService;
    final private NodeStarterFactory starterFactory;

    private Set<NodeMetadata> successfulNodes = Sets.newLinkedHashSet();
    private Map<NodeMetadata, Throwable> lostNodes = Maps.newHashMap();
    
    private Future<Set<NodeMetadata>> nodesFuture;
        
    StartupProcess(final String clusterName, final int numberOfNodes, 
        final int minNumberOfNodes, final int maxStartupRetries, final Set<String> roles, 
        final ComputeService computeService, final Template template, 
        final ExecutorService executorService, final NodeStarterFactory starterFactory) {
      this.clusterName = clusterName;
      this.numberOfNodes = numberOfNodes;
      this.minNumberOfNodes = minNumberOfNodes;
      this.maxStartupRetries = maxStartupRetries;
      this.roles = roles;
      this.computeService = computeService;
      this.template = template;
      this.executorService = executorService;
      this.starterFactory = starterFactory;
    }

    @Override
    public Set<? extends NodeMetadata> call() throws Exception {
      int retryCount = 0;
      boolean retryRequired;
      try {
      do {   
          runNodesWithTag();         
          waitForOutcomes(); 
          retryRequired = !isDone();
          
          if (++retryCount > maxStartupRetries) {
            break; // no more retries
          }
        } while (retryRequired);
        
        if (retryRequired) {// if still required, we cannot use the cluster
          // in this case of failed cluster startup, cleaning of the nodes are postponed
          throw new IOException("Too many instance failed while bootstrapping! " 
              + successfulNodes.size() + " successfully started instances while " + lostNodes.size() + " instances failed");      
        }
      } finally {
        cleanupFailedNodes();
      }
      return successfulNodes;
    }
        
    String getClusterName() {
      return clusterName;
    }
    
    Template getTemplate() {
      return template;
    }
    
    Set<NodeMetadata> getSuccessfulNodes() {
      return successfulNodes;
    }
    
    Map<NodeMetadata, Throwable> getNodeErrors() {
      return lostNodes;
    }
    
    boolean isDone() {
      return successfulNodes.size() >= minNumberOfNodes;
    }
    
    void runNodesWithTag() {
      final int num = numberOfNodes - successfulNodes.size();
      this.nodesFuture = executorService.submit(starterFactory.create(
          computeService, clusterName, roles, num, template));
    }
    
    void waitForOutcomes() throws InterruptedException {
      try {
        Set<? extends NodeMetadata> nodes = nodesFuture.get();
        successfulNodes.addAll(nodes);
      } catch (ExecutionException e) {
        // checking RunNodesException and collect the outcome
        Throwable th = e.getCause();
        if (th instanceof RunNodesException) {
          RunNodesException rnex = (RunNodesException) th;
          successfulNodes.addAll(rnex.getSuccessfulNodes());
          lostNodes.putAll(rnex.getNodeErrors());
        } else {
          LOG.error("Unexpected error while starting " + numberOfNodes + " nodes, minimum " 
              + minNumberOfNodes + " nodes for " + roles + " of cluster " + clusterName, e);
        }
      }
    }
    
    void cleanupFailedNodes() throws InterruptedException {
      if (lostNodes.size() > 0) {
        // parallel destroy of failed nodes
        Set<Future<NodeMetadata>> deletingNodeFutures = Sets.newLinkedHashSet();
        Iterator<?> it = lostNodes.keySet().iterator();
        while (it.hasNext()) {
          final NodeMetadata badNode = (NodeMetadata) it.next();         
          deletingNodeFutures.add(executorService.submit(
              new Callable<NodeMetadata>() {
                public NodeMetadata call() throws Exception {
                  final String nodeId = badNode.getId();
                  LOG.info("Deleting failed node node {}", nodeId);
                  computeService.destroyNode(nodeId);
                  LOG.info("Node deleted: {}", nodeId);
                  return badNode;
                }
              }
            ));
        }
        Iterator<Future<NodeMetadata>> results = deletingNodeFutures.iterator();
        while (results.hasNext()) {
          try {
            results.next().get();
          } catch (ExecutionException e) {
            LOG.warn("Error while destroying failed node:", e);
          }
        }
      }
    } 
  }
}

class NodeStarterFactory {   
  NodeStarter create(final ComputeService computeService, final String clusterName,
      final Set<String> roles, final int num, final Template template) {
    return new NodeStarter(computeService, clusterName, roles, num, template);
  }
}

class NodeStarter implements Callable<Set<NodeMetadata>> {

  private static final Logger LOG =
    LoggerFactory.getLogger(NodeStarter.class);

  final private ComputeService computeService;
  final private String clusterName;
  final private Set<String> roles;
  final private int num;
  final private Template template;
  
  public NodeStarter(final ComputeService computeService, final String clusterName,
    final Set<String> roles, final int num, final Template template) {
    this.computeService = computeService;
    this.clusterName = clusterName;
    this.roles = roles;
    this.num = num;
    this.template = template;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Set<NodeMetadata> call() throws Exception {
    LOG.info("Starting {} node(s) with roles {}", num,
        roles);
    Set<NodeMetadata> nodes = (Set<NodeMetadata>)computeService
    	.createNodesInGroup(clusterName, num, template);
    LOG.info("Nodes started: {}", nodes);
    return nodes;
  }
}

