package com.twitter.concurrent

import com.twitter.util.{Await, Future, Promise, Return, Throw}
import java.io.EOFException
import org.specs.SpecificationWithJUnit
import scala.collection.mutable.ArrayBuffer

import Spool.{*::, **::, seqToSpool}

/**
 * These tests make heavy use of **:: and cons, which are currently marked deprecated, and which
 * will eventually be removed and altered (respectively). Until then, we need to test them.
 */
class SpoolSpec extends SpecificationWithJUnit {
  "Empty Spool" should {
    val s = Spool.empty[Int]

    "iterate over all elements" in {
      val xs = new ArrayBuffer[Int]
      s foreach { xs += _ }
      xs.size must be_==(0)
    }

    "map" in {
      (s map { _ * 2 } ) must be_==(Spool.empty[Int])
    }

    "mapFuture" in {
      val mapFuture = s mapFuture { Future.value(_) }
      mapFuture.poll must be_==(Some(Return(s)))
    }

    "deconstruct" in {
      s must beLike {
        case x **:: rest => false
        case _ => true
      }
    }

    "append via ++"  in {
      (s ++ Spool.empty[Int]) must be_==(Spool.empty[Int])
      (Spool.empty[Int] ++ s) must be_==(Spool.empty[Int])

      val s2 = s ++ (3 **:: 4 **:: Spool.empty[Int])
      Await.result(s2.toSeq) must be_==(Seq(3, 4))
    }

    "append via ++ with Future rhs"  in {
      Await.result(s ++ Future(Spool.empty[Int])) must be_==(Spool.empty[Int])
      Await.result(Spool.empty[Int] ++ Future(s)) must be_==(Spool.empty[Int])

      val s2 = s ++ Future(3 **:: 4 **:: Spool.empty[Int])
      Await.result(s2 flatMap (_.toSeq)) must be_==(Seq(3, 4))
    }

    "flatMap" in {
      val f = (x: Int) => Future(x.toString **:: (x * 2).toString **:: Spool.empty)
      Await.result(s flatMap f) must be_==(Spool.empty[Int])
    }

    "fold left" in {
      val fold = s.foldLeft(0){(x, y) => x + y}
      Await.result(fold) must be_==(0)
    }

    "reduce left" in {
      val fold = s.reduceLeft{(x, y) => x + y}
      Await.result(fold) must throwAn[UnsupportedOperationException]
    }

    "take" in {
      s.take(10) must be_==(Spool.empty[Int])
    }
  }

  "Simple resolved Spool" should {
    val s = 1 **:: 2 **:: Spool.empty

    "iterate over all elements" in {
      val xs = new ArrayBuffer[Int]
      s foreach { xs += _ }
      xs.toSeq must be_==(Seq(1,2))
    }

    "buffer to a sequence" in {
      Await.result(s.toSeq) must be_==(Seq(1, 2))
    }

    "map" in {
      Await.result(s map { _ * 2 } toSeq) must be_==(Seq(2, 4))
    }

    "mapFuture" in {
      val f = s.mapFuture { Future.value(_) }.flatMap { _.toSeq }.poll
      f must be_==(Some(Return(Seq(1, 2))))
    }

    "deconstruct" in {
      s must beLike {
        case x **:: rest =>
          x must be_==(1)
          rest must beLike {
            case y **:: rest if y == 2 && rest.isEmpty => true
          }
      }
    }

    "append via ++"  in {
      Await.result((s ++ Spool.empty[Int]).toSeq) must be_==(Seq(1, 2))
      Await.result((Spool.empty[Int] ++ s).toSeq) must be_==(Seq(1, 2))

      val s2 = s ++ (3 **:: 4 **:: Spool.empty)
      Await.result(s2.toSeq) must be_==(Seq(1, 2, 3, 4))
    }

    "append via ++ with Future rhs"  in {
      Await.result(s ++ Future(Spool.empty[Int]) flatMap (_.toSeq)) must be_==(Seq(1, 2))
      Await.result(Spool.empty[Int] ++ Future(s) flatMap (_.toSeq)) must be_==(Seq(1, 2))

      val s2 = s ++ Future(3 **:: 4 **:: Spool.empty)
      Await.result(s2 flatMap (_.toSeq)) must be_==(Seq(1, 2, 3, 4))
    }

    "flatMap" in {
      val f = (x: Int) => Future(x.toString **:: (x * 2).toString **:: Spool.empty)
      val s2 = s flatMap f
      Await.result(s2 flatMap (_.toSeq)) must be_==(Seq("1", "2", "2", "4"))
    }

    "fold left" in {
      val fold = s.foldLeft(0){(x, y) => x + y}
      Await.result(fold) must be_==(3)
    }

    "reduce left" in {
      val fold = s.reduceLeft{(x, y) => x + y}
      Await.result(fold) must be_==(3)
    }

    "be roundtrippable through toSeq/toSpool" in {
      val seq = (0 to 10).toSeq
      Await.result(seq.toSpool.toSeq) must be_==(seq)
    }

    "flatten via flatMap of toSpool" in {
      val spool = Seq(1, 2) **:: Seq(3, 4) **:: Spool.empty
      val seq = Await.result(spool.toSeq)

      val flatSpool =
        spool.flatMap { inner =>
          Future.value(inner.toSpool)
        }

      Await.result(flatSpool.flatMap(_.toSeq)) must be_==(seq.flatten)
    }

    "take" in {
      val ls = (1 to 4).toSeq.toSpool
      Await.result(ls.take(2).toSeq) must be_==(Seq(1,2))
      Await.result(ls.take(1).toSeq) must be_==(Seq(1))
      Await.result(ls.take(0).toSeq) must be_==(Seq.empty)
      Await.result(ls.take(-2).toSeq) must be_==(Seq.empty)
    }
  }

  "Simple resolved spool with EOFException" should {
    val p = new Promise[Spool[Int]](Throw(new EOFException("sad panda")))
    val s = 1 **:: 2 *:: p

    "EOF iteration on EOFException" in {
        val xs = new ArrayBuffer[Option[Int]]
        s foreachElem { xs += _ }
        xs.toSeq must be_==(Seq(Some(1), Some(2), None))
    }
  }

  "Simple resolved spool with error" should {
    val p = new Promise[Spool[Int]](Throw(new Exception("sad panda")))
    val s = 1 **:: 2 *:: p

    "return with exception on error" in {
      val xs = new ArrayBuffer[Option[Int]]
      s foreachElem { xs += _ }
      Await.result(s.toSeq) must throwA[Exception]
    }

    "return with exception on error in callback" in {
      val xs = new ArrayBuffer[Option[Int]]
      val f = s foreach { _ => throw new Exception("sad panda") }
      Await.result(f) must throwA[Exception]
    }

    "return with exception on EOFException in callback" in {
      val xs = new ArrayBuffer[Option[Int]]
      val f = s foreach { _ => throw new EOFException("sad panda") }
      Await.result(f) must throwA[EOFException]
    }
  }

  "Simple delayed Spool" should {
    val p = new Promise[Spool[Int]]
    val p1 = new Promise[Spool[Int]]
    val p2 = new Promise[Spool[Int]]
    val s = 1 *:: p

    "iterate as results become available" in {
      val xs = new ArrayBuffer[Int]
      s foreach { xs += _ }
      xs.toSeq must be_==(Seq(1))
      p() = Return(2 *:: p1)
      xs.toSeq must be_==(Seq(1, 2))
      p1() = Return(Spool.empty)
      xs.toSeq must be_==(Seq(1, 2))
    }

    "EOF iteration on EOFException" in {
      val xs = new ArrayBuffer[Option[Int]]
      s foreachElem { xs += _ }
      xs.toSeq must be_==(Seq(Some(1)))
      p() = Throw(new EOFException("sad panda"))
      xs.toSeq must be_==(Seq(Some(1), None))
    }

    "return with exception on error" in {
      val xs = new ArrayBuffer[Option[Int]]
      s foreachElem { xs += _ }
      xs.toSeq must be_==(Seq(Some(1)))
      p() = Throw(new Exception("sad panda"))
      Await.result(s.toSeq) must throwA[Exception]
    }

    "return with exception on error in callback" in {
      val xs = new ArrayBuffer[Option[Int]]
      val f = s foreach { _ => throw new Exception("sad panda") }
      p() = Return(2 *:: p1)
      Await.result(f) must throwA[Exception]
    }

    "return with exception on EOFException in callback" in {
      val xs = new ArrayBuffer[Option[Int]]
      val f = s foreach { _ => throw new EOFException("sad panda") }
      p() = Return(2 *:: p1)
      Await.result(f) must throwA[EOFException]
    }

    "return a buffered seq when complete" in {
      val f = s.toSeq
      f.isDefined must beFalse
      p() = Return(2 *:: p1)
      f.isDefined must beFalse
      p1() = Return(Spool.empty)
      f.isDefined must beTrue
      Await.result(f) must be_==(Seq(1,2))
    }

    "deconstruct" in {
      s must beLike {
        case fst *:: rest if fst == 1 && !rest.isDefined => true
      }
    }

    "collect" in {
      val f = s collect {
        case x if x % 2 == 0 => x * 2
      }

      f.isDefined must beFalse  // 1 != 2 mod 0
      p() = Return(2 *:: p1)
      f.isDefined must beTrue
      val s1 = Await.result(f)
      s1 must beLike {
        case x *:: rest if x == 4 && !rest.isDefined => true
      }
      p1() = Return(3 *:: p2)
      s1 must beLike {
        case x *:: rest if x == 4 && !rest.isDefined => true
      }
      p2() = Return(4 **:: Spool.empty)
      val s1s = s1.toSeq
      s1s.isDefined must beTrue
      Await.result(s1s) must be_==(Seq(4, 8))
    }

    "fold left" in {
      val f = s.foldLeft(0){(x, y) => x + y}

      f.isDefined must beFalse
      p() = Return(2 *:: p1)
      f.isDefined must beFalse
      p1() = Return(Spool.empty)
      f.isDefined must beTrue
      Await.result(f) must be_==(3)
    }

    "take while" in {
      val taken = s.takeWhile(_ < 3)
      taken.isEmpty must beFalse
      val f = taken.toSeq

      f.isDefined must beFalse
      p() = Return(2 *:: p1)
      f.isDefined must beFalse
      p1() = Return(3 *:: p2)
      // despite the Spool having an unfulfilled tail, the takeWhile is satisfied
      f.isDefined must beTrue
      Await.result(f) must be_==(Seq(1, 2))
    }
  }

  "Lazily evaluated Spool" should {
    "be constructed lazily" in {
      applyLazily(Future.value _)
    }

    "collect lazily" in {
      applyLazily { spool =>
        spool.collect {
          case x if x % 2 == 0 => x
        }
      }
    }

    "map lazily" in {
      applyLazily { spool =>
        Future.value(spool.map(_ + 1))
      }
    }

    "mapFuture lazily" in {
      applyLazily { spool =>
        spool.mapFuture(Future.value(_))
      }
    }

    "flatMap lazily" in {
      applyLazily { spool =>
        spool.flatMap { item =>
          Future.value((item to (item + 5)).toSpool)
        }
      }
    }

    "takeWhile lazily" in {
      applyLazily { spool =>
        Future.value {
          spool.takeWhile(_ < Int.MaxValue)
        }
      }
    }

    "take lazily" in {
      applyLazily { spool =>
        Future.value {
          spool.take(2)
        }
      }
    }

    "++ lazily" in {
      val prefix = -2 **:: -1 **:: Spool.empty[Int]
      applyLazily { spool =>
        Future.value(prefix ++ spool)
      }
    }

    "act eagerly when forced" in {
      val (spool, tailReached) =
        applyLazily { spool =>
          Future.value(spool.map(_ + 1))
        }
      Await.ready { spool map (_ force) }
      tailReached.isDefined must beTrue
    }

    /**
     * Confirms that the given operation does not consume an entire Spool, and then
     * returns the resulting Spool and tail check for further validation.
     */
    def applyLazily(f: Spool[Int]=>Future[Spool[Int]]): (Future[Spool[Int]], Future[Unit]) = {
      val tailReached = new Promise[Unit]
      def poisonedSpool(i: Int = 0): Future[Spool[Int]] =
        Future.value {
          if (i < 10) {
            i *:: poisonedSpool(i + 1)
          } else {
            tailReached() = Return.Unit
            throw new AssertionError("Should not have produced " + i)
          }
        }

      // create, apply, poll
      val s = poisonedSpool().flatMap(f)
      s.poll
      tailReached.isDefined must beFalse
      (s, tailReached)
    }
  }
}
