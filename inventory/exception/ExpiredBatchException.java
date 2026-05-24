package inventory.exception;

public class ExpiredBatchException extends RuntimeException {
    public ExpiredBatchException(String batchId) {
        super("Batch has expired: " + batchId);
    }
}
