package com.commerce.common.util;

import java.security.SecureRandom;

public final class UlidGenerator {

	private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();

	private UlidGenerator() {
	}

	public static String generate() {
		char[] chars = new char[26];
		encodeTime(System.currentTimeMillis(), chars); // 10자리
		encodeRandom(chars); // 16자리
		return new String(chars);
	}

	/**
	 * - 시간값을 Base32로 변환
	 * - 시간(밀리초) 기반으로 생성 -> 시간순 정렬 가능
	 * - 시간값을 5비트(0~31)씩 잘라서 10글자로 만듦 (ULID는 Base32(2^5개 문자) 인코딩이라 5비트가 1글자)
	 * - time & 31 → 마지막 5비트 추출
	 * - time >>>= 5 → 다음 5비트를 추출하기 위해 이동
	 */
	private static void encodeTime(long time, char[] chars) {
		for (int i = 9; i >= 0; i--) {
			chars[i] = ENCODING[(int)(time & 31)];
			time >>>= 5;
		}
	}

	/**
	 * - 랜덤 값 -> 충돌 방지
	 * - 랜덤 바이트 10개(=80비트) 생성
	 * - ULID 랜덤 파트는 16글자 × 5비트 = 80비트
	 */
	private static void encodeRandom(char[] chars) {
		byte[] random = new byte[10];
		RANDOM.nextBytes(random);

		int index = 10;
		int buffer = 0;
		int bitsLeft = 0;
		for (byte value : random) {
			buffer = (buffer << 8) | (value & 0xFF);
			bitsLeft += 8;
			while (bitsLeft >= 5) {
				int code = (buffer >> (bitsLeft - 5)) & 31;
				chars[index++] = ENCODING[code];
				bitsLeft -= 5;
			}
		}
	}
}
