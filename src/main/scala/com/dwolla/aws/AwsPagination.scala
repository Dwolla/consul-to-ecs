package com.dwolla

import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException}

import cats.effect._
import cats.implicits._
import fs2._
import fs2.interop.reactivestreams.fromPublisher
import org.reactivestreams.Publisher

package object aws {
  def unfold[F[_]] = new PartiallyAppliedFromPublisherF[F]

  def eval[F[_]] = new PartiallyAppliedEvalF[F]

  private[aws] def cfToF[F[_]] = new PartialCompletableFutureToF[F]
}

package aws {
  class PartiallyAppliedFromPublisherF[F[_]] {
    def apply[Res](publisher: Publisher[Res]) =
      new PartiallyAppliedFromPublisherFRes[F, Res](publisher)
  }

  class PartiallyAppliedFromPublisherFRes[F[_], Res](publisher: Publisher[Res]) {
    import scala.jdk.CollectionConverters._

    private def toStream[T](res: Res => java.lang.Iterable[T]): Res => Stream[F, T] =
      res andThen (_.asScala) andThen Chunk.iterable andThen Stream.chunk

    def apply[O](extractor: Res => java.lang.Iterable[O])
                (implicit ev: ConcurrentEffect[F]): Stream[F, O] =
      fromPublisher[F, Res](publisher)
        .flatMap(toStream(extractor))

  }

  class PartiallyAppliedEvalF[F[_]] {
    def apply[Req, Res, O](req: => Req)
                          (client: Req => CompletableFuture[Res])
                          (extractor: Res => O)
                          (implicit ev: ConcurrentEffect[F]): Stream[F, O] =
      Stream.eval(cfToF[F](client(req))).map(extractor)
  }

  private[aws] class PartialCompletableFutureToF[F[_]] {
    def apply[A](makeCf: => CompletableFuture[A])
                (implicit ev: ConcurrentEffect[F]): F[A] =
      Concurrent.cancelableF[F, A] { cb =>
        val cf = makeCf
        cf.handle[Unit]((result: A, err: Throwable) => {
          err match {
            case null =>
              cb(Right(result))
            case _: CancellationException =>
              ()
            case ex: CompletionException if ex.getCause ne null =>
              cb(Left(ex.getCause))
            case ex =>
              cb(Left(ex))
          }
        })

        val cancelToken: CancelToken[F] = Sync[F].delay(cf.cancel(true)).void
        cancelToken.pure[F]
      }
  }
}
