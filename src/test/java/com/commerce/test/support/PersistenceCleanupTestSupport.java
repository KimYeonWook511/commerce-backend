package com.commerce.test.support;

import java.util.Arrays;
import java.util.Comparator;

import org.springframework.boot.test.context.TestComponent;

@TestComponent
public class PersistenceCleanupTestSupport {

	public void deleteAllInBatch(PersistenceTestSupport... supports) {
		Arrays.stream(supports)
			.sorted(Comparator.comparingInt(support -> support.cleanupOrder().value()))
			.forEach(PersistenceTestSupport::deleteAllInBatch);
	}
}
