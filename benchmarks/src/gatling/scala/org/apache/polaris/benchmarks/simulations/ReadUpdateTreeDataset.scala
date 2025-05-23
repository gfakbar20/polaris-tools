/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.polaris.benchmarks.simulations

import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef._
import org.apache.polaris.benchmarks.actions._
import org.apache.polaris.benchmarks.parameters.BenchmarkConfig.config
import org.apache.polaris.benchmarks.parameters.{
  ConnectionParameters,
  DatasetParameters,
  WorkloadParameters
}
import org.apache.polaris.benchmarks.util.CircularIterator
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration._

/**
 * This simulation tests read and update operations on an existing dataset.
 *
 * The ratio of read operations to write operations is controlled by the readWriteRatio parameter in
 * the ReadUpdateTreeDatasetParameters.
 */
class ReadUpdateTreeDataset extends Simulation {
  private val logger = LoggerFactory.getLogger(getClass)

  // --------------------------------------------------------------------------------
  // Load parameters
  // --------------------------------------------------------------------------------
  val cp: ConnectionParameters = config.connectionParameters
  val dp: DatasetParameters = config.datasetParameters
  val wp: WorkloadParameters = config.workloadParameters

  // --------------------------------------------------------------------------------
  // Helper values
  // --------------------------------------------------------------------------------
  private val numNamespaces: Int = dp.nAryTree.numberOfNodes
  private val accessToken: AtomicReference[String] = new AtomicReference()
  private val shouldRefreshToken: AtomicBoolean = new AtomicBoolean(true)

  private val authActions = AuthenticationActions(cp, accessToken)
  private val catActions = CatalogActions(dp, accessToken)
  private val nsActions = NamespaceActions(dp, wp, accessToken)
  private val tblActions = TableActions(dp, wp, accessToken)
  private val viewActions = ViewActions(dp, wp, accessToken)

  // --------------------------------------------------------------------------------
  // Authentication related workloads:
  // * Authenticate and store the access token for later use every minute
  // * Wait for an OAuth token to be available
  // * Stop the token refresh loop
  // --------------------------------------------------------------------------------
  val continuouslyRefreshOauthToken: ScenarioBuilder =
    scenario("Authenticate every minute using the Iceberg REST API")
      .asLongAs(_ => shouldRefreshToken.get()) {
        feed(authActions.feeder())
          .exec(authActions.authenticateAndSaveAccessToken)
          .pause(1.minute)
      }

  val waitForAuthentication: ScenarioBuilder =
    scenario("Wait for the authentication token to be available")
      .asLongAs(_ => accessToken.get() == null) {
        pause(1.second)
      }

  val stopRefreshingToken: ScenarioBuilder =
    scenario("Stop refreshing the authentication token")
      .exec { session =>
        shouldRefreshToken.set(false)
        session
      }

  private val nsListFeeder = new CircularIterator(nsActions.namespaceIdentityFeeder)
  private val nsExistsFeeder = new CircularIterator(nsActions.namespaceIdentityFeeder)
  private val nsFetchFeeder = new CircularIterator(nsActions.namespaceFetchFeeder)
  private val nsUpdateFeeder = nsActions.namespacePropertiesUpdateFeeder()

  private val tblListFeeder = new CircularIterator(tblActions.tableIdentityFeeder)
  private val tblExistsFeeder = new CircularIterator(tblActions.tableIdentityFeeder)
  private val tblFetchFeeder = new CircularIterator(tblActions.tableFetchFeeder)
  private val tblUpdateFeeder = tblActions.propertyUpdateFeeder()

  private val viewListFeeder = new CircularIterator(viewActions.viewIdentityFeeder)
  private val viewExistsFeeder = new CircularIterator(viewActions.viewIdentityFeeder)
  private val viewFetchFeeder = new CircularIterator(viewActions.viewFetchFeeder)
  private val viewUpdateFeeder = viewActions.propertyUpdateFeeder()

  // --------------------------------------------------------------------------------
  // Workload: Randomly read and write entities
  // --------------------------------------------------------------------------------
  val readWriteScenario: ScenarioBuilder =
    scenario("Read and write entities using the Iceberg REST API")
      .exec(authActions.restoreAccessTokenInSession)
      .randomSwitch(
        wp.readUpdateTreeDataset.gatlingReadRatio -> group("Read")(
          uniformRandomSwitch(
            exec(feed(nsListFeeder).exec(nsActions.fetchAllChildrenNamespaces)),
            exec(feed(nsExistsFeeder).exec(nsActions.checkNamespaceExists)),
            exec(feed(nsFetchFeeder).exec(nsActions.fetchNamespace)),
            exec(feed(tblListFeeder).exec(tblActions.fetchAllTables)),
            exec(feed(tblExistsFeeder).exec(tblActions.checkTableExists)),
            exec(feed(tblFetchFeeder).exec(tblActions.fetchTable)),
            exec(feed(viewListFeeder).exec(viewActions.fetchAllViews)),
            exec(feed(viewExistsFeeder).exec(viewActions.checkViewExists)),
            exec(feed(viewFetchFeeder).exec(viewActions.fetchView))
          )
        ),
        wp.readUpdateTreeDataset.gatlingWriteRatio -> group("Write")(
          uniformRandomSwitch(
            exec(feed(nsUpdateFeeder).exec(nsActions.updateNamespaceProperties)),
            exec(feed(tblUpdateFeeder).exec(tblActions.updateTable)),
            exec(feed(viewUpdateFeeder).exec(viewActions.updateView))
          )
        )
      )

  // --------------------------------------------------------------------------------
  // Build up the HTTP protocol configuration and set up the simulation
  // --------------------------------------------------------------------------------
  private val httpProtocol = http
    .baseUrl(cp.baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .disableCaching

  // Get the configured throughput and duration
  private val throughput = wp.readUpdateTreeDataset.throughput
  private val durationInMinutes = wp.readUpdateTreeDataset.durationInMinutes

  setUp(
    continuouslyRefreshOauthToken.inject(atOnceUsers(1)).protocols(httpProtocol),
    waitForAuthentication
      .inject(atOnceUsers(1))
      .andThen(
        readWriteScenario
          .inject(
            constantUsersPerSec(throughput).during(durationInMinutes.minutes).randomized
          )
          .protocols(httpProtocol)
      )
      .andThen(stopRefreshingToken.inject(atOnceUsers(1)).protocols(httpProtocol))
  )
}
