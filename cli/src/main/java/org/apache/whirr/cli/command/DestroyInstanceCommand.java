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

package org.apache.whirr.cli.command;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.whirr.service.ClusterSpec;
import org.apache.whirr.service.Service;
import org.apache.whirr.service.ServiceFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.util.List;

/**
 * A command to destroy an instance from a cluster
 */
public class DestroyInstanceCommand extends AbstractClusterSpecCommand {

  private OptionSpec<String> instanceOption = parser
      .accepts("instance-id", "Cluster instance ID")
      .withRequiredArg()
      .ofType(String.class);

  public DestroyInstanceCommand() throws IOException {
    this(new ServiceFactory());
  }

  public DestroyInstanceCommand(ServiceFactory factory) {
    super("destroy-instance", "Terminate and cleanup resources " +
        "for a single instance.", factory);
  }

  @Override
  public int run(InputStream in, PrintStream out,
                 PrintStream err, List<String> args) throws Exception {

    OptionSet optionSet = parser.parse(args.toArray(new String[0]));
    if (!optionSet.nonOptionArguments().isEmpty()) {
      printUsage(parser, err);
      return -1;
    }
    try {
      if (!optionSet.hasArgument(instanceOption)) {
        throw new IllegalArgumentException("You need to specify an instance ID.");
      }
      ClusterSpec clusterSpec = getClusterSpec(optionSet);
      Service service = createService(clusterSpec.getServiceName());

      String instanceId = optionSet.valueOf(instanceOption);
      service.destroyInstance(clusterSpec, instanceId);
      updateInstancesFile(clusterSpec, instanceId);

      return 0;

    } catch(IllegalArgumentException e) {
      err.println(e.getMessage());
      printUsage(parser, err);
      return -1;
    }
  }

  private void updateInstancesFile(ClusterSpec clusterSpec, String instanceId)
      throws IOException {
    File instances = new File(clusterSpec.getClusterDirectory(), "instances");
    if (!instances.exists()) return; // no file to update

    StringBuilder newLines = new StringBuilder();

    /* Filter the line containing the instance ID */
    BufferedReader reader = new BufferedReader(new FileReader(instances));
    String line = null;
    while((line = reader.readLine()) != null) {
      if (!line.contains(instanceId)) {
        newLines.append(line + "\n");
      }
    }
    reader.close();

    /* Rewrite the file to the disk */
    Writer writer = new FileWriter(instances);
    writer.write(newLines.toString());
    writer.close();
  }

  private void printUsage(OptionParser parser, PrintStream stream) throws IOException {
    stream.println("Usage: whirr destroy-instance --instance-id <ID> [OPTIONS]");
    stream.println();
    parser.printHelpOn(stream);
  }
}
