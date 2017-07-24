/*
 * Copyright [2016] Doug Turnbull
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.o19s.es.ltr.query;

import com.o19s.es.ltr.action.AddDerivedFeaturesToSetAction;
import com.o19s.es.ltr.action.AddFeaturesToSetAction;
import com.o19s.es.ltr.action.AddFeaturesToSetAction.AddFeaturesToSetRequestBuilder;
import com.o19s.es.ltr.action.BaseIntegrationTest;
import com.o19s.es.ltr.action.CachesStatsAction;
import com.o19s.es.ltr.action.CachesStatsAction.CachesStatsNodesResponse;
import com.o19s.es.ltr.action.ClearCachesAction;
import com.o19s.es.ltr.action.CreateModelFromSetAction;
import com.o19s.es.ltr.action.CreateModelFromSetAction.CreateModelFromSetRequestBuilder;
import com.o19s.es.ltr.feature.store.StoredDerivedFeature;
import com.o19s.es.ltr.feature.store.StoredFeature;
import com.o19s.es.ltr.feature.store.StoredLtrModel;
import com.o19s.es.ltr.feature.store.index.IndexFeatureStore;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.rescore.QueryRescoreMode;
import org.elasticsearch.search.rescore.RescoreBuilder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by doug on 12/29/16.
 */
public class StoredLtrQueryIT extends BaseIntegrationTest {

    private static final String SIMPLE_MODEL = "{" +
            "\"feature1\": 1," +
            "\"feature2\": -1," +
            "\"feature3\": 1" +
            "}";


    public void testFullUsecase() throws Exception {
        addElement(new StoredFeature("feature1", Collections.singletonList("query"), "mustache",
                QueryBuilders.matchQuery("field1", "{{query}}").toString()));
        addElement(new StoredFeature("feature2", Collections.singletonList("query"), "mustache",
                QueryBuilders.matchQuery("field2", "{{query}}").toString()));

        addElement(new StoredDerivedFeature("feature3", "feature1 * 2"));

        AddFeaturesToSetRequestBuilder builder = AddFeaturesToSetAction.INSTANCE.newRequestBuilder(client());
        builder.request().setFeatureSet("my_set");
        builder.request().setFeatureNameQuery("feature1");
        builder.request().setStore(IndexFeatureStore.DEFAULT_STORE);
        builder.execute().get();

        builder.request().setFeatureNameQuery("feature2");
        builder.execute().get();

        AddDerivedFeaturesToSetAction.AddDerivedFeaturesToSetRequestBuilder derivedBuilder =
                AddDerivedFeaturesToSetAction.INSTANCE.newRequestBuilder(client());
        derivedBuilder.request().setFeatureSet("my_set");
        derivedBuilder.request().setDerivedName("feature3");
        derivedBuilder.request().setStore(IndexFeatureStore.DEFAULT_STORE);

        long version = derivedBuilder.get().getResponse().getVersion();

        CreateModelFromSetRequestBuilder createModelFromSetRequestBuilder = CreateModelFromSetAction.INSTANCE.newRequestBuilder(client());
        createModelFromSetRequestBuilder.withVersion(IndexFeatureStore.DEFAULT_STORE, "my_set", version,
                "my_model", new StoredLtrModel.LtrModelDefinition("model/linear", SIMPLE_MODEL, true));
        createModelFromSetRequestBuilder.get();
        buildIndex();
        Map<String, Object> params = new HashMap<>();
        params.put("query", "bonjour");
        SearchRequestBuilder sb = client().prepareSearch("test_index")
                .setQuery(QueryBuilders.matchQuery("field1", "world"))
                .setRescorer(RescoreBuilder
                        .queryRescorer(new StoredLtrQueryBuilder().modelName("my_model").params(params))
                        .setScoreMode(QueryRescoreMode.Total)
                        .setQueryWeight(0)
                        .setRescoreQueryWeight(1));

        params.put("query", "hello");
        SearchResponse sr = sb.get();
        assertEquals(1, sr.getHits().getTotalHits());
        assertTrue(sr.getHits().getAt(0).score() > 0);

        StoredLtrModel model = getElement(StoredLtrModel.class, StoredLtrModel.TYPE, "my_model");
        CachesStatsNodesResponse stats = CachesStatsAction.INSTANCE.newRequestBuilder(client()).execute().get();
        assertEquals(1, stats.getAll().getTotal().getCount());
        assertEquals(model.compile(parserFactory()).ramBytesUsed(), stats.getAll().getTotal().getRam());
        assertEquals(1, stats.getAll().getModels().getCount());
        assertEquals(model.compile(parserFactory()).ramBytesUsed(), stats.getAll().getModels().getRam());
        assertEquals(0, stats.getAll().getFeatures().getCount());
        assertEquals(0, stats.getAll().getFeatures().getRam());
        assertEquals(0, stats.getAll().getFeaturesets().getCount());
        assertEquals(0, stats.getAll().getFeaturesets().getRam());

        ClearCachesAction.RequestBuilder clearCache = ClearCachesAction.INSTANCE.newRequestBuilder(client());
        clearCache.request().clearModel(IndexFeatureStore.DEFAULT_STORE, "my_model");
        clearCache.get();

        stats = CachesStatsAction.INSTANCE.newRequestBuilder(client()).execute().get();
        assertEquals(0, stats.getAll().getTotal().getCount());
        assertEquals(0, stats.getAll().getTotal().getRam());
    }

    public void buildIndex() {
        client().admin().indices().prepareCreate("test_index").get();
        client().prepareIndex("test_index", "test")
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field1", "hello world", "field2", "bonjour world")
                .get();
    }
}
