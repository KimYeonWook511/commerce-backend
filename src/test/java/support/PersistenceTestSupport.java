package support;

public interface PersistenceTestSupport {

	CleanupOrder cleanupOrder();

	void deleteAllInBatch();

}
