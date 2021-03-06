package io.pg.webhook

import cats.Monad
import cats.MonadError
import cats.implicits._
import io.odin.Logger
import io.pg.MergeRequests
import io.pg.ProjectActions
import io.pg.gitlab.webhook.Project
import io.pg.gitlab.webhook.WebhookEvent
import io.pg.messaging.Publisher
import io.pg.transport
import io.scalaland.chimney.dsl._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

object WebhookRouter {

  def routes[F[_]: MergeRequests: JsonDecoder: Monad](
    implicit eventPublisher: Publisher[F, WebhookEvent]
  ): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    HttpRoutes.of[F] {
      case req @ POST -> Root / "webhook" =>
        req.asJsonDecode[WebhookEvent].flatMap(eventPublisher.publish) *> Ok()

      case GET -> Root / "preview" / LongVar(projectId) =>
        val proj = Project(projectId)

        MergeRequests[F].build(proj).nested.map(_.transformInto[transport.MergeRequestState]).value.flatMap(Ok(_))
    }

  }

}

object WebhookProcessor {

  def instance[
    F[
      _
    ]: MergeRequests: ProjectActions: Logger: MonadError[*[
      _
    ], Throwable]
  ]: WebhookEvent => F[Unit] = { ev =>
    for {
      _      <- Logger[F].info("Received event", Map("event" -> ev.toString()))
      states <- MergeRequests[F].build(ev.project)

      nextMR = states.minByOption(_.mergeability)
      _      <- Logger[F].info("Considering MR for action", Map("mr" -> nextMR.show))
      action <- nextMR.flatTraverse(ProjectActions[F].resolve(_))
      _      <- action.traverse(ProjectActions[F].execute(_))
    } yield ()
  }

}
