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

package org.apache.whirr.service.hbase;

import static org.apache.whirr.service.RolePredicates.role;
import static org.jclouds.scriptbuilder.domain.Statements.call;

import java.io.IOException;
import java.net.InetAddress;

import org.apache.whirr.net.DnsUtil;
import org.apache.whirr.service.Cluster;
import org.apache.whirr.service.Cluster.Instance;
import org.apache.whirr.service.ClusterActionEvent;
import org.apache.whirr.service.ClusterSpec;
import org.apache.whirr.service.ComputeServiceContextBuilder;
import org.apache.whirr.service.jclouds.FirewallSettings;
import org.apache.whirr.service.zookeeper.ZooKeeperCluster;
import org.jclouds.compute.ComputeServiceContext;

public class HBaseRegionServerClusterActionHandler extends HBaseClusterActionHandler {

  public static final String ROLE = "hbase-regionserver";

  public static final int REGIONSERVER_PORT = 60020;
  public static final int REGIONSERVER_WEB_UI_PORT = 60030;

  @Override
  public String getRole() {
    return ROLE;
  }

  @Override
  protected void beforeBootstrap(ClusterActionEvent event) throws IOException {
    ClusterSpec clusterSpec = event.getClusterSpec();    
    addStatement(event, call("configure_hostnames",
      HBaseConstants.PARAM_PROVIDER, clusterSpec.getProvider()));
    addStatement(event, call("install_java"));
    String hbaseInstallFunction = getConfiguration(clusterSpec).getString(
      HBaseConstants.KEY_INSTALL_FUNCTION, HBaseConstants.FUNCTION_INSTALL);
    String tarurl = getConfiguration(clusterSpec).getString(
      HBaseConstants.KEY_TARBALL_URL);
    addStatement(event, call(hbaseInstallFunction,
      HBaseConstants.PARAM_PROVIDER, clusterSpec.getProvider(),
      HBaseConstants.PARAM_TARBALL_URL, tarurl));
    event.setTemplateBuilderStrategy(new HBaseTemplateBuilderStrategy());
  }

  @Override
  protected void beforeConfigure(ClusterActionEvent event)
      throws IOException, InterruptedException {
    ClusterSpec clusterSpec = event.getClusterSpec();
    Cluster cluster = event.getCluster();

    Instance instance = cluster.getInstanceMatching(
      role(HBaseMasterClusterActionHandler.ROLE));
    InetAddress masterPublicAddress = instance.getPublicAddress();

    ComputeServiceContext computeServiceContext =
      ComputeServiceContextBuilder.build(clusterSpec);
    FirewallSettings.authorizeIngress(computeServiceContext, instance, clusterSpec,
      REGIONSERVER_WEB_UI_PORT);
    FirewallSettings.authorizeIngress(computeServiceContext, instance, clusterSpec,
      REGIONSERVER_PORT);

    String hbaseConfigureFunction = getConfiguration(clusterSpec).getString(
      HBaseConstants.KEY_CONFIGURE_FUNCTION,
      HBaseConstants.FUNCTION_POST_CONFIGURE);
    String master = DnsUtil.resolveAddress(masterPublicAddress.getHostAddress());
    String quorum = ZooKeeperCluster.getHosts(cluster);
    String tarurl = getConfiguration(clusterSpec).getString(
      HBaseConstants.KEY_TARBALL_URL);   
    addStatement(event, call(hbaseConfigureFunction, ROLE,
      HBaseConstants.PARAM_MASTER, master,
      HBaseConstants.PARAM_QUORUM, quorum,
      HBaseConstants.PARAM_PROVIDER, clusterSpec.getProvider(),
      HBaseConstants.PARAM_TARBALL_URL, tarurl));
  }

}
