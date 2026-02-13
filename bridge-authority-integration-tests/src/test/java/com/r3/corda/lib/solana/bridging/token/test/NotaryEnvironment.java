package com.r3.corda.lib.solana.bridging.token.test;

import com.r3.corda.lib.solana.core.*;
import com.r3.corda.lib.solana.testing.SolanaTestValidator;
import net.corda.solana.notary.client.CordaNotary;
import net.corda.solana.notary.client.instructions.AuthorizeNotary;
import net.corda.solana.notary.client.instructions.CreateNetwork;
import net.corda.solana.notary.client.instructions.Initialize;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

// TODO Copied from solana-notary repo. Use that one when it's updated to use the new SolanaClient.
public class NotaryEnvironment {
    private final SolanaClient client;
    private final Signer admin;
    private short nextNetworkId;

    public NotaryEnvironment(SolanaClient client) {
        this.client = Objects.requireNonNull(client);
        admin = SolanaUtils.randomSigner();
    }

    public SolanaClient client() {
        return client;
    }

    public Signer admin() {
        return admin;
    }

    public short nextNetworkId() {
        return nextNetworkId;
    }

    public void initializeProgram() throws SolanaTransactionException, SolanaTransactionExpiredException {
        new AccountManagement(client).airdropSol(admin.publicKey(), 10);
        client.sendAndConfirm(
            (builder) -> builder.createTransaction(Initialize.instruction(admin.publicKey())),
            admin
        );
    }

    public short createNewCordaNetwork() throws SolanaTransactionException, SolanaTransactionExpiredException {
        var networkId = nextNetworkId++;
        client.sendAndConfirm(
            (builder) -> builder.createTransaction(CreateNetwork.instruction(admin.publicKey(), networkId)),
            admin
        );
        return networkId;
    }

    /**
     * Authorise the given notary key onto an existing network.
     */
    public void addCordaNotary(short networkId, PublicKey cordaNotary)
        throws SolanaTransactionException, SolanaTransactionExpiredException
    {
        client.sendAndConfirm(
            (builder) -> builder.createTransaction(AuthorizeNotary.instruction(cordaNotary, admin.publicKey(), networkId)),
            admin
        );
    }

    /**
     * Authorise the given notary key onto a new network.
     */
    public short addCordaNotary(PublicKey cordaNotary)
        throws SolanaTransactionException, SolanaTransactionExpiredException
    {
        var networkId = createNewCordaNetwork();
        addCordaNotary(networkId, cordaNotary);
        return networkId;
    }

    public static SolanaTestValidator.Builder addNotaryProgram(SolanaTestValidator.Builder builder) {
        Path programFile;
        try (var stream = Objects.requireNonNull(
            NotaryEnvironment.class.getResourceAsStream("/net/corda/solana/notary/program/corda_notary.so")
        )) {
            programFile = Files.createTempFile("corda_notary", ".so");
            Files.copy(stream, programFile, REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        builder.bpfProgram(CordaNotary.PROGRAM_ID, programFile);
        return builder;
    }
}
