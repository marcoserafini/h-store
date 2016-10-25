package edu.mit.benchmark.b2w.procedures;

import org.voltdb.ProcInfo;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

import static edu.mit.benchmark.b2w.B2WLoader.hashPartition;

@ProcInfo(
        partitionInfo = "CHECKOUT_PAYMENTS.partition_key: 0",
        singlePartition = true
    )
public class CreateCheckoutPayment extends VoltProcedure {
//    private static final Logger LOG = Logger.getLogger(VoltProcedure.class);
//    private static final LoggerBoolean debug = new LoggerBoolean();
//    private static final LoggerBoolean trace = new LoggerBoolean();
//    static {
//        LoggerUtil.setupLogging();
//        LoggerUtil.attachObserver(LOG, debug, trace);
//    }
    
    public final SQLStmt createCheckoutPaymentStmt = new SQLStmt(
            "INSERT INTO CHECKOUT_PAYMENTS (" +
                "partition_key, " +
                "checkoutId, " +
                "paymentOptionId, " +
                "paymentOptionType, " +
                "dueDays, " +
                "amount, " +
                "installmentQuantity, " +
                "interestAmount, " +
                "interestRate, " +
                "annualCET, " +
                "number, " +
                "criptoNumber, " +
                "holdersName, " +
                "securityCode, " +
                "expirationDate" +
            ") VALUES (" +
                "?, " +   // partition_key
                "?, " +   // checkoutId
                "?, " +   // paymentOptionId
                "?, " +   // paymentOptionType
                "?, " +   // dueDays
                "?, " +   // amount
                "?, " +   // installmentQuantity
                "?, " +   // interestAmount
                "?, " +   // interestRate
                "?, " +   // annualCET
                "?, " +   // number
                "?, " +   // criptoNumber
                "?, " +   // holdersName
                "?, " +   // securityCode
                "?"   +   // expirationDate
            ");");
    
    public VoltTable[] run(int partition_key, String checkout_id, String cart_id, String paymentOptionId, String paymentOptionType, int dueDays, double amount,
            int installmentQuantity, double interestAmount, int interestRate, int annualCET, String number, String criptoNumber, String holdersName, 
            String securityCode, String expirationDate){
        voltQueueSQL(createCheckoutPaymentStmt,
                hashPartition(checkout_id),
                checkout_id,
                paymentOptionId,
                paymentOptionType,
                dueDays,
                amount,
                installmentQuantity,
                interestAmount,
                interestRate,
                annualCET,
                number,
                criptoNumber,
                holdersName,
                securityCode,
                expirationDate);
        
        return voltExecuteSQL(true);
    }

}
