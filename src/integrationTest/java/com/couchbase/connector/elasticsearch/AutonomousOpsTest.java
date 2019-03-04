/*
 * Copyright 2019 Couchbase, Inc.
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

package com.couchbase.connector.elasticsearch;

import com.couchbase.connector.cluster.consul.AsyncTask;
import com.couchbase.connector.cluster.consul.ConsulConnector;
import com.couchbase.connector.cluster.consul.ConsulContext;
import com.couchbase.connector.cluster.consul.DocumentKeys;
import com.couchbase.connector.config.es.ConnectorConfig;
import com.couchbase.connector.testcontainers.CustomCouchbaseContainer;
import com.couchbase.connector.testcontainers.ElasticsearchContainer;
import com.google.common.collect.ImmutableMap;
import com.orbitz.consul.Consul;
import com.orbitz.consul.KeyValueClient;
import org.elasticsearch.Version;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import static com.couchbase.connector.elasticsearch.TestConfigHelper.readConfig;
import static com.couchbase.connector.testcontainers.CustomCouchbaseContainer.newCouchbaseCluster;
import static com.couchbase.connector.testcontainers.Poller.poll;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;

public class AutonomousOpsTest {

  private static ConsulCluster consulCluster;

  @BeforeClass
  public static void startConsul() {
    consulCluster = new ConsulCluster("consul:1.4.2", 1, Network.newNetwork()).start();
  }

  @AfterClass
  public static void stopConsul() {
    consulCluster.stop();
  }

  @Test
  public void singleWorker() throws Exception {
    final String couchbaseVersion = "enterprise-6.0.1";
    final String elasticsearchVersion = "6.6.0";

    try (CustomCouchbaseContainer couchbase = newCouchbaseCluster("couchbase/server:" + couchbaseVersion);
         ElasticsearchContainer elasticsearch = new ElasticsearchContainer(Version.fromString(elasticsearchVersion))
             .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container.elasticsearch")))) {

      System.out.println("Couchbase " + couchbase.getVersionString() +
          " listening at http://" + DockerHelper.getDockerHost() + ":" + couchbase.getMappedPort(8091));

      couchbase.loadSampleBucket("travel-sample", 100);

      elasticsearch.start();

      final String group = "integrationTest";
      final String config = readConfig(couchbase, elasticsearch, ImmutableMap.of(
          "group.name", group));

      final Consul.Builder consulBuilder = consulCluster.clientBuilder(0);
      final ConsulContext consulContext = new ConsulContext(consulBuilder, group, null);

      final KeyValueClient kv = consulContext.consul().keyValueClient();
      final DocumentKeys keys = consulContext.keys();
      kv.putValue(keys.config(), config);

      try (AsyncTask connector = AsyncTask.run(() -> ConsulConnector.run(consulContext));
           TestEsClient es = new TestEsClient(ConnectorConfig.from(config))) {
        System.out.println("connector has been started.");

        final int expectedAirlineCount = 187;
        final int expectedAirportCount = 1968;

        poll().until(() -> es.getDocumentCount("airlines") >= expectedAirlineCount);
        poll().until(() -> es.getDocumentCount("airports") >= expectedAirportCount);

        SECONDS.sleep(3); // quiet period, make sure no more documents appear in the index

        assertEquals(expectedAirlineCount, es.getDocumentCount("airlines"));
        assertEquals(expectedAirportCount, es.getDocumentCount("airports"));
      }

    }
  }

}