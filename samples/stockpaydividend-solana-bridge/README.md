# Stock Bridging To Solana Sample

This demo aims to demonstrate how to bridge Corda states built with [TokenSDK](https://training.corda.net/libraries/tokens-sdk/) to Solana Network via additional participant "Bridging Authority"
and Solana Notary.
An unmodified [Stock Cordapp](https://github.com/corda/samples-kotlin/tree/release/4.12/Tokens/stockpaydividend)
is deployed to several parties (the same as in [Stock Cordapp](https://github.com/corda/samples-kotlin/tree/release/4.12/Tokens/stockpaydividend) demo).

### Parties

[Stock Cordapp](https://github.com/corda/samples-kotlin/tree/release/4.12/Tokens/stockpaydividend) assumes there are 4 parties:
* **WayneCo** - creates the stock state.
* **Shareholder** - owns the stock and bridge shares to Solana Network.
* **Bank** - issues fiat tokens.
* **Observer** - monitors all the stocks by keeping a copy of transactions whenever a stock is created or updated.

New network participants:
* **Bridging Authority** - performs bridging by running "Corda-Solana-Toolkit" Cordapp
* **Solana Notary** - ensures states are created on Solana Network

## Usage
### Running the nodes

Open a terminal and go to the project root directory and type: (to deploy the nodes using bootstrapper)
```
 ./gradlew clean samples:stockpaydividend-solana-bridge:installSolanaNotaryDevKey samples:stockpaydividend-solana-bridge:installSolanaBridgeConfig
```
Then type: (to run the nodes)
```
./samples/stockpaydividend-solana-bridge/build/nodes/runnodes
```

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
>On company WayneCo's node, execute <br>`start IssueStock symbol: TEST, name: "Stock, SP500", currency: USD, price: 7.4, issueVol: 500, notary: "O=Notary Service, L=London, C=GB"`

##### 2. MoveStock - Stock Issuer
WayneCo transfers some stock tokens to the Shareholder.
>On company WayneCo's node, execute <br>`start MoveStock symbol: TEST, quantity: 100, recipient: Shareholder`

Now at the Shareholder's terminal, we can see that it received 100 stock tokens:
>On shareholder node, execute <br>`start GetStockBalance symbol: TEST`

##### 3. Bridge To Solana - Stock Issuer
Shareholder transfers some stock tokens to the Bridging Authority.
>On company WayneCo's node, execute <br>`start MoveStock symbol: TEST, quantity: 60, recipient: "Bridging Authority"`

Now at the Bridging Authority's terminal, we can see that it received 100 stock tokens:
>On Bridging Authority node, execute <br>`start GetStockBalance symbol: TEST`

Bridging Authority does not perform asset bridging to Solana yet.
