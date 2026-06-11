package com.r3.corda.lib.solana.testing;

import com.r3.corda.lib.solana.core.AccountManagement;
import com.r3.corda.lib.solana.core.SolanaClient;
import com.r3.corda.lib.solana.core.tokens.TokenManagement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.sava.core.accounts.PublicKey;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Wrapper around the {@link
 * <a href="https://docs.anza.xyz/cli/examples/test-validator"><code>solana-test-validator</code></a>}. Requires
 * Solana to be [installed](https://solana.com/docs/intro/installation) locally.
 */
public final class SolanaTestValidator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SolanaTestValidator.class);
    private static final Pattern finalizedSlotPattern = Pattern.compile("Finalized Slot: (\\d+)");

    private final Process process;
    private final int rpcPort;
    private final Path ledger;
    private final SolanaClient client;
    private final AccountManagement accounts;
    private final TokenManagement tokens;

    private SolanaTestValidator(Process process, int rpcPort, Path ledger) {
        this.process = process;
        this.rpcPort = rpcPort;
        this.ledger = ledger;
        client = new SolanaClient(rpcUrl(), websocketUrl());
        accounts = new AccountManagement(client);
        tokens = new TokenManagement(client);
    }

    public Process process() {
        return process;
    }

    public int rpcPort() {
        return rpcPort;
    }

    public int websocketPort() {
        return rpcPort + 1;
    }

    public Path ledger() {
        return ledger;
    }

    public URI rpcUrl() {
        return URI.create("http://127.0.0.1:" + rpcPort);
    }

    public URI websocketUrl() {
        return URI.create("ws://127.0.0.1:" + websocketPort());
    }

    public SolanaClient client() {
        return client;
    }

    public AccountManagement accounts() {
        return accounts;
    }

    public TokenManagement tokens() {
        return tokens;
    }

    /**
     * Waits for the test validator to have fully initialised and be ready to accept transactions.
     * @return this instance.
     */
    public SolanaTestValidator waitForReadiness() {
        var processOutput = process.inputReader();
        try {
            while (true) {
                var line = processOutput.readLine();
                if (line == null) {
                    throw new IllegalStateException("solana-test-validator didn't start or has terminated");
                }
                logger.debug("solana-test-validator: {}", line);
                // Wait until the validator has finalized at least one slot otherwise it will drop submitted
                // transactions
                var matcher = finalizedSlotPattern.matcher(line);
                if (matcher.find() && Integer.parseInt(matcher.group(1)) > 0) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("solana-test-validator didn't start or has terminated", e);
        }
        if (!client.isStarted()) {
            client.start();
        }
        return this;
    }

    /// Reads the validator's output until it reports a finalized slot (startup succeeded) or the
    /// stream closes (the process exited, which on a fresh launch almost always means a port-bind
    /// failure). Output consumed here is not re-read by [#waitForReadiness()], which picks up where
    /// this leaves off.
    private boolean confirmStarted() {
        var processOutput = process.inputReader();
        try {
            String line;
            while ((line = processOutput.readLine()) != null) {
                logger.debug("solana-test-validator: {}", line);
                if (finalizedSlotPattern.matcher(line).find()) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    @Override
    public void close() {
        client.close();
        var interrupted = false;
        while (process.isAlive()) {
            process.destroy();
            try {
                if (process.waitFor(1, TimeUnit.MINUTES)) {
                    continue;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
            process.destroyForcibly();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static Builder builder() {
        return new Builder();
    }


    public static class Builder {
        private static final int DEFAULT_RPC_PORT = 8899;
        private static final String DEFAULT_LEDGER_DIR_NAME = "test-ledger";
        private static final int MAX_PORT_ATTEMPTS = 100;
        private static final int MAX_START_ATTEMPTS = 5;

        // Each validator's ports form a 40-port block in [10000, 32000), clear of agave's default
        // range of (8000-10000):
        // https://github.com/anza-xyz/agave/blob/v3.1.14/net-utils/src/lib.rs#L60 (--help wrongly says 1024-65535)
        // We assume 40 is enough because for its tests it uses a 25-port slice:
        // https://github.com/anza-xyz/agave/blob/v3.1.14/net-utils/src/sockets.rs#L47-L59
        static final int PORT_BLOCK_WIDTH = 40;
        // 10000-32000 stays above agave's default range and below the ephemeral range (32768+).
        static final int PORT_BLOCK_MIN = 10000;
        static final int PORT_BLOCK_MAX = 32000;
        private static final int PORT_BLOCK_COUNT = (PORT_BLOCK_MAX - PORT_BLOCK_MIN) / PORT_BLOCK_WIDTH;

        private boolean reset;
        private Integer rpcPort;
        private Integer gossipPort;
        private Integer faucetPort;
        private boolean dynamicPorts;
        private Path ledger;
        private final Map<PublicKey, Path> bpfPrograms = new HashMap<>();

        private Builder() { }

        public Builder reset() {
            reset = true;
            return this;
        }

        public Builder rpcPort(int rpcPort) {
            this.rpcPort = rpcPort;
            return this;
        }

        public Builder gossipPort(int gossipPort) {
            this.gossipPort = gossipPort;
            return this;
        }

        public Builder faucetPort(int faucetPort) {
            this.faucetPort = faucetPort;
            return this;
        }

        /// Assigns every validator port from a randomly chosen block of contiguous ports (RPC,
        /// websocket, faucet, gossip, and the dynamic port range), so concurrent validators on the
        /// same host are very unlikely to overlap. Ports set explicitly on the builder keep their
        /// value, and if a block is taken anyway [#start()] retries on a fresh one.
        public Builder dynamicPorts() {
            dynamicPorts = true;
            return this;
        }

        public Builder ledger(Path ledger) {
            this.ledger = ledger;
            return this;
        }

        public Builder bpfProgram(PublicKey programId, Path programFile) {
            bpfPrograms.put(programId, programFile);
            return this;
        }

        /**
         * Start a new validator instance from the current builder configuration.
         * @throws IllegalStateException The test validator couldn't be started.
         */
        public SolanaTestValidator start() {
            if (!dynamicPorts) {
                return launch();
            }
            // findAvailablePortBlock probes before launching, but probing only narrows the odds:
            // the probe sockets close before the validator binds, and another process on the host
            // can grab a port in that window. When that happens the validator exits during startup,
            // so launch on a fresh block and try again.
            for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
                var validator = launch();
                if (validator.confirmStarted()) {
                    return validator;
                }
                logger.warn(
                    "solana-test-validator exited during startup (attempt {}/{}), likely a port "
                        + "collision; retrying on a new port block",
                    attempt, MAX_START_ATTEMPTS);
                validator.close();
            }
            throw new IllegalStateException(
                "solana-test-validator failed to start after " + MAX_START_ATTEMPTS + " attempts");
        }

        private SolanaTestValidator launch() {
            var rpcPort = this.rpcPort;
            var gossipPort = this.gossipPort;
            var faucetPort = this.faucetPort;
            Integer blockBase = null;
            if (dynamicPorts) {
                blockBase = findAvailablePortBlock();
                if (rpcPort == null) {
                    rpcPort = blockBase;
                }
                if (faucetPort == null) {
                    faucetPort = blockBase + 2;
                }
                if (gossipPort == null) {
                    gossipPort = blockBase + 3;
                }
            }

            final var command = new ArrayList<String>();
            command.add("solana-test-validator");
            addPortArg("rpc", rpcPort, command);
            addPortArg("gossip", gossipPort, command);
            addPortArg("faucet", faucetPort, command);
            if (blockBase != null) {
                command.add("--dynamic-port-range");
                command.add((blockBase + 3) + "-" + (blockBase + PORT_BLOCK_WIDTH - 1));
            }
            if (reset) {
                command.add("--reset");
            }
            if (ledger != null) {
                command.add("--ledger");
                command.add(ledger.getFileName().toString());
            }
            bpfPrograms.forEach((programId, file) -> {
                command.add("--bpf-program");
                command.add(programId.toBase58());
                command.add(file.toAbsolutePath().toString());
            });
            // Merge stderr into stdout so the single reader drains both: a bind failure (printed to
            // stderr) can't block on a full pipe, and confirmStarted sees the process exit.
            var processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            if (ledger != null) {
                processBuilder.directory(ledger.getParent().toFile());
            }
            logger.debug("cmd: {}", command);
            Process process;
            try {
                process = processBuilder.start();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to start solana-test-validator process", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
            return new SolanaTestValidator(
                process,
                rpcPort != null ? rpcPort : DEFAULT_RPC_PORT,
                ledger != null ? ledger : Paths.get(DEFAULT_LEDGER_DIR_NAME)
            );
        }

        private static int findAvailablePortBlock() {
            for (int i = 0; i < MAX_PORT_ATTEMPTS; i++) {
                var base = PORT_BLOCK_MIN + ThreadLocalRandom.current().nextInt(PORT_BLOCK_COUNT) * PORT_BLOCK_WIDTH;
                if (isBlockAvailable(base)) {
                    return base;
                }
            }
            throw new IllegalStateException("Unable to find an available port block");
        }

        private static boolean isBlockAvailable(int base) {
            for (int port = base; port < base + PORT_BLOCK_WIDTH; port++) {
                if (!isPortAvailable(port)) {
                    return false;
                }
            }
            return true;
        }

        @SuppressWarnings("unused")
        private static boolean isPortAvailable(int port) {
            // The validator binds most of its node sockets over UDP, so a TCP-only probe misses
            // a live validator's tvu/tpu ports; check both protocols.
            try (var tcp = new ServerSocket(port); var udp = new DatagramSocket(port)) {
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private static void addPortArg(String portName, Integer assignedPort, List<String> args) {
            if (assignedPort != null) {
                args.add("--" + portName + "-port");
                args.add(assignedPort.toString());
            }
        }
    }
}
