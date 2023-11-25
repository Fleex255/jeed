@file:Suppress("SpellCheckingInspection", "JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE", "DEPRECATION")

package edu.illinois.cs.cs125.jeed.core

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeAsciiOnly
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import sun.management.ManagementFactoryHelper
import java.io.ByteArrayInputStream
import java.io.FilePermission
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.lang.invoke.LambdaMetafactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.security.AccessControlContext
import java.security.Permission
import java.security.Permissions
import java.security.ProtectionDomain
import java.security.SecurityPermission
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Properties
import java.util.PropertyPermission
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min
import kotlin.reflect.KProperty
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaMethod

private typealias SandboxCallableArguments<T> = (Pair<ClassLoader, (() -> Any?) -> JeedOutputCapture>) -> T

object Sandbox {
    init {
        warmPlatform()
    }

    private val runtime = try {
        ManagementFactoryHelper.getHotspotRuntimeMBean()
    } catch (e: IllegalAccessError) {
        // Gracefully degrade if deployed without the needed --add-exports
        null
    }

    @JsonClass(generateAdapter = true)
    class ClassLoaderConfiguration(
        val whitelistedClasses: Set<String> = DEFAULT_WHITELISTED_CLASSES,
        blacklistedClasses: Set<String> = DEFAULT_BLACKLISTED_CLASSES,
        unsafeExceptions: Set<String> = DEFAULT_UNSAFE_EXCEPTIONS,
        isolatedClasses: Set<String> = DEFAULT_ISOLATED_CLASSES,
        val blacklistedMethods: Set<MethodFilter> = DEFAULT_BLACKLISTED_METHODS,
        val isWhiteList: Boolean? = null,
    ) {
        val blacklistedClasses = blacklistedClasses.union(PERMANENTLY_BLACKLISTED_CLASSES)
        val unsafeExceptions = unsafeExceptions.union(ALWAYS_UNSAFE_EXCEPTIONS)
        val isolatedClasses = isolatedClasses.union(ALWAYS_ISOLATED_CLASSES)

        init {
            val badClasses = whitelistedClasses.filter { whitelistedClass ->
                PERMANENTLY_BLACKLISTED_CLASSES.any { blacklistedClass ->
                    whitelistedClass.startsWith(blacklistedClass)
                }
            }
            require(badClasses.isEmpty()) {
                "attempt to allow access to unsafe classes: $badClasses"
            }
            require(
                !(
                    whitelistedClasses.isNotEmpty() &&
                        blacklistedClasses.minus(
                            PERMANENTLY_BLACKLISTED_CLASSES.union(DEFAULT_BLACKLISTED_CLASSES),
                        ).isNotEmpty()
                    ),
            ) {
                "can't set both a class whitelist and blacklist"
            }
            unsafeExceptions.forEach { name ->
                val klass = Class.forName(name) ?: error("$name does not refer to a Java class")
                require(Throwable::class.java.isAssignableFrom(klass)) { "$name does not refer to a Java Throwable" }
            }
        }

        companion object {
            val DEFAULT_WHITELISTED_CLASSES = setOf<String>()
            val DEFAULT_BLACKLISTED_CLASSES = setOf("java.lang.reflect.")
            val DEFAULT_UNSAFE_EXCEPTIONS = setOf<String>()
            val DEFAULT_ISOLATED_CLASSES = setOf<String>()
            val DEFAULT_BLACKLISTED_METHODS = setOf(
                MethodFilter("java.lang.invoke.MethodHandles.Lookup", ""),
                MethodFilter("java.lang.Class", "forName"),
                MethodFilter("java.lang.Module", "add"),
                // can cause trouble for GC
                MethodFilter("java.nio.", "allocateDirect"),
                MethodFilter("java.lang.Class", "getClassLoader", allowInReload = true),
                MethodFilter("java.lang.ClassLoader", "", allowInReload = true),
                MethodFilter("java.lang.ModuleLayer", "", allowInReload = true),
                MethodFilter("java.lang.NoClassDefFoundError", "<init>", allowInReload = true),
                MethodFilter("kotlin.reflect.", "", allowInReload = true),
            )
            val PERMANENTLY_BLACKLISTED_CLASSES = setOf(
                "edu.illinois.cs.cs125.jeed.",
                "org.objectweb.asm.",
                "com.sun.",
                "net.bytebuddy.agent.",
                "java.lang.invoke.MethodHandles",
            )
            val ALWAYS_UNSAFE_EXCEPTIONS = setOf("java.lang.Error")
            val ALWAYS_ISOLATED_CLASSES = setOf("kotlin.coroutines.", "kotlinx.coroutines.")
        }
    }

    @Suppress("LongParameterList")
    @JsonClass(generateAdapter = true)
    open class ExecutionArguments(
        // var because may be increased in the presence of coroutines
        var timeout: Long = DEFAULT_TIMEOUT,
        permissions: Set<Permission> = setOf(),
        // var because may be increased in the presence of coroutines
        var maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS,
        val maxOutputLines: Int = DEFAULT_MAX_OUTPUT_LINES,
        val maxIOBytes: Int = DEFAULT_MAX_IO_BYTES,
        val classLoaderConfiguration: ClassLoaderConfiguration = ClassLoaderConfiguration(),
        val waitForShutdown: Boolean = DEFAULT_WAIT_FOR_SHUTDOWN,
        val returnTimeout: Int = DEFAULT_RETURN_TIMEOUT,
        val permissionBlacklist: Boolean = DEFAULT_PERMISSION_BLACKLIST,
        @Transient val systemInStream: InputStream? = null,
    ) {
        val permissions = if (permissionBlacklist) {
            permissions + BLACKLISTED_PERMISSIONS
        } else {
            permissions
        }
        companion object {
            const val DEFAULT_TIMEOUT = 100L
            const val DEFAULT_MAX_EXTRA_THREADS = 0
            const val DEFAULT_MAX_OUTPUT_LINES = 1024
            const val DEFAULT_MAX_IO_BYTES = 4 * 1024
            const val DEFAULT_WAIT_FOR_SHUTDOWN = false
            const val DEFAULT_RETURN_TIMEOUT = 1
            const val DEFAULT_PERMISSION_BLACKLIST = false
        }
    }

    @JsonClass(generateAdapter = true)
    data class MethodFilter(val ownerClassPrefix: String, val methodPrefix: String, val allowInReload: Boolean = false)

    @Suppress("LongParameterList", "unused")
    class TaskResults<T>(
        val returned: T?,
        val threw: Throwable?,
        val timeout: Boolean,
        val outputLines: MutableList<OutputLine> = mutableListOf(),
        val inputLines: MutableList<InputLine> = mutableListOf(),
        val combinedInputOutput: String = "",
        val permissionRequests: MutableList<PermissionRequest> = mutableListOf(),
        val interval: Interval,
        val executionInterval: Interval,
        @Transient val sandboxedClassLoader: SandboxedClassLoader? = null,
        val truncatedLines: Int,
        @Suppress("unused")
        val executionArguments: ExecutionArguments,
        val killReason: String? = null,
        // For serialization
        @Suppress("MemberVisibilityCanBePrivate")
        val pluginResults: Map<String, Any>,
        @Deprecated("no-op, to be removed")
        val killedClassInitializers: List<String>,
        @Suppress("unused")
        val totalSafetime: Long?,
    ) {
        @JsonClass(generateAdapter = true)
        data class OutputLine(
            val console: Console,
            val line: String,
            val timestamp: Instant,
            val thread: Long? = null,
        ) {
            enum class Console { STDOUT, STDERR }
        }

        @JsonClass(generateAdapter = true)
        data class InputLine(
            val line: String,
            val timestamp: Instant,
        )

        @JsonClass(generateAdapter = true)
        data class PermissionRequest(val permission: Permission, val granted: Boolean)

        val completed: Boolean
            get() {
                return threw == null && !timeout && killReason == null
            }
        val permissionDenied: Boolean
            get() {
                return permissionRequests.any { !it.granted }
            }
        val deniedPermissions: List<String>
            get() {
                return permissionRequests.filter { !it.granted }.map { it.permission.name }
            }
        val stdoutLines: List<OutputLine>
            get() {
                return outputLines.filter { it.console == OutputLine.Console.STDOUT }
            }
        val stderrLines: List<OutputLine>
            get() {
                return outputLines.filter { it.console == OutputLine.Console.STDERR }
            }
        val stdout: String
            get() {
                return stdoutLines.joinToString(separator = "\n") { it.line }
            }
        val stderr: String
            get() {
                return stderrLines.joinToString(separator = "\n") { it.line }
            }
        val stdin: String
            get() {
                return inputLines.joinToString(separator = "\n") { it.line }
            }
        val output: String
            get() {
                return outputLines.sortedBy { it.timestamp }.joinToString(separator = "\n") { it.line }
            }
        val totalDuration: Duration
            get() {
                return Duration.between(interval.start, interval.end)
            }

        fun <V : Any> pluginResult(plugin: SandboxPlugin<*, V>): V {
            @Suppress("UNCHECKED_CAST")
            return pluginResults[plugin.id] as V
        }
    }

    val BLACKLISTED_PERMISSIONS = setOf(
        // Suggestions from here: https://github.com/pro-grade/pro-grade/issues/31.
        RuntimePermission("createClassLoader"),
        RuntimePermission("accessClassInPackage.sun"),
        RuntimePermission("setSecurityManager"),
        // Required for Java Streams to work...
        // ReflectPermission("suppressAccessChecks")
        SecurityPermission("setPolicy"),
        SecurityPermission("setProperty.package.access"),
        @Suppress("MaxLineLength")
        // Other additions from here: https://docs.oracle.com/javase/7/docs/technotes/guides/security/permissions.html
        SecurityPermission("createAccessControlContext"),
        SecurityPermission("getDomainCombiner"),
        RuntimePermission("createSecurityManager"),
        RuntimePermission("exitVM"),
        RuntimePermission("shutdownHooks"),
        RuntimePermission("setIO"),
        RuntimePermission("queuePrintJob"),
        RuntimePermission("setDefaultUncaughtExceptionHandler"),
        // These are particularly important to prevent untrusted code from escaping the sandbox
        // which is based on thread groups
        RuntimePermission("modifyThread"),
        RuntimePermission("modifyThreadGroup"),
    )

    suspend fun <T> execute(
        sandboxedClassLoader: SandboxedClassLoader,
        executionArguments: ExecutionArguments,
        callable: SandboxCallableArguments<T>,
    ): TaskResults<out T?> {
        when (executionArguments.permissionBlacklist) {
            true -> require(executionArguments.permissions.containsAll(BLACKLISTED_PERMISSIONS)) {
                "attempt to allow unsafe permissions"
            }

            false -> require(executionArguments.permissions.intersect(BLACKLISTED_PERMISSIONS).isEmpty()) {
                "attempt to allow unsafe permissions"
            }
        }

        if (!running) {
            require(autoStart) { "Sandbox not running and autoStart not enabled" }
            start()
            require(running) { "Sandbox not running even after being started" }
        }

        return coroutineScope {
            val resultsChannel = Channel<ExecutorResult<T>>()
            val executor = Executor(callable, sandboxedClassLoader, executionArguments, resultsChannel)
            threadPool.submit(executor)
            val result = executor.resultChannel.receive()
            result.taskResults ?: throw result.executionException
        }
    }

    suspend fun <T> execute(
        sandboxableClassLoader: SandboxableClassLoader = EmptyClassLoader,
        executionArguments: ExecutionArguments = ExecutionArguments(),
        configuredPlugins: List<ConfiguredSandboxPlugin<*, *>> = listOf(),
        callable: SandboxCallableArguments<T>,
    ): TaskResults<out T?> {
        val sandboxedClassLoader = try {
            SandboxedClassLoader(sandboxableClassLoader, executionArguments.classLoaderConfiguration, configuredPlugins)
        } catch (e: OutOfMemoryError) {
            throw SandboxStartFailed("Out of memory while transforming bytecode", e)
        }
        return execute(sandboxedClassLoader, executionArguments, callable)
    }

    private const val MAX_THREAD_SHUTDOWN_RETRIES = 256
    private const val THREAD_SHUTDOWN_DELAY = 20L
    private const val MAX_COROUTINE_SHUTDOWN_RETRIES = 3

    private data class ExecutorResult<T>(val taskResults: TaskResults<T>?, val executionException: Throwable)
    private class Executor<T>(
        val callable: SandboxCallableArguments<T>,
        val sandboxedClassLoader: SandboxedClassLoader,
        val executionArguments: ExecutionArguments,
        val resultChannel: Channel<ExecutorResult<T>>,
    ) : Callable<Any> {
        private data class TaskResult<T>(val returned: T, val threw: Throwable? = null, val timeout: Boolean = false)

        @Suppress("ComplexMethod", "ReturnCount", "LongMethod")
        override fun call() {
            @Suppress("TooGenericExceptionCaught")
            try {
                val confinedTask = confine(callable, sandboxedClassLoader, executionArguments)
                val executionStarted = Instant.now()
                val safetimeStarted = runtime?.totalSafepointTime
                val taskResult = try {
                    confinedTask.thread.start()
                    TaskResult(confinedTask.task.get(executionArguments.timeout, TimeUnit.MILLISECONDS))
                } catch (e: TimeoutException) {
                    confinedTask.thread.interrupt()
                    val (returnValue, threw) = try {
                        Pair(
                            confinedTask.task.get(executionArguments.returnTimeout.toLong(), TimeUnit.MILLISECONDS),
                            null,
                        )
                    } catch (e: TimeoutException) {
                        confinedTask.task.cancel(true)
                        Pair(null, null)
                    } catch (e: Throwable) {
                        confinedTask.task.cancel(true)
                        Pair(null, e)
                    }
                    TaskResult(returnValue, threw, true)
                } catch (e: CancellationException) {
                    TaskResult(null, null)
                } catch (e: Throwable) {
                    TaskResult(null, e.cause ?: e)
                }

                fun threadGroupActive(): Boolean {
                    val threads = Array<Thread?>(confinedTask.threadGroup.activeCount() * 2) { null }
                    confinedTask.threadGroup.enumerate(threads)
                    return threads.filterNotNull().any {
                        it.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
                    }
                }

                val coroutinesUsed = sandboxedClassLoader.loadedClasses.any { it.startsWith("kotlinx.coroutines.") }
                fun anyActiveCoroutines(): Boolean {
                    if (!coroutinesUsed) return false
                    try {
                        performingSafeUnconstrainedInvocation.set(true)
                        val defaultExecutorName = "kotlinx.coroutines.DefaultExecutor"
                        val defaultExecutorClass = sandboxedClassLoader.loadClass(defaultExecutorName)
                        if (!sandboxedClassLoader.isClassReloaded(defaultExecutorClass)) return false // Shenanigans
                        val defaultExecutor = defaultExecutorClass.kotlin.objectInstance
                        val emptyProp = defaultExecutorClass.kotlin.memberProperties
                            .first { it.name == "isEmpty" }.also { it.isAccessible = true } as KProperty<*>
                        return emptyProp.getter.call(defaultExecutor) == false
                    } catch (e: Throwable) {
                        return false
                    } finally {
                        performingSafeUnconstrainedInvocation.set(false)
                    }
                }

                fun workPending(): Boolean {
                    if (threadGroupActive() || anyActiveCoroutines()) return true
                    if (coroutinesUsed) {
                        /*
                         * Our checks might happen right in the time between a coroutine continuation being taken off
                         * the queue and actually getting started running, in which case we would miss it in both
                         * places, shutting down the thread pool before it had a chance to run. Check a few times to
                         * increase the chance of noticing it.
                         */
                        repeat(MAX_COROUTINE_SHUTDOWN_RETRIES) {
                            Thread.yield()
                            if (threadGroupActive() || anyActiveCoroutines()) return true
                        }
                    }
                    return false
                }
                if (executionArguments.waitForShutdown && !confinedTask.shuttingDown) {
                    while (Instant.now().isBefore(executionStarted.plusMillis(executionArguments.timeout)) &&
                        workPending()
                    ) {
                        // Give non-main tasks like coroutines a chance to finish
                        Thread.yield()
                    }
                }

                val totalSafetime = runtime?.totalSafepointTime?.let { it - safetimeStarted!! }
                val executionEnded = Instant.now()
                release(confinedTask)

                // confinedTask.systemInStream?.close()

                val executionResult = TaskResults(
                    taskResult.returned, taskResult.threw, taskResult.timeout,
                    confinedTask.outputLines,
                    confinedTask.inputLines,
                    confinedTask.ioBytes.toByteArray().decodeToString(),
                    confinedTask.permissionRequests,
                    Interval(confinedTask.started, Instant.now()),
                    Interval(executionStarted, executionEnded),
                    sandboxedClassLoader,
                    confinedTask.truncatedLines,
                    executionArguments,
                    confinedTask.killReason,
                    confinedTask.pluginData.map { (plugin, workingData) ->
                        plugin.id to plugin.createFinalData(workingData)
                    }.toMap(),
                    listOf(),
                    totalSafetime,
                )
                @Suppress("ThrowingExceptionsWithoutMessageOrCause")
                runBlocking { resultChannel.send(ExecutorResult(executionResult, Exception())) }
            } catch (e: Throwable) {
                runBlocking { resultChannel.send(ExecutorResult(null, e)) }
            } finally {
                resultChannel.close()
            }
        }
    }

    private class ConfinedTask<T>(
        val classLoader: SandboxedClassLoader,
        val task: FutureTask<T>,
        val thread: Thread,
        executionArguments: ExecutionArguments,
    ) {
        val threadGroup = thread.threadGroup ?: error("thread should be in thread group")
        val permissionBlacklist = executionArguments.permissionBlacklist
        val permissions: Permissions = Permissions().apply {
            executionArguments.permissions.forEach { add(it) }
        }
        val accessControlContext: AccessControlContext? = when (permissionBlacklist) {
            false -> AccessControlContext(arrayOf(ProtectionDomain(null, permissions)))
            true -> null
        }

        val maxExtraThreads: Int = executionArguments.maxExtraThreads
        val maxOutputLines: Int = executionArguments.maxOutputLines
        val maxIOBytes: Int = executionArguments.maxIOBytes

        @Volatile
        var shuttingDown: Boolean = false

        @Volatile
        var killReason: String? = null

        val extraThreadsCreated = AtomicInteger(0)

        var truncatedLines: Int = 0
        val currentLines: MutableMap<TaskResults.OutputLine.Console, CurrentLine> = mutableMapOf()
        val outputLines = mutableListOf<TaskResults.OutputLine>()

        var currentInputLine: CurrentLine? = null
        var currentRedirectingInputLine: CurrentLine? = null
        var redirectedInput = StringBuilder()

        val inputLines: MutableList<TaskResults.InputLine> = mutableListOf()

        val ioBytes = mutableListOf<Byte>()
        var redirectingIOBytes = mutableListOf<Byte>()

        var outputCurrentBytes = 0
        var outputHardLimitBytes: Int? = null

        var currentRedirectedLines: MutableMap<TaskResults.OutputLine.Console, CurrentLine>? = null
        var redirectedOutputLines = mutableListOf<TaskResults.OutputLine>()

        var redirectingOutput: Boolean = false
        var squashNormalOutput: Boolean = false
        var redirectingOutputLimit: Int? = null
        var redirectingTruncatedLines: Int = 0
        var outputListener: OutputListener? = null

        val permissionRequests: MutableList<TaskResults.PermissionRequest> = mutableListOf()

        val started: Instant = Instant.now()

        val pluginData = classLoader.pluginInstrumentationData.associate { (plugin, instrumentationData) ->
            plugin to plugin.createInitialData(instrumentationData, executionArguments)
        }

        private val isolatedLocksSyncRoot = Object()
        private val isolatedLocks = IdentityHashMap<Any, ReentrantLock>()
        private val isolatedConditions = IdentityHashMap<Any, Condition>()

        data class CurrentLine(
            var started: Instant = Instant.now(),
            val bytes: MutableList<Byte> = mutableListOf(),
            val startedThread: Long = Thread.currentThread().id,
        ) {
            override fun toString() = bytes.toByteArray().decodeToString()
        }

        fun addPermissionRequest(permission: Permission, granted: Boolean, throwException: Boolean = true) {
            permissionRequests.add(TaskResults.PermissionRequest(permission, granted))
            if (!granted && throwException) {
                throw SecurityException()
            }
        }

        private val ourStdout = object : OutputStream() {
            override fun write(int: Int) {
                redirectedWrite(int, TaskResults.OutputLine.Console.STDOUT)
            }
        }
        private val ourStderr = object : OutputStream() {
            override fun write(int: Int) {
                redirectedWrite(int, TaskResults.OutputLine.Console.STDERR)
            }
        }
        val printStreams: Map<TaskResults.OutputLine.Console, PrintStream> = mapOf(
            TaskResults.OutputLine.Console.STDOUT to PrintStream(ourStdout),
            TaskResults.OutputLine.Console.STDERR to PrintStream(ourStderr),
        )

        val systemInStream = executionArguments.systemInStream ?: ByteArrayInputStream(byteArrayOf())

        private fun redirectedWrite(int: Int, console: TaskResults.OutputLine.Console) {
            if (shuttingDown) {
                return
            }

            val currentLine = currentLines.getOrPut(console) { CurrentLine() }
            val currentRedirectingLine = currentRedirectedLines?.getOrPut(console) { CurrentLine() }

            if (!squashNormalOutput && ioBytes.size <= maxIOBytes) {
                ioBytes += int.toByte()
            }
            if (redirectingOutput) {
                redirectingIOBytes += int.toByte()
            }
            outputHardLimitBytes?.also { currentLimit ->
                outputCurrentBytes += 1
                if (outputCurrentBytes >= currentLimit) {
                    throw OutputHardLimitExceeded(currentLimit)
                }
            }

            outputListener?.also {
                when (console) {
                    TaskResults.OutputLine.Console.STDOUT -> it.stdout(int)
                    TaskResults.OutputLine.Console.STDERR -> it.stderr(int)
                }
            }

            when (int.toChar()) {
                '\n' -> {
                    if (!squashNormalOutput) {
                        if (outputLines.size < maxOutputLines) {
                            outputLines.add(
                                TaskResults.OutputLine(
                                    console,
                                    currentLine.toString(),
                                    currentLine.started,
                                    currentLine.startedThread,
                                ),
                            )
                        } else {
                            truncatedLines += 1
                        }
                        currentLines.remove(console)
                    }

                    if (redirectingOutput && currentRedirectingLine!!.bytes.size > 0) {
                        if (redirectedOutputLines.size < (redirectingOutputLimit ?: Int.MAX_VALUE)) {
                            redirectedOutputLines.add(
                                TaskResults.OutputLine(
                                    console,
                                    currentRedirectingLine.toString() + "\n",
                                    currentRedirectingLine.started,
                                    currentRedirectingLine.startedThread,
                                ),
                            )
                        } else {
                            redirectingTruncatedLines += 1
                        }
                        currentRedirectedLines?.remove(console)
                    }
                }

                '\r' -> {
                    // Ignore - results will contain Unix line endings only
                }

                else -> {
                    if (!squashNormalOutput && truncatedLines == 0) {
                        currentLine.bytes.add(int.toByte())
                    }
                    if (redirectingOutput && redirectingTruncatedLines == 0) {
                        currentRedirectingLine?.bytes?.add(int.toByte())
                    }
                }
            }
        }

        fun getIsolatedLock(monitor: Any): ReentrantLock {
            synchronized(isolatedLocksSyncRoot) {
                return isolatedLocks.getOrPut(monitor) {
                    ReentrantLock()
                }
            }
        }

        fun getIsolatedCondition(monitor: Any): Condition {
            synchronized(isolatedLocksSyncRoot) {
                return isolatedConditions.getOrPut(monitor) {
                    getIsolatedLock(monitor).newCondition()
                }
            }
        }
    }

    private val confinedTasks: MutableMap<ThreadGroup, ConfinedTask<*>> = mutableMapOf()
    private val confinedClassLoaders: MutableSet<SandboxedClassLoader> = mutableSetOf()

    private fun confinedTaskByThreadGroup(): ConfinedTask<*>? {
        return confinedTasks[Thread.currentThread().threadGroup]
    }

    @Synchronized
    private fun <T> confine(
        callable: SandboxCallableArguments<T>,
        sandboxedClassLoader: SandboxedClassLoader,
        executionArguments: ExecutionArguments,
    ): ConfinedTask<T> {
        val threadGroup = object : ThreadGroup("Sandbox") {
            @Suppress("EmptyFunctionBlock")
            override fun uncaughtException(t: Thread?, e: Throwable?) {
            }
        }
        require(!confinedClassLoaders.contains(sandboxedClassLoader)) {
            "Duplicate class loader for confined task"
        }
        require(!confinedTasks.containsKey(threadGroup)) {
            "Duplicate thread group in confined tasks map"
        }
        threadGroup.maxPriority = Thread.MIN_PRIORITY
        val task = FutureTask(SandboxedCallable<T>(callable, sandboxedClassLoader))
        val thread = Thread(threadGroup, task, "main")
        val confinedTask = ConfinedTask(sandboxedClassLoader, task, thread, executionArguments)
        confinedTasks[threadGroup] = confinedTask
        confinedClassLoaders.add(sandboxedClassLoader)
        return confinedTask
    }

    @Synchronized
    @Suppress("ComplexMethod", "LongMethod")
    private fun <T> release(confinedTask: ConfinedTask<T>) {
        val threadGroup = confinedTask.threadGroup
        require(confinedTasks.containsKey(threadGroup)) { "thread group is not confined" }

        confinedTask.shuttingDown = true

        if (threadGroup.activeGroupCount() > 0) {
            val threadGroups = Array<ThreadGroup?>(threadGroup.activeGroupCount()) { null }
            threadGroup.enumerate(threadGroups, true)
            assert(threadGroups.toList().filterNotNull().sumOf { it.activeCount() } == 0)
        }

        val stoppedThreads: MutableSet<Thread> = mutableSetOf()
        val threadGroupShutdownRetries = (0..MAX_THREAD_SHUTDOWN_RETRIES).find {
            if (threadGroup.activeCount() == 0) {
                return@find true
            }
            val activeThreads = arrayOfNulls<Thread>(threadGroup.activeCount() * 2)
            threadGroup.enumerate(activeThreads)
            val existingActiveThreads = activeThreads.filterNotNull()
            existingActiveThreads.filter { !stoppedThreads.contains(it) }.forEach {
                stoppedThreads.add(it)
                @Suppress("DEPRECATION")
                it.stop()
            }
            threadGroup.maxPriority = Thread.NORM_PRIORITY
            stoppedThreads.filter { it.isAlive }.forEach {
                it.priority = Thread.NORM_PRIORITY
                it.join(THREAD_SHUTDOWN_DELAY)
                it.priority = Thread.MIN_PRIORITY
            }
            false
        }

        @Suppress("FoldInitializerAndIfToElvisOperator")
        if (threadGroupShutdownRetries == null) {
            throw SandboxContainmentFailure("failed to shut down thread group ($threadGroup)")
        }

        @Suppress("DEPRECATION")
        threadGroup.destroy()
        @Suppress("DEPRECATION")
        assert(threadGroup.isDestroyed)

        if (confinedTask.truncatedLines == 0) {
            for (console in TaskResults.OutputLine.Console.entries) {
                val currentLine = confinedTask.currentLines[console] ?: continue
                if (currentLine.bytes.isNotEmpty()) {
                    confinedTask.outputLines.add(
                        TaskResults.OutputLine(
                            console,
                            currentLine.toString(),
                            currentLine.started,
                            currentLine.startedThread,
                        ),
                    )
                }
            }
        }

        if (confinedTask.currentInputLine?.bytes?.isNotEmpty() == true) {
            confinedTask.inputLines.add(
                TaskResults.InputLine(
                    confinedTask.currentInputLine!!.toString(),
                    confinedTask.currentInputLine!!.started,
                ),
            )
        }

        confinedTask.pluginData.forEach { (plugin, data) ->
            plugin.executionFinished(data)
        }

        confinedTasks.remove(threadGroup)
        confinedClassLoaders.remove(confinedTask.classLoader)
    }

    private class SandboxedCallable<T>(
        val callable: SandboxCallableArguments<T>,
        val sandboxedClassLoader: SandboxedClassLoader,
    ) : Callable<T> {
        override fun call(): T {
            sandboxedClassLoader.pluginInstrumentationData.forEach { (plugin, _) ->
                plugin.executionStartedInSandbox()
            }
            return callable(Pair(sandboxedClassLoader, Sandbox::redirectOutput))
        }
    }

    interface SandboxableClassLoader {
        val bytecodeForClasses: Map<String, ByteArray>
        val classLoader: ClassLoader
    }

    interface EnumerableClassLoader {
        val definedClasses: Set<String>
        val providedClasses: Set<String>
        val loadedClasses: Set<String>
    }

    // Part of the sandbox plugin API
    object CurrentTask {
        @JvmStatic // Might as well be friendly to Java plugins
        fun <W> getWorkingData(plugin: SandboxPlugin<*, *>): W {
            val confinedTask =
                confinedTaskByThreadGroup() ?: error("attempt to access current task data outside any task")
            @Suppress("UNCHECKED_CAST")
            return confinedTask.pluginData[plugin] as W
        }

        @JvmStatic
        fun kill(reason: String): Nothing {
            val confinedTask = confinedTaskByThreadGroup() ?: error("attempt to kill current task outside any task")
            confinedTask.shuttingDown = true
            confinedTask.killReason = reason
            confinedTask.task.cancel(false)
            while (true) {
                // Wait for the sandbox to kill this thread
                Thread.yield()
            }
        }
    }

    class SandboxedClassLoader(
        private val sandboxableClassLoader: SandboxableClassLoader,
        classLoaderConfiguration: ClassLoaderConfiguration,
        configuredPlugins: List<ConfiguredSandboxPlugin<*, *>>,
    ) : ClassLoader(sandboxableClassLoader.classLoader.parent), EnumerableClassLoader {
        private val whitelistedClasses = classLoaderConfiguration.whitelistedClasses
        private val blacklistedClasses = classLoaderConfiguration.blacklistedClasses
        private val isolatedClasses = classLoaderConfiguration.isolatedClasses
        private val sandboxRequiredClasses = configuredPlugins.fold(ALWAYS_ALLOWED_CLASS_NAMES) { set, plugin ->
            set.union(plugin.plugin.requiredClasses.map { clazz -> clazz.name })
        }
        private val blacklistedMethods = classLoaderConfiguration.blacklistedMethods
        val unsafeExceptionClasses: Set<Class<*>> = classLoaderConfiguration.unsafeExceptions.map { name ->
            val klass = Class.forName(name) ?: error("$name does not refer to a Java class")
            require(Throwable::class.java.isAssignableFrom(klass)) { "$name does not refer to a Java Throwable" }
            klass
        }.toSet()

        init {
            val plugins = configuredPlugins.map { it.plugin }
            plugins.forEachIndexed { index, plugin ->
                if (plugins.lastIndexOf(plugin) > index) {
                    error("Duplicate plugin: $plugin")
                }
            }
        }

        internal val pluginInstrumentationData = configuredPlugins.map {
            @Suppress("UNCHECKED_CAST")
            it as ConfiguredSandboxPlugin<Any, Any>
            it.plugin to it.plugin.createInstrumentationData(it.arguments, classLoaderConfiguration, configuredPlugins)
        } // Intentionally not toMap'd so that order is preserved

        override val definedClasses: Set<String> get() = knownClasses.keys.toSet()
        override val providedClasses: MutableSet<String> = mutableSetOf()
        override val loadedClasses: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

        private val reloadedClasses: MutableMap<String, Class<*>> = mutableMapOf()
        private val canCacheReloadedClasses = configuredPlugins.none { it.plugin.transformsReloadedClasses }
        private val reloader = TrustedReloader()
        var transformedReloadedClasses: Int = 0 // Not atomic, but used only for statistics purposes

        @Suppress("MemberVisibilityCanBePrivate")
        val knownClasses = sandboxableClassLoader.bytecodeForClasses
            .mapValues { (name, unsafeByteArray) ->
                RewriteBytecode.rewrite(
                    name,
                    unsafeByteArray,
                    unsafeExceptionClasses,
                    blacklistedMethods,
                    pluginInstrumentationData,
                    RewritingContext.UNTRUSTED,
                )
            }

        override fun findClass(name: String): Class<*> {
            return if (knownClasses.containsKey(name)) {
                loadedClasses.add(name)
                providedClasses.add(name)
                val byteArray = knownClasses[name] ?: error("should still be in map")
                return defineClass(name, byteArray, 0, byteArray.size)
            } else {
                super.findClass(name)
            }
        }

        private val isWhiteList = classLoaderConfiguration.isWhiteList ?: whitelistedClasses.isNotEmpty()

        private fun delegateClass(name: String): Class<*> {
            val klass = super.loadClass(name)
            loadedClasses.add(name)
            return klass
        }

        @Suppress("ReturnCount", "LongMethod", "ComplexMethod", "NestedBlockDepth")
        override fun loadClass(name: String): Class<*> {
            val confinedTask = confinedTaskByThreadGroup() ?: return super.loadClass(name)

            if (isolatedClasses.any { name.startsWith(it) }) {
                if (!isWhiteList && blacklistedClasses.any { name.startsWith(it) }) {
                    confinedTask.addPermissionRequest(
                        RuntimePermission("loadIsolatedClass $name"),
                        granted = false,
                        throwException = false,
                    )

                    throw ClassNotFoundException(name)
                }
                return reloadedClasses.getOrPut(name) {
                    reloader.reload(name).also { loadedClasses.add(name) }
                }
            }
            if (knownClasses.containsKey(name)) {
                return delegateClass(name)
            }
            if (name in sandboxRequiredClasses) {
                return delegateClass(name)
            }
            if (name.startsWith("jdk.internal.reflect.")) {
                // Loaded when trusted code is reflectively accessing sandboxed members
                // Standard access restrictions prevent untrusted code from using jdk.internal directly
                return delegateClass(name)
            }
            return if (isWhiteList) {
                if (whitelistedClasses.any { name.startsWith(it) }) {
                    delegateClass(name)
                } else {
                    confinedTask.addPermissionRequest(
                        RuntimePermission("loadClass $name"),
                        granted = false,
                        throwException = false,
                    )
                    throw ClassNotFoundException(name)
                }
            } else {
                if (blacklistedClasses.any { name.startsWith(it) }) {
                    if (name == "java.lang.invoke.MethodHandles${"$"}Lookup") {
                        /*
                         * Jacoco adds a dynamic constant, computed by a bootstrap method added to the same class.
                         * This requires mentioning MethodHandles.Lookup in the bootstrap method signature.
                         * However, Jacoco does not actually use the lookup, so all Lookup methods are still banned
                         * by default by poisoning invoke instructions.
                         */
                        delegateClass(name)
                    } else {
                        confinedTask.addPermissionRequest(
                            RuntimePermission("loadClass $name"),
                            granted = false,
                            throwException = false,
                        )
                        throw ClassNotFoundException(name)
                    }
                } else {
                    delegateClass(name)
                }
            }
        }

        internal fun isClassReloaded(clazz: Class<*>): Boolean {
            return reloadedClasses[clazz.name] == clazz
        }

        companion object {
            private val ALWAYS_ALLOWED_CLASS_NAMES =
                setOf(
                    RewriteBytecode::class.java.name,
                    InvocationTargetException::class.java.name,
                )
            private const val RELOAD_CACHE_SIZE_BYTES: Long = 256 * 1024 * 1024
            private val reloadedBytecodeCache: Cache<ReloadCacheKey, ByteArray> = Caffeine.newBuilder()
                .maximumWeight(RELOAD_CACHE_SIZE_BYTES)
                .weigher<ReloadCacheKey, ByteArray> { _, value -> value.size }
                .executor { task -> task.run() } // Do not create new cache-cleaning thread
                .build()
        }

        internal inner class TrustedReloader {
            fun reload(name: String): Class<*> {
                val classBytes = if (canCacheReloadedClasses) {
                    val key = ReloadCacheKey(
                        name,
                        unsafeExceptionClasses.map { it.name }.toSet(),
                        blacklistedMethods,
                    )
                    reloadedBytecodeCache.get(key) { transformFromClasspath(name) }
                } else {
                    transformFromClasspath(name)
                }
                loadedClasses.add(name)
                return defineClass(name, classBytes, 0, classBytes.size)
            }

            private fun transformFromClasspath(name: String): ByteArray {
                val originalBytes = sandboxableClassLoader.classLoader.parent
                    .getResourceAsStream("${name.replace('.', '/')}.class")?.readAllBytes()
                    ?: throw ClassNotFoundException("failed to reload $name")
                return RewriteBytecode.rewrite(
                    name,
                    originalBytes,
                    unsafeExceptionClasses,
                    blacklistedMethods,
                    pluginInstrumentationData.filter { (plugin, _) -> plugin.transformsReloadedClasses },
                    RewritingContext.RELOADED,
                ).also { transformedReloadedClasses++ }
            }
        }

        private data class ReloadCacheKey(
            val className: String,
            val unsafeExceptionClasses: Set<String>,
            val blacklistedMethods: Set<MethodFilter>,
        )
    }

    object EmptyClassLoader : ClassLoader(getSystemClassLoader()), SandboxableClassLoader {
        override val bytecodeForClasses: Map<String, ByteArray> = mapOf()
        override val classLoader: ClassLoader = this
        override fun findClass(name: String): Class<*> {
            throw ClassNotFoundException(name)
        }
    }

    @Suppress("TooManyFunctions")
    object RewriteBytecode {
        val rewriterClassName =
            classNameToPath(RewriteBytecode::class.java.name ?: error("should have a class name"))
        private val checkMethodName = RewriteBytecode::checkException.javaMethod?.name
            ?: error("should have a method name")
        private val checkMethodDescription = Type.getMethodDescriptor(RewriteBytecode::checkException.javaMethod)
            ?: error("should be able to retrieve method signature")
        val enclosureMethodName = RewriteBytecode::checkSandboxEnclosure.javaMethod?.name
            ?: error("should have a method name for the enclosure checker")
        val enclosureMethodDescriptor =
            Type.getMethodDescriptor(RewriteBytecode::checkSandboxEnclosure.javaMethod)
                ?: error("should be able to retrieve method signature for enclosure checker")
        private val syncNotifyMethods = mapOf(
            "wait:()V" to RewriteBytecode::conditionWait,
            "wait:(J)V" to RewriteBytecode::conditionWaitMs,
            "wait:(JI)V" to RewriteBytecode::conditionWaitMsNs,
            "notify:()V" to RewriteBytecode::conditionNotify,
            "notifyAll:()V" to RewriteBytecode::conditionNotifyAll,
        )
        private const val NS_PER_MS = 1000000L
        private const val MAX_CLASS_FILE_SIZE = 1000000
        private const val SYNC_WRAPPER_STACK_ITEMS = 2

        @Suppress("ThrowsCount")
        @JvmStatic
        fun checkException(throwable: Throwable) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            if (confinedTask.shuttingDown) {
                throw SandboxDeath()
            }
            // This check is required because of how we handle finally blocks
            if (confinedTask.classLoader.unsafeExceptionClasses.any { it.isAssignableFrom(throwable.javaClass) }) {
                throw throwable
            }
        }

        @JvmStatic
        fun enterMonitor(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedLock(monitor).lockInterruptibly()
        }

        @JvmStatic
        fun exitMonitor(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedLock(monitor).unlock()
        }

        @JvmStatic
        fun conditionWait(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).await()
        }

        @JvmStatic
        fun conditionWaitMs(monitor: Any, timeout: Long) {
            require(timeout >= 0) { "timeout cannot be negative" }
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).await(timeout, TimeUnit.MILLISECONDS)
        }

        @JvmStatic
        fun conditionWaitMsNs(monitor: Any, timeout: Long, plusNanos: Int) {
            require(timeout >= 0) { "timeout cannot be negative" }
            require(plusNanos >= 0) { "nanos cannot be negative" }
            require(plusNanos < NS_PER_MS) { "nanos cannot specify another full ms" }
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).await(timeout * NS_PER_MS + plusNanos, TimeUnit.NANOSECONDS)
        }

        @JvmStatic
        fun conditionNotify(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).signal()
        }

        @JvmStatic
        fun conditionNotifyAll(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).signalAll()
        }

        @JvmStatic
        fun forbiddenMethod() {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.permissionRequests.add(
                TaskResults.PermissionRequest(
                    RuntimePermission("useForbiddenMethod"),
                    false,
                ),
            )
            throw SecurityException("use of forbidden method")
        }

        @JvmStatic
        fun checkSandboxEnclosure() {
            if (confinedTaskByThreadGroup() == null && !performingSafeUnconstrainedInvocation.get()) {
                throw SecurityException("invocation of untrusted code outside confined task")
            }
        }

        @Suppress("LongParameterList")
        internal fun rewrite(
            name: String,
            originalByteArray: ByteArray,
            unsafeExceptionClasses: Set<Class<*>>,
            blacklistedMethods: Set<MethodFilter>,
            plugins: List<Pair<SandboxPlugin<*, *>, Any?>>,
            context: RewritingContext,
        ): ByteArray {
            require(originalByteArray.size <= MAX_CLASS_FILE_SIZE) { "bytecode is over 1 MB" }
            val pretransformed = plugins.fold(originalByteArray) { bytecode, (plugin, instrumentationData) ->
                plugin.transformBeforeSandbox(bytecode, name, instrumentationData, context)
            }
            val classReader = ClassReader(pretransformed)
            val allPreinspections = preInspectMethods(classReader)
            val classWriter = ClassWriter(classReader, 0)
            var className: String? = null
            val sandboxingVisitor = object : ClassVisitor(Opcodes.ASM8, classWriter) {
                private val enclosedHandles = mutableMapOf<Handle, Handle>()

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    super.visit(version, access, name, signature, superName, interfaces)
                    className = name
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    return if (name == "finalize" && descriptor == "()V") {
                        null // Drop the finalizer
                    } else {
                        val preinspection = allPreinspections[VisitedMethod(name, descriptor)]
                            ?: error("missing pre-inspection for $name:$descriptor")
                        val transformedMethodName = if (Modifier.isSynchronized(access)) {
                            val transformedNameOfOriginal = "$name\$syncbody"
                            val nonSynchronizedModifiers = access and Modifier.SYNCHRONIZED.inv()
                            emitSynchronizedBridge(
                                super.visitMethod(nonSynchronizedModifiers, name, descriptor, signature, exceptions),
                                className ?: error("should have visited the class"),
                                transformedNameOfOriginal,
                                preinspection.parameters,
                                access,
                                descriptor,
                            )
                            transformedNameOfOriginal
                        } else {
                            name
                        }
                        val transformedModifiers = if (Modifier.isSynchronized(access)) {
                            (
                                access
                                    and Modifier.PUBLIC.inv()
                                    and Modifier.PROTECTED.inv()
                                    and Modifier.SYNCHRONIZED.inv()
                                ) or Modifier.PRIVATE
                        } else {
                            access
                        }
                        SandboxingMethodVisitor(
                            unsafeExceptionClasses,
                            blacklistedMethods,
                            preinspection.badTryCatchBlockPositions,
                            context,
                            this::getEnclosedHandle,
                            super.visitMethod(
                                transformedModifiers,
                                transformedMethodName,
                                descriptor,
                                signature,
                                exceptions,
                            ),
                        )
                    }
                }

                private fun getEnclosedHandle(handle: Handle) = enclosedHandles.getOrPut(handle) {
                    val handleType = Type.getType(handle.desc)
                    val actualParameters = handleType.argumentTypes.toMutableList()
                    var actualReturn = handleType.returnType
                    when (handle.tag) {
                        Opcodes.H_INVOKESPECIAL, Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE -> {
                            actualParameters.add(0, Type.getObjectType(handle.owner))
                        }

                        Opcodes.H_NEWINVOKESPECIAL -> {
                            actualReturn = Type.getObjectType(handle.owner)
                        }
                    }
                    val actualType = Type.getMethodType(actualReturn, *actualParameters.toTypedArray())
                    val safeOwnerName = handle.owner.replace('/', '_').replace('$', '_')
                    val safeMethodName = if (handle.name == "<init>") "NEW\$" else handle.name
                    val wrapperName = "sandboxMH${handle.tag}\$$safeOwnerName\$$safeMethodName"
                    val wrapperMv = super.visitMethod(
                        Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                        wrapperName,
                        actualType.descriptor,
                        null,
                        null,
                    )
                    wrapperMv.visitCode()
                    wrapperMv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        rewriterClassName,
                        enclosureMethodName,
                        enclosureMethodDescriptor,
                        false,
                    )
                    wrapperMv.visitLdcInsn(handle)
                    var parameterLocal = 0
                    actualParameters.forEach {
                        wrapperMv.visitIntInsn(it.getOpcode(Opcodes.ILOAD), parameterLocal)
                        parameterLocal += it.size
                    }
                    wrapperMv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/invoke/MethodHandle",
                        "invokeExact",
                        actualType.descriptor,
                        false,
                    )
                    wrapperMv.visitInsn(actualReturn.getOpcode(Opcodes.IRETURN))
                    wrapperMv.visitMaxs(parameterLocal + 2, parameterLocal) // +2 in case of long return
                    wrapperMv.visitEnd()
                    Handle(
                        Opcodes.H_INVOKESTATIC,
                        className,
                        wrapperName,
                        actualType.descriptor,
                        false,
                    )
                }
            }
            classReader.accept(sandboxingVisitor, 0)
            val sandboxed = classWriter.toByteArray()
            return plugins.toList().fold(sandboxed) { bytecode, (plugin, instrumentationData) ->
                plugin.transformAfterSandbox(bytecode, name, instrumentationData, context)
            }
        }

        @Suppress("LongParameterList", "ComplexMethod")
        private fun emitSynchronizedBridge(
            template: MethodVisitor,
            className: String,
            bridgeTo: String,
            parameters: List<VisitedParameter>,
            modifiers: Int,
            descriptor: String,
        ) {
            val methodVisitor = MonitorIsolatingMethodVisitor(template)
            fun loadSelf() {
                if (Modifier.isStatic(modifiers)) {
                    methodVisitor.visitLdcInsn(Type.getType("L$className;")) // the class object
                } else {
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0) // this
                }
            }
            parameters.forEach { methodVisitor.visitParameter(it.name, it.modifiers) }
            methodVisitor.visitCode()
            val callStartLabel = Label()
            val callEndLabel = Label()
            val finallyLabel = Label()
            methodVisitor.visitTryCatchBlock(callStartLabel, callEndLabel, finallyLabel, null) // try-finally
            loadSelf()
            methodVisitor.visitInsn(Opcodes.MONITORENTER) // will be transformed by MonitorIsolatingMethodVisitor
            var localIndex = 0
            if (!Modifier.isStatic(modifiers)) {
                loadSelf()
                localIndex++
            }
            Type.getArgumentTypes(descriptor).forEach {
                methodVisitor.visitVarInsn(it.getOpcode(Opcodes.ILOAD), localIndex)
                localIndex += it.size
            }
            methodVisitor.visitLabel(callStartLabel)
            methodVisitor.visitMethodInsn(
                if (Modifier.isStatic(modifiers)) Opcodes.INVOKESTATIC else Opcodes.INVOKESPECIAL,
                className,
                bridgeTo,
                descriptor,
                false,
            )
            methodVisitor.visitLabel(callEndLabel)
            loadSelf()
            methodVisitor.visitInsn(Opcodes.MONITOREXIT)
            methodVisitor.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN))
            methodVisitor.visitLabel(finallyLabel)
            val onlyThrowableOnStack = arrayOf<Any>(classNameToPath(Throwable::class.java.name))
            methodVisitor.visitFrame(Opcodes.F_SAME1, 0, emptyArray(), 1, onlyThrowableOnStack)
            loadSelf()
            methodVisitor.visitInsn(Opcodes.MONITOREXIT)
            methodVisitor.visitInsn(Opcodes.ATHROW)
            val returnSize = Type.getReturnType(descriptor).size
            methodVisitor.visitMaxs(localIndex + returnSize + SYNC_WRAPPER_STACK_ITEMS, localIndex)
            methodVisitor.visitEnd()
        }

        private open class MonitorIsolatingMethodVisitor(
            downstream: MethodVisitor,
        ) : MethodVisitor(Opcodes.ASM8, downstream) {
            override fun visitInsn(opcode: Int) {
                when (opcode) {
                    Opcodes.MONITORENTER -> {
                        super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            rewriterClassName,
                            RewriteBytecode::enterMonitor.javaMethod?.name ?: error("missing enter-monitor name"),
                            Type.getMethodDescriptor(RewriteBytecode::enterMonitor.javaMethod),
                            false,
                        )
                    }

                    Opcodes.MONITOREXIT -> {
                        super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            rewriterClassName,
                            RewriteBytecode::exitMonitor.javaMethod?.name ?: error("missing exit-monitor name"),
                            Type.getMethodDescriptor(RewriteBytecode::exitMonitor.javaMethod),
                            false,
                        )
                    }

                    else -> super.visitInsn(opcode)
                }
            }
        }

        private class SandboxingMethodVisitor(
            val unsafeExceptionClasses: Set<Class<*>>,
            val blacklistedMethods: Set<MethodFilter>,
            val badTryCatchBlockPositions: Set<Int>,
            val rewritingContext: RewritingContext,
            val handleEncloser: (Handle) -> Handle,
            methodVisitor: MethodVisitor,
        ) : MonitorIsolatingMethodVisitor(methodVisitor) {
            private val labelsToRewrite: MutableSet<Label> = mutableSetOf()
            private var rewroteLabel = false
            private var currentTryCatchBlockPosition = -1

            override fun visitCode() {
                super.visitCode()
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    rewriterClassName,
                    enclosureMethodName,
                    enclosureMethodDescriptor,
                    false,
                )
            }

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                currentTryCatchBlockPosition++
                if (start == handler) {
                    /*
                     * For unclear reasons, the Java compiler sometimes emits exception table entries that catch any
                     * exception and transfer control to the inside of the same block. This produces an infinite loop
                     * if an exception is thrown, e.g. by our checkException function. Since any exception during
                     * non-sandboxed execution would also cause this infinite loop, the table entry must not serve any
                     * purpose. Drop it to avoid the infinite loop.
                     */
                    return
                }
                val exceptionClass = type?.let {
                    try {
                        Class.forName(pathToClassName(type))
                    } catch (_: ClassNotFoundException) {
                        null
                    }
                }
                if (exceptionClass == null) {
                    labelsToRewrite.add(handler)
                } else {
                    val needsRewrite = unsafeExceptionClasses
                        .any { exceptionClass.isAssignableFrom(it) || it.isAssignableFrom(exceptionClass) }
                    if (needsRewrite) {
                        labelsToRewrite.add(handler)
                    }
                }
                /*
                 * For unclear reasons, the Java compiler *also* sometimes emits exception table entries that cover
                 * a larger region than necessary and partially overlap the handler, especially with throw statements
                 * inside try-finally blocks. These entries do have functional significance, but must have their
                 * protected regions shortened to avoid an infinite loop.
                 */
                val safeEnd = if (currentTryCatchBlockPosition in badTryCatchBlockPositions) handler else end
                super.visitTryCatchBlock(start, safeEnd, handler, type)
            }

            private var nextLabel: Label? = null
            override fun visitLabel(label: Label) {
                if (labelsToRewrite.contains(label)) {
                    assert(nextLabel == null)
                    nextLabel = label
                }
                super.visitLabel(label)
            }

            override fun visitFrame(
                type: Int,
                numLocal: Int,
                local: Array<out Any>?,
                numStack: Int,
                stack: Array<out Any>?,
            ) {
                super.visitFrame(type, numLocal, local, numStack, stack)
                if (nextLabel != null) {
                    super.visitInsn(Opcodes.DUP)
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        rewriterClassName,
                        checkMethodName,
                        checkMethodDescription,
                        false,
                    )
                    rewroteLabel = true
                    labelsToRewrite.remove(nextLabel ?: error("nextLabel changed"))
                    nextLabel = null
                }
            }

            private fun isBlacklistedMethod(ownerBinaryName: String, methodName: String): Boolean {
                val ownerClassName = binaryNameToClassName(ownerBinaryName)
                return blacklistedMethods.any {
                    ownerClassName.startsWith(it.ownerClassPrefix) &&
                        methodName.startsWith(it.methodPrefix) &&
                        (rewritingContext == RewritingContext.UNTRUSTED || !it.allowInReload)
                }
            }

            private fun addForbiddenMethodTrap() {
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    rewriterClassName,
                    RewriteBytecode::forbiddenMethod.javaMethod?.name ?: error("need forbidden method name"),
                    Type.getMethodDescriptor(RewriteBytecode::forbiddenMethod.javaMethod),
                    false,
                )
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) {
                if (isBlacklistedMethod(owner, name)) {
                    // Adding an extra call instead of replacing the call avoids the need for fiddly stack manipulation
                    addForbiddenMethodTrap()
                }
                val rewriteTarget = if (!isInterface && opcode == Opcodes.INVOKEVIRTUAL &&
                    owner == classNameToPath(Object::class.java.name)
                ) {
                    syncNotifyMethods["$name:$descriptor"]
                } else {
                    null
                }
                if (rewriteTarget == null) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                } else {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        rewriterClassName,
                        rewriteTarget.javaMethod?.name ?: error("missing notification method name"),
                        Type.getMethodDescriptor(rewriteTarget.javaMethod),
                        false,
                    )
                }
            }

            private fun sandboxBootstrapArguments(args: Array<out Any?>): Array<Any?> {
                return args.map {
                    if (it is Handle && it.owner.contains('/')) { // Reference to library method
                        handleEncloser(it)
                    } else if (it is ConstantDynamic) {
                        sandboxConstantDynamic(it)
                    } else {
                        it
                    }
                }.toTypedArray()
            }

            private fun sandboxConstantDynamic(condy: ConstantDynamic): ConstantDynamic {
                return ConstantDynamic(
                    condy.name,
                    condy.descriptor,
                    condy.bootstrapMethod,
                    *sandboxBootstrapArguments(condy.bootstrapArguments),
                )
            }

            private fun hasBlacklistedBootstrapRef(args: Array<out Any?>) = args.any {
                when (it) {
                    is Handle -> isBlacklistedMethod(it.owner, it.name)
                    is ConstantDynamic -> isConstantDynamicBlacklisted(it)
                    else -> false
                }
            }

            private fun isConstantDynamicBlacklisted(condy: ConstantDynamic): Boolean {
                return isBlacklistedMethod(condy.bootstrapMethod.owner, condy.bootstrapMethod.name) ||
                    hasBlacklistedBootstrapRef(condy.bootstrapArguments)
            }

            override fun visitInvokeDynamicInsn(
                name: String?,
                descriptor: String,
                bootstrapMethodHandle: Handle,
                vararg bootstrapMethodArguments: Any?,
            ) {
                val forbidden = isBlacklistedMethod(bootstrapMethodHandle.owner, bootstrapMethodHandle.name) ||
                    hasBlacklistedBootstrapRef(bootstrapMethodArguments)
                if (forbidden) {
                    addForbiddenMethodTrap()
                }
                val arguments = if (!forbidden && rewritingContext == RewritingContext.UNTRUSTED) {
                    sandboxBootstrapArguments(bootstrapMethodArguments)
                } else {
                    bootstrapMethodArguments
                }
                val newDesc = if (bootstrapMethodHandle.owner == Type.getInternalName(LambdaMetafactory::class.java)) {
                    /*
                     * LambdaMetafactory requires all bound parameter types to match exactly between the implementation
                     * handle type and the factory type... except for the receiver type in the case of an instance
                     * method being the implementation. The Java compiler takes advantage of this special case and
                     * uses the specific receiver type for the factory type even when the method is inherited.
                     * Unfortunately, enclosing the implementation handle in an H_INVOKESTATIC-kind handle disables the
                     * special handling in LMF. The factory type must therefore be adjusted when an instance method
                     * handle has been enclosed (adding 1 to the argument list as seen by ASM) and its receiver will be
                     * bound (factory argument list is nonempty).
                     */
                    val originalHandle = bootstrapMethodArguments[1] as Handle
                    val originalHandleType = Type.getType(originalHandle.desc)
                    val sandboxedHandle = arguments[1] as Handle
                    val sandboxedHandleType = Type.getType(sandboxedHandle.desc)
                    val factoryType = Type.getType(descriptor)
                    val factoryArgTypes = factoryType.argumentTypes
                    if (originalHandleType.argumentTypes.size != sandboxedHandleType.argumentTypes.size && // instance
                        factoryArgTypes.isNotEmpty() // bound
                    ) {
                        factoryArgTypes[0] = sandboxedHandleType.argumentTypes[0]
                        Type.getMethodDescriptor(factoryType.returnType, *factoryArgTypes)
                    } else {
                        descriptor
                    }
                } else {
                    descriptor
                }
                super.visitInvokeDynamicInsn(
                    name,
                    newDesc,
                    bootstrapMethodHandle,
                    *arguments,
                )
            }

            override fun visitLdcInsn(value: Any?) {
                if (value is ConstantDynamic && isConstantDynamicBlacklisted(value)) {
                    addForbiddenMethodTrap()
                }
                val sandboxed = if (value is ConstantDynamic && rewritingContext == RewritingContext.UNTRUSTED) {
                    sandboxConstantDynamic(value)
                } else {
                    value
                }
                super.visitLdcInsn(sandboxed)
            }

            override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                // The DUP instruction for checkException calls makes the stack one item taller
                super.visitMaxs(maxStack + 1, maxLocals)
            }

            override fun visitEnd() {
                assert(labelsToRewrite.isEmpty()) { "failed to write all flagged labels" }
                super.visitEnd()
            }
        }

        private data class VisitedMethod(val name: String, val descriptor: String)
        private data class VisitedParameter(val name: String?, val modifiers: Int)
        private data class MethodPreinspection(
            val badTryCatchBlockPositions: Set<Int>,
            val parameters: List<VisitedParameter>,
        )

        private fun preInspectMethods(reader: ClassReader): Map<VisitedMethod, MethodPreinspection> {
            /*
             * ASM doesn't provide a way to get the bytecode positions of a try-catch block's labels while the code
             * is being visited, so we have to go through all the methods to figure out the positions of the labels
             * with respect to each other to determine which try-catch blocks are bad (i.e. will loop forever) before
             * doing the real visit in SandboxingMethodVisitor.
             */
            val methodVisitors = mutableMapOf<VisitedMethod, PreviewingMethodVisitor>()
            reader.accept(
                object : ClassVisitor(Opcodes.ASM8) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        return PreviewingMethodVisitor()
                            .also { methodVisitors[VisitedMethod(name, descriptor)] = it }
                    }
                },
                0,
            )
            return methodVisitors.mapValues {
                MethodPreinspection(it.value.getBadTryCatchBlockPositions(), it.value.getParameters())
            }
        }

        private class PreviewingMethodVisitor : MethodVisitor(Opcodes.ASM8) {

            private val labelPositions = mutableMapOf<Label, Int>()
            private val tryCatchBlocks = mutableListOf<Triple<Label, Label, Label>>()
            private val parameters = mutableListOf<VisitedParameter>()

            override fun visitLabel(label: Label) {
                super.visitLabel(label)
                labelPositions[label] = labelPositions.size
            }

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                super.visitTryCatchBlock(start, end, handler, type)
                tryCatchBlocks.add(Triple(start, end, handler))
            }

            override fun visitParameter(name: String?, access: Int) {
                super.visitParameter(name, access)
                parameters.add(VisitedParameter(name, access))
            }

            fun getBadTryCatchBlockPositions(): Set<Int> {
                // Called after this visitor has accepted the entire method, so all positioning information is ready
                val badPositions = mutableSetOf<Int>()
                tryCatchBlocks.forEachIndexed { i, (startLabel, endLabel, handlerLabel) ->
                    val startPos = labelPositions[startLabel] ?: error("start $startLabel not visited")
                    val endPos = labelPositions[endLabel] ?: error("end $endLabel not visited")
                    val handlerPos = labelPositions[handlerLabel] ?: error("handler $handlerLabel not visited")
                    if (handlerPos in startPos until endPos) badPositions.add(i)
                }
                return badPositions
            }

            fun getParameters(): List<VisitedParameter> {
                return parameters
            }
        }
    }

    val systemSecurityManager: SecurityManager? = System.getSecurityManager()

    private object SandboxSecurityManager : SecurityManager() {
        private val SET_IO_PERMISSION = RuntimePermission("setIO")
        private val GET_CLASSLOADER_PERMISSION = RuntimePermission("getClassLoader")
        private val inReentrantPermissionCheck = ThreadLocal.withInitial { false }

        @Suppress("ReturnCount")
        private fun confinedTaskByClassLoader(): ConfinedTask<*>? {
            val confinedTask = confinedTaskByThreadGroup() ?: return null
            var classIsConfined = false
            classContext.forEach { klass ->
                if (klass.classLoader == confinedTask.classLoader) {
                    classIsConfined = true
                    return@forEach
                } else if (klass == SandboxedClassLoader.TrustedReloader::class.java) {
                    return null
                }
            }
            return if (classIsConfined) {
                confinedTask
            } else {
                null
            }
        }

        private fun confinedTaskByClassLoaderReentrant(): ConfinedTask<*>? {
            return if (inReentrantPermissionCheck.get()) {
                null
            } else {
                try {
                    inReentrantPermissionCheck.set(true)
                    confinedTaskByClassLoader()
                } finally {
                    inReentrantPermissionCheck.set(false)
                }
            }
        }

        override fun checkRead(file: String) {
            val confinedTask = confinedTaskByClassLoader()
                ?: return systemSecurityManager?.checkRead(file) ?: return
            if (file.endsWith("currency.properties")) {
                confinedTask.addPermissionRequest(FilePermission(file, "read"), true)
            } else if (!file.endsWith(".class")) {
                confinedTask.addPermissionRequest(FilePermission(file, "read"), false)
            } else {
                systemSecurityManager?.checkRead(file)
            }
        }

        override fun checkAccess(thread: Thread) {
            val confinedTask = confinedTaskByThreadGroup()
                ?: return systemSecurityManager?.checkAccess(thread) ?: return
            if (thread.threadGroup != Thread.currentThread().threadGroup) {
                confinedTask.addPermissionRequest(RuntimePermission("changeThreadGroup"), false)
            } else {
                if (confinedTask.shuttingDown) throw SandboxDeath()
                systemSecurityManager?.checkAccess(thread)
            }
        }

        override fun checkAccess(threadGroup: ThreadGroup) {
            val confinedTask = confinedTaskByThreadGroup()
                ?: return systemSecurityManager?.checkAccess(threadGroup) ?: return
            if (threadGroup != Thread.currentThread().threadGroup) {
                confinedTask.addPermissionRequest(RuntimePermission("changeThreadGroup"), false)
            } else {
                // This is the only time the Thread construction process is guaranteed to consult the SecurityManager
                checkThreadLimits(confinedTask, confinedTask.extraThreadsCreated.incrementAndGet())
                systemSecurityManager?.checkAccess(threadGroup)
            }
        }

        @Suppress("ReturnCount")
        override fun getThreadGroup(): ThreadGroup {
            val threadGroup = Thread.currentThread().threadGroup
            val confinedTask = confinedTaskByThreadGroup()
                ?: return systemSecurityManager?.threadGroup ?: return threadGroup
            checkThreadLimits(confinedTask, confinedTask.extraThreadsCreated.get())
            return systemSecurityManager?.threadGroup ?: threadGroup
        }

        private fun checkThreadLimits(confinedTask: ConfinedTask<*>, newExtraThreadCount: Int) {
            if (confinedTask.shuttingDown) {
                confinedTask.permissionRequests.add(
                    TaskResults.PermissionRequest(
                        RuntimePermission("createThreadAfterTimeout"),
                        false,
                    ),
                )
                throw SandboxDeath()
            }
            if (newExtraThreadCount > confinedTask.maxExtraThreads) {
                confinedTask.permissionRequests.add(
                    TaskResults.PermissionRequest(
                        RuntimePermission("exceedThreadLimit"),
                        false,
                    ),
                )
                throw SecurityException()
            }
        }

        override fun checkPermission(permission: Permission) {
            val confinedTask = when (permission) {
                SET_IO_PERMISSION -> confinedTaskByThreadGroup() // Even trusted tasks shouldn't call System.setOut
                GET_CLASSLOADER_PERMISSION -> confinedTaskByClassLoaderReentrant() // Avoid StackOverflowError
                else -> confinedTaskByClassLoader()
            } ?: return systemSecurityManager?.checkPermission(permission) ?: return

            try {
                checkTaskPermission(confinedTask, permission)
                confinedTask.addPermissionRequest(permission, true)
            } catch (e: SecurityException) {
                confinedTask.addPermissionRequest(permission, granted = false, throwException = false)
                throw e
            }
            systemSecurityManager?.checkPermission(permission)
        }

        private fun checkTaskPermission(confinedTask: ConfinedTask<*>, permission: Permission) {
            if (permission is PropertyPermission &&
                permission.actions == "read" &&
                confinedTask.pluginData.any { it.key.getSystemProperty(permission.name) != null }
            ) {
                return
            }
            when (confinedTask.permissionBlacklist) {
                true -> {
                    if (confinedTask.permissions.implies(permission)) {
                        throw SecurityException()
                    }
                }
                false -> confinedTask.accessControlContext!!.checkPermission(permission)
            }
        }
    }

    private class SandboxedProperties(properties: Properties) : Properties(properties) {
        @Suppress("ReturnCount")
        override fun getProperty(key: String?): String? {
            val confinedTask = confinedTaskByThreadGroup() ?: return super.getProperty(key)
            if (key == "kotlinx.coroutines.scheduler.max.pool.size") {
                return confinedTask.maxExtraThreads.toString()
            } else if (key == "kotlinx.coroutines.scheduler.core.pool.size") {
                return (confinedTask.maxExtraThreads - 1).toString()
            } else if (key != null) {
                confinedTask.pluginData.keys.forEach {
                    return it.getSystemProperty(key) ?: return@forEach
                }
            }
            return super.getProperty(key)
        }
    }

    interface OutputListener {
        fun stdout(int: Int)
        fun stderr(int: Int)
    }

    @JvmStatic
    fun hardLimitOutput(hardLimit: Int, block: () -> Any?): Any? {
        val confinedTask = confinedTaskByThreadGroup() ?: error("should only be used from a confined task")
        check(confinedTask.outputHardLimitBytes == null) { "can't nest calls to hardLimitOutput" }

        confinedTask.outputCurrentBytes = 0
        confinedTask.outputHardLimitBytes = hardLimit
        return try {
            block()
        } finally {
            confinedTask.outputHardLimitBytes = null
        }
    }

    @JvmStatic
    fun redirectOutput(block: () -> Any?) = redirectOutput(null, null, null, block)

    @JvmStatic
    fun redirectOutput(
        outputListener: OutputListener? = null,
        redirectingOutputLimit: Int? = null,
        squashNormalOutput: Boolean? = null,
        block: () -> Any?,
    ): JeedOutputCapture {
        val confinedTask = confinedTaskByThreadGroup() ?: error("should only be used from a confined task")
        check(!confinedTask.redirectingOutput) { "can't nest calls to redirectOutput" }

        confinedTask.redirectingOutput = true
        confinedTask.currentRedirectedLines = mutableMapOf()
        confinedTask.outputListener = outputListener
        confinedTask.redirectingOutputLimit = redirectingOutputLimit
        confinedTask.squashNormalOutput = squashNormalOutput == true

        val (returned, threw) = try {
            Pair(block(), null)
        } catch (e: Throwable) {
            if (e is ThreadDeath || e is LineLimitExceeded || e is OutOfMemoryError) {
                confinedTask.currentRedirectedLines = null
                throw e
            }
            Pair(null, e)
        } finally {
            confinedTask.redirectingOutput = false
            confinedTask.squashNormalOutput = false
        }

        val flushedStdout =
            confinedTask.currentRedirectedLines!![TaskResults.OutputLine.Console.STDOUT]?.toString() ?: ""
        val flushedStderr =
            confinedTask.currentRedirectedLines!![TaskResults.OutputLine.Console.STDERR]?.toString() ?: ""
        val flushedStdin =
            confinedTask.currentRedirectingInputLine?.toString() ?: ""

        return JeedOutputCapture(
            returned,
            threw,
            confinedTask.redirectedOutputLines
                .filter { it.console == TaskResults.OutputLine.Console.STDOUT }
                .joinToString("") { it.line } + flushedStdout,
            confinedTask.redirectedOutputLines
                .filter { it.console == TaskResults.OutputLine.Console.STDERR }
                .joinToString("") { it.line } + flushedStderr,
            confinedTask.redirectedInput.toString() + flushedStdin,
            confinedTask.redirectingIOBytes.toByteArray().decodeToString(),
            confinedTask.redirectingTruncatedLines,
        ).also {
            confinedTask.currentRedirectedLines = null
            confinedTask.outputListener = null
            confinedTask.redirectingOutputLimit = null
            confinedTask.redirectingTruncatedLines = 0

            confinedTask.redirectedOutputLines = mutableListOf()
            confinedTask.redirectedInput = StringBuilder()
            confinedTask.redirectingIOBytes = mutableListOf()
        }
    }

    /*
     * Obviously this requires some explanation. One of the saddest pieces of code I've ever written...
     *
     * The problem is that System.out is a PrintStream. And, internally, it passes content through several buffered
     * streams before it gets to the output stream that you pass.
     *
     * This becomes an issue once you have to kill off runaway threads. If a thread exits uncleanly,
     * it can leave content somewhere in the buffers hidden by the PrintStream. Which is then spewed out at whoever
     * happens to use the stream next. Not OK.
     *
     * Our original plan was to have _one_ PrintStream (per console) that sent all output to a shared OutputStream
     * and then separate it there by thread group. This is much, much cleaner since you only have to override the
     * OutputStream public interface which is one function. (See the code above on the ConfinedTask class.) But again,
     * this fails in the presence of unclean exits.
     *
     * Note that the issue here isn't really with concurrency: it's with unclean exit. Concurrency just means that
     * you don't know whose output stream might be polluted by the garbage left over if you share a PrintStream.
     *
     * So the "solution" is to create one PrintStream per confined task. This works because unclean exit just leaves
     * detritus in that thread group's PrintStream which is eventually cleaned up and destroyed.
     *
     * But the result is that you have to implement the entire PrintStream public API. Leaving anything out means that
     * stuff doesn't get forwarded, and we have to make sure that nothing is shared.
     *
     * Hence, this sadness.
     */
    @Suppress("EmptyFunctionBlock")
    private val nullOutputStream = object : OutputStream() {
        override fun write(b: Int) {}
    }

    @Suppress("TooManyFunctions", "SpreadOperator")
    private class RedirectingPrintStream(val console: TaskResults.OutputLine.Console) : PrintStream(nullOutputStream) {
        private val taskPrintStream: PrintStream
            get() {
                val confinedTask = confinedTaskByThreadGroup() ?: return (
                    originalPrintStreams[console]
                        ?: error("original console should exist")
                    )
                return confinedTask.printStreams[console] ?: error("confined console should exist")
            }

        override fun append(char: Char): PrintStream {
            return taskPrintStream.append(char)
        }

        override fun append(charSequence: CharSequence?): PrintStream {
            return taskPrintStream.append(charSequence)
        }

        override fun append(charSequence: CharSequence?, start: Int, end: Int): PrintStream {
            return taskPrintStream.append(charSequence, start, end)
        }

        override fun close() {
            taskPrintStream.close()
        }

        override fun flush() {
            taskPrintStream.flush()
        }

        override fun format(locale: Locale?, format: String, vararg args: Any?): PrintStream {
            return taskPrintStream.format(locale, format, *args)
        }

        override fun format(format: String, vararg args: Any?): PrintStream {
            return taskPrintStream.format(format, *args)
        }

        override fun print(boolean: Boolean) {
            taskPrintStream.print(boolean)
        }

        override fun print(char: Char) {
            taskPrintStream.print(char)
        }

        override fun print(charArray: CharArray) {
            taskPrintStream.print(charArray)
        }

        override fun print(double: Double) {
            taskPrintStream.print(double)
        }

        override fun print(float: Float) {
            taskPrintStream.print(float)
        }

        override fun print(int: Int) {
            taskPrintStream.print(int)
        }

        override fun print(long: Long) {
            taskPrintStream.print(long)
        }

        override fun print(any: Any?) {
            taskPrintStream.print(any)
        }

        override fun print(string: String?) {
            taskPrintStream.print(string)
        }

        override fun printf(locale: Locale?, format: String, vararg args: Any?): PrintStream {
            return taskPrintStream.printf(locale, format, *args)
        }

        override fun printf(format: String, vararg args: Any?): PrintStream {
            return taskPrintStream.printf(format, *args)
        }

        override fun println() {
            taskPrintStream.println()
        }

        override fun println(boolean: Boolean) {
            taskPrintStream.println(boolean)
        }

        override fun println(char: Char) {
            taskPrintStream.println(char)
        }

        override fun println(charArray: CharArray) {
            taskPrintStream.println(charArray)
        }

        override fun println(double: Double) {
            taskPrintStream.println(double)
        }

        override fun println(float: Float) {
            taskPrintStream.println(float)
        }

        override fun println(int: Int) {
            taskPrintStream.println(int)
        }

        override fun println(long: Long) {
            taskPrintStream.println(long)
        }

        override fun println(any: Any?) {
            taskPrintStream.println(any)
        }

        override fun println(string: String?) {
            taskPrintStream.println(string)
        }

        override fun write(byteArray: ByteArray, off: Int, len: Int) {
            taskPrintStream.write(byteArray, off, len)
        }

        override fun write(int: Int) {
            taskPrintStream.write(int)
        }

        override fun write(byteArray: ByteArray) {
            taskPrintStream.write(byteArray)
        }
    }

    private class RedirectingInputStream : InputStream() {
        override fun read(): Int {
            val confinedTask = confinedTaskByThreadGroup() ?: error("Non-confined tasks should not use System.in")

            val int = confinedTask.systemInStream.read()
            if (int != -1) {
                confinedTask.apply {
                    if (!squashNormalOutput && currentInputLine == null) {
                        currentInputLine = ConfinedTask.CurrentLine()
                    }
                    if (redirectingOutput && currentRedirectingInputLine == null) {
                        currentRedirectingInputLine = ConfinedTask.CurrentLine()
                    }

                    if (!squashNormalOutput && ioBytes.size <= maxIOBytes) {
                        ioBytes += int.toByte()
                    }
                    if (redirectingOutput) {
                        redirectingIOBytes += int.toByte()
                    }

                    when (int.toChar()) {
                        '\n' -> {
                            if (!squashNormalOutput) {
                                inputLines.add(
                                    TaskResults.InputLine(
                                        currentInputLine!!.toString(),
                                        currentInputLine!!.started,
                                    ),
                                )
                                currentInputLine = null
                            }
                            if (redirectingOutput && currentRedirectingInputLine!!.bytes.size > 0) {
                                redirectedInput.append(currentRedirectingInputLine.toString() + "\n")
                                currentRedirectingInputLine = null
                            }
                        }

                        '\r' -> {
                            // Ignore - input will contain Unix line endings only
                        }

                        else -> {
                            if (!squashNormalOutput) {
                                currentInputLine!!.bytes.add(int.toByte())
                            }
                            if (redirectingOutput) {
                                currentRedirectingInputLine!!.bytes.add(int.toByte())
                            }
                        }
                    }
                }
            }
            return int
        }
    }

    // Save a bit of time by not filling in the stack trace
    private class SandboxDeath : ThreadDeath() {
        override fun fillInStackTrace() = this
    }

    class UnexpectedExtraThreadError : Error(
        "An extra thread was detected by a feature not configured to support multiple threads",
    )

    class SandboxStartFailed(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
    class SandboxContainmentFailure(message: String) : Throwable(message)

    private lateinit var originalStdout: PrintStream
    private lateinit var originalStderr: PrintStream
    private lateinit var originalStdin: InputStream

    private var originalSecurityManager: SecurityManager? = null
    private lateinit var originalProperties: Properties

    @Suppress("TooGenericExceptionCaught")
    private val MAX_THREAD_POOL_SIZE = try {
        System.getenv("JEED_MAX_THREAD_POOL_SIZE").toInt()
    } catch (e: Exception) {
        Runtime.getRuntime().availableProcessors()
    }

    private lateinit var threadPool: ExecutorService

    @Suppress("MemberVisibilityCanBePrivate")
    var autoStart = true
    var running = false
        private set

    private lateinit var originalPrintStreams: Map<TaskResults.OutputLine.Console, PrintStream>

    private val performingSafeUnconstrainedInvocation: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    @JvmStatic
    @Synchronized
    fun start(size: Int = min(Runtime.getRuntime().availableProcessors(), MAX_THREAD_POOL_SIZE)) {
        if (running) {
            return
        }

        originalStdout = System.out
        originalStderr = System.err
        originalStdin = System.`in`

        System.setOut(RedirectingPrintStream(TaskResults.OutputLine.Console.STDOUT))
        System.setErr(RedirectingPrintStream(TaskResults.OutputLine.Console.STDERR))
        System.setIn(RedirectingInputStream())

        threadPool = Executors.newFixedThreadPool(size)

        originalSecurityManager = System.getSecurityManager()
        System.setSecurityManager(SandboxSecurityManager)
        originalProperties = System.getProperties()
        System.setProperties(SandboxedProperties(System.getProperties()))

        originalPrintStreams = mapOf(
            TaskResults.OutputLine.Console.STDOUT to originalStdout,
            TaskResults.OutputLine.Console.STDERR to originalStderr,
        )

        running = true
    }

    @JvmStatic
    @Synchronized
    fun stop(timeout: Long = 10) {
        if (!running) {
            return
        }
        threadPool.shutdown()
        try {
            require(threadPool.awaitTermination(timeout / 2, TimeUnit.SECONDS))
        } catch (e: Exception) {
            threadPool.shutdownNow()
            @Suppress("EmptyCatchBlock")
            try {
                require(threadPool.awaitTermination(timeout / 2, TimeUnit.SECONDS))
            } catch (_: Exception) {
            }
        }

        System.setOut(originalStdout)
        System.setErr(originalStderr)

        System.setSecurityManager(originalSecurityManager)
        System.setProperties(originalProperties)

        running = false
    }
}

@Suppress("UNUSED")
fun <T> withSandbox(run: () -> T): T {
    try {
        Sandbox.start()
        return run()
    } finally {
        Sandbox.stop()
    }
}

fun Sandbox.SandboxableClassLoader.sandbox(
    classLoaderConfiguration: Sandbox.ClassLoaderConfiguration,
    configuredPlugins: List<ConfiguredSandboxPlugin<*, *>> = listOf(),
): Sandbox.SandboxedClassLoader {
    return Sandbox.SandboxedClassLoader(this, classLoaderConfiguration, configuredPlugins)
}

data class JeedOutputCapture(
    val returned: Any?,
    val threw: Throwable?,
    val stdout: String,
    val stderr: String,
    val stdin: String,
    val interleavedInputOutput: String,
    val truncatedLines: Int,
)

interface SandboxPlugin<A : Any, V : Any> {
    val id: String
        get() = javaClass.simpleName.decapitalizeAsciiOnly()

    val transformsReloadedClasses: Boolean
        get() = false

    fun createInstrumentationData(
        arguments: A,
        classLoaderConfiguration: Sandbox.ClassLoaderConfiguration,
        allPlugins: List<ConfiguredSandboxPlugin<*, *>>,
    ): Any? = null

    fun transformBeforeSandbox(
        bytecode: ByteArray,
        name: String,
        instrumentationData: Any?,
        context: RewritingContext,
    ): ByteArray = bytecode

    fun transformAfterSandbox(
        bytecode: ByteArray,
        name: String,
        instrumentationData: Any?,
        context: RewritingContext,
    ): ByteArray = bytecode

    fun createInitialData(instrumentationData: Any?, executionArguments: Sandbox.ExecutionArguments): Any?
    fun executionStartedInSandbox() {}
    fun getSystemProperty(property: String): String? = null
    fun executionFinished(workingData: Any?) {}
    fun createFinalData(workingData: Any?): V
    val requiredClasses: Set<Class<*>>
        get() = setOf()
}

interface SandboxPluginWithDefaultArguments<A : Any, V : Any> : SandboxPlugin<A, V> {
    fun createDefaultArguments(): A
}

data class ConfiguredSandboxPlugin<A : Any, V : Any>(
    val plugin: SandboxPlugin<A, V>,
    val arguments: A,
)

enum class RewritingContext {
    UNTRUSTED,
    RELOADED,
}

class OutputHardLimitExceeded(limit: Int) : Error("Output limit $limit exceeded")
