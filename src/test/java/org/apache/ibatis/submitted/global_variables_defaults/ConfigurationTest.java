/**
 * Copyright 2009-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.submitted.global_variables_defaults;

import java.io.IOException;
import java.io.Reader;
import java.util.Properties;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.CacheBuilder;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ConfigurationTest {

    @Test
    void applyDefaultValueOnXmlConfiguration() throws IOException {

        Properties props = new Properties();
        props.setProperty(PropertyParser.KEY_ENABLE_DEFAULT_VALUE, "true");

        Reader reader = Resources.getResourceAsReader("org/apache/ibatis/submitted/global_variables_defaults/mybatis-config.xml");
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(reader, props);
        Configuration configuration = factory.getConfiguration();

        Assertions.assertThat(configuration.getJdbcTypeForNull()).isEqualTo(JdbcType.NULL);
        Assertions.assertThat(((UnpooledDataSource)configuration.getEnvironment().getDataSource()).getUrl())
            .isEqualTo("jdbc:hsqldb:mem:global_variables_defaults");
        Assertions.assertThat(configuration.getDatabaseId()).isEqualTo("hsql");
        Assertions.assertThat(((SupportClasses.CustomObjectFactory)configuration.getObjectFactory()).getProperties().getProperty("name"))
            .isEqualTo("default");

    }

    @Test
    void applyPropertyValueOnXmlConfiguration() throws IOException {

        Properties props = new Properties();
        props.setProperty(PropertyParser.KEY_ENABLE_DEFAULT_VALUE, "true");
        props.setProperty("settings.jdbcTypeForNull", JdbcType.CHAR.name());
        props.setProperty("db.name", "global_variables_defaults_custom");
        props.setProperty("productName.hsql", "Hsql");
        props.setProperty("objectFactory.name", "custom");

        Reader reader = Resources.getResourceAsReader("org/apache/ibatis/submitted/global_variables_defaults/mybatis-config.xml");
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(reader, props);
        Configuration configuration = factory.getConfiguration();

        Assertions.assertThat(configuration.getJdbcTypeForNull()).isEqualTo(JdbcType.CHAR);
        Assertions.assertThat(((UnpooledDataSource)configuration.getEnvironment().getDataSource()).getUrl())
            .isEqualTo("jdbc:hsqldb:mem:global_variables_defaults_custom");
        Assertions.assertThat(configuration.getDatabaseId()).isNull();
        Assertions.assertThat(((SupportClasses.CustomObjectFactory)configuration.getObjectFactory()).getProperties().getProperty("name"))
            .isEqualTo("custom");

    }

    /**
     * 该方法测试的是 Configuration.StrictMap 的功能
     * 跟 Cache 没啥关系，因为 StrictMap 是私有的，所以只能通过这种方式来测试了
     */
    @Test
    public void strictMap() {
        TypeAliasRegistry typeAliasRegistry = new TypeAliasRegistry();
        Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias("org.apache.ibatis.submitted.global_variables.CustomCache");
        Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias("org.apache.ibatis.cache.decorators.LruCache");
        String namespace1 = "org.apache.ibatis.domain.blog.mappers1.BlogMapper";
        String namespace2 = "org.apache.ibatis.domain.blog.mappers2.BlogMapper";

        Cache cache1 = new CacheBuilder(namespace1).implementation(typeClass).addDecorator(evictionClass).clearInterval(10000l).size(100).readWrite(false).blocking(false).build();
        Cache cache2 = new CacheBuilder(namespace2).implementation(typeClass).addDecorator(evictionClass).clearInterval(10000l).size(100).readWrite(false).blocking(false).build();

        Configuration configuration = new Configuration();
        configuration.addCache(cache1);
        configuration.addCache(cache2);
        System.out.println(configuration.getCache(namespace1));
        System.out.println(configuration.getCache(namespace2));
        try {
            System.out.println(configuration.getCache("BlogMapper"));//这里会抛异常
        } catch (Exception e) {
            e.printStackTrace();
            Assertions.assertThatExceptionOfType(e.getClass());
        }
    }
}
