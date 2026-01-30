package com.r3.corda.lib.solana.core;

import com.r3.corda.lib.solana.testing.SolanaTestValidatorExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.rpc.json.http.response.Lamports;

import java.math.BigDecimal;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@ExtendWith(SolanaTestValidatorExtension.class)
public class SolanaClientJavaTest {
    @Test
    public void valid_calls(SolanaClient client) throws ExecutionException, InterruptedException {
        var account = SolanaUtils.randomSigner().publicKey();
        var signature = client.call("requestAirdrop", String.class, account, 1_000_000_000L);
        client.asyncConfirm(signature).get();
        assertThat(client.call("getBalance", Lamports.class, account).lamports()).isEqualTo(1_000_000_000);
    }

    @Test
    public void call_on_unknown_method(SolanaClient client) {
        var account = SolanaUtils.randomSigner().publicKey();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> client.call("requestAirDrop", String.class, account, 1_000_000_000L))
            .withMessage("requestAirDrop not an RPC method");
    }

    @Test
    public void call_with_invalid_parameter_type(SolanaClient client) {
        var account = SolanaUtils.randomSigner().publicKey();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> client.call("requestAirdrop", String.class, account, 1_000_000))
            .withMessageContaining("No matching overload found");
    }

    @Test
    public void call_with_incorrect_return_type(SolanaClient client) {
        var account = SolanaUtils.randomSigner().publicKey();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> client.call("requestAirdrop", BigDecimal.class, account, 1_000_000_000L))
            .withMessage("requestAirdrop returns a java.lang.String, not a java.math.BigDecimal");
    }
}
