package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveOutput
import edu.illinois.cs.cs125.jeed.core.haveTimedOut
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.should
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec
import kotlinx.coroutines.async

class TestByteCodeRewriters : StringSpec({
    "should not intercept safe exceptions" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (NullPointerException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("Try\nCatch\nFinally")
    }
    "should intercept exceptions configured to be unsafe in catch blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (NullPointerException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should intercept subclasses of exceptions configured to be unsafe in catch blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (Exception e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should intercept subclasses of exceptions configured to be unsafe in finally blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should not intercept exceptions configured to be safe in finally blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (ClassCastException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.ClassCastException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nFinally")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should handle nested try-catch blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    try {
        System.out.println("Try");
        String s = (String) new Object();
    } catch (ClassCastException e) {
        System.out.println("Catch");
        Object o = null;
        o.toString();
    } finally {
        System.out.println("Finally");
    }
} catch (NullPointerException e) {
    System.out.println("Broken");
} finally {
    System.out.println("Bah");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nCatch")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should handle try-catch blocks in loops" {
        val executionResult = Source.fromSnippet(
            """
while (true) {
    try {
        System.out.println("Try");
        String s = (String) new Object();
    } catch (ClassCastException e) {
        System.out.println("Catch");
        Object o = null;
        o.toString();
    } finally {
        System.out.println("Finally");
    }
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nCatch")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should remove finalizers" {
        val executionResult = Source.fromSnippet(
            """
public class Example {
    public Example() {
        finalize();
    }
    protected void finalize() {
        System.out.println("Finalizer");
    }
}
Example ex = new Example();
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveOutput("Finalizer")
    }
    "should not remove non-finalizer finalize methods" {
        val executionResult = Source.fromSnippet(
            """
public class Example {
    public Example() {
        finalize(0);
        finalize("", 0.0);
    }
    protected void finalize(int unused) {
        System.out.println("Finalizer 1");
    }
    public String finalize(String toReturn, double unused) {
        System.out.println("Finalizer 2");
        return toReturn;
    }
}
Example ex = new Example();
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Finalizer 1\nFinalizer 2")
    }
    "should allow synchronization to work correctly" {
        val executionResult = Source(mapOf("Main.java" to """
public class Other implements Runnable {
    public void run() {
        for (int i = 0; i < 100; i++) {
            synchronized (Main.monitor) {
                int temp = Main.counter + 1;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    System.out.println("Interrupted!");
                }
                Main.counter = temp;
            }
        }
    }
}
public class Main {
    public static Object monitor = new Object();
    public static int counter = 0;
    public static void main() throws InterruptedException {
        Thread other = new Thread(new Other());
        other.start();
        for (int i = 0; i < 100; i++) {
            synchronized (monitor) {
                int temp = counter + 1;
                Thread.sleep(1);
                counter = temp;
            }
        }
        other.join();
        System.out.println(counter);
    }
}""".trim())).compile().execute(SourceExecutionArguments(maxExtraThreads = 1, timeout = 500L))
        executionResult shouldNot haveTimedOut()
        executionResult should haveCompleted()
        executionResult should haveOutput("200")
    }
    "should allow synchronization with notification" {
        setOf("", "1000L", "999L, 999999").forEach { waitParamList ->
            val executionResult = Source(mapOf("Main.java" to """
public class Other implements Runnable {
    public void run() {
        synchronized (Main.monitor) {
            Main.monitor.notifyAll();
            System.out.println("Notified");
        }
    }
}
public class Main {
    public static Object monitor = new Object();
    public static void main() {
        new Thread(new Other()).start();
        synchronized (monitor) {
            try {
                monitor.wait([PARAM_LIST]);
                System.out.println("Finished wait");
            } catch (InterruptedException e) {
                System.out.println("Failed to wait");
            }
        }
    }
}""".trim().replace("[PARAM_LIST]", waitParamList)))
                .compile().execute(SourceExecutionArguments(maxExtraThreads = 1))
            executionResult should haveCompleted()
            executionResult should haveOutput("Notified\nFinished wait")
        }
    }
    "should prevent cross-task monitor interference" {
        val badCompileResult = Source(mapOf("Main.java" to """
public class LockHog {
    public static void main() {
        System.out.println("About to spin");
        synchronized (Object.class) {
            while (true) {}
        }
    }
}""".trim())).compile()
        val goodCompileResult = Source.fromSnippet("""
Thread.sleep(100);
synchronized (Object.class) {
    System.out.println("Synchronized");
}""".trim()).compile()
        val badTask = async {
            badCompileResult.execute(SourceExecutionArguments(timeout = 800L, klass = "LockHog"))
        }
        val goodTaskResult = goodCompileResult.execute(SourceExecutionArguments(timeout = 150L))
        goodTaskResult should haveCompleted()
        goodTaskResult should haveOutput("Synchronized")
        val badTaskResult = badTask.await()
        badTaskResult should haveTimedOut()
        badTaskResult should haveOutput("About to spin")
    }
    "should allow synchronized methods to run" {
        val executionResult = Source.fromSnippet("""
            synchronized int getFive() {
                return 5;
            }
            System.out.println(getFive());
        """.trimIndent()).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("5")
    }
    "should correctly handle try-catch blocks inside synchronized methods" {
        val executionResult = Source.fromSnippet("""
            synchronized int getFive() {
                try {
                    Object obj = null;
                    return obj.hashCode();
                } catch (NullPointerException e) {
                    return 5;
                } finally {
                    System.out.println("Finally");
                }
            }
            System.out.println(getFive());
        """.trimIndent()).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Finally\n5")
    }
    "should correctly handle throw statements inside synchronized methods" {
        val executionResult = Source.fromSnippet("""
            synchronized int getFive() {
                System.out.println("Synchronized");
                try {
                    throw new Exception("Boom!");
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                    return 5;
                } finally {
                    System.out.println("Finally");
                }
            }
            System.out.println(getFive());
        """.trimIndent()).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Synchronized\nBoom!\nFinally\n5")
    }
    "should correctly handle synchronized methods that always throw" {
        val executionResult = Source.fromSnippet("""
            synchronized int throwFive() throws Exception {
                System.out.println("Synchronized");
                throw new Exception("5");
            }
            try {
                throwFive();
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
        """.trimIndent()).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Synchronized\n5")
    }
    "should correctly handle synchronized methods that return references" {
        val executionResult = Source.fromSnippet("""
            synchronized String getFive() {
                return "5";
            }
            System.out.println(getFive());
        """.trimIndent()).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("5")
    }
    "should correctly handle synchronized methods that return large primitives" {
        val executionResult = Source.fromSnippet("""
            synchronized long getFive() {
                return 5L;
            }
            synchronized double getPi() {
                return 3.14159;
            }
            System.out.println((int) (getFive() * getFive() * getPi()));
        """.trimIndent()).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("78")
    }
    "should correctly handle synchronized methods that take parameters" {
        val executionResult = Source.fromSnippet("""
            synchronized void printSum(String prefix, byte a, long c, double factor) {
                double sum = (double) a + c * factor;
                System.out.println(prefix + sum);
            }
            printSum("Sum: ", (byte) 10, 100, 3.13);
        """.trimIndent()).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Sum: 323.0")
    }
    "should correctly handle recursive synchronized methods" {
        val executionResult = Source.fromSnippet("""
            synchronized long factorial(int n) {
                if (n <= 1) {
                    return 1;
                } else {
                    return n * factorial(n - 1);
                }
            }
            System.out.println(factorial(14));
        """.trimIndent()).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("87178291200")
    }
    "should correctly handle synchronized instance methods" {
        val executionResult = Source.fromSnippet("""
            class Example {
                synchronized int getFivePlus(short value) {
                    return 5 + value;
                }
            }
            System.out.println(new Example().getFivePlus((short) 10));
        """.trimIndent()).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("15")
    }
    "should unlock the monitor on successful exit from synchronized methods" {
        val executionResult = Source.fromSnippet("""
            class Example implements Runnable {
                public void run() {
                    Util.printExcitedly("Bye");
                }
            }
            class Util {
                synchronized static void printExcitedly(String text) {
                    try {
                        Object obj = null;
                        obj.hashCode();
                    } catch (NullPointerException e) {
                        // Wow this is pointless!
                    }
                    System.out.println(text + "!");
                }
            }
            Util.printExcitedly("Hi");
            Thread t = new Thread(new Example());
            t.start();
            t.join();
        """.trimIndent()).compile().execute(SourceExecutionArguments(maxExtraThreads = 1))
        executionResult should haveCompleted()
        executionResult should haveOutput("Hi!\nBye!")
    }
    "should unlock the monitor on exceptional exit from synchronized methods" {
        val executionResult = Source.fromSnippet("""
            class Example implements Runnable {
                public void run() {
                    try {
                        Util.throwExcitedly("Bye");
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }
            }
            class Util {
                synchronized static void throwExcitedly(String text) throws Exception {
                    if (System.currentTimeMillis() != 0) {
                        throw new Exception(text + "!");
                    }
                }
            }
            try {
                Util.throwExcitedly("Hi");
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
            Thread t = new Thread(new Example());
            t.start();
            t.join();
        """.trimIndent()).compile().execute(SourceExecutionArguments(maxExtraThreads = 1))
        executionResult should haveCompleted()
        executionResult should haveOutput("Hi!\nBye!")
    }
    "should allow exclusion to work correctly with synchronized methods" {
        val executionResult = Source(mapOf("Main.java" to """
public class Counter {
    public static int counter;
    public static synchronized void increment() throws InterruptedException {
        int tmp = counter + 1;
        Thread.sleep(1);
        counter = tmp;
    }
}
public class Other implements Runnable {
    public void run() {
        try {
            for (int i = 0; i < 100; i++) {
                Counter.increment();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
public class Main {
    public static void main() throws InterruptedException {
        Thread other = new Thread(new Other());
        other.start();
        for (int i = 0; i < 100; i++) {
            Counter.increment();
        }
        other.join();
        System.out.println(Counter.counter);
    }
}""".trim())).compile().execute(SourceExecutionArguments(maxExtraThreads = 1, timeout = 500L))
        executionResult shouldNot haveTimedOut()
        executionResult should haveCompleted()
        executionResult should haveOutput("200")
    }
})
