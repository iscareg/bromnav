package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Test

class LogicTest {
    @Test fun stars() {
        assertEquals(5, Urgency.stars(600.0, 500.0))   // already later than ETA
        assertEquals(4, Urgency.stars(600.0, 600.0))   // exactly on ETA
        assertEquals(0, Urgency.stars(600.0, 1200.0))  // twice the ETA
        assertEquals(0, Urgency.stars(600.0, 5000.0))
        assertEquals(1, Urgency.stars(600.0, 1150.0))
        assertEquals(2, Urgency.stars(600.0, 1000.0))
        assertEquals(3, Urgency.stars(600.0, 800.0))
    }

    private fun matrix(n: Int, seed: Int): Array<DoubleArray> {
        val r = java.util.Random(seed.toLong())
        val xs = DoubleArray(n) { r.nextDouble() * 3000 }; val ys = DoubleArray(n) { r.nextDouble() * 3000 }
        return Array(n) { i -> DoubleArray(n) { j -> Math.hypot(xs[i] - xs[j], ys[i] - ys[j]) / 7 + if (i < j) 5 else 0 } }
    }

    @Test fun exactIsOptimal() {
        for (seed in 1..20) {
            val n = 2 + 6                     // start + 6 stops + end
            val t = matrix(n, seed)
            val stops = (1..6).toList()
            val best = TspSolver.solve(t, stops, n - 1, emptyMap(), 60.0)
            // compare against nearest-neighbour: exact must never be worse
            val nn = ArrayList<Int>(); val left = stops.toMutableList(); var c = 0
            while (left.isNotEmpty()) { val x = left.minBy { t[c][it] }; nn.add(x); left.remove(x); c = x }
            val nnRes = TspSolver.evaluate(t, nn, n - 1, emptyMap(), 60.0)
            assert(best.totalS <= nnRes.totalS + 1e-9)
            assertEquals(6, best.order.toSet().size)
        }
    }

    @Test fun deadlineReorders() {
        // stops 1 and 2 are on a line: 0 -> 1 (100 s) -> 2 (100 s); 2 has a tight deadline
        val t = arrayOf(
            doubleArrayOf(0.0, 100.0, 150.0, 0.0),
            doubleArrayOf(100.0, 0.0, 100.0, 100.0),
            doubleArrayOf(150.0, 100.0, 0.0, 150.0),
            doubleArrayOf(0.0, 100.0, 150.0, 0.0),
        )
        val free = TspSolver.solve(t, listOf(1, 2), 3, emptyMap(), 0.0)
        assertEquals(listOf(1, 2), free.order)
        val urgent = TspSolver.solve(t, listOf(1, 2), 3, mapOf(2 to 160.0), 0.0)
        assertEquals(listOf(2, 1), urgent.order)
    }

    @Test fun marginPrefersBuffer() {
        // A (node 1) has a deadline that is met either way but only barely if visited second;
        // B (node 2) has no deadline. Going A first costs 20 s extra driving but keeps the buffer.
        val t = arrayOf(
            doubleArrayOf(0.0, 300.0, 280.0, 0.0),
            doubleArrayOf(300.0, 0.0, 300.0, 300.0),
            doubleArrayOf(280.0, 300.0, 0.0, 300.0),
            doubleArrayOf(0.0, 300.0, 280.0, 0.0),
        )
        val r = TspSolver.solve(t, listOf(1, 2), 3, mapOf(1 to 600.0), 0.0)
        assertEquals(listOf(1, 2), r.order)
    }
}
