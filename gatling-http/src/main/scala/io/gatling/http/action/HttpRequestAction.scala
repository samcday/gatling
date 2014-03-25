/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.action

import com.typesafe.scalalogging.slf4j.StrictLogging

import akka.actor.{ ActorRef, ActorContext }
import akka.actor.ActorDSL.actor
import io.gatling.core.session.Session
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.http.ahc.{ HttpEngine, HttpTx }
import io.gatling.http.cache.CacheHandling
import io.gatling.http.fetch.ResourceFetcher
import io.gatling.http.request.HttpRequest
import io.gatling.http.response.ResponseBuilder

object HttpRequestAction extends StrictLogging {

  def startHttpTransaction(tx: HttpTx)(implicit ctx: ActorContext) {

      def startHttpTransaction(tx: HttpTx) {
        logger.info(s"Sending request=${tx.requestName} uri=${tx.request.getURI}: scenario=${tx.session.scenarioName}, userId=${tx.session.userId}")
        HttpEngine.instance.startHttpTransaction(tx)
      }

      def skipCached(tx: HttpTx) {
        logger.info(s"Skipping cached request=${tx.requestName} uri=${tx.request.getURI}: scenario=${tx.session.scenarioName}, userId=${tx.session.userId}")
        tx.next ! tx.session
      }

    val uri = tx.request.getURI
    CacheHandling.getExpire(tx.protocol, tx.session, uri) match {

      case None                               => startHttpTransaction(tx)

      case Some(expire) if nowMillis > expire => startHttpTransaction(tx.copy(session = CacheHandling.clearExpire(tx.session, uri)))

      case _ if tx.protocol.responsePart.fetchHtmlResources =>
        val explicitResources = HttpRequest.buildNamedRequests(tx.explicitResources, tx.session)

        ResourceFetcher.fromCache(tx.request.getURI, tx, explicitResources) match {
          case Some(resourceFetcher) =>
            logger.info(s"Fetching resources of cached page request=${tx.requestName} uri=${tx.request.getURI}: scenario=${tx.session.scenarioName}, userId=${tx.session.userId}")
            actor(resourceFetcher())

          case None => skipCached(tx)
        }

      case _ => skipCached(tx)
    }
  }
}

/**
 * This is an action that sends HTTP requests
 *
 * @constructor constructs an HttpRequestAction
 * @param httpRequest the request
 * @param next the next action that will be executed after the request
 */
class HttpRequestAction(httpRequest: HttpRequest, val next: ActorRef) extends RequestAction {

  import httpRequest._

  val responseBuilderFactory = ResponseBuilder.newResponseBuilderFactory(checks, responseTransformer, protocol)
  val requestName = httpRequest.requestName

  def sendRequest(requestName: String, session: Session) =
    for {
      ahcRequest <- ahcRequest(session)
      tx = HttpTx(session, ahcRequest, requestName, checks, responseBuilderFactory, protocol, next, followRedirect, maxRedirects, throttled, silent, explicitResources, extraInfoExtractor)
    } yield HttpRequestAction.startHttpTransaction(tx)
}
