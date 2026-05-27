package com.commerce.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.common.jpa.JpaConfig;

import jakarta.persistence.EntityManager;

@DataJpaTest
@Import({JpaConfig.class, CartItemRepositoryAdapter.class})
@ActiveProfiles("test")
class CartItemRepositoryAdapterTest {

	private static final Long MEMBER_ID = 1L;
	private static final Long OTHER_MEMBER_ID = 2L;
	private static final Long PRODUCT_ID = 100L;
	private static final Long OTHER_PRODUCT_ID = 200L;

	@Autowired
	private CartItemRepository cartItemRepository;

	@Autowired
	private EntityManager entityManager;

	@DisplayName("save 후 findByMemberIdAndProductId로 같은 항목을 조회한다")
	@Test
	void save_thenFindByMemberIdAndProductId_returnsSavedItem() {
		// given
		CartItem saved = cartItemRepository.save(CartItem.create(MEMBER_ID, PRODUCT_ID, 3));
		entityManager.flush();
		entityManager.clear();

		// when
		Optional<CartItem> found = cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID);

		// then
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(saved.getId());
		assertThat(found.get().getQuantity()).isEqualTo(3);
	}

	@DisplayName("findAllByMemberIdOrderByCreatedAtDesc는 다른 회원의 cart 항목을 제외한다")
	@Test
	void findAllByMemberIdOrderByCreatedAtDesc_whenOtherMemberHasItems_excludesOtherMembers() {
		// given
		CartItem mine = cartItemRepository.save(CartItem.create(MEMBER_ID, PRODUCT_ID, 1));
		cartItemRepository.save(CartItem.create(MEMBER_ID, OTHER_PRODUCT_ID, 2));
		cartItemRepository.save(CartItem.create(OTHER_MEMBER_ID, PRODUCT_ID, 5));
		entityManager.flush();
		entityManager.clear();

		// when
		List<CartItem> results = cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID);

		// then
		assertThat(results).hasSize(2);
		assertThat(results)
			.extracting(CartItem::getMemberId)
			.containsOnly(MEMBER_ID);
		assertThat(results)
			.extracting(CartItem::getProductId)
			.containsExactlyInAnyOrder(PRODUCT_ID, OTHER_PRODUCT_ID);
		assertThat(results)
			.extracting(CartItem::getId)
			.contains(mine.getId());
	}

	@DisplayName("findAllByMemberIdOrderByCreatedAtDesc는 createdAt 내림차순으로 반환한다 (결정 6-3)")
	@Test
	void findAllByMemberIdOrderByCreatedAtDesc_sortsByCreatedAtDesc() {
		// given — createdAt을 명시 주입하여 DB DATETIME 해상도/CI clock skew에 의존하지 않는 결정론적 검증
		CartItem first = cartItemRepository.save(CartItem.create(MEMBER_ID, PRODUCT_ID, 1));
		CartItem second = cartItemRepository.save(CartItem.create(MEMBER_ID, OTHER_PRODUCT_ID, 2));
		entityManager.flush();
		LocalDateTime now = LocalDateTime.now();
		ReflectionTestUtils.setField(first, "createdAt", now.minusSeconds(60));
		ReflectionTestUtils.setField(second, "createdAt", now);
		entityManager.flush();
		entityManager.clear();

		// when
		List<CartItem> results = cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID);

		// then
		assertThat(results).hasSize(2);
		assertThat(results.get(0).getId()).isEqualTo(second.getId());
		assertThat(results.get(1).getId()).isEqualTo(first.getId());
	}

	@DisplayName("같은 (memberId, productId)로 두 번 저장하면 UNIQUE 위반으로 예외가 발생한다")
	@Test
	void save_whenDuplicateMemberAndProduct_throwsDataIntegrityViolation() {
		// given
		cartItemRepository.save(CartItem.create(MEMBER_ID, PRODUCT_ID, 1));
		entityManager.flush();

		// when & then
		assertThatThrownBy(() -> {
			cartItemRepository.save(CartItem.create(MEMBER_ID, PRODUCT_ID, 2));
			entityManager.flush();
		}).isInstanceOf(DataIntegrityViolationException.class);
	}

	@DisplayName("delete(entity)는 해당 entity만 삭제하고 다른 회원/상품 항목은 보존한다")
	@Test
	void delete_whenEntityProvided_removesSingleItem() {
		// given
		CartItem mine = cartItemRepository.save(CartItem.create(MEMBER_ID, PRODUCT_ID, 1));
		cartItemRepository.save(CartItem.create(MEMBER_ID, OTHER_PRODUCT_ID, 2));
		cartItemRepository.save(CartItem.create(OTHER_MEMBER_ID, PRODUCT_ID, 3));
		entityManager.flush();

		// when
		cartItemRepository.delete(mine);
		entityManager.flush();
		entityManager.clear();

		// then
		assertThat(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).isEmpty();
		assertThat(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, OTHER_PRODUCT_ID)).isPresent();
		assertThat(cartItemRepository.findByMemberIdAndProductId(OTHER_MEMBER_ID, PRODUCT_ID)).isPresent();
	}

	@DisplayName("deleteByMemberIdAndProductIdIn은 같은 회원의 지정 상품 항목만 일괄 삭제한다")
	@Test
	void deleteByMemberIdAndProductIdIn_whenItemsExist_removesOnlyMatchingProducts() {
		// given
		cartItemRepository.save(CartItem.create(MEMBER_ID, PRODUCT_ID, 1));
		cartItemRepository.save(CartItem.create(MEMBER_ID, OTHER_PRODUCT_ID, 2));
		cartItemRepository.save(CartItem.create(MEMBER_ID, 300L, 3));
		cartItemRepository.save(CartItem.create(OTHER_MEMBER_ID, PRODUCT_ID, 5));
		entityManager.flush();
		entityManager.clear();

		// when
		cartItemRepository.deleteByMemberIdAndProductIdIn(MEMBER_ID, List.of(PRODUCT_ID, OTHER_PRODUCT_ID));
		entityManager.flush();
		entityManager.clear();

		// then
		List<CartItem> remaining = cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID);
		assertThat(remaining)
			.extracting(CartItem::getProductId)
			.containsExactly(300L);
		assertThat(cartItemRepository.findByMemberIdAndProductId(OTHER_MEMBER_ID, PRODUCT_ID)).isPresent();
	}
}
