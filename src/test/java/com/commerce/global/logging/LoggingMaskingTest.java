package com.commerce.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class LoggingMaskingTest {

	/**
	 * logback-spring.xml의 MASK_PATTERN property와 동일한 정규식 표현.
	 * '와 }는 logback %replace 옵션 파서가 종료 문자로 해석하므로 유니코드 이스케이프(', })로 적는다.
	 * Java 정규식 엔진이 이를 동일 문자로 해석하므로 의미가 같다.
	 */
	private static final String CONSOLE_MASK_PATTERN =
		"(?i)(password|accessToken|refreshToken|token)([\"\\u0027\\s]*[:=][\"\\u0027\\s]*)([^\"\\u0027\\s,\\u007D]+)";

	@DisplayName("password와 token 키워드가 메시지 필드에서 ***로 치환된다")
	@Test
	void writeTo_masksPasswordAndToken() throws Exception {
		String message = serializeMessage("login attempt password=hunter2 token=abc123");

		assertThat(message).contains("password=***");
		assertThat(message).contains("token=***");
		assertThat(message).doesNotContain("hunter2");
		assertThat(message).doesNotContain("abc123");
	}

	@DisplayName("JSON 본문 안의 accessToken과 refreshToken이 모두 마스킹된다")
	@Test
	void writeTo_masksAccessTokenAndRefreshToken() throws Exception {
		String message = serializeMessage("json body: {\"accessToken\":\"xyz\", \"refreshToken\":\"qqq\"}");

		assertThat(message).contains("\"accessToken\":\"***\"");
		assertThat(message).contains("\"refreshToken\":\"***\"");
		assertThat(message).doesNotContain("xyz");
		assertThat(message).doesNotContain("qqq");
	}

	@DisplayName("마스킹 대상 키워드가 없는 메시지는 원본 그대로 직렬화된다")
	@Test
	void writeTo_keepsOriginalWhenNoSensitiveKeyword() throws Exception {
		String original = "order created orderId=42 userId=7";

		String message = serializeMessage(original);

		assertThat(message).isEqualTo(original);
	}

	@DisplayName("provider mask()와 콘솔 마스킹 정규식이 동일한 매칭 결과를 낸다")
	@Test
	void maskPatternsAreEquivalent() {
		// 두 정규식은 표현이 다르다(Java 상수는 ', }를 그대로, 콘솔 상수는 logback %replace 파서 회피를 위해 ', }로).
		// 한쪽만 수정되어 의미가 어긋나면 본 테스트가 fail해야 한다.
		Pattern consolePattern = Pattern.compile(CONSOLE_MASK_PATTERN);

		String[] samples = {
			"login attempt password=hunter2 token=abc123",
			"{\"accessToken\":\"xyz\", \"refreshToken\":\"qqq\"}",
			"order created orderId=42 userId=7",
			"password=p1, token=t1, accessToken=a1, refreshToken=r1",
			"no sensitive data"
		};

		for (String sample : samples) {
			String providerMasked = MaskingMessageJsonProvider.mask(sample);
			String consoleMasked = consolePattern.matcher(sample).replaceAll("$1$2***");
			assertThat(consoleMasked)
				.as("두 정규식이 동일하게 마스킹해야 한다: %s", sample)
				.isEqualTo(providerMasked);
		}
	}

	@DisplayName("PatternLayoutEncoder의 %replace가 콘솔 출력에 동일한 마스킹을 적용한다")
	@Test
	void consoleEncoderAppliesSameMasking() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

		PatternLayoutEncoder encoder = new PatternLayoutEncoder();
		encoder.setContext(context);
		encoder.setPattern("%replace(%msg){'" + CONSOLE_MASK_PATTERN + "', '$1$2***'}%n");
		encoder.start();

		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.setContext(context);
		appender.start();

		Logger logger = context.getLogger("com.commerce.global.logging.LoggingMaskingTest");
		logger.setLevel(Level.INFO);
		logger.setAdditive(false);
		logger.addAppender(appender);

		try {
			logger.info("login attempt password=hunter2 token=abc123");

			assertThat(appender.list).hasSize(1);
			byte[] encoded = encoder.encode(appender.list.get(0));
			String rendered = new String(encoded);

			assertThat(rendered).contains("password=***");
			assertThat(rendered).contains("token=***");
			assertThat(rendered).doesNotContain("hunter2");
			assertThat(rendered).doesNotContain("abc123");
		} finally {
			logger.detachAppender(appender);
			appender.stop();
			encoder.stop();
		}
	}

	private String serializeMessage(String formattedMessage) throws Exception {
		MaskingMessageJsonProvider provider = new MaskingMessageJsonProvider();
		provider.setFieldName("message");

		LoggingEvent event = new LoggingEvent();
		event.setMessage(formattedMessage);

		StringWriter writer = new StringWriter();
		JsonFactory factory = new JsonFactory();
		try (JsonGenerator generator = factory.createGenerator(writer)) {
			generator.writeStartObject();
			provider.writeTo(generator, event);
			generator.writeEndObject();
		}

		ObjectMapper mapper = new ObjectMapper();
		JsonNode root = mapper.readTree(writer.toString());
		JsonNode messageNode = root.get("message");
		assertThat(messageNode).isNotNull();
		return messageNode.asText();
	}
}
