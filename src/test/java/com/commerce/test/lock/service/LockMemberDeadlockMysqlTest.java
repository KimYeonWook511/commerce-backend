package com.commerce.test.lock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.commerce.test.lock.domain.LockMember;
import com.commerce.test.lock.repository.LockMemberRepository;
import support.TestcontainersSupport;

import jakarta.persistence.EntityManager;

@Tag("test")
@SpringBootTest
@ActiveProfiles("test")
class LockMemberDeadlockMysqlTest {

	@Autowired
	private LockMemberRepository lockMemberRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManager entityManager;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@AfterEach
	void tearDown() {
		lockMemberRepository.deleteAllInBatch();
	}

	@DisplayName("IN 목록 크기에 따라 옵티마이저가 선택하는 인덱스가 달라질 수 있다")
	@Test
	void explainOptimizerPath_whenInListSizeDiffers_selectDifferentIndex() {
		createMembers(10);

		List<LockMember> members = findMembers();
		List<Long> idsOrdered = members.stream()
			.map(LockMember::getId)
			.toList();

		List<Long> smallIds = idsOrdered.subList(0, 2);
		List<Long> largeIds = idsOrdered;

		String smallKey = explainKeyForIds(smallIds);
		String largeKey = explainKeyForIds(largeIds);

		assertThat(smallKey).isNotBlank();
		assertThat(largeKey).isNotBlank();
		System.out.println("[explain-index] smallKey=" + smallKey + ", largeKey=" + largeKey);
	}

	@DisplayName("PK 인덱스와 보조 인덱스를 사용하는 조회 쿼리를 사용하여 락 경합을 유도한다")
	@Test
	void lockByIdWithPrimaryAndSecondaryForcedIndex_whenMixedPaths_holdLocks() throws Exception {
		createMembers(5);
		int attempts = 100;
		int errorCount = 0;

		ConcurrentLinkedQueue<Throwable> error = new ConcurrentLinkedQueue<>();
		for (int attempt = 1; attempt <= attempts; attempt++) {
			// given
			List<LockMember> members = findMembers();
			List<Long> idsOrdered = members.stream()
				.map(LockMember::getId)
				.toList();

			TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
			CountDownLatch readyLatch = new CountDownLatch(2);
			CountDownLatch startLatch = new CountDownLatch(1);
			CountDownLatch doneLatch = new CountDownLatch(2);
			ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				executor.submit(() -> {
					readyLatch.countDown();
					try {
						startLatch.await();
						transactionTemplate.executeWithoutResult(status -> lockByIdsWithPrimaryIndex(idsOrdered));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (Throwable t) {
						errors.add(t);
					} finally {
						doneLatch.countDown();
					}
				});

				executor.submit(() -> {
					readyLatch.countDown();
					try {
						startLatch.await();
						transactionTemplate.executeWithoutResult(status -> lockByIdsWithSecondaryIndex(idsOrdered));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (Throwable t) {
						errors.add(t);
					} finally {
						doneLatch.countDown();
					}
				});

				readyLatch.await();
				startLatch.countDown();
				doneLatch.await(5, TimeUnit.SECONDS);
			} finally {
				executor.shutdown();
				executor.awaitTermination(5, TimeUnit.SECONDS);
			}

			if (!errors.isEmpty()) {
				errorCount++;
				for (Throwable t : errors) {
					error.add(t);
				}
			}
		}

		for (Throwable t : error) {
			System.out.println(t.getMessage());
		}
		System.out.println("[mixed-index] attempts=" + attempts + ", errorCount=" + errorCount);
	}

	@DisplayName("PK 인덱스를 사용하는 조회 쿼리는 IN 역순이어도 데드락이 발생하지 않을 수 있다")
	@Test
	void lockByIdWithPrimaryIndex_whenOppositeOrder_deadlockMayNotOccur() throws Exception {
		createMembers(5);
		int attempts = 1000;
		int errorCount = 0;

		ConcurrentLinkedQueue<Throwable> error = new ConcurrentLinkedQueue<>();
		for (int attempt = 1; attempt <= attempts; attempt++) {
			// given
			List<LockMember> members = findMembers();
			List<Long> idsOrdered = members.stream()
				.map(LockMember::getId)
				.toList();
			List<Long> idsReversed = new ArrayList<>(idsOrdered);
			Collections.reverse(idsReversed);

			TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
			CountDownLatch readyLatch = new CountDownLatch(2);
			CountDownLatch startLatch = new CountDownLatch(1);
			CountDownLatch doneLatch = new CountDownLatch(2);
			ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				executor.submit(() -> {
					readyLatch.countDown();
					try {
						startLatch.await();
						transactionTemplate.executeWithoutResult(status -> lockByIdsWithPrimaryIndex(idsOrdered));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (Throwable t) {
						errors.add(t);
					} finally {
						doneLatch.countDown();
					}
				});

				executor.submit(() -> {
					readyLatch.countDown();
					try {
						startLatch.await();
						transactionTemplate.executeWithoutResult(status -> lockByIdsWithPrimaryIndex(idsReversed));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (Throwable t) {
						errors.add(t);
					} finally {
						doneLatch.countDown();
					}
				});

				readyLatch.await();
				startLatch.countDown();
				doneLatch.await(5, TimeUnit.SECONDS);
			} finally {
				executor.shutdown();
				executor.awaitTermination(5, TimeUnit.SECONDS);
			}

			if (!errors.isEmpty()) {
				errorCount++;
				for (Throwable t : errors) {
					error.add(t);
				}
			}
		}

		for (Throwable t : error) {
			System.out.println(t.getMessage());
		}
		System.out.println("[primary-opposite-order] attempts=" + attempts + ", errorCount=" + errorCount);
	}

	private void lockByIdsInOrder(List<Long> ids) {
		String inClause = ids.stream()
			.map(String::valueOf)
			.reduce((left, right) -> left + "," + right)
			.orElse("");
		String sql = "select * from lock_member where id in (" + inClause + ") for update";
		entityManager.createNativeQuery(sql, LockMember.class).getResultList();
	}

	private void lockByNamesInOrder(List<String> names) {
		String inClause = names.stream()
			.map(name -> "'" + name + "'")
			.reduce((left, right) -> left + "," + right)
			.orElse("");
		String sql = "select * from lock_member where name in (" + inClause + ") for update";
		entityManager.createNativeQuery(sql, LockMember.class).getResultList();
	}

	private void lockByIdsWithPrimaryIndex(List<Long> ids) {
		String inClause = ids.stream()
			.map(String::valueOf)
			.reduce((left, right) -> left + "," + right)
			.orElse("");
		String sql = "select * from lock_member force index (PRIMARY) where id in ("
			+ inClause + ") for update";
		entityManager.createNativeQuery(sql, LockMember.class).getResultList();
	}

	private void lockByIdsWithSecondaryIndex(List<Long> ids) {
		String inClause = ids.stream()
			.map(String::valueOf)
			.reduce((left, right) -> left + "," + right)
			.orElse("");
		String sql = "select * from lock_member force index (idx_lock_member_name) where id in ("
			+ inClause + ") for update";
		entityManager.createNativeQuery(sql, LockMember.class).getResultList();
	}

	private String explainKeyForIds(List<Long> ids) {
		String inClause = ids.stream()
			.map(String::valueOf)
			.reduce((left, right) -> left + "," + right)
			.orElse("");
		String sql = "explain select * from lock_member where id in (" + inClause + ") for update";
		List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
		if (rows.isEmpty()) {
			return "";
		}
		Object key = rows.get(0)[6];
		return key == null ? "" : key.toString();
	}

	private List<LockMember> createMembers(int cnt) {
		List<LockMember> members = new LinkedList<>();
		for (int index = cnt; index >= 1; index--) {
			String name = String.format("NAME_%05d", index);
			members.add(lockMemberRepository.save(new LockMember(name)));
		}
		return members;
	}

	private List<LockMember> findMembers() {
		return entityManager.createQuery("select m from LockMember m", LockMember.class).getResultList();
	}

	private void sleepSeconds(int seconds) {
		try {
			TimeUnit.SECONDS.sleep(seconds);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
