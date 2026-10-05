package com.bmscompanion.app.data.wdp

import kotlin.math.pow

/**
 * Weapon Delivery Planner's engine methods, run as the program runs them.
 *
 * Falcas's five engine classes are the flight manual's charts, each method a table of figures with its
 * interpolation unrolled into thousands of `else if` branches — 90,000 lines of C#. Sampling the tables at their
 * breakpoints and interpolating between them comes close but is not the program: the ladders carry their own
 * slips (a cell interpolated from the wrong row, a weight band that jumps, a chart that answers 0 past one end
 * and the last row past the other), and each one shows in the Performance page's figures. So the methods were
 * translated statement for statement (`tools/extractor/src/wdpengines.mjs`) into a small prefix code, and this runs
 * that code: the figures, the branches and the slips are the program's own.
 *
 * The code (`assets/data/wdp/engines/engines.wdpc`) is one method per line, `<class> M <name> <return type>
 * <parameters> <slots> <statement>`, or `= <class> <name> <other class>` for a method two classes share. The
 * statements and expressions are listed at the top of `wdpengines.mjs`. It is compiled here, a class at a time
 * when first asked for, into an `IntArray` per method with its numbers in a pool beside it, and walked with a
 * program counter — the five classes are 600,000 tokens, which as a tree of objects would weigh on a phone.
 *
 * Arithmetic is IEEE double as the program's is; `Math.Round` rounds to even; `(int)` truncates, and throws where
 * the program's checked conversion would; decimals, which the take-off factor compares in, are carried as doubles.
 */
class EngineCode private constructor(private val lines: Map<String, String>, private val aliases: Map<String, String>) {

    private class Method(
        val name: String,
        val returnsText: Boolean,
        val params: Int,
        val slots: Int,
        val code: IntArray,
        val pool: DoubleArray,
        val texts: Array<String>,
        /** the methods this one calls, by the index its CALLs carry */
        val callees: Array<String>,
    )

    private val compiled = HashMap<String, Method>()

    /** The classes the code holds, in its order. */
    val classes: List<String> = (lines.keys.map { it.substringBefore('.') } + aliases.keys.map { it.substringBefore('.') }).distinct()

    fun has(cls: String, method: String): Boolean = "$cls.$method" in lines || "$cls.$method" in aliases

    /** Calls a method that answers a number. */
    fun call(cls: String, method: String, vararg args: Double): Double {
        val m = method(cls, method)
        val f = run(cls, m, args)
        return f.result
    }

    /** Calls a method that answers text (the climb schedules). */
    fun callText(cls: String, method: String, vararg args: Double): String {
        val m = method(cls, method)
        val f = run(cls, m, args)
        return f.text ?: ""
    }

    private fun method(cls: String, name: String): Method {
        val key = "$cls.$name"
        compiled[key]?.let { return it }
        val own = aliases[key]?.let { "$it.$name" } ?: key
        val text = lines[own] ?: throw IllegalArgumentException("$key is not in the engine code")
        val m = compiled[own] ?: Compiler(text).method().also { compiled[own] = it }
        compiled[key] = m
        return m
    }

    // ------------------------------------------------------------------------------------------------ running

    private class Frame(m: Method) {
        val slots = DoubleArray(m.slots)
        val arrays = arrayOfNulls<DoubleArray>(m.slots)
        val strs = arrayOfNulls<String>(m.slots)
        var pc = 0
        var result = 0.0
        var text: String? = null
    }

    private fun run(cls: String, m: Method, args: DoubleArray): Frame {
        require(args.size == m.params) { "${m.name} takes ${m.params} arguments" }
        val f = Frame(m)
        args.copyInto(f.slots)
        exec(cls, m, f)
        return f
    }

    /** Runs the statement at the frame's counter; true when it returned. */
    private fun exec(cls: String, m: Method, f: Frame): Boolean {
        val c = m.code
        when (c[f.pc++]) {
            ST_BLOCK -> {
                val n = c[f.pc++]
                repeat(n) { if (exec(cls, m, f)) return true }
            }
            ST_STORE -> { val k = c[f.pc++]; f.slots[k] = eval(cls, m, f) }
            ST_ASTORE -> {
                val k = c[f.pc++]
                val i = eval(cls, m, f).toInt()
                val v = eval(cls, m, f)
                (f.arrays[k] ?: throw IllegalStateException("${m.name}: array $k used before it is made"))[i] = v
            }
            ST_AINIT -> {
                val k = c[f.pc++]; val n = c[f.pc++]; val at = c[f.pc++]
                // the figures go into the array the method made, which may be longer: the rest stay 0, as in C#
                val a = f.arrays[k]?.takeIf { it.size >= n } ?: DoubleArray(n).also { f.arrays[k] = it }
                m.pool.copyInto(a, 0, at, at + n)
            }
            ST_NEWARR -> { val k = c[f.pc++]; f.arrays[k] = DoubleArray(c[f.pc++]) }
            ST_IF -> {
                val thenLen = c[f.pc++]
                val elseLen = c[f.pc++]
                if (eval(cls, m, f) != 0.0) {
                    if (exec(cls, m, f)) return true
                    f.pc += elseLen
                } else {
                    f.pc += thenLen
                    if (exec(cls, m, f)) return true
                }
            }
            ST_RETURN -> { f.result = eval(cls, m, f); return true }
            ST_NOP -> {}
            ST_SSTORE -> { val k = c[f.pc++]; f.strs[k] = m.texts[c[f.pc++]] }
            ST_RETS -> { f.text = m.texts[c[f.pc++]]; return true }
            ST_RETSL -> { f.text = f.strs[c[f.pc++]] ?: ""; return true }
            else -> throw IllegalStateException("${m.name}: bad statement at ${f.pc - 1}")
        }
        return false
    }

    private fun eval(cls: String, m: Method, f: Frame): Double {
        val c = m.code
        return when (c[f.pc++]) {
            EX_CONST -> m.pool[c[f.pc++]]
            EX_LOAD -> f.slots[c[f.pc++]]
            EX_AREAD -> {
                val k = c[f.pc++]
                val i = eval(cls, m, f).toInt()
                (f.arrays[k] ?: throw IllegalStateException("${m.name}: array $k used before it is made"))[i]
            }
            EX_ADD -> eval(cls, m, f) + eval(cls, m, f)
            EX_SUB -> { val a = eval(cls, m, f); a - eval(cls, m, f) }
            EX_MUL -> eval(cls, m, f) * eval(cls, m, f)
            EX_DIV -> { val a = eval(cls, m, f); a / eval(cls, m, f) }
            EX_IDIV -> {
                val a = eval(cls, m, f).toInt(); val b = eval(cls, m, f).toInt()
                if (b == 0) throw ArithmeticException("${m.name}: division by zero")
                (a / b).toDouble()
            }
            EX_EQ -> b(eval(cls, m, f) == eval(cls, m, f))
            EX_NE -> b(eval(cls, m, f) != eval(cls, m, f))
            EX_LT -> { val a = eval(cls, m, f); b(a < eval(cls, m, f)) }
            EX_GT -> { val a = eval(cls, m, f); b(a > eval(cls, m, f)) }
            EX_LE -> { val a = eval(cls, m, f); b(a <= eval(cls, m, f)) }
            EX_GE -> { val a = eval(cls, m, f); b(a >= eval(cls, m, f)) }
            // both sides are always evaluated, as the program's & and | do; they have no side effects either way
            EX_AND -> { val a = eval(cls, m, f); val bb = eval(cls, m, f); b(a != 0.0 && bb != 0.0) }
            EX_OR -> { val a = eval(cls, m, f); val bb = eval(cls, m, f); b(a != 0.0 || bb != 0.0) }
            EX_NOT -> b(eval(cls, m, f) == 0.0)
            EX_NEG -> -eval(cls, m, f)
            EX_TRUNC -> {
                val v = eval(cls, m, f)
                if (v.isNaN() || v >= 2147483648.0 || v <= -2147483649.0) throw ArithmeticException("${m.name}: arithmetic overflow")
                v.toInt().toDouble()
            }
            EX_ROUND -> DataCardNet.bankers(eval(cls, m, f))
            EX_ROUNDN -> { val v = eval(cls, m, f); DataCardNet.round(v, eval(cls, m, f).toInt()) }
            EX_POW -> { val a = eval(cls, m, f); a.pow(eval(cls, m, f)) }
            EX_CMP -> { val a = eval(cls, m, f); a.compareTo(eval(cls, m, f)).coerceIn(-1, 1).toDouble() }
            EX_TERN -> {
                val aLen = c[f.pc++]; val bLen = c[f.pc++]
                if (eval(cls, m, f) != 0.0) { val v = eval(cls, m, f); f.pc += bLen; v }
                else { f.pc += aLen; eval(cls, m, f) }
            }
            EX_INTERP -> {
                // Interpolate(Reference, Spread, Value, LowValue1, HighValue1, LowValue2, HighValue2, Factor),
                // the one helper every class shares
                val ref = eval(cls, m, f); val spread = eval(cls, m, f); val value = eval(cls, m, f)
                val lo1 = eval(cls, m, f); val hi1 = eval(cls, m, f); val lo2 = eval(cls, m, f); val hi2 = eval(cls, m, f)
                val factor = eval(cls, m, f)
                val num = lo1 + (hi1 - lo1) * factor
                val num2 = lo2 + (hi2 - lo2) * factor
                val num3 = (value - ref) / spread
                num - (num - num2) * num3
            }
            EX_CALL -> {
                val callee = m.callees[c[f.pc++]]
                val n = c[f.pc++]
                val args = DoubleArray(n) { eval(cls, m, f) }
                run(cls, method(cls, callee), args).result
            }
            else -> throw IllegalStateException("${m.name}: bad expression at ${f.pc - 1}")
        }
    }

    private fun b(v: Boolean) = if (v) 1.0 else 0.0

    // ------------------------------------------------------------------------------------------------ compiling

    /** Turns one method's tokens into its code; [IF] and the conditional carry the lengths of their branches. */
    private class Compiler(line: String) {
        private val tok = line.split(' ')
        private var p = 0
        private val out = IntList()
        private val pool = ArrayList<Double>()
        private val poolIndex = HashMap<Double, Int>()
        private val texts = ArrayList<String>()
        private val callees = ArrayList<String>()

        fun method(): Method {
            // "M <name> <ret> <params> <slots> <statement>"
            check(tok[p++] == "M") { "not a method" }
            val name = tok[p++]
            val ret = tok[p++]
            val params = tok[p++].toInt()
            val slots = tok[p++].toInt()
            statement()
            return Method(name, ret == "string", params, slots, out.toArray(), pool.toDoubleArray(), texts.toTypedArray(), callees.toTypedArray())
        }

        private fun const(v: Double): Int = poolIndex.getOrPut(v) { pool += v; pool.size - 1 }

        private fun text(t: String): Int {
            // "470~/~0.90": quotes off, spaces back
            texts += t.removePrefix("\"").removeSuffix("\"").replace('~', ' ')
            return texts.size - 1
        }

        private fun statement() {
            val t = tok[p++]
            when {
                t == "B" -> { val n = tok[p++].toInt(); out.add(ST_BLOCK); out.add(n); repeat(n) { statement() } }
                t == "IF" -> {
                    out.add(ST_IF)
                    val lens = out.size
                    out.add(0); out.add(0)
                    expr()
                    val a = out.size; statement(); val thenLen = out.size - a
                    val b = out.size; statement(); val elseLen = out.size - b
                    out[lens] = thenLen; out[lens + 1] = elseLen
                }
                t == "R" -> { out.add(ST_RETURN); expr() }
                t == "N" -> out.add(ST_NOP)
                t == "RS" -> { out.add(ST_RETS); out.add(text(tok[p++])) }
                t == "RSL" -> { out.add(ST_RETSL); out.add(tok[p++].toInt()) }
                t.startsWith("SS") -> { out.add(ST_SSTORE); out.add(t.substring(2).toInt()); out.add(text(tok[p++])) }
                t.startsWith("AI") -> {
                    val k = t.substring(2).toInt(); val n = tok[p++].toInt()
                    // an array's figures go into the pool one after another, so the array is a slice of it
                    val at = pool.size
                    repeat(n) { pool += tok[p++].toDouble() }
                    out.add(ST_AINIT); out.add(k); out.add(n); out.add(at)
                }
                t.startsWith("NA") -> { out.add(ST_NEWARR); out.add(t.substring(2).toInt()); out.add(tok[p++].toInt()) }
                t.startsWith("S") -> { out.add(ST_STORE); out.add(t.substring(1).toInt()); expr() }
                t.startsWith("X") -> { out.add(ST_ASTORE); out.add(t.substring(1).toInt()); expr(); expr() }
                else -> throw IllegalStateException("unknown statement $t")
            }
        }

        private fun expr() {
            val t = tok[p++]
            when {
                t.startsWith("#") -> { out.add(EX_CONST); out.add(const(t.substring(1).toDouble())) }
                t.startsWith("L") -> { out.add(EX_LOAD); out.add(t.substring(1).toInt()) }
                t == "T" -> {
                    out.add(EX_TERN)
                    val lens = out.size
                    out.add(0); out.add(0)
                    expr()
                    val a = out.size; expr(); val aLen = out.size - a
                    val b = out.size; expr(); val bLen = out.size - b
                    out[lens] = aLen; out[lens + 1] = bLen
                }
                t == "IP" -> { out.add(EX_INTERP); repeat(8) { expr() } }
                t == "CALL" -> {
                    val name = tok[p++]; val n = tok[p++].toInt()
                    var i = callees.indexOf(name)
                    if (i < 0) { callees += name; i = callees.size - 1 }
                    out.add(EX_CALL); out.add(i); out.add(n)
                    repeat(n) { expr() }
                }
                t.startsWith("A") -> { out.add(EX_AREAD); out.add(t.substring(1).toInt()); expr() }
                else -> {
                    val op = OPS[t] ?: throw IllegalStateException("unknown expression $t")
                    out.add(op)
                    repeat(ARITY.getValue(op)) { expr() }
                }
            }
        }
    }

    /** A growable IntArray, so a method's code is not boxed while it is built. */
    private class IntList {
        private var a = IntArray(1024)
        var size = 0
            private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun set(i: Int, v: Int) { a[i] = v }
        fun toArray(): IntArray = a.copyOf(size)
    }

    companion object {
        private const val ST_BLOCK = 1
        private const val ST_STORE = 2
        private const val ST_ASTORE = 3
        private const val ST_AINIT = 4
        private const val ST_NEWARR = 5
        private const val ST_IF = 6
        private const val ST_RETURN = 7
        private const val ST_NOP = 8
        private const val ST_SSTORE = 9
        private const val ST_RETS = 10
        private const val ST_RETSL = 11

        private const val EX_CONST = 20
        private const val EX_LOAD = 21
        private const val EX_AREAD = 22
        private const val EX_ADD = 23
        private const val EX_SUB = 24
        private const val EX_MUL = 25
        private const val EX_DIV = 26
        private const val EX_IDIV = 27
        private const val EX_EQ = 28
        private const val EX_NE = 29
        private const val EX_LT = 30
        private const val EX_GT = 31
        private const val EX_LE = 32
        private const val EX_GE = 33
        private const val EX_AND = 34
        private const val EX_OR = 35
        private const val EX_NOT = 36
        private const val EX_NEG = 37
        private const val EX_TRUNC = 38
        private const val EX_ROUND = 39
        private const val EX_ROUNDN = 40
        private const val EX_POW = 41
        private const val EX_CMP = 42
        private const val EX_TERN = 43
        private const val EX_INTERP = 44
        private const val EX_CALL = 45

        private val OPS = mapOf(
            "+" to EX_ADD, "-" to EX_SUB, "*" to EX_MUL, "/" to EX_DIV, "//" to EX_IDIV,
            "==" to EX_EQ, "!=" to EX_NE, "<" to EX_LT, ">" to EX_GT, "<=" to EX_LE, ">=" to EX_GE,
            "&" to EX_AND, "O" to EX_OR, "!" to EX_NOT, "NEG" to EX_NEG, "TRUNC" to EX_TRUNC,
            "ROUND" to EX_ROUND, "ROUNDN" to EX_ROUNDN, "POW" to EX_POW, "CMP" to EX_CMP,
        )
        private val ARITY = mapOf(
            EX_ADD to 2, EX_SUB to 2, EX_MUL to 2, EX_DIV to 2, EX_IDIV to 2, EX_EQ to 2, EX_NE to 2, EX_LT to 2,
            EX_GT to 2, EX_LE to 2, EX_GE to 2, EX_AND to 2, EX_OR to 2, EX_NOT to 1, EX_NEG to 1, EX_TRUNC to 1,
            EX_ROUND to 1, EX_ROUNDN to 2, EX_POW to 2, EX_CMP to 2,
        )

        /** Reads the code; each method is compiled the first time it is called. Null when the text is not engine code. */
        fun parse(text: String): EngineCode? {
            val lines = HashMap<String, String>()
            val aliases = HashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("= ")) {
                    val f = line.split(' ')
                    if (f.size >= 4) aliases["${f[1]}.${f[2]}"] = f[3]
                    continue
                }
                val cls = line.substringBefore(' ')
                val rest = line.substringAfter(' ')
                val name = rest.split(' ', limit = 3).getOrNull(1) ?: continue
                lines["$cls.$name"] = rest
            }
            if (lines.isEmpty()) return null
            return EngineCode(lines, aliases)
        }
    }
}
