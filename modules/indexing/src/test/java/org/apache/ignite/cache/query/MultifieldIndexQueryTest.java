/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.cache.query;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import javax.cache.Cache;
import javax.cache.CacheException;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.query.annotations.QuerySqlField;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.apache.ignite.cache.query.IndexQueryCriteriaBuilder.between;
import static org.apache.ignite.cache.query.IndexQueryCriteriaBuilder.eq;
import static org.apache.ignite.cache.query.IndexQueryCriteriaBuilder.gt;
import static org.apache.ignite.cache.query.IndexQueryCriteriaBuilder.gte;
import static org.apache.ignite.cache.query.IndexQueryCriteriaBuilder.lt;
import static org.apache.ignite.cache.query.IndexQueryCriteriaBuilder.lte;

/** */
@RunWith(Parameterized.class)
public class MultifieldIndexQueryTest extends GridCommonAbstractTest {
    /** */
    private static final String CACHE = "TEST_CACHE";

    /** */
    private static final String INDEX = "TEST_IDX";

    /** */
    private static final String DESC_INDEX = "TEST_DESC_IDX";

    /** */
    private static final int CNT = 10_000;

    /** */
    @Parameterized.Parameter()
    public int nodesCnt;

    /** Query index, {@code null} or index name. */
    @Parameterized.Parameter(1)
    public String qryIdx;

    /** Query desc index, {@code null} or index name. */
    @Parameterized.Parameter(2)
    public String qryDescIdx;

    /** Query key PK index, {@code null} or index name. */
    @Parameterized.Parameter(3)
    public String qryKeyPKIdx;

    /** */
    private Ignite ignite;

    /** */
    private IgniteCache<Object, Object> cache;

    /** */
    @Parameterized.Parameters(name = "nodesCnt={0} qryIdx={1}")
    public static Collection<Object[]> testParams() {
        return Arrays.asList(
            new Object[] {1, null, null, null},
            new Object[] {2, null, null, null},
            new Object[] {1, INDEX, DESC_INDEX, "_key_PK"},
            new Object[] {2, INDEX, DESC_INDEX, "_key_PK"});
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        ignite = startGrids(nodesCnt);

        cache = ignite.cache(CACHE);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() {
        stopAllGrids();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        CacheConfiguration<?, ?> ccfg = new CacheConfiguration<>()
            .setName(CACHE)
            .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            .setIndexedTypes(Long.class, Person.class)
            .setQueryParallelism(4);

        cfg.setCacheConfiguration(ccfg);

        return cfg;
    }

    /** */
    @Test
    public void testQueryKeyPKIndex() {
        insertData();

        int pivot = new Random().nextInt(CNT);

        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryKeyPKIdx)
            .setCriteria(lt("_KEY", (long) pivot));

        checkPerson(cache.query(qry), 0, pivot);
    }

    /** */
    @Test
    public void testEmptyCacheQuery() {
        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", Integer.MAX_VALUE), lt("secId", Integer.MAX_VALUE));

        QueryCursor<Cache.Entry<Long, Person>> cursor = cache.query(qry);

        assertTrue(cursor.getAll().isEmpty());

        // Check query with single column only.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", Integer.MAX_VALUE));

        assertTrue(cache.query(qry).getAll().isEmpty());
    }

    /** */
    @Test
    public void testCheckBoundaries() {
        cache.put(1L, new Person(0, 1));
        cache.put(2L, new Person(1, 0));
        cache.put(3L, new Person(1, 1));

        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(between("id", 0, 1), eq("secId", 1));

        List<Cache.Entry<Long, Person>> result = cache.query(qry).getAll();

        assertEquals(2, result.size());

        result.sort(Comparator.comparingLong(Cache.Entry::getKey));

        assertEquals(1L, (long) result.get(0).getKey());
        assertEquals(3L, (long) result.get(1).getKey());

        assertEquals(new Person(0, 1), result.get(0).getValue());
        assertEquals(new Person(1, 1), result.get(1).getValue());
    }

    /** */
    @Test
    public void testQuerySingleField() {
        insertData();

        // Should return empty result for ID that less any inserted.
        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", -1));

        assertTrue(cache.query(qry).getAll().isEmpty());

        // Should return all data for ID that greater any inserted.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", 1));

        checkPerson(cache.query(qry), 0, CNT);

        // Checks the same with query for DESC_IDX.
        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lt("id", -1));

        assertTrue(cache.query(qry).getAll().isEmpty());

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lt("id", 1));

        checkPerson(cache.query(qry), 0, CNT);
    }

    /** */
    @Test
    public void testLtQueryMultipleField() {
        insertData();

        int pivot = new Random().nextInt(CNT);

        // Should return empty result for ID that less any inserted.
        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", -1), lt("secId", pivot));

        assertTrue(cache.query(qry).getAll().isEmpty());

        // Should return all data for ID and SECID that greater any inserted.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", 1), lt("secId", CNT));

        checkPerson(cache.query(qry), 0, CNT);

        // Should return part of data, as ID equals to inserted data ID field.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", 0), lt("secId", pivot));

        assertTrue(cache.query(qry).getAll().isEmpty());

        // Should return all data for ID greater any inserted.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", 1), lt("secId", pivot));

        checkPerson(cache.query(qry), 0, pivot);
    }

    /** */
    @Test
    public void testLtQueryMultipleFieldReverseFieldsOrder() {
        insertData();

        int pivot = new Random().nextInt(CNT);

        // Should return empty result for ID that less any inserted.
        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("secId", pivot), lt("id", -1));

        assertTrue(cache.query(qry).getAll().isEmpty());

        // Should return all data for ID and SECID that greater any inserted.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("secId", CNT), lt("id", 1));

        checkPerson(cache.query(qry), 0, CNT);

        // Should return part of data, as ID equals to inserted data ID field.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("secId", pivot), lt("id", 0));

        assertTrue(cache.query(qry).getAll().isEmpty());

        // Should return all data for ID greater any inserted.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("secId", pivot), lt("id", 1));

        checkPerson(cache.query(qry), 0, pivot);
    }

    /** */
    @Test
    public void testLegalDifferentCriteria() {
        insertData();

        int pivot = new Random().nextInt(CNT);

        // Eq as first criterion.
        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(eq("id", 1), lt("secId", pivot));

        assertTrue(cache.query(qry).getAll().isEmpty());

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(eq("id", 0), lte("secId", pivot));

        checkPerson(cache.query(qry), 0, pivot + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(eq("id", 0), gt("secId", pivot));

        checkPerson(cache.query(qry), pivot + 1, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(eq("id", 0), gte("secId", pivot));

        checkPerson(cache.query(qry), pivot, CNT);

        int lower = new Random().nextInt(CNT / 2);
        int upper = lower + new Random().nextInt(CNT / 2);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(eq("id", 0), between("secId", lower, upper));

        checkPerson(cache.query(qry), lower, upper + 1);

        // Lt as first criterion.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", 1), lte("secId", pivot));

        checkPerson(cache.query(qry), 0, pivot + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", 1), eq("secId", pivot));

        checkPerson(cache.query(qry), pivot, pivot + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", 1), between("secId", lower, upper));

        checkPerson(cache.query(qry), lower, upper + 1);

        // Lte as first criterion.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lte("id", 0), lt("secId", pivot));

        checkPerson(cache.query(qry), 0, pivot);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lte("id", 1), between("secId", lower, upper));

        checkPerson(cache.query(qry), lower, upper + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lte("id", 0), eq("secId", pivot));

        checkPerson(cache.query(qry), pivot, pivot + 1);

        // Gt as first criterion.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(gt("id", -1), gte("secId", pivot));

        checkPerson(cache.query(qry), pivot, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(gt("id", -1), eq("secId", pivot));

        checkPerson(cache.query(qry), pivot, pivot + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(gt("id", -1), between("secId", lower, upper));

        checkPerson(cache.query(qry), lower, upper + 1);

        // Gte as first criterion.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(gte("id", 0), gt("secId", pivot));

        checkPerson(cache.query(qry), pivot + 1, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(gte("id", 0), between("secId", lower, upper));

        checkPerson(cache.query(qry), lower, upper + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(gte("id", 0), eq("secId", pivot));

        checkPerson(cache.query(qry), pivot, pivot + 1);

        // Between as first criterion.
        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(between("id", -1, 1), lt("secId", pivot));

        checkPerson(cache.query(qry), 0, pivot);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(between("id", -1, 1), lte("secId", pivot));

        checkPerson(cache.query(qry), 0, pivot + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(between("id", -1, 1), gt("secId", pivot));

        checkPerson(cache.query(qry), pivot + 1, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(between("id", -1, 1), gte("secId", pivot));

        checkPerson(cache.query(qry), pivot, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(between("id", -1, 1), eq("secId", pivot));

        checkPerson(cache.query(qry), pivot, pivot + 1);
    }

    /** */
    @Test
    public void testLegalDifferentCriteriaWithDescIdx() {
        insertData();

        int pivot = new Random().nextInt(CNT);
        int lower = new Random().nextInt(CNT / 2);
        int upper = lower + new Random().nextInt(CNT / 2);

        // Eq as first criteria.
        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(eq("id", 1), lt("descId", pivot));

        assertTrue(cache.query(qry).getAll().isEmpty());

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(eq("id", 0), lt("descId", pivot));

        checkPerson(cache.query(qry), 0, pivot);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(eq("id", 0), lte("descId", pivot));

        checkPerson(cache.query(qry), 0, pivot + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(eq("id", 0), gt("descId", pivot));

        checkPerson(cache.query(qry), pivot + 1, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(eq("id", 0), gte("descId", pivot));

        checkPerson(cache.query(qry), pivot, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(eq("id", 0), between("descId", lower, upper));

        checkPerson(cache.query(qry), lower, upper + 1);

        // Lt as first criteria.
        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lt("id", 1), gt("descId", pivot));

        checkPerson(cache.query(qry), pivot + 1, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lt("id", 1), gte("descId", pivot));

        checkPerson(cache.query(qry), pivot, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lt("id", 1), between("descId", lower, upper));

        checkPerson(cache.query(qry), lower, upper + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lt("id", 1), eq("descId", pivot));

        checkPerson(cache.query(qry), pivot, pivot + 1);

        // Lte as first criteria.
        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lte("id", 0), gt("descId", pivot));

        checkPerson(cache.query(qry), pivot + 1, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lte("id", 0), gte("descId", pivot));

        checkPerson(cache.query(qry), pivot, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lte("id", 0), between("descId", lower, upper));

        checkPerson(cache.query(qry), lower, upper + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(lte("id", 0), eq("descId", pivot));

        checkPerson(cache.query(qry), pivot, pivot + 1);

        // Gte as first criteria.
        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(gte("id", 0), lt("descId", pivot));

        checkPerson(cache.query(qry), 0, pivot);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(gte("id", 0), lte("descId", pivot));

        checkPerson(cache.query(qry), 0, pivot + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(gte("id", 0), between("descId", lower, upper));

        checkPerson(cache.query(qry), lower, upper + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(gte("id", 0), eq("descId", pivot));

        checkPerson(cache.query(qry), pivot, pivot + 1);

        // Between as first criteria.
        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(between("id", -1, 1), lt("descId", pivot));

        checkPerson(cache.query(qry), 0, pivot);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(between("id", -1, 1), lte("descId", pivot));

        checkPerson(cache.query(qry), 0, pivot + 1);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(between("id", -1, 1), gt("descId", pivot));

        checkPerson(cache.query(qry), pivot + 1, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(between("id", -1, 1), gte("descId", pivot));

        checkPerson(cache.query(qry), pivot, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryDescIdx)
            .setCriteria(between("id", -1, 1), eq("descId", pivot));

        checkPerson(cache.query(qry), pivot, pivot + 1);
    }

    /** */
    @Test
    public void testNoRightIndexRangeDifferentCriteria() {
        insertData();

        int pivot = new Random().nextInt(CNT);

        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", 1), gt("secId", pivot));

        checkPerson(cache.query(qry), pivot + 1, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", 1), gte("secId", pivot));

        checkPerson(cache.query(qry), pivot, CNT);

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(gt("id", 2), lt("secId", pivot));

        assertTrue(cache.query(qry).getAll().isEmpty());

        qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(gt("id", 2), eq("secId", pivot));

        assertTrue(cache.query(qry).getAll().isEmpty());
    }

    /** */
    @Test
    public void testWrongBoundaryClass() {
        insertData();

        // Use long boundary instead of int.
        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(lt("id", (long) 0));

        GridTestUtils.assertThrows(null,
            () -> cache.query(qry).getAll(), CacheException.class, null);

        GridTestUtils.assertThrowsWithCause(
            () -> cache.query(qry).getAll(), ClassCastException.class);
    }

    /** */
    @Test
    public void testQueryIndexWithKeyQuery() {
        insertData();

        int pivot = new Random().nextInt(CNT);

        IndexQuery<Long, Person> qry = new IndexQuery<Long, Person>(Person.class, qryIdx)
            .setCriteria(eq("id", 0), lt("secId", pivot), lt("_KEY", (long) pivot));

        checkPerson(cache.query(qry), 0, pivot);
    }

    /** */
    private void insertData() {
        try (IgniteDataStreamer<Long, Person> streamer = ignite.dataStreamer(cache.getName())) {
            for (int i = 0; i < CNT; i++)
                streamer.addData((long) i, new Person(i));
        }
    }

    /** */
    private void checkPerson(QueryCursor<Cache.Entry<Long, Person>> cursor, int left, int right) {
        List<Cache.Entry<Long, Person>> all = cursor.getAll();

        assertEquals(right - left, all.size());

        Set<Long> expKeys = LongStream.range(left, right).boxed().collect(Collectors.toSet());

        for (int i = 0; i < all.size(); i++) {
            Cache.Entry<Long, Person> entry = all.get(i);

            assertTrue(expKeys.remove(entry.getKey()));

            assertEquals(new Person(entry.getKey().intValue()), all.get(i).getValue());
        }
    }

    /** */
    private static class Person {
        /** */
        @QuerySqlField(orderedGroups = {
            @QuerySqlField.Group(name = INDEX, order = 0),
            @QuerySqlField.Group(name = DESC_INDEX, order = 0)}
        )
        final int id;

        /** */
        @QuerySqlField(orderedGroups = @QuerySqlField.Group(name = INDEX, order = 1))
        final int secId;

        /** */
        @QuerySqlField(orderedGroups = @QuerySqlField.Group(name = DESC_INDEX, order = 1, descending = true))
        final int descId;

        /** */
        Person(int secId) {
            id = 0;
            this.secId = secId;
            descId = secId;
        }

        /** */
        Person(int id, int secId) {
            this.id = id;
            this.secId = secId;
            descId = secId;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;

            if (o == null || getClass() != o.getClass())
                return false;

            Person person = (Person) o;

            return Objects.equals(id, person.id) && Objects.equals(secId, person.secId);
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            return Objects.hash(id, secId);
        }
    }
}
