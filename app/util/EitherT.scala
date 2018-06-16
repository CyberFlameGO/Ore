package util

import scala.language.higherKinds

import play.api.libs.functional.{Applicative, Functor}
import play.api.libs.functional.syntax._
import syntax._

case class EitherT[F[_], A, B](value: F[Either[A, B]]) {

  def fold[C](fa: A => C, fb: B => C)(implicit F: Functor[F]): F[C] = value.fmap(_.fold(fa, fb))

  def swap(implicit F: Functor[F]): EitherT[F, B, A] = EitherT(value.fmap(_.swap))

  def getOrElse[B1 >: B](or: => B1)(implicit F: Functor[F]): F[B1] = value.fmap(_.getOrElse(or))

  def merge[A1 >: A](implicit ev: B <:< A1, F: Functor[F]): F[A1] = value.fmap(_.fold(identity, ev.apply))

  def getOrElseF[B1 >: B](default: => F[B1])(implicit F: Monad[F]): F[B1] =
    value.flatMap {
      case Left(_)  => default
      case Right(b) => F.pure(b)
    }

  def orElse[A1, B1 >: B](default: => EitherT[F, A1, B1])(implicit F: Monad[F]): EitherT[F, A1, B1] = {
    EitherT(
      value.flatMap {
        case Left(_)      => default.value
        case r @ Right(_) =>
          F.pure(r.asInstanceOf[Either[A1, B1]]) //This is safe as B1 >: B and the left is uninhabited
      }
    )
  }

  def contains[B1 >: B](elem: B1)(implicit F: Functor[F]): F[Boolean] = value.fmap(_.contains(elem))

  def forall(f: B => Boolean)(implicit F: Functor[F]): F[Boolean] = value.fmap(_.forall(f))

  def exists(f: B => Boolean)(implicit F: Functor[F]): F[Boolean] = value.fmap(_.exists(f))

  def transform[C, D](f: Either[A, B] => Either[C, D])(implicit F: Functor[F]): EitherT[F, C, D] =
    EitherT(value.fmap(f))

  def flatMap[A1 >: A, D](f: B => EitherT[F, A1, D])(implicit F: Monad[F]): EitherT[F, A1, D] =
    EitherT(
      value.flatMap {
        case l @ Left(_) =>
          F.pure(l.asInstanceOf[Either[A1, D]]) //This is safe as A1 >: A and the right is uninhabited
        case Right(b)    => f(b).value
      }
    )

  def flatMapF[A1 >: A, D](f: B => F[Either[A1, D]])(implicit F: Monad[F]): EitherT[F, A1, D] =
    flatMap(f andThen EitherT.apply)

  def subflatMap[A1 >: A, D](f: B => Either[A1, D])(implicit F: Functor[F]): EitherT[F, A1, D] =
    transform(_.flatMap(f))

  def semiFlatMap[D](f: B => F[D])(implicit F: Monad[F]): EitherT[F, A, D] =
    flatMap(b => EitherT.right(f(b)))

  def leftFlatMap[B1 >: B, D](f: A => EitherT[F, D, B1])(implicit F: Monad[F]): EitherT[F, D, B1] =
    EitherT(
      value.flatMap {
        case Left(a) => f(a).value
        case r @ Right(_) => F.pure(r.asInstanceOf[Either[D, B1]]) //This is safe as B1 >: B and the left is uninhabited
      }
    )

  def leftSemiFlatMap[D](f: A => F[D])(implicit F: Monad[F]): EitherT[F, D, B] =
    EitherT(
      value.flatMap {
        case Left(a)      => f(a).fmap(d => Left(d))
        case r @ Right(_) => F.pure(r.asInstanceOf[Either[D, B]]) //This is safe as the left is uninhabited
      }
    )

  def map[B1](f: B => B1)(implicit F: Functor[F]): EitherT[F, A, B1] = bimap(identity, f)

  def leftMap[C](f: A => C)(implicit F: Functor[F]): EitherT[F, C, B] = bimap(f, identity)

  def bimap[C, D](fa: A => C, fb: B => D)(implicit F: Functor[F]): EitherT[F, C, D] =
    EitherT(
      value.fmap {
        case Left(a)  => Left(fa(a))
        case Right(b) => Right(fb(b))
      }
    )

  def filterOrElse[A1 >: A](p: B => Boolean, zero: => A1)(implicit F: Functor[F]): EitherT[F, A1, B] =
    EitherT(value.fmap(_.filterOrElse(p, zero)))

  def toOption(implicit F: Functor[F]): OptionT[F, B] = OptionT(value.fmap(_.toOption))

  def isLeft(implicit F: Functor[F]): F[Boolean] = value.fmap(_.isLeft)

  def isRight(implicit F: Functor[F]): F[Boolean] = value.fmap(_.isRight)
}
object EitherT {

  final class LeftPartiallyApplied[B](val b: Boolean = true) extends AnyVal {
    def apply[F[_], A](fa: F[A])(implicit F: Functor[F]): EitherT[F, A, B] = EitherT(fa.fmap(Left.apply))
  }

  final def left[B]: LeftPartiallyApplied[B] = new LeftPartiallyApplied[B]

  final class LeftTPartiallyApplied[F[_], B](val b: Boolean = true) extends AnyVal {
    def apply[A](a: A)(implicit F: Applicative[F]): EitherT[F, A, B] = EitherT(F.pure(Left(a)))
  }

  final def leftT[F[_], B]: LeftTPartiallyApplied[F, B] = new LeftTPartiallyApplied[F, B]

  final class RightPartiallyApplied[A](val b: Boolean = true) extends AnyVal {
    def apply[F[_], B](fb: F[B])(implicit F: Functor[F]): EitherT[F, A, B] = EitherT(fb.fmap(Right.apply))
  }

  final def right[A]: RightPartiallyApplied[A] = new RightPartiallyApplied[A]

  final class PurePartiallyApplied[F[_], A](val b: Boolean = true) extends AnyVal {
    def apply[B](b: B)(implicit F: Applicative[F]): EitherT[F, A, B] = EitherT(F.pure(Right(b)))
  }

  final def pure[F[_], A]: PurePartiallyApplied[F, A] = new PurePartiallyApplied[F, A]

  final def rightT[F[_], A]: PurePartiallyApplied[F, A] = pure

  final def liftF[F[_], A, B](fb: F[B])(implicit F: Functor[F]): EitherT[F, A, B] = right(fb)

  final def fromEither[F[_]]: FromEitherPartiallyApplied[F] = new FromEitherPartiallyApplied

  final class FromEitherPartiallyApplied[F[_]](val b: Boolean = true) extends AnyVal {
    def apply[E, A](either: Either[E, A])(implicit F: Applicative[F]): EitherT[F, E, A] =
      EitherT(F.pure(either))
  }

  final def fromOption[F[_]]: FromOptionPartiallyApplied[F] = new FromOptionPartiallyApplied

  final class FromOptionPartiallyApplied[F[_]](val b: Boolean = true) extends AnyVal {
    def apply[E, A](opt: Option[A], ifNone: => E)(implicit F: Applicative[F]): EitherT[F, E, A] =
      fromEither(opt.toRight(ifNone))
  }

  final def fromOptionF[F[_], E, A](fopt: F[Option[A]], ifNone: => E)(implicit F: Functor[F]): EitherT[F, E, A] =
    EitherT(fopt.fmap(_.toRight(ifNone)))

  final def cond[F[_]]: CondPartiallyApplied[F] = new CondPartiallyApplied

  final class CondPartiallyApplied[F[_]](val b: Boolean = true) extends AnyVal {
    def apply[E, A](test: Boolean, right: => A, left: => E)(implicit F: Applicative[F]): EitherT[F, E, A] =
      EitherT(F.pure(Either.cond(test, right, left)))
  }
}