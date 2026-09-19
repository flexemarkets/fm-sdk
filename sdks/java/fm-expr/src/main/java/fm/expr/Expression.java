package fm.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One parsed panel expression: what a manager writes in a {@code score}
 * field, checked once and evaluated per participant.
 *
 * <h2>The language</h2>
 *
 * <p>Every construct is here because a study in fm-robots cannot be written
 * without it, and nothing else is. That is the rule that keeps the list
 * closed rather than current: a construct gets in when an existing study or
 * a written-down customer requirement needs it, and the requirement is
 * recorded beside it.
 *
 * <pre>
 * references   me.side  mkt.period  now.cash  now.units.WDG  open.cash  f.gross
 *              -- always namespaced; a bare name is an error, not a variable
 * literals     15   0.25   "SELLER"
 * arithmetic   + - * /   and unary -          (numbers only; no text joining)
 * comparison   == != &lt; &lt;= &gt; &gt;=                (both sides the same kind)
 * functions    if(cond, a, b)   abs(x)   sum(v)   take(v, n)
 * </pre>
 *
 * <ul>
 *   <li><b>{@code if}</b> evaluates only the arm it takes, so the other arm
 *       may reference what this participant lacks.</li>
 *   <li><b>{@code take(v, n)}</b> is the first {@code n} entries of a
 *       vector, or the whole vector when it has fewer: an induced-value
 *       schedule consumed past its end contributes nothing, as Smith 62's
 *       settlement has it.</li>
 *   <li><b>{@code sum}</b> and <b>{@code abs}</b> are what they say.</li>
 * </ul>
 *
 * <p>Deliberately absent: loops, user functions, assignment, text
 * concatenation, randomness, a clock, and any reach into another
 * participant's state. Each is recorded, with why, in the Panel Expressions
 * specification.
 *
 * <h2>Numbers</h2>
 *
 * <p>Decimal throughout. Addition, subtraction and multiplication are exact;
 * division is carried at scale six, rounded half up. Comparisons are by
 * value, so {@code 1 == 1.0}. Whole cents in, whole cents out unless
 * something divided.
 *
 * <h2>Use</h2>
 *
 * <p>{@link #parse} once, when the configuration is saved or loaded, and
 * keep the result: it is immutable and thread-safe. {@link #references}
 * says what a scope has to provide, which is also what decides when a
 * panel recomputes -- {@code me.*} and {@code mkt.*} on state,
 * {@code now.*} on holdings. {@link #evaluate} per participant.
 */
public final class Expression {

    /** Scale for the one operation that can leave a fraction. */
    static final int DIVISION_SCALE = 6;

    private final String source;
    private final Node root;
    private final Set<String> references;

    private Expression(String source, Node root, Set<String> references) {
        this.source = source;
        this.root = root;
        this.references = Collections.unmodifiableSet(references);
    }

    /**
     * Parse an expression, or say where it fails.
     *
     * @param source the expression as written
     * @return the parsed expression
     * @throws ExpressionException on a malformed expression, with the position
     */
    public static Expression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new ExpressionException("empty expression", 0);
        }
        var tokens = Lexer.tokens(source);
        var parser = new Parser(tokens);
        var root = parser.expression();
        parser.expect(Token.Kind.END, "end of expression");
        var references = new LinkedHashSet<String>();
        root.collect(references::add);
        return new Expression(source, root, references);
    }

    /**
     * The expression as written.
     *
     * @return the source
     */
    public String source() {
        return source;
    }

    /**
     * Every reference the expression reads, as written: {@code me.side},
     * {@code now.units.WDG}, {@code f.n}. In order of first appearance.
     *
     * @return the references; empty for a constant expression
     */
    public Set<String> references() {
        return references;
    }

    /**
     * The namespaces those references fall in -- {@code me}, {@code now},
     * {@code f} -- which is what decides when a panel recomputes.
     *
     * @return the namespaces, in order of first appearance
     */
    public Set<String> namespaces() {
        var out = new LinkedHashSet<String>();
        for (var reference : references) {
            out.add(reference.substring(0, reference.indexOf('.')));
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Evaluate against a scope.
     *
     * @param scope where references resolve
     * @return the value
     * @throws ExpressionException on a reference the scope lacks, a type
     *                             mismatch, or division by zero
     */
    public Value evaluate(Scope scope) {
        return root.evaluate(scope);
    }

    @Override
    public String toString() {
        return source;
    }

    // ---------------------------------------------------------------- tokens

    record Token(Kind kind, String text, int position) {
        enum Kind { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, COMMA, END }
    }

    static final class Lexer {
        private static final String OPERATOR_CHARS = "+-*/=!<>";

        static List<Token> tokens(String source) {
            var out = new ArrayList<Token>();
            int i = 0;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (Character.isDigit(c) || (c == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
                    int start = i;
                    while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) {
                        i++;
                    }
                    var text = source.substring(start, i);
                    if (text.indexOf('.') != text.lastIndexOf('.')) {
                        throw new ExpressionException("malformed number " + text, start);
                    }
                    out.add(new Token(Token.Kind.NUMBER, text, start));
                } else if (c == '"') {
                    int start = i++;
                    var text = new StringBuilder();
                    while (true) {
                        if (i >= source.length()) {
                            throw new ExpressionException("unterminated string", start);
                        }
                        char d = source.charAt(i++);
                        if (d == '"') {
                            break;
                        }
                        if (d == '\\' && i < source.length()) {
                            d = source.charAt(i++);
                        }
                        text.append(d);
                    }
                    out.add(new Token(Token.Kind.STRING, text.toString(), start));
                } else if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_' || source.charAt(i) == '.')) {
                        i++;
                    }
                    out.add(new Token(Token.Kind.IDENT, source.substring(start, i), start));
                } else if (c == '(') {
                    out.add(new Token(Token.Kind.LPAREN, "(", i++));
                } else if (c == ')') {
                    out.add(new Token(Token.Kind.RPAREN, ")", i++));
                } else if (c == ',') {
                    out.add(new Token(Token.Kind.COMMA, ",", i++));
                } else if (OPERATOR_CHARS.indexOf(c) >= 0) {
                    int start = i++;
                    if (i < source.length() && source.charAt(i) == '=' && "=!<>".indexOf(c) >= 0) {
                        i++;
                    }
                    var text = source.substring(start, i);
                    if (text.equals("=") || text.equals("!")) {
                        throw new ExpressionException("unknown operator " + text + " (comparison is ==)", start);
                    }
                    out.add(new Token(Token.Kind.OP, text, start));
                } else {
                    throw new ExpressionException("unexpected character '" + c + "'", i);
                }
            }
            out.add(new Token(Token.Kind.END, "", source.length()));
            return out;
        }
    }

    // ---------------------------------------------------------------- parser

    /**
     * Precedence climbing over three levels -- comparison, additive,
     * multiplicative -- then unary minus, then primaries. Comparison does
     * not chain: {@code a < b < c} is an error rather than a surprise.
     */
    static final class Parser {
        private final List<Token> tokens;
        private int index;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token next() {
            return tokens.get(index++);
        }

        private boolean at(Token.Kind kind, String text) {
            var token = peek();
            return token.kind() == kind && token.text().equals(text);
        }

        void expect(Token.Kind kind, String what) {
            var token = next();
            if (token.kind() != kind) {
                throw new ExpressionException("expected " + what + " but found "
                        + (token.kind() == Token.Kind.END ? "end of expression" : "'" + token.text() + "'"),
                        token.position());
            }
        }

        Node expression() {
            var left = additive();
            var token = peek();
            if (token.kind() == Token.Kind.OP && isComparison(token.text())) {
                next();
                var right = additive();
                var after = peek();
                if (after.kind() == Token.Kind.OP && isComparison(after.text())) {
                    throw new ExpressionException("comparisons do not chain", after.position());
                }
                return new Node.Compare(token.text(), left, right, token.position());
            }
            return left;
        }

        private static boolean isComparison(String op) {
            return switch (op) {
                case "==", "!=", "<", "<=", ">", ">=" -> true;
                default -> false;
            };
        }

        private Node additive() {
            var left = multiplicative();
            while (at(Token.Kind.OP, "+") || at(Token.Kind.OP, "-")) {
                var op = next();
                left = new Node.Arith(op.text(), left, multiplicative(), op.position());
            }
            return left;
        }

        private Node multiplicative() {
            var left = unary();
            while (at(Token.Kind.OP, "*") || at(Token.Kind.OP, "/")) {
                var op = next();
                left = new Node.Arith(op.text(), left, unary(), op.position());
            }
            return left;
        }

        private Node unary() {
            if (at(Token.Kind.OP, "-")) {
                var op = next();
                return new Node.Negate(unary(), op.position());
            }
            return primary();
        }

        private Node primary() {
            var token = next();
            switch (token.kind()) {
                case NUMBER:
                    return new Node.Literal(Value.Num.of(token.text()));
                case STRING:
                    return new Node.Literal(new Value.Text(token.text()));
                case LPAREN: {
                    var inner = expression();
                    expect(Token.Kind.RPAREN, "')'");
                    return inner;
                }
                case IDENT:
                    if (peek().kind() == Token.Kind.LPAREN) {
                        return call(token);
                    }
                    if (token.text().indexOf('.') < 0) {
                        throw new ExpressionException("references are namespaced -- me." + token.text()
                                + ", mkt." + token.text() + ", now." + token.text() + " -- not bare " + token.text(),
                                token.position());
                    }
                    if (token.text().endsWith(".") || token.text().contains("..")) {
                        throw new ExpressionException("malformed reference " + token.text(), token.position());
                    }
                    return new Node.Reference(token.text(), token.position());
                case END:
                    throw new ExpressionException("unexpected end of expression", token.position());
                default:
                    throw new ExpressionException("unexpected '" + token.text() + "'", token.position());
            }
        }

        private Node call(Token name) {
            expect(Token.Kind.LPAREN, "'('");
            var args = new ArrayList<Node>();
            if (peek().kind() != Token.Kind.RPAREN) {
                args.add(expression());
                while (peek().kind() == Token.Kind.COMMA) {
                    next();
                    args.add(expression());
                }
            }
            expect(Token.Kind.RPAREN, "')' closing " + name.text() + "(");
            var arity = switch (name.text()) {
                case "if" -> 3;
                case "abs", "sum" -> 1;
                case "take" -> 2;
                default -> throw new ExpressionException("unknown function " + name.text(), name.position());
            };
            if (args.size() != arity) {
                throw new ExpressionException(name.text() + " takes " + arity + " argument" + (arity == 1 ? "" : "s")
                        + ", not " + args.size(), name.position());
            }
            return new Node.Call(name.text(), args, name.position());
        }
    }

    // ---------------------------------------------------------------- nodes

    sealed interface Node {
        Value evaluate(Scope scope);

        void collect(Consumer<String> references);

        record Literal(Value value) implements Node {
            @Override public Value evaluate(Scope scope) { return value; }
            @Override public void collect(Consumer<String> references) { }
        }

        record Reference(String name, int position) implements Node {
            @Override
            public Value evaluate(Scope scope) {
                return scope.lookup(name).orElseThrow(() -> new ExpressionException(name + " is not available"));
            }

            @Override public void collect(Consumer<String> references) { references.accept(name); }
        }

        record Negate(Node operand, int position) implements Node {
            @Override
            public Value evaluate(Scope scope) {
                return new Value.Num(number(operand.evaluate(scope), "-").negate());
            }

            @Override public void collect(Consumer<String> references) { operand.collect(references); }
        }

        record Arith(String op, Node left, Node right, int position) implements Node {
            @Override
            public Value evaluate(Scope scope) {
                var a = number(left.evaluate(scope), op);
                var b = number(right.evaluate(scope), op);
                return new Value.Num(switch (op) {
                    case "+" -> a.add(b);
                    case "-" -> a.subtract(b);
                    case "*" -> a.multiply(b);
                    case "/" -> {
                        if (b.signum() == 0) {
                            throw new ExpressionException("division by zero");
                        }
                        yield a.divide(b, DIVISION_SCALE, RoundingMode.HALF_UP);
                    }
                    default -> throw new IllegalStateException(op);
                });
            }

            @Override
            public void collect(Consumer<String> references) {
                left.collect(references);
                right.collect(references);
            }
        }

        record Compare(String op, Node left, Node right, int position) implements Node {
            @Override
            public Value evaluate(Scope scope) {
                var a = left.evaluate(scope);
                var b = right.evaluate(scope);
                if (!a.kind().equals(b.kind())) {
                    throw new ExpressionException("cannot compare " + a.kind() + " with " + b.kind() + " (" + op + ")");
                }
                if (a instanceof Value.Num x && b instanceof Value.Num y) {
                    int c = x.value().compareTo(y.value());
                    return Value.Bool.of(switch (op) {
                        case "==" -> c == 0;
                        case "!=" -> c != 0;
                        case "<" -> c < 0;
                        case "<=" -> c <= 0;
                        case ">" -> c > 0;
                        case ">=" -> c >= 0;
                        default -> throw new IllegalStateException(op);
                    });
                }
                return switch (op) {
                    case "==" -> Value.Bool.of(a.equals(b));
                    case "!=" -> Value.Bool.of(!a.equals(b));
                    default -> throw new ExpressionException("only numbers order (" + op + "); " + a.kind() + " compares with == and !=");
                };
            }

            @Override
            public void collect(Consumer<String> references) {
                left.collect(references);
                right.collect(references);
            }
        }

        record Call(String name, List<Node> args, int position) implements Node {
            @Override
            public Value evaluate(Scope scope) {
                return switch (name) {
                    case "if" -> {
                        var cond = args.get(0).evaluate(scope);
                        if (!(cond instanceof Value.Bool b)) {
                            throw new ExpressionException("if needs a comparison first, not a " + cond.kind());
                        }
                        yield (b.value() ? args.get(1) : args.get(2)).evaluate(scope);
                    }
                    case "abs" -> new Value.Num(number(args.get(0).evaluate(scope), "abs").abs());
                    case "sum" -> {
                        var total = BigDecimal.ZERO;
                        for (var v : vector(args.get(0).evaluate(scope), "sum").values()) {
                            total = total.add(v);
                        }
                        yield new Value.Num(total);
                    }
                    case "take" -> {
                        var v = vector(args.get(0).evaluate(scope), "take");
                        var n = number(args.get(1).evaluate(scope), "take");
                        if (n.signum() < 0 || n.stripTrailingZeros().scale() > 0) {
                            throw new ExpressionException("take needs a whole number of entries, not " + n.stripTrailingZeros().toPlainString());
                        }
                        int count = Math.min(v.values().size(), n.intValue());
                        yield new Value.Vector(v.values().subList(0, count));
                    }
                    default -> throw new IllegalStateException(name);
                };
            }

            @Override
            public void collect(Consumer<String> references) {
                for (var arg : args) {
                    arg.collect(references);
                }
            }
        }

        private static BigDecimal number(Value value, String op) {
            if (value instanceof Value.Num n) {
                return n.value();
            }
            throw new ExpressionException(op + " needs a number, not a " + value.kind());
        }

        private static Value.Vector vector(Value value, String op) {
            if (value instanceof Value.Vector v) {
                return v;
            }
            throw new ExpressionException(op + " needs a vector, not a " + value.kind());
        }
    }
}
