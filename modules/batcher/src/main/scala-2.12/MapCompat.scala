package com.filippodeluca.batcher

import scala.collection.immutable.Map
import scala.collection.mutable.{Map => MutMap}
import scala.collection.IterableView

object MapCompat {
  implicit class RichMapIterableView[K, V](xs: IterableView[(K, V), Map[K, V]]) {
    def mapValues[W](f: V => W) = {
      xs.map { case (k, v) => (k, f(v)) }
    }

    def filterKeys(p: K => Boolean) = {
      xs.filter { case (k, _) =>
        p(k)
      }
    }
  }

  implicit class RichMutMapIterableView[K, V](xs: IterableView[(K, V), MutMap[K, V]]) {
    def mapValues[W](f: V => W) = {
      xs.map { case (k, v) => (k, f(v)) }
    }

    def filterKeys(p: K => Boolean) = {
      xs.filter { case (k, _) =>
        p(k)
      }
    }
  }
}
