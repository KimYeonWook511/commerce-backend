package com.commerce.global.logging;

import java.io.IOException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonGenerator;

import ch.qos.logback.classic.spi.ILoggingEvent;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;

/**
 * 파일 JSON 로그의 message 필드에 민감 정보 마스킹을 적용하는 provider.
 * logback-spring.xml의 콘솔 %replace 토큰과 동일한 정규식을 유지하여 콘솔/파일 마스킹을 일치시킨다.
 * 키워드 추가 시 본 상수와 logback-spring.xml의 MASK_PATTERN property를 함께 수정해야 한다.
 */
public class MaskingMessageJsonProvider extends MessageJsonProvider {

	static final Pattern MASK_PATTERN = Pattern.compile(
		"(?i)(password|accessToken|refreshToken|token)([\"'\\s]*[:=][\"'\\s]*)([^\"'\\s,}]+)"
	);

	private static final String MASK_REPLACEMENT = "$1$2***";

	@Override
	public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
		generator.writeStringField(getFieldName(), mask(event.getFormattedMessage()));
	}

	public static String mask(String message) {
		if (message == null) {
			return null;
		}
		return MASK_PATTERN.matcher(message).replaceAll(MASK_REPLACEMENT);
	}
}
