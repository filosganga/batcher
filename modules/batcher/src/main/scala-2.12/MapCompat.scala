/*
 * Copyright 2023 Filippo De Luca
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.filippodeluca.batcher

import scala.collection.IterableView
import scala.collection.immutable.Map
import scala.collection.mutable.Map as MutMap

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
