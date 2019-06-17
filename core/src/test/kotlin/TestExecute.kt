package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.specs.StringSpec
import io.kotlintest.*
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.stream.Collectors
import kotlin.system.measureTimeMillis

class TestExecute : StringSpec({
    "should execute snippets" {
        val executeMainResult = Source.fromSnippet(
"""int i = 0;
i++;
System.out.println(i);
            """.trim()).compile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("1")
    }
    "should execute snippets that include class definitions" {
        val executeMainResult = Source.fromSnippet(
                """
public class Foo {
    int i = 0;
}
Foo foo = new Foo();
foo.i = 4;
System.out.println(foo.i);
            """.trim()).compile().execute(ExecutionArguments())
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("4")
    }
    "should execute the right class in snippets that include multiple class definitions" {
        val compiledSource = Source.fromSnippet(
                """
public class Bar {
    public static void main() {
        System.out.println("Bar");
    }
}
public class Foo {
    public static void main() {
        System.out.println("Foo");
    }
}
System.out.println("Main");
            """.trim()).compile()

        val executeBarResult = compiledSource.execute(ExecutionArguments(klass = "Bar"))
        executeBarResult should haveCompleted()
        executeBarResult should haveOutput("Bar")

        val executeFooResult = compiledSource.execute(ExecutionArguments(klass = "Foo"))
        executeFooResult should haveCompleted()
        executeFooResult should haveOutput("Foo")

        val executeMainResult = compiledSource.execute(ExecutionArguments(klass = "Main"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("Main")
    }
    "should execute the right method in snippets that include multiple method definitions" {
        val compiledSource = Source.fromSnippet(
                """
public static void foo() {
    System.out.println("foo");
}
public static void bar() {
    System.out.println("bar");
}
System.out.println("main");
""".trim()).compile()

        val executeFooResult = compiledSource.execute(ExecutionArguments(method = "foo()"))
        executeFooResult should haveCompleted()
        executeFooResult should haveOutput("foo")

        val executeBarResult = compiledSource.execute(ExecutionArguments(method = "bar()"))
        executeBarResult should haveCompleted()
        executeBarResult should haveOutput("bar")

        val executeMainResult = compiledSource.execute(ExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute private methods" {
        val compiledSource = Source.fromSnippet(
                """
private static void foo() {
    System.out.println("foo");
}
System.out.println("main");
""".trim()).compile()

        shouldThrow<ExecutionError> {
            compiledSource.execute(ExecutionArguments(method = "foo()"))
        }

        val executeMainResult = compiledSource.execute(ExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute methods that require arguments" {
        val compiledSource = Source.fromSnippet(
                """
public static void foo(int i) {
    System.out.println("foo");
}
System.out.println("main");
""".trim()).compile()

        shouldThrow<ExecutionError> {
            compiledSource.execute(ExecutionArguments(method = "foo()"))
        }

        val executeMainResult = compiledSource.execute(ExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should execute sources" {
        val executionResult = Source(mapOf(
                "Test" to
                        """
public class Main {
    public static void main() {
        var i = 0;
        System.out.println("Here");
    }
}
""".trim())).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
    }
    "should execute multiple sources with dependencies" {
        val executionResult = Source(mapOf(
                "Test" to
                        """
public class Main {
    public static void main() {
        var i = 0;
        Foo.foo();
    }
}
""".trim(),
                "Foo" to """
public class Foo {
    public static void foo() {
        System.out.println("Foo");
    }
}
""".trim())).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Foo")
    }
    "should capture stdout" {
        val executionResult = Source.fromSnippet(
"""System.out.println("Here");
""".trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("")
    }
    "should capture stderr" {
        val executionResult = Source.fromSnippet(
"""System.err.println("Here");
""".trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("")
        executionResult should haveStderr("Here")
    }
    "should capture stderr and stdout" {
        val executionResult = Source.fromSnippet(
                """System.out.println("Here");
System.err.println("There");
""".trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("There")
        executionResult should haveOutput("Here\nThere")
    }
    "should timeout correctly on snippet" {
        val executionResult = Source.fromSnippet(
"""
int i = 0;
while (true) {
    i++;
}""".trim()).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }
    "should timeout correctly on sources" {
        val executionResult = Source(mapOf("Foo" to
"""
public class Main {
    public static void main() {
        int i = 0;
        while (true) {
            i++;
        }
    }
}""".trim())).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }
    "should return output after timeout" {
        val executionResult = Source.fromSnippet(
                """
System.out.println("Here");
int i = 0;
while (true) {
    i++;
}""".trim()).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Here")
    }
    "should import libraries properly" {
        val executionResult = Source.fromSnippet(
                """
import java.util.List;
import java.util.ArrayList;

List<Integer> list = new ArrayList<>();
list.add(8);
System.out.println(list.get(0));
""".trim()).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("8")
    }
    "should execute sources that use inner classes" {
        val executionResult = Source(mapOf(
                "Main" to
                        """
public class Main {
    class Inner {
        Inner() {
            System.out.println("Inner");
        }
    }
    Main() {
        Inner inner = new Inner();
    }
    public static void main() {
        Main main = new Main();
    }
}
""".trim()
        )).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveStdout("Inner")
    }
    "should execute correctly in parallel using streams" {
        (0..8).toList().parallelStream().map { value ->
            val result = runBlocking {
                Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024; j++);
    System.out.println($value);
}
""".trim()).compile().execute(ExecutionArguments(timeout = 1000L))
            }
            result should haveCompleted()
            result.stdoutLines shouldHaveSize 32
            result.stdoutLines.all { it.line.trim() == value.toString() } shouldBe true
        }
    }
    "should execute correctly in parallel using coroutines" {
        (0..8).toList().map { value ->
            async {
                Pair(Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024; j++);
    System.out.println($value);
}
""".trim()).compile().execute(ExecutionArguments(timeout = 1000L)), value)
            }
        }.map { it ->
            val (result, value) = it.await()
            result should haveCompleted()
            result.stdoutLines shouldHaveSize 32
            result.stdoutLines.all { it.line.trim() == value.toString() } shouldBe true
        }
    }
    "should execute efficiently in parallel using streams" {
        val compiledSources = (0..8).toList().map {
            async {
                Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024 * 1024; j++);
}
""".trim()).compile()
            }
        }.map { it.await() }

        lateinit var results: List<ExecutionResult>
        val totalTime = measureTimeMillis {
            results = compiledSources.parallelStream().map {
                runBlocking {
                    it.execute(ExecutionArguments(timeout = 1000L))
                }
            }.collect(Collectors.toList()).toList()
        }

        val individualTimeSum = results.map { result ->
            result shouldNot haveCompleted()
            result should haveTimedOut()
            result.totalDuration.toMillis()
        }.sum()

        totalTime.toDouble() shouldBeLessThan individualTimeSum * 0.8
    }
    "should execute efficiently in parallel using coroutines" {
        val compiledSources = (0..8).toList().map {
            async {
                Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024 * 1024; j++);
}
""".trim()).compile()
            }
        }.map { it.await() }

        lateinit var results: List<ExecutionResult>
        val totalTime = measureTimeMillis {
            results = compiledSources.map {
                async {
                    it.execute(ExecutionArguments(timeout = 1000L))
                }
            }.map { it.await() }
        }

        val individualTimeSum = results.map { result ->
            result shouldNot haveCompleted()
            result should haveTimedOut()
            result.totalDuration.toMillis()
        }.sum()

        totalTime.toDouble() shouldBeLessThan individualTimeSum * 0.8
    }
})

fun haveCompleted() = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        return Result(
                value.completed,
                "Code should have run",
                "Code should not have run"
        )
    }
}
fun haveTimedOut() = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        return Result(
                value.timedOut,
                "Code should have timed out",
                "Code should not have timed out"
        )
    }
}
fun haveOutput(output: String = "") = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        val actualOutput = value.output.trim()
        return Result(
                actualOutput == output,
                "Expected output $output, found $actualOutput",
                "Expected to not find output $actualOutput"
        )
    }
}
fun haveStdout(output: String) = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        val actualOutput = value.stdout.trim()
        return Result(
                actualOutput == output,
                "Expected stdout $output, found $actualOutput",
                "Expected to not find stdout $actualOutput"
        )
    }
}
fun haveStderr(output: String) = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        val actualOutput = value.stderr.trim()
        return Result(
                actualOutput == output,
                "Expected stderr $output, found $actualOutput",
                "Expected to not find stderr $actualOutput"
        )
    }
}
