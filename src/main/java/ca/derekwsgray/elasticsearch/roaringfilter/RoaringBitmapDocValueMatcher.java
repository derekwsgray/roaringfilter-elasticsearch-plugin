/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package ca.derekwsgray.elasticsearch.roaringfilter;

import org.roaringbitmap.RoaringBitmap;

/**
 * Pure membership logic shared by {@link RoaringFilterPlugin} and unit tests.
 * {@link RoaringBitmap} keys are unsigned 32-bit integers; doc values are read as {@code long}.
 */
final class RoaringBitmapDocValueMatcher {

	private RoaringBitmapDocValueMatcher() {}

	/** Single-valued document (no array allocation). */
	static boolean filterResult(boolean include, RoaringBitmap bitmap, long raw) {
		boolean hit = keyMatches(bitmap, raw);
		return include ? hit : !hit;
	}

	/** Multi-valued document: match if any in-range key is contained (OR). */
	static boolean filterResult(boolean include, RoaringBitmap bitmap, long[] rawValues) {
		for (long raw : rawValues) {
			if (keyMatches(bitmap, raw)) {
				return include;
			}
		}
		return !include;
	}

	static boolean keyMatches(RoaringBitmap bitmap, long raw) {
		if (raw < 0L || raw > 0xFFFF_FFFFL) {
			return false;
		}
		return bitmap.contains((int) raw);
	}
}
