package com.bizstudio.eth.migration.gnt;

import org.web3j.crypto.Credentials;
import org.web3j.golemnetworktoken.GolemNetworkToken;
import org.web3j.newgolemnetworktoken.NewGolemNetworkToken;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.gas.ContractGasProvider;

import java.math.BigInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GntMigration {
    private static final String OLD_TOKEN_ADDRESS = "0xa74476443119A942dE498590Fe1f2454d7D4aC0d";
    private static final String BATCHING_TOKEN_ADDRESS = "0xA7dfb33234098c66FdE44907e918DAD70a3f211c";
    private static final String DEPOSIT_ADDRESS = "0x98d3ca6528A2532Ffd9BDef50F92189568932570";
    private static final String NEW_TOKEN_ADDRESS = "0x7DD9c5Cba05E151C895FDe1CF355C9A1D5DA6429";

    private ExecutorService executorService = Executors.newFixedThreadPool(10);

    private final Web3j web3j;
    private final ContractGasProvider gasProvider;

    public GntMigration(Web3j web3j, ContractGasProvider gasProvider) {
        this.web3j = web3j;
        this.gasProvider = gasProvider;
    }

    public void migrate(String privateKey) throws Exception {
        Credentials credentials = Credentials.create(privateKey);
        String contractAddress = OLD_TOKEN_ADDRESS;


        System.out.println(String.format("Wallet address: %s",credentials.getAddress()));

        if (checkNoTokensWrapped(credentials, BATCHING_TOKEN_ADDRESS) || !checkNoTokensDeposited(credentials, DEPOSIT_ADDRESS)) {
            return;
        }


        GolemNetworkToken token = GolemNetworkToken.load(contractAddress,web3j,credentials,gasProvider);

        BigInteger balance = token.balanceOf(credentials.getAddress()).send();
        if (balance.compareTo(BigInteger.ZERO) == 0) {
            System.out.println("This address doesn't have any GNT tokens. Aborting");
            return;
        }

        System.out.println(String.format("%s GNT will be migrated to GNL",balance));
        TransactionReceipt tx = token.migrate(balance).send();
        executorService.submit(() -> {
            String hash = tx.getTransactionHash();
            try {

                System.out.println(String.format("[%s] Transactions sent. See details here https://etherscan.io/tx/$s",hash,hash));
                System.out.println(String.format("[%s] Waiting for confirmation...",hash));
                while (true) {
                    EthGetTransactionReceipt transactionReceipt = web3j
                            .ethGetTransactionReceipt(tx.getTransactionHash())
                            .send();
                    if (transactionReceipt.getResult() != null) {
                        break;
                    }
                    Thread.sleep(10000);
                }
                System.out.println(String.format("[%s] Transaction confirmed!",hash));
                checkMigrationResult(credentials, token.getContractAddress(), NEW_TOKEN_ADDRESS , balance);
            }catch (Exception e){
                System.out.println(String.format("[%s] %s",hash, e.getMessage()));
            }
        });

    }

    public boolean checkNoTokensWrapped(Credentials wallet, String contractAddress) throws Exception {
        if (isBalanceNotEmpty(wallet, contractAddress)) {
            System.out.println("There are wrapped GNT tokens on this address. Please use the migration UI");
            return false;
        }
        return true;
    }

    boolean checkNoTokensDeposited(Credentials wallet, String contractAddress) throws Exception {
        if (isBalanceNotEmpty(wallet, contractAddress)) {
            System.out.println("There are deposited GNT tokens on this address. Please use the migration UI");
            return false;
        }
        return true;
    }

    boolean isBalanceNotEmpty(Credentials credentials, String contractAddress) throws Exception {
        GolemNetworkToken token = GolemNetworkToken.load(contractAddress,web3j,credentials,gasProvider);
        BigInteger balance = token.balanceOf(credentials.getAddress()).send();
        return balance.compareTo(BigInteger.ZERO) > 0;
    }

    public void checkMigrationResult(Credentials credentials, String oldTokenAddress, String newTokenAddress, BigInteger expectedBalance) throws Exception {
        if (isBalanceNotEmpty(credentials, oldTokenAddress)) {
            System.out.println("Something went wrong, not all tokens were migrated");
        }
        NewGolemNetworkToken newToken = NewGolemNetworkToken.load(newTokenAddress,web3j, credentials, gasProvider);
        BigInteger balance = newToken.balanceOf(credentials.getAddress()).send();
        if (balance.compareTo(expectedBalance) != 0) {
            System.out.println(String.format("Something went wrong. Expected GML balance is %s, while actual is %s",expectedBalance.toString(), balance.toString()));
        }
    }
}
