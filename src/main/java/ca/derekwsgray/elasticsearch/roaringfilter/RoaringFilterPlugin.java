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

import org.apache.lucene.index.SortedNumericDocValues;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.DocReader;
import org.elasticsearch.script.DocValuesDocReader;
import org.elasticsearch.script.FilterScript;
import org.elasticsearch.script.FilterScript.LeafFactory;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;
import org.elasticsearch.script.ScriptFactory;
import org.elasticsearch.search.lookup.SearchLookup;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * RoaringBitmap plugin that allows filtering documents using a base64-encoded Roaring Bitmap of integers.
 */
public class RoaringFilterPlugin extends Plugin implements ScriptPlugin {

	@Override
	public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
		return new RoaringFilterScriptEngine();
	}

	// tag::roaring_filter
	private static class RoaringFilterScriptEngine implements ScriptEngine {
		@Override
		public String getType() {
			return "roaring_filter";
		}

		@Override
		public <FactoryType> FactoryType compile(
				String scriptName,
				String scriptSource,
				ScriptContext<FactoryType> context,
				Map<String, String> params
		) {
			if (context != FilterScript.CONTEXT) {
				throw new IllegalArgumentException(
						getType() + " scripts cannot be used for context [" + context.name + "]"
				);
			}
			if ("roaring_filter".equals(scriptSource)) {
				FilterScript.Factory factory = new RoaringFilterFactory();
				return context.factoryClazz.cast(factory);
			}
			throw new IllegalArgumentException("Unknown script name " + scriptSource);
		}

		@Override
		public void close() {
			// no resources to release
		}

		@Override
		public Set<ScriptContext<?>> getSupportedContexts() {
			return Set.of(FilterScript.CONTEXT);
		}

		private static class RoaringFilterFactory implements FilterScript.Factory, ScriptFactory {
			@Override
			public boolean isResultDeterministic() {
				return true;
			}

			@Override
			public LeafFactory newFactory(Map<String, Object> params, SearchLookup lookup) {
				Object termsParam = params.get("terms");
				if (termsParam == null) {
					throw new IllegalArgumentException("Missing parameter [terms]");
				}
				byte[] decodedTerms = Base64.getDecoder().decode(termsParam.toString());
				ByteBuffer buffer = ByteBuffer.wrap(decodedTerms);
				RoaringBitmap rBitmap = new RoaringBitmap();
				try {
					rBitmap.deserialize(buffer);
				} catch (IOException e) {
					throw new IllegalArgumentException("Invalid Roaring Bitmap in [terms]", e);
				}
				return new RoaringFilterLeafFactory(params, rBitmap);
			}
		}

		private static class RoaringFilterLeafFactory implements LeafFactory {
			private final Map<String, Object> params;
			private final String fieldName;
			private final String opType;
			private final RoaringBitmap rBitmap;
			private final boolean include;
			private final boolean exclude;

			private RoaringFilterLeafFactory(Map<String, Object> params, RoaringBitmap rBitmap) {
				if (!params.containsKey("field")) {
					throw new IllegalArgumentException("Missing parameter [field]");
				}
				Object operation = params.get("operation");
				if (operation == null) {
					throw new IllegalArgumentException("Missing parameter [operation]");
				}
				this.params = params;
				this.rBitmap = rBitmap;
				this.opType = operation.toString();
				this.fieldName = params.get("field").toString();
				this.include = "include".equals(this.opType);
				this.exclude = !this.include;
			}

			@Override
			public FilterScript newInstance(DocReader docReader) throws IOException {
				DocValuesDocReader dvReader = (DocValuesDocReader) docReader;
				SortedNumericDocValues docValues =
						dvReader.getLeafReaderContext().reader().getSortedNumericDocValues(fieldName);
				if (docValues == null) {
					return new FilterScript(params, null, docReader) {
						@Override
						public boolean execute() {
							return exclude;
						}
					};
				}
				return new FilterScript(params, null, docReader) {
					@Override
					public void setDocument(int docId) {
						try {
							super.setDocument(docId);
							docValues.advance(docId);
						} catch (IOException e) {
							throw ExceptionsHelper.convertToElastic(e);
						}
					}

					@Override
					public boolean execute() {
						try {
							long raw = docValues.nextValue();
							int docVal = Math.toIntExact(raw);
							if (exclude && rBitmap.contains(docVal)) {
								return false;
							}
							return !include || rBitmap.contains(docVal);
						} catch (IOException e) {
							throw ExceptionsHelper.convertToElastic(e);
						}
					}
				};
			}
		}
	}
	// end::roaring_filter
}
