package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.core.SolanaClient
import com.r3.corda.lib.solana.testing.SolanaTestValidator.Builder
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier.isStatic
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files

class SolanaTestValidatorExtension : ParameterResolver, AfterAllCallback {
    private companion object {
        private val logger = LoggerFactory.getLogger(SolanaTestValidatorExtension::class.java)
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean {
        val parameterType = parameterContext.parameter.type
        return parameterType == SolanaTestValidator::class.java || parameterType == SolanaClient::class.java
    }

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any {
        val store = getStore(extensionContext)
        val testClass = extensionContext.requiredTestClass
        val solanaTestClass = testClass.getAnnotation(SolanaTestClass::class.java)
        val waitForReadiness = solanaTestClass?.waitForReadiness ?: true
        return when (parameterContext.parameter.getType()) {
            SolanaTestValidator::class.java -> store.getRequiredTestValidator(testClass, waitForReadiness)
            SolanaClient::class.java -> store.getRequiredTestValidator(testClass, waitForReadiness).client()
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

    private fun ExtensionContext.Store.getRequiredTestValidator(
        testClass: Class<*>,
        waitForReadiness: Boolean,
    ): SolanaTestValidator {
        return getOrComputeIfAbsent(
            "validator",
            {
                val testValidator = startTestValidator(testClass)
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

    private fun startTestValidator(testClass: Class<*>): SolanaTestValidator {
        val builder = SolanaTestValidator
            .builder()
            .ledger(Files.createTempDirectory("ledger"))
            .dynamicPorts()
        configureBuilder(testClass, builder)
        return builder.start()
    }

    private fun configureBuilder(testClass: Class<*>, builder: Builder) {
        val methods = testClass.methods.filter { it.isAnnotationPresent(ConfigureValidator::class.java) }
        if (methods.isEmpty()) {
            return
        }
        check(methods.size == 1) { "Multiple @ConfigureValidator methods found in ${testClass.name}" }
        val method = methods[0]
        check(isStatic(method.modifiers)) { "@ConfigureValidator method ${method.name} must be static" }
        check(method.parameterCount == 1 && method.parameterTypes[0] == Builder::class.java) {
            "@ConfigureValidator method ${method.name} must have a single SolanaTestValidator.Builder parameter"
        }
        check(method.returnType == Void.TYPE) { "@ConfigureValidator method ${method.name} must be void" }
        try {
            method.invoke(null, builder)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun SolanaTestValidator.isListening(): Boolean {
        return try {
            Socket("127.0.0.1", rpcPort()).use { true }
        } catch (_: SocketException) {
            false
        }
    }
}
