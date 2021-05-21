package arrow.core

import arrow.core.computations.EvalEffect
import arrow.core.computations.RestrictedEvalEffect
import arrow.core.computations.eval
import arrow.core.test.UnitSpec
import arrow.core.test.concurrency.SideEffect
import arrow.core.test.laws.FxLaws
import io.kotlintest.fail
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll
import io.kotlintest.shouldBe

class EvalTest : UnitSpec() {

  init {

    testLaws(
      FxLaws.suspended<EvalEffect<*>, Eval<Int>, Int>(Gen.int().map(Eval.Companion::now), Gen.int().map(Eval.Companion::now), Eval<Int>::equals, eval::invoke) {
        it.bind()
      },
      FxLaws.eager<RestrictedEvalEffect<*>, Eval<Int>, Int>(Gen.int().map(Eval.Companion::now), Gen.int().map(Eval.Companion::now), Eval<Int>::equals, eval::eager) {
        it.bind()
      }
    )

    "should map wrapped value" {
      val sideEffect = SideEffect()
      val mapped = Eval.now(0)
        .map { sideEffect.increment(); it + 1 }
      sideEffect.counter shouldBe 0
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 2
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 3
    }

    "later should lazily evaluate values once" {
      val sideEffect = SideEffect()
      val mapped = Eval.later { sideEffect.increment(); sideEffect.counter }
      sideEffect.counter shouldBe 0
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
    }

    "later should memoize values" {
      val sideEffect = SideEffect()
      val mapped = Eval.later { sideEffect.increment(); sideEffect.counter }.memoize()
      sideEffect.counter shouldBe 0
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
    }

    "always should lazily evaluate values repeatedly" {
      val sideEffect = SideEffect()
      val mapped = Eval.always { sideEffect.increment(); sideEffect.counter }
      sideEffect.counter shouldBe 0
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 2
      sideEffect.counter shouldBe 2
      mapped.value() shouldBe 3
      sideEffect.counter shouldBe 3
    }

    "always should memoize values" {
      val sideEffect = SideEffect()
      val mapped = Eval.always { sideEffect.increment(); sideEffect.counter }.memoize()
      sideEffect.counter shouldBe 0
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
    }

    "defer should lazily evaluate other Evals" {
      val sideEffect = SideEffect()
      val mapped = Eval.defer { sideEffect.increment(); Eval.later { sideEffect.increment(); sideEffect.counter } }
      sideEffect.counter shouldBe 0
      mapped.value() shouldBe 2
      sideEffect.counter shouldBe 2
      mapped.value() shouldBe 4
      sideEffect.counter shouldBe 4
      mapped.value() shouldBe 6
      sideEffect.counter shouldBe 6
    }

    "defer should memoize Eval#later" {
      val sideEffect = SideEffect()
      val mapped = Eval.defer { sideEffect.increment(); Eval.later { sideEffect.increment(); sideEffect.counter } }.memoize()
      sideEffect.counter shouldBe 0
      mapped.value() shouldBe 2
      sideEffect.counter shouldBe 2
      mapped.value() shouldBe 2
      sideEffect.counter shouldBe 2
      mapped.value() shouldBe 2
      sideEffect.counter shouldBe 2
    }

    "defer should memoize Eval#now" {
      val sideEffect = SideEffect()
      val mapped = Eval.defer { sideEffect.increment(); Eval.now(sideEffect.counter) }.memoize()
      sideEffect.counter shouldBe 0
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
      mapped.value() shouldBe 1
      sideEffect.counter shouldBe 1
    }

    "flatMap should complete without blowing up the stack" {
      val limit = 10000
      val sideEffect = SideEffect()
      val flatMapped = Eval.now(0).flatMap(recur(limit, sideEffect))
      sideEffect.counter shouldBe 0
      flatMapped.value() shouldBe -1
      sideEffect.counter shouldBe limit + 1
    }

    "stack safety stress test" {
      forAll(DeepEval.gen) { d: DeepEval ->
        try {
          d.eval.value()
          true
        } catch (e: StackOverflowError) {
          fail("stack overflowed with eval-depth ${DeepEval.maxDepth}")
        }
      }
    }
  }

  private data class DeepEval(val eval: Eval<Int>) {
    sealed class O {
      data class Map(val f: (Int) -> Int) : O()
      data class FlatMap(val f: (Int) -> Eval<Int>) : O()
      class Memoize : O()
      class Defer : O()

      companion object {
        val gen = Gen.oneOf<O>(
          Gen.create { O.Map { it + 1 } },
          Gen.create { O.FlatMap { Eval.Now(it) } },
          Gen.create { O.Memoize() },
          Gen.create { O.Defer() }
        )
      }
    }

    companion object {
      const val maxDepth = 10000

      fun build(leaf: () -> Eval<Int>, os: List<O>) = run {
        tailrec fun step(i: Int, leaf: () -> Eval<Int>, cbs: MutableList<(Eval<Int>) -> Eval<Int>>): Eval<Int> =
          if (i >= os.size) {
            cbs.fold(leaf()) { e, f -> f(e) }
          } else {
            val o = os[i]
            when (o) {
              is O.Defer -> Eval.defer {
                @Suppress("NON_TAIL_RECURSIVE_CALL")
                step(i + 1, leaf, cbs)
              }
              is O.Memoize -> step(i + 1, leaf, cbs.also { it.add(0) { e: Eval<Int> -> e.memoize() } })
              is O.Map -> step(i + 1, leaf, cbs.also { it.add(0) { e: Eval<Int> -> e.map(o.f) } })
              is O.FlatMap -> step(i + 1, leaf, cbs.also { it.add(0) { e: Eval<Int> -> e.flatMap(o.f) } })
            }
          }

        step(0, leaf, mutableListOf())
      }

      val gen = Gen.create {
        val leaf = { Eval.Now(0) }
        val eval = build(leaf, O.gen.random().take(maxDepth).toList())
        DeepEval(eval)
      }
    }
  }

  private fun recur(limit: Int, sideEffect: SideEffect): (Int) -> Eval<Int> {
    return { num ->
      if (num <= limit) {
        sideEffect.increment()
        Eval.defer {
          recur(limit, sideEffect).invoke(num + 1)
        }
      } else {
        Eval.now(-1)
      }
    }
  }
}