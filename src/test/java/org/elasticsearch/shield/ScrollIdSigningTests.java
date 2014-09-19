/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.shield.authz.AuthorizationException;
import org.elasticsearch.shield.key.InternalKeyService;
import org.elasticsearch.shield.key.KeyService;
import org.elasticsearch.shield.test.ShieldIntegrationTest;
import org.junit.Before;
import org.junit.Test;

import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 *
 */
public class ScrollIdSigningTests extends ShieldIntegrationTest {

    private KeyService keyService;

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(InternalKeyService.FILE_SETTING, writeFile(newFolder(), "system_key", generateKey()))
                .build();
    }

    @Before
    public void init() throws Exception {
        keyService = internalCluster().getInstance(KeyService.class);
    }

    @Test
    public void testSearchAndClearScroll() throws Exception {
        IndexRequestBuilder[] docs = new IndexRequestBuilder[randomIntBetween(20, 100)];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = client().prepareIndex("idx", "type").setSource("field", "value");
        }
        indexRandom(true, docs);
        SearchResponse response = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setScroll(TimeValue.timeValueMinutes(2))
                .setSize(randomIntBetween(1, 10)).get();

        try {
            assertSigned(response.getScrollId());
            while (true) {
                response = client().prepareSearchScroll(response.getScrollId())
                        .setScroll(TimeValue.timeValueMinutes(2)).get();
                assertSigned(response.getScrollId());
                if (response.getHits().getHits().length == 0) {
                    break;
                }
            }
        } finally {
            clearScroll(response.getScrollId());
        }
    }

    @Test
    public void testSearchScroll_WithTamperedScrollId() throws Exception {
        IndexRequestBuilder[] docs = new IndexRequestBuilder[randomIntBetween(20, 100)];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = client().prepareIndex("idx", "type").setSource("field", "value");
        }
        indexRandom(true, docs);
        SearchResponse response = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setScroll(TimeValue.timeValueMinutes(2))
                .setSize(randomIntBetween(1, 10)).get();
        String scrollId = response.getScrollId();
        String tamperedScrollId = randomBoolean() ? scrollId.substring(randomIntBetween(1, 10)) : scrollId + randomAsciiOfLength(randomIntBetween(3, 10));
        try {
            client().prepareSearchScroll(tamperedScrollId).setScroll(TimeValue.timeValueMinutes(2)).get();
            fail("Expected an authorization exception to be thrown when scroll id is tampered");
        } catch (Exception e) {
            assertThat(ExceptionsHelper.unwrap(e, AuthorizationException.class), notNullValue());
        } finally {
            clearScroll(scrollId);
        }
    }

    @Test
    public void testClearScroll_WithTamperedScrollId() throws Exception {
        IndexRequestBuilder[] docs = new IndexRequestBuilder[randomIntBetween(20, 100)];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = client().prepareIndex("idx", "type").setSource("field", "value");
        }
        indexRandom(true, docs);
        SearchResponse response = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setScroll(TimeValue.timeValueMinutes(2))
                .setSize(5).get();
        String scrollId = response.getScrollId();
        String tamperedScrollId = randomBoolean() ? scrollId.substring(randomIntBetween(1, 10)) : scrollId + randomAsciiOfLength(randomIntBetween(3, 10));
        try {
            client().prepareClearScroll().addScrollId(tamperedScrollId).get();
            fail("Expected an authorization exception to be thrown when scroll id is tampered");
        } catch (Exception e) {
            assertThat(ExceptionsHelper.unwrap(e, AuthorizationException.class), notNullValue());
        } finally {
            clearScroll(scrollId);
        }
    }

    private void assertSigned(String scrollId) {
        assertThat(keyService.signed(scrollId), is(true));
    }

    private static byte[] generateKey() {
        try {
            return InternalKeyService.generateKey();
        } catch (Exception e) {
            fail("failed to generate key");
            return null;
        }
    }
}
