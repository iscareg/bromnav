package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TspSolverTest {
    /** random travel-time matrix: n nodes in a 3 x 3 km square at ~25 km/h, slightly asymmetric */
    private fun matrix(n: Int, rnd: Random): Array<DoubleArray> {
        val xs = DoubleArray(n) { rnd.nextDouble() * 3000 }; val ys = DoubleArray(n) { rnd.nextDouble() * 3000 }
        return Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else Math.hypot(xs[i] - xs[j], ys[i] - ys[j]) / 7 + (if (i < j) 5 else 0) } }
    }

    private fun bruteForce(t: Array<DoubleArray>, stops: List<Int>, end: Int, dl: Map<Int, Double?>, ea: Map<Int, Double?>): Double {
        var best = Double.MAX_VALUE
        fun perm(a: MutableList<Int>, k: Int) {
            if (k == a.size) { best = minOf(best, TspSolver.cost(TspSolver.evaluate(t, a, end, dl, 60.0, ea))); return }
            for (i in k until a.size) { a[k] = a[i].also { a[i] = a[k] }; perm(a, k + 1); a[k] = a[i].also { a[i] = a[k] } }
        }
        perm(stops.toMutableList(), 0)
        return best
    }

    /** the branch-and-bound search finds exactly the brute-force optimum, with deadlines and exact-time orders */
    @Test fun branchAndBoundIsOptimal() {
        val rnd = Random(7)
        repeat(200) {
            val k = 1 + rnd.nextInt(7)
            val n = k + 2
            val t = matrix(n, rnd)
            val stops = (1..k).toList()
            val dl = HashMap<Int, Double?>(); val ea = HashMap<Int, Double?>()
            for (s in stops) when (rnd.nextInt(3)) {
                0 -> dl[s] = 300.0 + rnd.nextDouble() * 1800                      // ASAP with a deadline
                1 -> { val e = 300.0 + rnd.nextDouble() * 1500; ea[s] = e; dl[s] = e + 600 }   // exact time
            }
            val r = TspSolver.solve(t, stops, n - 1, dl, 60.0, ea)
            assertEquals(stops.toSet(), r.order.toSet())
            assertEquals(k, r.order.size)
            assertEquals(bruteForce(t, stops, n - 1, dl, ea), TspSolver.cost(r), 1e-6)
        }
    }

    @Test fun exactUpToTenStopsIsFast() {
        val rnd = Random(3)
        for (k in listOf(8, 9, 10)) {
            val t = matrix(k + 2, rnd)
            val stops = (1..k).toList()
            val t0 = System.nanoTime()
            val r = TspSolver.solve(t, stops, k + 1, mapOf(1 to 900.0, 2 to 1200.0), 60.0)
            val ms = (System.nanoTime() - t0) / 1e6
            println("$k stops: exact in %.1f ms".format(ms))
            assertEquals(k, r.order.toSet().size)
            assertTrue("$k stops took $ms ms", ms < 3000)
        }
    }

    /** worst case for pruning: all stops equally far apart */
    @Test fun uniformMatrixStillFinishes() {
        val k = TspSolver.EXACT_MAX
        val t = Array(k + 2) { i -> DoubleArray(k + 2) { j -> if (i == j) 0.0 else 120.0 } }
        val t0 = System.nanoTime()
        val r = TspSolver.solve(t, (1..k).toList(), k + 1, emptyMap(), 60.0)
        val ms = (System.nanoTime() - t0) / 1e6
        println("uniform $k stops: %.1f ms".format(ms))
        assertEquals(k, r.order.size)
        assertTrue(ms < 5000)
    }

    @Test fun heuristicAboveExactLimitVisitsEveryStop() {
        val rnd = Random(11)
        val k = TspSolver.EXACT_MAX + 4
        val t = matrix(k + 2, rnd)
        val r = TspSolver.solve(t, (1..k).toList(), k + 1, emptyMap(), 60.0)
        assertEquals((1..k).toSet(), r.order.toSet())
        // 2-opt result is never worse than plain nearest neighbour
        val nn = ArrayList<Int>(); val left = (1..k).toMutableList(); var c = 0
        while (left.isNotEmpty()) { val x = left.minBy { t[c][it] }; nn.add(x); left.remove(x); c = x }
        assertTrue(r.totalS <= TspSolver.evaluate(t, nn, k + 1, emptyMap(), 60.0).totalS + 1e-6)
    }

    @Test fun noStops() {
        val t = arrayOf(doubleArrayOf(0.0, 50.0), doubleArrayOf(50.0, 0.0))
        val r = TspSolver.solve(t, emptyList(), 1, emptyMap())
        assertEquals(0, r.order.size); assertEquals(50.0, r.totalS, 0.0)
    }

    @Test fun lateIsCounted() {
        // single stop 600 s away with a deadline at 500 s -> 100 s late
        val t = arrayOf(doubleArrayOf(0.0, 600.0, 0.0), doubleArrayOf(600.0, 0.0, 600.0), doubleArrayOf(0.0, 600.0, 0.0))
        val r = TspSolver.solve(t, listOf(1), 2, mapOf(1 to 500.0), 60.0)
        assertEquals(100.0, r.lateS, 1e-9)
        assertEquals(600.0 + 60 + 600, r.totalS, 1e-9)
    }
}
