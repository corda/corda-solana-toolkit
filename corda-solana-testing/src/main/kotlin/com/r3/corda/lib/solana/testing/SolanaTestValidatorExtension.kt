package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.core.SolanaClient
import com.r3.corda.lib.solana.testing.SolanaTestValidatorExtension.Companion.builder
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolutionException
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.platform.commons.support.AnnotationSupport.findAnnotatedMethods
import org.junit.platform.commons.support.HierarchyTraversalMode.BOTTOM_UP
import org.junit.platform.commons.support.ModifierSupport.isPublic
import org.junit.platform.commons.support.ModifierSupport.isStatic
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.util.function.Consumer
import com.r3.corda.lib.solana.testing.SolanaTestValidator.Builder as ValidatorBuilder

/**
 * Starts a [SolanaTestValidator] for a test class and resolves it (and a connected [SolanaClient]) as test
 * parameters. The validator is started once per test class and closed when the class finishes.
 *
 * Two registration styles are supported:
 * * Declarative registration via [SolanaTestClass] (`@ExtendWith`) takes its configuration from a static
 * [ConfigureValidator] method, and starts the validator lazily on first parameter resolution.
 * * Programmatic registration via [builder] and a static `@RegisterExtension` field takes its configuration as a
 * lambda and starts the validator in `beforeAll`.
 *
 * Note, Solana must be [installed](https://solana.com/docs/intro/installation) locally to use this extension.
 */
class SolanaTestValidatorExtension private constructor(
    private val configure: Consumer<ValidatorBuilder>?,
    private val waitForReadinessOverride: Boolean?,
    private val startInBeforeAll: Boolean,
) : ParameterResolver, BeforeAllCallback, AfterAllCallback {
    constructor() : this(configure = null, waitForReadinessOverride = null, startInBeforeAll = false)

    override fun beforeAll(context: ExtensionContext) {
        // Declarative registration keeps its original lazy-start behavior; only the programmatic builder path
        // starts eagerly so a cooperating extension can rely on the validator being up.
        if (startInBeforeAll) {
            getValidator(context)
        }
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean {
        val parameterType = parameterContext.parameter.type
        return parameterType == SolanaTestValidator::class.java || parameterType == SolanaClient::class.java
    }

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any {
        val testValidator = getValidator(extensionContext)
        return when (parameterContext.parameter.getType()) {
            SolanaTestValidator::class.java -> testValidator
            SolanaClient::class.java -> testValidator.client()
            else -> throw IllegalArgumentException("Unsupported parameter type: ${parameterContext.parameter}")
        }
    }

    override fun afterAll(context: ExtensionContext) {
        val testValidator = getOptionalTestValidator(context)
        if (testValidator != null) {
            logger.info("Closing test validator on RPC port ${testValidator.rpcPort()}")
            testValidator.close()
        }
    }

    /**
     * Returns the validator for the test class in [context], starting and caching it on first call. A cooperating
     * extension holding this instance can call this to obtain the same validator that the [SolanaTestValidator] and
     * [SolanaClient] test parameters resolve to, independent of `beforeAll` ordering between the two extensions.
     */
    fun getValidator(context: ExtensionContext): SolanaTestValidator {
        return getStore(context).getOrComputeIfAbsent(
            "validator",
            {
                val testValidator = startTestValidator(context.requiredTestClass)
                logger.info("Started test validator on RPC port ${testValidator.rpcPort()}")
                if (resolveWaitForReadiness(context.requiredTestClass)) {
                    testValidator.waitForReadiness()
                } else {
                    while (!testValidator.isListening()) {
                        Thread.sleep(100)
                    }
                    testValidator.client().start()
                }
                testValidator
            },
            SolanaTestValidator::class.java,
        )
    }

    private fun resolveWaitForReadiness(testClass: Class<*>): Boolean {
        if (waitForReadinessOverride != null) {
            return waitForReadinessOverride
        }
        return testClass.getAnnotation(SolanaTestClass::class.java)?.waitForReadiness ?: true
    }

    private fun startTestValidator(testClass: Class<*>): SolanaTestValidator {
        val builder = SolanaTestValidator
            .builder()
            .ledger(Files.createTempDirectory("ledger"))
            .dynamicPorts()
        applyConfiguration(testClass, builder)
        return builder.start()
    }

    private fun applyConfiguration(testClass: Class<*>, builder: ValidatorBuilder) {
        val configure = configure
        if (configure != null) {
            configure.accept(builder)
        } else {
            configureFromAnnotatedMethod(testClass, builder)
        }
    }

    private fun configureFromAnnotatedMethod(testClass: Class<*>, builder: ValidatorBuilder) {
        val methods = findAnnotatedMethods(testClass, ConfigureValidator::class.java, BOTTOM_UP)
        val method = when (methods.size) {
            0 -> return
            1 -> methods[0]
            else -> throw ParameterResolutionException(
                "Multiple @ConfigureValidator methods found in ${testClass.name}"
            )
        }
        if (!isPublic(method)) {
            throw ParameterResolutionException("@ConfigureValidator method ${method.name} must be public")
        }
        if (!isStatic(method)) {
            throw ParameterResolutionException("@ConfigureValidator method ${method.name} must be static")
        }
        if (method.parameterCount != 1 || method.parameterTypes[0] != ValidatorBuilder::class.java) {
            throw ParameterResolutionException(
                "@ConfigureValidator method ${method.name} must have a single SolanaTestValidator.Builder parameter"
            )
        }
        if (method.returnType != Void.TYPE) {
            throw ParameterResolutionException("@ConfigureValidator method ${method.name} must be void")
        }
        try {
            method.invoke(null, builder)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun getStore(context: ExtensionContext): ExtensionContext.Store {
        val namespace = Namespace.create(SolanaTestValidatorExtension::class, context.requiredTestClass)
        return context.root.getStore(namespace)
    }

    private fun getOptionalTestValidator(context: ExtensionContext): SolanaTestValidator? {
        return getStore(context).get("validator", SolanaTestValidator::class.java)
    }

    private fun SolanaTestValidator.isListening(): Boolean {
        return try {
            Socket("127.0.0.1", rpcPort()).use { true }
        } catch (_: SocketException) {
            false
        }
    }

    /** Configures a programmatically-registered [SolanaTestValidatorExtension] for use with `@RegisterExtension`. */
    class Builder internal constructor() {
        private var configure: Consumer<ValidatorBuilder>? = null
        private var waitForReadiness: Boolean = true

        /** Customizes the underlying validator. */
        fun configureValidator(configure: Consumer<ValidatorBuilder>): Builder = apply { this.configure = configure }

        /** See [SolanaTestValidator.waitForReadiness]. Defaults to `true`. */
        fun waitForReadiness(waitForReadiness: Boolean): Builder = apply { this.waitForReadiness = waitForReadiness }

        fun build(): SolanaTestValidatorExtension {
            return SolanaTestValidatorExtension(
                configure = configure,
                waitForReadinessOverride = waitForReadiness,
                startInBeforeAll = true,
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SolanaTestValidatorExtension::class.java)

        /** Starts a [Builder] for programmatic registration via a static `@RegisterExtension` field. */
        fun builder(): Builder = Builder()
    }
}
