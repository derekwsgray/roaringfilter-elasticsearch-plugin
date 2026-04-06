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

import org.junit.Test;
import org.roaringbitmap.RoaringBitmap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoaringBitmapDocValueMatcherTest {

	private static RoaringBitmap bitmapWith(int... keys) {
		return RoaringBitmap.bitmapOf(keys);
	}

	@Test
	public void unsigned32AboveSignedMaxInt_matchesWithoutOverflow() {
		long threeBillion = 3_000_000_000L;
		RoaringBitmap bm = bitmapWith((int) threeBillion);
		assertTrue(RoaringBitmapDocValueMatcher.keyMatches(bm, threeBillion));
		assertTrue(RoaringBitmapDocValueMatcher.filterResult(true, bm, threeBillion));
		assertFalse(RoaringBitmapDocValueMatcher.filterResult(false, bm, threeBillion));
	}

	@Test
	public void mathToIntExactWouldHaveThrown_includeDoesNotMatch() {
		RoaringBitmap bm = bitmapWith(1, 2, 3);
		assertFalse(RoaringBitmapDocValueMatcher.filterResult(true, bm, 3_000_000_000L));
	}

	@Test
	public void outOfUnsigned32Range_neverMatches() {
		RoaringBitmap bm = bitmapWith(1);
		assertFalse(RoaringBitmapDocValueMatcher.keyMatches(bm, -1L));
		assertFalse(RoaringBitmapDocValueMatcher.keyMatches(bm, 0x1_0000_0000L));
		assertTrue(RoaringBitmapDocValueMatcher.filterResult(false, bm, 0x1_0000_0000L));
		assertFalse(RoaringBitmapDocValueMatcher.filterResult(true, bm, 0x1_0000_0000L));
	}

	@Test
	public void multiValued_orSemantics() {
		RoaringBitmap bm = bitmapWith(40);
		long[] missThenHit = new long[] { 10L, 40L };
		assertTrue(RoaringBitmapDocValueMatcher.filterResult(true, bm, missThenHit));
		assertFalse(RoaringBitmapDocValueMatcher.filterResult(false, bm, missThenHit));
	}
}
