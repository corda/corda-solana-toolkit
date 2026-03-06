# Solana Bridge Authority

These are the CorDapps for a "bridge authority" node which can be introduced into a Corda network that already uses the
[Corda Tokens SDK](https://github.com/corda/token-sdk) and which wishes to bridge their Corda assets onto Solana. The
existing network CorDapp does not need to be modified to do this. Instead, Corda participants simply "send" their
asset to be bridged to the bridge authority which takes care of minting the revelant Token-2022 token on Solana. It
also listens for redemption requests and automatically burns the tokens before returning the Corda asset back to the
original owner.

See this [sample](https://github.com/corda/samples-kotlin/tree/release/ent/4.14/Solana/bridge-authority) which uses this
against an existing CorDapp.
