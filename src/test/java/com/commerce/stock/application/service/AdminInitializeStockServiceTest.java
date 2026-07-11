package com.commerce.stock.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.stock.application.dto.AdminStockCreateCommand;
import com.commerce.stock.application.dto.AdminStockResult;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.StockAdjustmentReason;
import com.commerce.stock.domain.StockHistory;
import com.commerce.stock.domain.repository.StockHistoryRepository;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;

@ExtendWith(MockitoExtension.class)
class AdminInitializeStockServiceTest {

	@Mock
	private StockRepository stockRepository;

	@Mock
	private StockHistoryRepository stockHistoryRepository;

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private AdminInitializeStockService adminInitializeStockService;

	@DisplayName("관리자 초기 재고 생성은 재고와 양수 이력을 저장한다")
	@Test
	void createInitialStock_whenValid_saveStockAndHistory() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		AdminStockCreateCommand command = AdminStockCreateCommand.builder()
			.productId(1L)
			.quantity(10)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(productRepository.findNotDeletedProduct(1L)).willReturn(Optional.of(product));
		given(stockRepository.findByProductId(1L)).willReturn(Optional.empty());
		given(stockRepository.save(any(Stock.class))).willAnswer(invocation -> {
			Stock stock = invocation.getArgument(0);
			ReflectionTestUtils.setField(stock, "id", 100L);
			return stock;
		});

		// when
		AdminStockResult result = adminInitializeStockService.createInitialStock(command);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.getStockId()).isEqualTo(100L);
		assertThat(result.getQuantity()).isEqualTo(10);

		ArgumentCaptor<StockHistory> historyCaptor = ArgumentCaptor.forClass(StockHistory.class);
		then(stockHistoryRepository).should().save(historyCaptor.capture());
		StockHistory history = historyCaptor.getValue();
		assertThat(history.getStockId()).isEqualTo(100L);
		assertThat(history.getQuantityChange()).isEqualTo(10);
		assertThat(history.getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
		assertThat(history.getAdminMemberId()).isEqualTo(10L);
	}

	@DisplayName("초기 재고 생성 대상 상품이 없으면 예외가 발생한다")
	@Test
	void createInitialStock_whenProductNotFound_throwException() {
		// given
		AdminStockCreateCommand command = AdminStockCreateCommand.builder()
			.productId(1L)
			.quantity(10)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(productRepository.findNotDeletedProduct(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> adminInitializeStockService.createInitialStock(command))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException) exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

	@DisplayName("관리자 초기 재고 수량이 0이면 0 수량 이력을 함께 저장한다")
	@Test
	void createInitialStock_whenQuantityIsZero_saveStockAndZeroHistory() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		AdminStockCreateCommand command = AdminStockCreateCommand.builder()
			.productId(1L)
			.quantity(0)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(productRepository.findNotDeletedProduct(1L)).willReturn(Optional.of(product));
		given(stockRepository.findByProductId(1L)).willReturn(Optional.empty());
		given(stockRepository.save(any(Stock.class))).willAnswer(invocation -> {
			Stock stock = invocation.getArgument(0);
			ReflectionTestUtils.setField(stock, "id", 100L);
			return stock;
		});

		// when
		AdminStockResult result = adminInitializeStockService.createInitialStock(command);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.getStockId()).isEqualTo(100L);
		assertThat(result.getQuantity()).isZero();

		ArgumentCaptor<StockHistory> historyCaptor = ArgumentCaptor.forClass(StockHistory.class);
		then(stockHistoryRepository).should().save(historyCaptor.capture());
		StockHistory history = historyCaptor.getValue();
		assertThat(history.getStockId()).isEqualTo(100L);
		assertThat(history.getQuantityChange()).isZero();
		assertThat(history.getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
		assertThat(history.getAdminMemberId()).isEqualTo(10L);
	}

	@DisplayName("이미 재고가 있으면 초기 재고 생성에 실패한다")
	@Test
	void createInitialStock_whenStockAlreadyExists_throwException() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 10);
		AdminStockCreateCommand command = AdminStockCreateCommand.builder()
			.productId(1L)
			.quantity(10)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(productRepository.findNotDeletedProduct(1L)).willReturn(Optional.of(product));
		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));

		// when & then
		assertThatThrownBy(() -> adminInitializeStockService.createInitialStock(command))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.STOCK_ALREADY_EXISTS);
			});
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.create(product.getId(), quantity);
	}

	private Product createProduct(Long id, String name, int price) {
		Product product = Product.create(name, price, null, null, ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}
}
