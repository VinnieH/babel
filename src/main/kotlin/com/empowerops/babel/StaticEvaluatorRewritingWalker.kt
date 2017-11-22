package com.empowerops.babel

import com.empowerops.babel.BabelParser.*
import com.empowerops.babel.StaticEvaluatorRewritingWalker.Availability.Runtime
import com.empowerops.babel.StaticEvaluatorRewritingWalker.Availability.Static
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RuleContext
import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.tree.TerminalNodeImpl
import java.util.*

//TBD: terrible name. what does LLVM/Clang calls these? Optimizers? what about kotlinc?
class StaticEvaluatorRewritingWalker() : BabelParserBaseListener() {
    
    enum class Availability { Static, Runtime }

    private val availability: Deque<Availability> = LinkedList()

    override fun exitScalarExpr(ctx: ScalarExprContext){
        val availabilityByExpr = buildAndUpdateAvailabilityIndex(ctx)

        when {

            ctx.sum() != null || ctx.prod() != null -> {
                tryRewriteChildren(ctx, availabilityByExpr)
                tryStaticallyUnrolling(ctx, availabilityByExpr)
            }

            else -> tryRewriteChildren(ctx, availabilityByExpr)
        }

        val newAvailability = when {
            availabilityByExpr.isEmpty() -> null
            ctx.`var`() != null -> Runtime
            availabilityByExpr.values.all { it == Static } -> Static
            availabilityByExpr.values.any { it == Runtime } -> Runtime
            else -> TODO()
        }
        if(newAvailability != null) { availability.push(newAvailability) }
    }

    override fun exitExpression(ctx: ExpressionContext) {
        tryRewriteChildren(ctx, buildAndUpdateAvailabilityIndex(ctx))
    }

    private fun tryStaticallyUnrolling(ctx: ScalarExprContext, availabilityByExpr: Map<ScalarExprContext, Availability>) {

        return

        val lowerBoundExpr = ctx.scalarExpr(0)
        val upperBoundExpr = ctx.scalarExpr(1)
        val lambdaExpr = ctx.lambdaExpr().scalarExpr()

        if(availabilityByExpr[lowerBoundExpr] == Static
                && availabilityByExpr[upperBoundExpr] == Static
                && availabilityByExpr[lambdaExpr] == Runtime){

            //assumes that the other re-write has already happened...
            // really not liking the mutability here.
            val (lower, upper) = listOf(lowerBoundExpr, upperBoundExpr).map { it.asStaticValue().roundToIndex()!! }

//            fail; //TBD: how do we convey to code-gen that it needs to set the lambda param?
            // read: run the test "sum from 1 to 10 of i", note that the variable "i" is not set in this evaluation.
            //
            // option 1: custom subtype of ExprContext that has a "alias" field. a simple "instanceof"
            // check in code gen can then pick it up
            //     downside: feels like a hack -> more specifically, would involve polluting existing memory subsystem with special case checks.
            // option 2: put a `sum` infront of the whole thing, use LambdaExprs instead of Exprs, see what happens?
            //     downside makes code-gen a bit more fragile since it has this hidding special case that isnt in the g4 file
            // option 3: create a new (unused) parser rule, instantiate it here, add explicit code to handle it
            //     downside: a bunch of new code, "unused" rules are wierd... I'm leaning toward this

            // more thoughts:
            // whats best for error handling?
            // how might we push the memory system to be more eager such that we expose symbols resolvably and statically?

            //also need fixture of things like sum(0, 0, i -> i) and sum(1, 0, i -> i)

            var root = lambdaExpr.clone()
            for(index in lower+1 .. upper){
                val left = root
                val op = when {
                    ctx.sum() != null -> PlusContext(null, -1)
                    ctx.mult() != null -> MultContext(null, -1)
                    else -> TODO()
                }
                val right = lambdaExpr.clone()

                root = ExprContext(children = listOf(left, op, right))
            }

            ctx.children = root.children
        }
    }

    private fun tryRewriteChildren(ctx: ParserRuleContext, exprsByAvailability: Map<ScalarExprContext, Availability>) {

        when {
            ctx is ExpressionContext || Runtime in exprsByAvailability.values -> {
                for (evaluable in exprsByAvailability.filterValues { it == Static }.keys) {

                    val result = evaluate(evaluable)

                    evaluable.children = result.children
                }
            }
        }
    }

    private fun buildAndUpdateAvailabilityIndex(ctx: ParserRuleContext): Map<ScalarExprContext, Availability> {
        val childExprs = ctx.children
                .map { it as? ScalarExprContext ?: (it as? LambdaExprContext)?.scalarExpr() }
                .filterIsInstance<ScalarExprContext>()

        val exprsByAvailability = childExprs.asReversed().associate { it to availability.pop() }
        return exprsByAvailability
    }

    private fun evaluate(node: ScalarExprContext): ScalarExprContext {

        val compiler = CodeGeneratingWalker(node.text).apply { walk(node) }

        val expr = BabelExpression(
                node.text,
                containsDynamicLookup = true,
                isBooleanExpression = false,
                staticallyReferencedSymbols = emptySet(),
                runtime = compiler.instructions.configuration
        )

        val result = expr.evaluate(emptyMap())

        val terminal = TerminalNodeImpl(ValueToken(result, node.text))
        val literal = LiteralContext(terminal)
        return ExprContext(literal)
    }

    override fun exitVariable(ctx: VariableContext) { availability.push(Runtime) }
    override fun exitLiteral(ctx: LiteralContext) { availability.push(Static) }

    private fun ScalarExprContext.clone() = this //TBD: is this a problem?
    
    private fun ExprContext(parent: ParserRuleContext? = null, children: List<RuleContext> = emptyList())
            = ScalarExprContext(parent, -1).apply { children.forEach(this::adopt) }

    private fun ExprContext(literalContext: LiteralContext) = ExprContext().apply { adopt(literalContext) }
    private fun LiteralContext(node: TerminalNode) = LiteralContext(null, -1).apply { adopt(node) }

    private fun ScalarExprContext.asStaticValue(): Double
            = ((children.single() as LiteralContext).FLOAT().symbol as ValueToken).value
}

private fun <T> MutableList<T>.replace(old: T, new: T) { this[indexOf(old)] = new }

class ValueToken(val value: Double, text: String = value.toString()): CommonToken(BabelLexer.FLOAT, value.toString()){
    init { this.text = text }
}
