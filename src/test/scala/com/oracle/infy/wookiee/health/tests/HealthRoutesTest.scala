package com.oracle.infy.wookiee.health.tests

import cats.effect.IO
import cats.effect.implicits._
import cats.effect.unsafe.implicits.global
import com.oracle.infy.wookiee.grpc.common.UTestScalaCheck
import com.oracle.infy.wookiee.health.HeathCheckServer
import com.oracle.infy.wookiee.health.json.Serde
import com.oracle.infy.wookiee.health.model.{Critical, Normal, State, WookieeHealth}
import com.oracle.infy.wookiee.grpc.utils.implicits._
import org.http4s._
import org.http4s.implicits._
import utest.framework.ExecutionContext.RunNow
import utest.{Tests, test}

import scala.concurrent.{ExecutionContext, Future}

object HealthRoutesTest extends UTestScalaCheck with Serde {

  def checkResponse[A](actual: IO[Response[IO]], expectedStatus: Status, expectedBody: Option[A])(
      implicit ev: EntityDecoder[IO, A]
  ): Future[Boolean] =
    (for {
      actualResp <- actual
      actualBody <- actualResp.body.compile.toVector
      statusCheck = actualResp.status === expectedStatus
      bodyCheck = expectedBody.fold[Boolean](actualBody.isEmpty)(
        expected => actualResp.as[A].unsafeRunSync() === expected
      )
    } yield {
      statusCheck && bodyCheck
    }).unsafeToFuture()

  def tests(): Tests = {

    val health = WookieeHealth(Normal, "Thunderbirds are GO", Map("ZK" -> WookieeHealth(Critical, "no host found")))
    val response: IO[Response[IO]] = HeathCheckServer
      .healthCheckRoutes(() => IO(health))
      .orNotFound
      .run(
        Request(method = Method.GET, uri = uri"/healthcheck")
      )
    val lbResponse: IO[Response[IO]] = HeathCheckServer
      .healthCheckRoutes(() => IO(health))
      .orNotFound
      .run(
        Request(method = Method.GET, uri = uri"/healthcheck/lb")
      )

    val nagiosResponse: IO[Response[IO]] = HeathCheckServer
      .healthCheckRoutes(() => IO(health))
      .orNotFound
      .run(
        Request(method = Method.GET, uri = uri"/healthcheck/nagios")
      )

    val expectedHealth = health.copy(state = Critical, details = "no host found")

    Tests {
      test("get application health") {
        checkResponse[WookieeHealth](response, Status.Ok, Some(expectedHealth)).map(assert)
      }
      test("get load balancer state") {
        checkResponse[State](lbResponse, Status.Ok, Some(expectedHealth.state)).map(assert)
      }
      test("get nagios health") {
        checkResponse[String](nagiosResponse, Status.Ok, Some(s"${expectedHealth.state}|${expectedHealth.details}"))
          .map(assert)
      }
    }

  }
}
