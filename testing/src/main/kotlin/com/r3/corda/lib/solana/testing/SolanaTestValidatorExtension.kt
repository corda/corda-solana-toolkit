package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.core.SolanaClient
import com.r3.corda.lib.solana.testing.SolanaTestValidator.Builder
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.slf4j.LoggerFactory
import java.lang.reflect.ParameterizedType
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.util.function.Consumer

/**
 * Automatically starts a [SolanaTestValidator] before all the tests and closes it after they have run.
 *
 * Tests and lifecycle methods can specify a [SolanaClient] parameter whiich will be connected to the validator. They
 * can also specify a [SolanaTestValidator] parameter to get access to the instance itself.
 *
 * By default, the test validator uses a temporary directory for its ledger and listens on dynamically assigned ports.
 * This can be changed, or further configured, by having a [BeforeAll] method with a [SolanaTestValidator.Builder]
 * parameter.
 *
 * If such a method is defined, then it's not possible to have a second [BeforeAll] for the final [SolanaTestValidator].
 * This is becuase it is not possible to guarantee the builder method will be called before the validator method.
 * Instead, the [BeforeAll] method must manually start the validator and pass in the instance to a second [Consumer]
 * parameter. If there is no need to access the [SolanaTestValidator] in the [BeforeAll] method then the [Consumer]
 * parameter is not required. The extension will automatically start the validator when needed.
 *
 */
class SolanaTestValidatorExtension : ParameterResolver, AfterAllCallback {
    private companion object {
        private val logger = LoggerFactory.getLogger(SolanaTestValidatorExtension::class.java)
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean {
        val isBeforeAll = parameterContext.declaringExecutable.isAnnotationPresent(BeforeAll::class.java)
        return when (parameterContext.parameter.type) {
            SolanaTestValidator::class.java, SolanaClient::class.java -> true
            Builder::class.java -> isBeforeAll
            Consumer::class.java -> {
                val consumerType = parameterContext.parameter.parameterizedType as? ParameterizedType
                isBeforeAll && consumerType?.actualTypeArguments?.get(0) == SolanaTestValidator::class.java
            }
            else -> false
        }
    }

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any {
        val store = getStore(extensionContext)
        val solanaTestClass = extensionContext.requiredTestClass.getAnnotation(SolanaTestClass::class.java)
        val waitForReadiness = solanaTestClass?.waitForReadiness ?: true
        return when (parameterContext.parameter.getType()) {
            SolanaTestValidator::class.java -> store.getRequiredTestValidator(waitForReadiness)
            SolanaClient::class.java -> store.getRequiredTestValidator(waitForReadiness).client()
            Builder::class.java -> {
                check(store.getOptionalTestValidator() == null) {
                    "The builder can no longer be accessed as the SolanaTestValidator has already started"
                }
                store.getOrComputeIfAbsent("builder", { newBuilder() }, Builder::class.java)
            }
            Consumer::class.java -> {
                Consumer<SolanaTestValidator> { capturedValidator ->
                    store.remove("builder")
                    store.put("validator", capturedValidator)
                }
            }
            else -> throw IllegalArgumentException("Unsupported parameter type: ${parameterContext.parameter}")
        }
    }

    override fun afterAll(context: ExtensionContext) {
        val testValidator = getStore(context).getOptionalTestValidator()
        if (testValidator != null) {
            logger.info("Closing test validator on RPC port ${testValidator.rpcPort()}")
            testValidator.close()
        }
    }

    private fun getStore(extensionContext: ExtensionContext): ExtensionContext.Store {
        val namespace = Namespace.create(SolanaTestValidatorExtension::class, extensionContext.requiredTestClass)
        val store = extensionContext.root.getStore(namespace)
        return store
    }

    private fun ExtensionContext.Store.getOptionalTestValidator(): SolanaTestValidator? {
        return get("validator", SolanaTestValidator::class.java)
    }

    private fun ExtensionContext.Store.getRequiredTestValidator(waitForReadiness: Boolean): SolanaTestValidator {
        return getOrComputeIfAbsent(
            "validator",
            {
                val builder = remove("builder", Builder::class.java) ?: newBuilder()
                val testValidator = builder.start()
                logger.info("Started test validator on RPC port ${testValidator.rpcPort()}")
                if (waitForReadiness) {
                    testValidator.waitForReadiness()
                } else {
                    while (!testValidator.isListening()) {
                        Thread.sleep(100)
                    }
                    testValidator.client().start()
                }
                testValidator
            },
            SolanaTestValidator::class.java
        )
    }

    private fun SolanaTestValidator.isListening(): Boolean {
        return try {
            Socket("127.0.0.1", rpcPort()).use { true }
        } catch (_: SocketException) {
            false
        }
    }

    private fun newBuilder(): Builder {
        return SolanaTestValidator
            .builder()
            .ledger(Files.createTempDirectory("ledger"))
            .dynamicPorts()
    }
}
