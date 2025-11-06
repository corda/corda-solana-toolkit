# Stock Bridging To Solana Sample

This demo shows how to bridge Corda states built with [Token SDK]((https://training.corda.net/libraries/tokens-sdk/))
to the Solana network via an additional participant, the Bridge Authority, and a Solana Notary.
As a sample application, the unmodified [Stock CorDapp](https://github.com/corda/samples-kotlin/tree/release/4.12/Tokens/stockpaydividend)
is deployed to several parties (the same party set as in the original Stock CorDapp demo).

### Parties

Stock CorDapp assumes there are 4 parties:
* **WayneCo** - creates the stock state.
* **Shareholder** - owns the stock and bridge shares to Solana Network.
* **Bank** - issues fiat tokens.
* **Observer** - monitors all the stocks by keeping a copy of transactions whenever a stock is created or updated.

Bridging activities requires additional parties:
* **Bridge Authority** - performs bridging by running "Corda-Solana-Toolkit" Cordapp
* **Solana Notary** - ensures tokens are created on Solana Network

## Usage
### Running the nodes with Solana Dev Net

Open a terminal and go to the project root directory and type: (to deploy the nodes using bootstrapper)
```bash
./gradlew samples:stockpaydividend-solana-bridge:deployNodes
```
Create and fund Solana accounts for `Wayne Company` and the `Bridge Authority`, create `Mint` and `TokenAccount`:
```bash
./gradlew samples:stockpaydividend-solana-bridge:installSolanaBridgeConfig
```
Then type: (to run the nodes)
```bash
./samples/stockpaydividend-solana-bridge/build/nodes/runnodes
```

### Running with Solana Local Validator

TODO the guide requires access to Corda Enterprise source code (to build and access Corda Solana Aggregator jar and key) - this will change.

Start a local solana validator with the notary program deployed you need to have access to Corda Enterprise `admin-cli` JAR and Notary key.
Run `./runSolana.sh <PATH_TO_CORDA_ENTERPRISE>` script. Below scripts invocations assume you have source code of Corda Enterprise as sibling directory:
The script build Corda Solana program and starts a local solana validator:
```bash
./runSolana.sh "../../../enterprise"
```
The script creates the new network and adds notary key to the Corda program:
```bash
./addNotaryToSolana.sh "../../../enterprise"
```
The expected output is:
```
Requesting airdrop of 10 SOL

Signature: Qx2tToY7XCMTCNxTUamW74K2G5a3m61deYoZspxYLCKt14UBismHzVu8HgCkus5BZRfBcfP7inzdLe6BnXJsWzr

10 SOL
Initializing notary program SolanaAccount{'DevPb8sxMzZCzXW3dAKM4BnRSoEJ6PrCrVTF7Pku5KUL'}...
✓ Notary program initialized successfully with DevAD5S5AFhTTCmrD8Jg58bDhbZabSzth7Bu6rG4HFYo as admin.
Creating network ...
✓ Corda network creation successful - network ID: 0
Authorizing notary Dev7chG99tLCAny3PNYmBdyhaKEVcZnSTp3p1mKVb5m5...
✓ Notary authorized successfully: Dev7chG99tLCAny3PNYmBdyhaKEVcZnSTp3p1mKVb5m5
Network ID: 0
  1. Notary: Dev7chG99tLCAny3PNYmBdyhaKEVcZnSTp3p1mKVb5m5
```

Follow the steps from Running on Solana Dev Net.
The only change is to replace Solana DevNet URL with the local validator url ( `"rpcUrl" = "http://localhost:8899"`)
in the notary config entry of `depolyNodes` task.


## Interacting with the nodes

When started via the command line, each node will display an interactive shell:

    Welcome to the Corda interactive shell.
    Useful commands include 'help' to see what is available, and 'bye' to shut down the node.

    Thu Oct 23 11:54:18 BST 2025>>>

You can use this shell to interact with your node.

### Running the nodes

These steps focus on bridging activities and not on dividend as in [Stock Cordapp](https://github.com/corda/samples-kotlin/tree/release/4.12/Tokens/stockpaydividend) usage.

##### 1. IssueStock - Stock Issuer
WayneCo creates a StockState and issues some stock tokens associated to the created StockState.
>On company WayneCo's node, execute <br>`start CreateAndIssueStock symbol: TEST, name: "Test Stock", currency: USD, price: 7.4, issueVol: 2000, notary: "O=Notary Service,L=Zurich,C=CH", linearId: 6116560b-c78e-4e13-871d-d666a5d032a3`

`linearID` `6116560b-c78e-4e13-871d-d666a5d032a3` matches the configuration in Bridge Authority. TODO providing linearID will not be needed soon.

##### 2. MoveStock - Stock Issuer
WayneCo transfers some stock tokens to the Shareholder.
>On company WayneCo's node, execute <br>`start MoveStock symbol: TEST, quantity: 100, recipient: Shareholder`

Now at the Shareholder's terminal, we can see that it received 100 stock tokens:
>On shareholder node, execute <br>`start GetStockBalance symbol: TEST`

##### 3. Bridge To Solana - Stock Issuer
Shareholder transfers some stock tokens to the Bridge Authority.
>On shareholder node, execute <br>`start MoveStock symbol: TEST, quantity: 60, recipient: "Bridge Authority"`

Now at the Bridge Authority's terminal, we can see that it received 100 stock tokens:
>On Bridge Authority node, execute <br>`start GetStockBalance symbol: TEST`

Bridge Authority performs asset bridging to Solana.
You can check the current balance on Solana Account has increased:

```bash
spl-token balance --address $TOKEN_ACCOUNT
spl-token display $TOKEN_ACCOUNT
```
