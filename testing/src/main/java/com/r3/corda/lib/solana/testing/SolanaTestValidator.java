package com.r3.corda.lib.solana.testing;

import com.r3.corda.lib.solana.core.AccountManagement;
import com.r3.corda.lib.solana.core.SolanaClient;
import com.r3.corda.lib.solana.core.TokenManagement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.sava.core.accounts.PublicKey;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
        client.start();
        return this;
    }

    @Override
    public void close() {
        var interrupted = false;
        while (process.isAlive()) {
            client.close();
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

        public SolanaTestValidator start() throws IOException {
            var portsTaken = new HashSet<Integer>();
            var rpcPort = availableRpcAndWebsocketPorts(portsTaken);
            var gossipPort = availablePort(this.gossipPort, portsTaken);
            var faucetPort = availablePort(this.faucetPort, portsTaken);

            final var command = new ArrayList<String>();
            command.add("solana-test-validator");
            addPortArg("rpc", rpcPort, command);
            addPortArg("gossip", gossipPort, command);
            addPortArg("faucet", faucetPort, command);
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
            var processBuilder = new ProcessBuilder(command);
            if (ledger != null) {
                processBuilder.directory(ledger.getParent().toFile());
            }
            logger.debug("cmd: {}", command);
            var process = processBuilder.start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
            return new SolanaTestValidator(
                process,
                rpcPort != null ? rpcPort : DEFAULT_RPC_PORT,
                ledger != null ? ledger : Paths.get(DEFAULT_LEDGER_DIR_NAME)
            );
        }

        private Integer availablePort(Integer specifiedPort, Set<Integer> portsTaken) throws IOException {
            if (dynamicPorts && specifiedPort == null) {
                for (int i = 0; i < MAX_PORT_ATTEMPTS; i++) {
                    var port = availablePort();
                    if (portsTaken.add(port)) {
                        return port;
                    }
                }
                throw new IllegalStateException("Unable to find an available port");
            } else {
                return specifiedPort;
            }
        }

        private static int availablePort() throws IOException {
            try (var server = new ServerSocket(0)) {
                return server.getLocalPort();
            }
        }

        private static boolean isPortAvailable(int port) {
            try (var ignored = new ServerSocket(port)) {
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private Integer availableRpcAndWebsocketPorts(Set<Integer> portsTaken) throws IOException {
            if (dynamicPorts && rpcPort == null) {
                for (int i = 0; i < MAX_PORT_ATTEMPTS; i++) {
                    var rpcPort = availablePort();
                    var websocketPort = rpcPort + 1;
                    if (isPortAvailable(websocketPort)) {
                        portsTaken.add(rpcPort);
                        portsTaken.add(websocketPort);
                        return rpcPort;
                    }
                }
                throw new IllegalStateException("Unable to find available ports");
            } else {
                return rpcPort;
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
