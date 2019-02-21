package org.codelibs.elasticsearch.kuromoji.neologd;

import static org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner.newConfigs;
import static org.junit.Assert.assertEquals;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

import org.codelibs.curl.CurlResponse;
import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
import org.codelibs.elasticsearch.runner.net.EcrCurl;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.node.Node;
import org.elasticsearch.rest.RestStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class KuromojiNeologdPluginTest {

    private ElasticsearchClusterRunner runner;

    private File[] userDictFiles;

    private int numOfNode = 2;

    private int numOfDocs = 1000;

    private String clusterName;

    @Before
    public void setUp() throws Exception {
        clusterName = "es-kuromojineologd-" + System.currentTimeMillis();
        runner = new ElasticsearchClusterRunner();
        runner.onBuild(new ElasticsearchClusterRunner.Builder() {
            @Override
            public void build(final int number, final Builder settingsBuilder) {
                settingsBuilder.put("http.cors.enabled", true);
                settingsBuilder.put("http.cors.allow-origin", "*");
                settingsBuilder.putList("discovery.zen.ping.unicast.hosts", "localhost:9301-9310");
            }
        }).build(newConfigs().clusterName(clusterName).numOfNode(numOfNode)
                .pluginTypes("org.codelibs.elasticsearch.kuromoji.ipadic.neologd.KuromojiNeologdPlugin"));

        userDictFiles = null;
    }

    private void updateDictionary(File file, String content) throws IOException, UnsupportedEncodingException, FileNotFoundException {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
            bw.write(content);
            bw.flush();
        }
    }

    @After
    public void cleanUp() throws Exception {
        runner.close();
        runner.clean();
        if (userDictFiles != null) {
            for (File file : userDictFiles) {
                file.deleteOnExit();
            }
        }
    }

    @Test
    public void test_kuromoji_neologd() throws Exception {
        userDictFiles = new File[numOfNode];
        for (int i = 0; i < numOfNode; i++) {
            String homePath = runner.getNode(i).settings().get("path.home");
            File confPath = new File(homePath, "config");
            userDictFiles[i] = new File(confPath, "userdict_ja.txt");
            updateDictionary(userDictFiles[i], "東京スカイツリー,東京 スカイツリー,トウキョウ スカイツリー,カスタム名詞");
        }

        runner.ensureYellow();
        Node node = runner.node();

        final String index = "dataset";
        final String type = "item";

        final String indexSettings = "{\"index\":{\"analysis\":{" + "\"tokenizer\":{"//
                + "\"kuromoji_user_dict\":{\"type\":\"kuromoji_neologd_tokenizer\",\"mode\":\"extended\",\"user_dictionary\":\"userdict_ja.txt\"}"
                + "},"//
                + "\"analyzer\":{"
                + "\"ja_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"kuromoji_user_dict\",\"filter\":[\"kuromoji_neologd_stemmer\"]}"
                + "}"//
                + "}}}";
        runner.createIndex(index, Settings.builder().loadFromSource(indexSettings, XContentType.JSON).build());

        // create a mapping
        final XContentBuilder mappingBuilder = XContentFactory.jsonBuilder()//
                .startObject()//
                .startObject(type)//
                .startObject("properties")//

                // id
                .startObject("id")//
                .field("type", "keyword")//
                .endObject()//

                // msg1
                .startObject("msg")//
                .field("type", "text")//
                .field("analyzer", "ja_analyzer")//
                .endObject()//

                .endObject()//
                .endObject()//
                .endObject();
        runner.createMapping(index, type, mappingBuilder);

        final IndexResponse indexResponse1 = runner.insert(index, type, "1", "{\"msg\":\"東京スカイツリー\", \"id\":\"1\"}");
        assertEquals(RestStatus.CREATED, indexResponse1.status());
        runner.refresh();

        assertDocCount(1, index, type, "msg", "東京スカイツリー");

        String text = "東京スカイツリー";
        try (CurlResponse response = EcrCurl.post(node, "/" + index + "/_analyze").header("Content-Type", "application/json")
                .body("{\"analyzer\":\"ja_analyzer\",\"text\":\"" + text + "\"}").execute()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tokens = (List<Map<String, Object>>) response.getContent(EcrCurl.jsonParser).get("tokens");
            assertEquals("東京", tokens.get(0).get("token").toString());
            assertEquals("スカイツリ", tokens.get(1).get("token").toString());
        }

        text = "きゃりーぱみゅぱみゅ";
        try (CurlResponse response = EcrCurl.post(node, "/" + index + "/_analyze").header("Content-Type", "application/json")
                .body("{\"analyzer\":\"ja_analyzer\",\"text\":\"" + text + "\"}").execute()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tokens = (List<Map<String, Object>>) response.getContent(EcrCurl.jsonParser).get("tokens");
            assertEquals("きゃりーぱみゅぱみゅ", tokens.get(0).get("token").toString());
        }

    }

    @Test
    public void test_reloadable_kuromoji() throws Exception {
        userDictFiles = new File[numOfNode];
        for (int i = 0; i < numOfNode; i++) {
            String homePath = runner.getNode(i).settings().get("path.home");
            File confPath = new File(homePath, "config");
            userDictFiles[i] = new File(confPath, "userdict_ja.txt");
            updateDictionary(userDictFiles[i], "東京スカイツリー,東京 スカイツリー,トウキョウ スカイツリー,カスタム名詞");
        }

        runner.ensureYellow();
        Node node = runner.node();

        final String index = "dataset";
        final String type = "item";

        final String indexSettings = "{\"index\":{\"analysis\":{" + "\"tokenizer\":{"//
                + "\"kuromoji_user_dict\":{\"type\":\"kuromoji_neologd_tokenizer\",\"mode\":\"extended\",\"user_dictionary\":\"userdict_ja.txt\"},"
                + "\"kuromoji_user_dict_reload\":{\"type\":\"reloadable_kuromoji_neologd\",\"mode\":\"extended\",\"user_dictionary\":\"userdict_ja.txt\",\"reload_interval\":\"1s\"}"
                + "},"//
                + "\"analyzer\":{"
                + "\"ja_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"kuromoji_user_dict\",\"filter\":[\"kuromoji_neologd_stemmer\"]},"
                + "\"ja_reload_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"kuromoji_user_dict_reload\",\"filter\":[\"kuromoji_neologd_stemmer\"]}"
                + "}"//
                + "}}}";
        runner.createIndex(index, Settings.builder().loadFromSource(indexSettings, XContentType.JSON).build());

        // create a mapping
        final XContentBuilder mappingBuilder = XContentFactory.jsonBuilder()//
                .startObject()//
                .startObject(type)//
                .startObject("properties")//

                // id
                .startObject("id")//
                .field("type", "keyword")//
                .endObject()//

                // msg1
                .startObject("msg1")//
                .field("type", "text")//
                .field("analyzer", "ja_reload_analyzer")//
                .endObject()//

                // msg2
                .startObject("msg2")//
                .field("type", "text")//
                .field("analyzer", "ja_analyzer")//
                .endObject()//

                .endObject()//
                .endObject()//
                .endObject();
        runner.createMapping(index, type, mappingBuilder);

        final IndexResponse indexResponse1 =
                runner.insert(index, type, "1", "{\"msg1\":\"東京スカイツリー\", \"msg2\":\"東京スカイツリー\", \"id\":\"1\"}");
        assertEquals(RestStatus.CREATED, indexResponse1.status());
        runner.refresh();

        for (int i = 0; i < 1000; i++) {
            assertDocCount(1, index, type, "msg1", "東京スカイツリー");
            assertDocCount(1, index, type, "msg2", "東京スカイツリー");

            String text = "東京スカイツリー";
            try (CurlResponse response = EcrCurl.post(node, "/" + index + "/_analyze").header("Content-Type", "application/json")
                    .body("{\"analyzer\":\"ja_reload_analyzer\",\"text\":\"" + text + "\"}").execute()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) response.getContent(EcrCurl.jsonParser).get("tokens");
                assertEquals("東京", tokens.get(0).get("token").toString());
                assertEquals("スカイツリ", tokens.get(1).get("token").toString());
            }

            text = "Java太郎";
            try (CurlResponse response = EcrCurl.post(node, "/" + index + "/_analyze").header("Content-Type", "application/json")
                    .body("{\"analyzer\":\"ja_reload_analyzer\",\"text\":\"" + text + "\"}").execute()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) response.getContent(EcrCurl.jsonParser).get("tokens");
                assertEquals("Java", tokens.get(0).get("token").toString());
                assertEquals("太郎", tokens.get(1).get("token").toString());
            }
        }

        // changing a file timestamp
        Thread.sleep(2000);

        for (int i = 0; i < numOfNode; i++) {
            updateDictionary(userDictFiles[i], "東京スカイツリー,東京 スカイ ツリー,トウキョウ スカイ ツリー,カスタム名詞\n" + "Java太郎,Java太郎,ジャバタロウ,人名");
        }

        final IndexResponse indexResponse2 =
                runner.insert(index, type, "2", "{\"msg1\":\"東京スカイツリー\", \"msg2\":\"東京スカイツリー\", \"id\":\"2\"}");
        assertEquals(RestStatus.CREATED, indexResponse2.status());
        runner.refresh();

        for (int i = 0; i < 1000; i++) {
            assertDocCount(1, index, type, "msg1", "東京スカイツリー");
            assertDocCount(2, index, type, "msg2", "東京スカイツリー");

            String text = "東京スカイツリー";
            try (CurlResponse response = EcrCurl.post(node, "/" + index + "/_analyze").header("Content-Type", "application/json")
                    .body("{\"analyzer\":\"ja_reload_analyzer\",\"text\":\"" + text + "\"}").execute()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) response.getContent(EcrCurl.jsonParser).get("tokens");
                assertEquals("東京", tokens.get(0).get("token").toString());
                assertEquals("スカイ", tokens.get(1).get("token").toString());
                assertEquals("ツリー", tokens.get(2).get("token").toString());
            }

            text = "Java太郎";
            try (CurlResponse response = EcrCurl.post(node, "/" + index + "/_analyze").header("Content-Type", "application/json")
                    .body("{\"analyzer\":\"ja_reload_analyzer\",\"text\":\"" + text + "\"}").execute()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) response.getContent(EcrCurl.jsonParser).get("tokens");
                assertEquals("Java太郎", tokens.get(0).get("token").toString());
            }
        }
    }

    private void assertDocCount(int expected, final String index, final String type, final String field, final String value) {
        final SearchResponse searchResponse =
                runner.search(index, type, QueryBuilders.matchPhraseQuery(field, value), null, 0, numOfDocs);
        assertEquals(expected, searchResponse.getHits().getTotalHits());
    }
}
